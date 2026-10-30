import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { DOCUMENT } from '@angular/common';

import { AuthService } from './auth.service';

/** The page the identity module sends the browser back to after sign-in. */
@Component({
  selector: 'fs-auth-callback',
  template: `
    @if (error(); as message) {
      <p role="alert">{{ message }}</p>
      <button type="button" (click)="retry()">Sign in again</button>
    } @else {
      <p>Signing you in…</p>
    }
  `,
})
export class AuthCallback implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const returnTo = await this.auth.completeSignIn(
        new URLSearchParams(this.document.location.search),
      );
      await this.router.navigateByUrl(returnTo, { replaceUrl: true });
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Sign-in failed');
    }
  }

  protected retry(): void {
    void this.auth.signIn('/');
  }
}
