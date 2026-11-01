import { HttpBackend, HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, PLATFORM_ID, inject, signal } from '@angular/core';
import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { firstValueFrom } from 'rxjs';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { BrowserNavigation } from './browser-navigation';
import { randomUrlSafe, s256Challenge } from './pkce';

const TOKEN_KEY = 'foodstack.auth.token';
const FLOW_KEY = 'foodstack.auth.flow';

interface StoredToken {
  accessToken: string;
  idToken: string | null;
  expiresAt: number;
}

interface PendingFlow {
  state: string;
  verifier: string;
  returnTo: string;
}

interface TokenResponse {
  access_token: string;
  id_token?: string;
  expires_in: number;
}

/** Renew this long before the access token runs out. */
const RENEW_BEFORE_MS = 60_000;

/**
 * Sign-in with the authorization code flow and PKCE against the FoodStack identity module
 * (TDD 9.1). Public SPA clients get no refresh token, and access tokens last minutes, so the
 * service renews in the background: it runs the authorize request again with a same-origin fetch,
 * which the identity session answers with a fresh code, and swaps the code for a token. The page
 * and any half-filled form stay as they are. Only when the identity session itself has ended does
 * the user go back to the login page.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly document = inject(DOCUMENT);
  private readonly navigation = inject(BrowserNavigation);
  // Token requests must not pass through the auth interceptor.
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly token = signal<StoredToken | null>(this.readToken());
  private renewing: Promise<boolean> | null = null;
  private renewTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    this.scheduleRenewal();
  }

  /** Whether the user holds an access token that has not expired yet. Checked at call time. */
  signedIn(): boolean {
    const token = this.token();
    return token !== null && token.expiresAt > Date.now();
  }

  /** Whether the user signed in during this browser session, even if the token has since expired. */
  hasSession(): boolean {
    return this.token() !== null;
  }

  /** Whether the token is gone or runs out within `marginMs`, so a request should renew first. */
  expiresSoon(marginMs = 5_000): boolean {
    const token = this.token();
    return token !== null && token.expiresAt - marginMs <= Date.now();
  }

  accessToken(): string | null {
    return this.signedIn() ? (this.token()?.accessToken ?? null) : null;
  }

  /**
   * Gets a fresh token without leaving the page; false when the identity session has ended (or
   * the issuer is on another origin), and the user has to sign in again. Calls made while a
   * renewal runs share it.
   */
  renew(): Promise<boolean> {
    this.renewing ??= this.renewOnce().finally(() => (this.renewing = null));
    return this.renewing;
  }

  /** The current page as a router URL, to come back to after signing in. */
  currentPath(): string {
    const location = this.document.location;
    const base = new URL(this.document.baseURI).pathname;
    const path = location.pathname.startsWith(base)
      ? location.pathname.slice(base.length - 1)
      : location.pathname;
    return path + location.search;
  }

  /** Sends the browser to the login page; after sign-in it comes back to {@link redirectUri}. */
  async signIn(returnTo = '/'): Promise<void> {
    const flow: PendingFlow = { state: randomUrlSafe(16), verifier: randomUrlSafe(48), returnTo };
    this.storage()?.setItem(FLOW_KEY, JSON.stringify(flow));
    this.navigation.assign(await this.authorizeUrl(flow));
  }

  /** Finishes sign-in on the callback page; returns where the user was going. */
  async completeSignIn(query: URLSearchParams): Promise<string> {
    const flow = JSON.parse(this.storage()?.getItem(FLOW_KEY) ?? 'null') as PendingFlow | null;
    this.storage()?.removeItem(FLOW_KEY);
    const code = query.get('code');
    if (!flow || !code || query.get('state') !== flow.state) {
      throw new Error(
        query.get('error_description') ?? 'Sign-in could not be completed. Please try again.',
      );
    }
    await this.exchange(code, flow.verifier);
    return flow.returnTo;
  }

  /** Ends the identity session too (OIDC RP-initiated logout), so the next sign-in asks again. */
  signOut(): void {
    const idToken = this.token()?.idToken;
    clearTimeout(this.renewTimer);
    this.storage()?.removeItem(TOKEN_KEY);
    this.token.set(null);
    const params = new HttpParams({
      fromObject: {
        client_id: this.config.clientId,
        post_logout_redirect_uri: this.document.baseURI,
        ...(idToken ? { id_token_hint: idToken } : {}),
      },
    });
    this.navigation.assign(`${this.config.issuer}/connect/logout?${params.toString()}`);
  }

  /** The callback URL registered for this app: `<base href>auth/callback`. */
  redirectUri(): string {
    return new URL('auth/callback', this.document.baseURI).toString();
  }

  private async authorizeUrl(flow: { state: string; verifier: string }): Promise<string> {
    const params = new HttpParams({
      fromObject: {
        response_type: 'code',
        client_id: this.config.clientId,
        redirect_uri: this.redirectUri(),
        scope: 'openid',
        state: flow.state,
        code_challenge: await s256Challenge(flow.verifier),
        code_challenge_method: 'S256',
      },
    });
    return `${this.config.issuer}/oauth2/authorize?${params.toString()}`;
  }

  private async exchange(code: string, verifier: string): Promise<void> {
    const body = new HttpParams({
      fromObject: {
        grant_type: 'authorization_code',
        code,
        redirect_uri: this.redirectUri(),
        client_id: this.config.clientId,
        code_verifier: verifier,
      },
    });
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.config.issuer}/oauth2/token`, body.toString(), {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      }),
    );
    const previous = this.token();
    const token: StoredToken = {
      accessToken: response.access_token,
      idToken: response.id_token ?? previous?.idToken ?? null,
      expiresAt: Date.now() + response.expires_in * 1000,
    };
    this.storage()?.setItem(TOKEN_KEY, JSON.stringify(token));
    this.token.set(token);
    this.scheduleRenewal();
  }

  /**
   * The identity session answers the authorize request with a redirect to our callback carrying a
   * code; a same-origin fetch follows it and shows the final URL. Without a session it lands on the
   * login page instead, and renewal reports false.
   */
  private async renewOnce(): Promise<boolean> {
    const view = this.document.defaultView;
    if (!this.browser || !view?.fetch || !this.hasSession()) return false;
    const flow = { state: randomUrlSafe(16), verifier: randomUrlSafe(48) };
    try {
      const response = await view.fetch(await this.authorizeUrl(flow), {
        credentials: 'same-origin',
        redirect: 'follow',
        cache: 'no-store',
      });
      const landed = new URL(response.url);
      const callback = new URL(this.redirectUri());
      const code = landed.searchParams.get('code');
      if (
        landed.origin !== callback.origin ||
        landed.pathname !== callback.pathname ||
        landed.searchParams.get('state') !== flow.state ||
        !code
      ) {
        return false;
      }
      await this.exchange(code, flow.verifier);
      return true;
    } catch {
      return false;
    }
  }

  /** Renews a minute before the token runs out, so an open form never meets an expired token. */
  private scheduleRenewal(): void {
    clearTimeout(this.renewTimer);
    const token = this.token();
    if (!this.browser || !token) return;
    const delay = Math.max(token.expiresAt - RENEW_BEFORE_MS - Date.now(), 0);
    this.renewTimer = setTimeout(() => void this.renew(), delay);
  }

  private readToken(): StoredToken | null {
    return JSON.parse(this.storage()?.getItem(TOKEN_KEY) ?? 'null') as StoredToken | null;
  }

  private storage(): Storage | null {
    return this.document.defaultView?.sessionStorage ?? null;
  }
}
