import { HttpBackend, HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { firstValueFrom } from 'rxjs';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { BrowserNavigation } from './browser-navigation';
import { randomUrlSafe, s256Challenge } from './pkce';

const TOKEN_KEY = 'foodstack.auth.token';
const FLOW_KEY = 'foodstack.auth.flow';

interface StoredToken {
  accessToken: string;
  expiresAt: number;
}

interface PendingFlow {
  state: string;
  verifier: string;
  returnTo: string;
}

interface TokenResponse {
  access_token: string;
  expires_in: number;
}

/**
 * Sign-in with the authorization code flow and PKCE against the FoodStack identity module
 * (TDD 9.1). Public SPA clients get no refresh token: when the access token expires the user is
 * sent through the authorize endpoint again, which signs them straight back in while their
 * identity session lasts.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly document = inject(DOCUMENT);
  private readonly navigation = inject(BrowserNavigation);
  // Token requests must not pass through the auth interceptor.
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly token = signal<StoredToken | null>(this.readToken());

  readonly signedIn = computed(() => {
    const token = this.token();
    return token !== null && token.expiresAt > Date.now();
  });

  accessToken(): string | null {
    return this.signedIn() ? (this.token()?.accessToken ?? null) : null;
  }

  /** Sends the browser to the login page; after sign-in it comes back to {@link redirectUri}. */
  async signIn(returnTo = '/'): Promise<void> {
    const flow: PendingFlow = { state: randomUrlSafe(16), verifier: randomUrlSafe(48), returnTo };
    this.storage()?.setItem(FLOW_KEY, JSON.stringify(flow));
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
    this.navigation.assign(`${this.config.issuer}/oauth2/authorize?${params.toString()}`);
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
    const body = new HttpParams({
      fromObject: {
        grant_type: 'authorization_code',
        code,
        redirect_uri: this.redirectUri(),
        client_id: this.config.clientId,
        code_verifier: flow.verifier,
      },
    });
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.config.issuer}/oauth2/token`, body.toString(), {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      }),
    );
    const token: StoredToken = {
      accessToken: response.access_token,
      expiresAt: Date.now() + response.expires_in * 1000,
    };
    this.storage()?.setItem(TOKEN_KEY, JSON.stringify(token));
    this.token.set(token);
    return flow.returnTo;
  }

  signOut(): void {
    this.storage()?.removeItem(TOKEN_KEY);
    this.token.set(null);
    this.navigation.assign(`${this.config.issuer}/logout`);
  }

  /** The callback URL registered for this app: `<base href>auth/callback`. */
  redirectUri(): string {
    return new URL('auth/callback', this.document.baseURI).toString();
  }

  private readToken(): StoredToken | null {
    return JSON.parse(this.storage()?.getItem(TOKEN_KEY) ?? 'null') as StoredToken | null;
  }

  private storage(): Storage | null {
    return this.document.defaultView?.sessionStorage ?? null;
  }
}
