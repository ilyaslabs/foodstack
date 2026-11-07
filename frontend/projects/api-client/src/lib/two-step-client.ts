import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';

/** Whether the account has two-step sign-in, and whether its role requires it (PRD ID-5). */
export interface TwoStepStatus {
  enabled: boolean;
  required: boolean;
  recoveryCodesLeft: number;
}

/** What an authenticator app needs: the `otpauth://` link as a QR code, or the secret to type. */
export interface TwoStepEnrollment {
  secret: string;
  uri: string;
}

/** Which social sign-in buttons the apps show, and the client id each provider needs (PRD ID-2). */
export interface SignInProviders {
  googleClientIds: string[];
  appleClientIds: string[];
}

/** Two-step sign-in on the signed-in account, the administrator's reset, and social sign-in settings. */
@Injectable({ providedIn: 'root' })
export class TwoStepClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  status(): Observable<TwoStepStatus> {
    return this.http.get<TwoStepStatus>(this.me(''));
  }

  /** A new secret; nothing changes until a code from the app confirms it. */
  enroll(): Observable<TwoStepEnrollment> {
    return this.http.post<TwoStepEnrollment>(this.me('/enroll'), null);
  }

  /** Turns two-step sign-in on and returns the ten recovery codes, shown once. */
  confirm(code: string): Observable<string[]> {
    return this.http
      .post<{ recoveryCodes: string[] }>(this.me('/confirm'), { code })
      .pipe(map((response) => response.recoveryCodes));
  }

  /** New recovery codes after a current code; the old ones stop working. */
  newRecoveryCodes(code: string): Observable<string[]> {
    return this.http
      .post<{ recoveryCodes: string[] }>(this.me('/recovery-codes'), { code })
      .pipe(map((response) => response.recoveryCodes));
  }

  disable(code: string): Observable<void> {
    return this.http.post<void>(this.me('/disable'), { code });
  }

  /** An administrator removes two-step sign-in from an account; its sign-ins end. */
  reset(userId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.config.apiUrl}/api/v1/users/${encodeURIComponent(userId)}/two-step`,
    );
  }

  providers(): Observable<SignInProviders> {
    return this.http.get<SignInProviders>(`${this.config.apiUrl}/api/v1/auth/providers`);
  }

  updateProviders(providers: SignInProviders): Observable<SignInProviders> {
    return this.http.put<SignInProviders>(`${this.config.apiUrl}/api/v1/auth/providers`, providers);
  }

  private me(path: string): string {
    return `${this.config.apiUrl}/api/v1/me/two-step${path}`;
  }
}
