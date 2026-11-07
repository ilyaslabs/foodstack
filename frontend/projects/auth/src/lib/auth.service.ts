import { HttpBackend, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, PLATFORM_ID, inject, signal } from '@angular/core';
import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { BrowserNavigation } from './browser-navigation';

/** Not a secret: only remembers that this browser holds a sign-in cookie worth refreshing. */
const HINT_KEY = 'foodstack.auth.signedIn';

/** Cross-tab lock, so two tabs never spend the same refresh token. */
const REFRESH_LOCK = 'foodstack-auth-refresh';

/** Renew this long before the access token runs out. */
const RENEW_BEFORE_MS = 60_000;

interface Token {
  accessToken: string;
  expiresAt: number;
}

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshExpiresAt: number;
}

/** What a code is for: signing in, or setting a new password. */
export type CodePurpose = 'LOGIN' | 'PASSWORD_RESET';

/**
 * The first step proved who the user is, and the account needs a second one (PRD ID-5): a code
 * from the authenticator app (`TOTP`), or setting one up first (`TOTP_ENROLL`). No tokens yet.
 */
export interface SecondStep {
  next: 'TOTP' | 'TOTP_ENROLL';
  challenge: string;
  /** Seconds the challenge stays valid. */
  expiresIn: number;
}

/** A Google or Apple sign-in the app did itself; FoodStack checks the provider's ID token. */
export type SocialProvider = 'google' | 'apple';

/**
 * Stateless sign-in against the identity module's auth API (TDD 9.1). The access token lives in
 * memory only, for 10 minutes. The refresh token is an httpOnly, SameSite=Strict cookie that
 * scripts never see, sent only to the refresh endpoint; each refresh rotates it. A reload or a new
 * tab gets a fresh access token from that cookie without asking again. Tabs take turns refreshing
 * (Web Locks), because a refresh token used twice ends the whole sign-in.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly document = inject(DOCUMENT);
  private readonly navigation = inject(BrowserNavigation);
  private readonly router = inject(Router);
  // Token requests must not pass through the auth interceptor.
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly token = signal<Token | null>(null);
  private readonly hinted = signal(this.readHint());
  private renewing: Promise<boolean> | null = null;
  private renewTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    // a reload keeps the person signed in: the cookie gets a new access token
    if (this.browser && this.hinted()) void this.renew();
  }

  /** Whether the user holds an access token that has not expired yet. Checked at call time. */
  signedIn(): boolean {
    const token = this.token();
    return token !== null && token.expiresAt > Date.now();
  }

  /** Whether this browser is signed in, even if the access token has to be renewed first. */
  hasSession(): boolean {
    return this.token() !== null || this.hinted();
  }

  /** Whether the token is gone or runs out within `marginMs`, so a request should renew first. */
  expiresSoon(marginMs = 5_000): boolean {
    const token = this.token();
    return token === null ? this.hinted() : token.expiresAt - marginMs <= Date.now();
  }

  accessToken(): string | null {
    return this.signedIn() ? (this.token()?.accessToken ?? null) : null;
  }

  /**
   * Gets a fresh access token from the refresh cookie without leaving the page; false when the
   * sign-in has ended, and the user has to sign in again. Calls made while a renewal runs share it.
   */
  renew(): Promise<boolean> {
    this.renewing ??= this.renewOnce().finally(() => (this.renewing = null));
    return this.renewing;
  }

  /** A token that is valid now, renewed first when it is about to run out; for streams outside HttpClient. */
  async freshToken(): Promise<string | null> {
    if (this.expiresSoon(30_000)) await this.renew();
    return this.accessToken();
  }

  /** The current page as a router URL, to come back to after signing in. */
  currentPath(): string {
    return this.router.url || '/';
  }

  /** Opens this app's sign-in page; after signing in the user comes back to `returnTo`. */
  async signIn(returnTo = '/', options: { signUp?: boolean } = {}): Promise<void> {
    const queryParams: Record<string, string> = {};
    if (returnTo && returnTo !== '/' && !returnTo.startsWith('/sign-in'))
      queryParams['returnTo'] = returnTo;
    if (options.signUp) queryParams['mode'] = 'signup';
    await this.router.navigate(['/sign-in'], { queryParams });
  }

  /** Signs in, or answers the second step the account still has to take. */
  loginWithPassword(email: string, password: string): Promise<SecondStep | null> {
    return this.start('/login', { client: 'web', email, password });
  }

  loginWithCode(email: string, code: string): Promise<SecondStep | null> {
    return this.start('/login', { client: 'web', email, code });
  }

  /** Signs in with the ID token Google or Apple gave this page (PRD ID-2). */
  loginWithSocial(
    provider: SocialProvider,
    idToken: string,
    displayName?: string,
  ): Promise<SecondStep | null> {
    const body: Record<string, string> = { client: 'web', provider, idToken };
    if (displayName) body['displayName'] = displayName;
    return this.start('/social', body);
  }

  /** Which social sign-in buttons to show; the client id each provider's script needs. */
  providers(): Promise<{ googleClientIds: string[]; appleClientIds: string[] }> {
    return firstValueFrom(
      this.http.get<{ googleClientIds: string[]; appleClientIds: string[] }>(
        this.url('/providers'),
      ),
    );
  }

  /** The second step: a code from the authenticator app, or a recovery code. */
  async completeTwoStep(challenge: string, code: string): Promise<void> {
    const next = await this.start('/two-step', { challenge, code });
    if (next) throw new Error('Unexpected second step');
  }

  /** A role that requires two-step sign-in: the secret for the authenticator app. */
  startEnrollment(challenge: string): Promise<{ secret: string; uri: string }> {
    return firstValueFrom(
      this.http.post<{ secret: string; uri: string }>(this.url('/two-step/enroll'), { challenge }),
    );
  }

  /** Confirms the new app with a code from it and signs in; the recovery codes are shown once. */
  async confirmEnrollment(challenge: string, code: string): Promise<string[]> {
    const response = await firstValueFrom(
      this.http.post<TokenResponse & { recoveryCodes: string[] }>(
        this.url('/two-step/confirm'),
        { challenge, code },
        { withCredentials: true },
      ),
    );
    this.keep(response);
    return response.recoveryCodes;
  }

  /** Emails a 6-digit code; the answer is the same whether or not the email has an account. */
  async requestCode(email: string, purpose: CodePurpose): Promise<void> {
    await firstValueFrom(this.http.post<void>(this.url('/code'), { email, purpose }));
  }

  /** Sets a new password with an emailed reset code, and signs in. */
  resetPassword(email: string, code: string, newPassword: string): Promise<SecondStep | null> {
    return this.start('/reset', { client: 'web', email, code, newPassword });
  }

  /** Creates a customer account and signs in with it. */
  async register(email: string, password: string, displayName: string): Promise<SecondStep | null> {
    await firstValueFrom(this.http.post(this.url('/register'), { email, password, displayName }));
    return this.loginWithPassword(email, password);
  }

  /** Ends this sign-in on the server too, then reloads the app signed out. */
  async signOut(): Promise<void> {
    const token = this.accessToken() ?? ((await this.renew()) ? this.accessToken() : null);
    if (token) {
      try {
        await firstValueFrom(
          this.http.post(this.url('/logout'), null, {
            headers: { Authorization: `Bearer ${token}` },
            withCredentials: true,
          }),
        );
      } catch {
        // signed out here regardless; the cookie expires on its own
      }
    }
    this.forget();
    this.navigation.assign(this.document.baseURI);
  }

  private async start(path: string, body: Record<string, string>): Promise<SecondStep | null> {
    const response = await firstValueFrom(
      this.http.post<TokenResponse | SecondStep>(this.url(path), body, { withCredentials: true }),
    );
    if ('challenge' in response) return response;
    this.keep(response);
    return null;
  }

  private async renewOnce(): Promise<boolean> {
    if (!this.browser) return false;
    const locks = this.document.defaultView?.navigator?.locks;
    const run = () => this.refresh();
    return locks ? locks.request(REFRESH_LOCK, run) : run();
  }

  /** One refresh; another tab that just refreshed answers 409, so wait for its cookie and retry. */
  private async refresh(attempt = 0): Promise<boolean> {
    try {
      const response = await firstValueFrom(
        this.http.post<TokenResponse>(this.url('/refresh'), {}, { withCredentials: true }),
      );
      this.keep(response);
      return true;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409 && attempt < 2) {
        await new Promise((resolve) => setTimeout(resolve, 300));
        return this.refresh(attempt + 1);
      }
      if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 400)) {
        this.forget();
      }
      return false;
    }
  }

  private keep(response: TokenResponse): void {
    this.token.set({
      accessToken: response.accessToken,
      expiresAt: Date.now() + response.expiresIn * 1000,
    });
    this.writeHint(true);
    this.scheduleRenewal();
  }

  private forget(): void {
    clearTimeout(this.renewTimer);
    this.token.set(null);
    this.writeHint(false);
  }

  /** Renews a minute before the token runs out, so an open form never meets an expired token. */
  private scheduleRenewal(): void {
    clearTimeout(this.renewTimer);
    const token = this.token();
    if (!this.browser || !token) return;
    const delay = Math.max(token.expiresAt - RENEW_BEFORE_MS - Date.now(), 0);
    this.renewTimer = setTimeout(() => void this.renew(), delay);
  }

  private url(path: string): string {
    return `${this.config.apiUrl}/api/v1/auth${path}`;
  }

  private readHint(): boolean {
    try {
      return this.storage()?.getItem(HINT_KEY) === '1';
    } catch {
      return false;
    }
  }

  private writeHint(value: boolean): void {
    this.hinted.set(value);
    try {
      if (value) this.storage()?.setItem(HINT_KEY, '1');
      else this.storage()?.removeItem(HINT_KEY);
    } catch {
      // private mode: a reload asks to sign in again
    }
  }

  private storage(): Storage | null {
    return this.browser ? (this.document.defaultView?.localStorage ?? null) : null;
  }
}
