import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { AuthService } from './auth.service';

/** Every word on the sign-in card, so an app can pass its own language. */
export interface SignInTexts {
  signInTitle: string;
  signUpTitle: string;
  codeTitle: string;
  resetTitle: string;
  email: string;
  password: string;
  newPassword: string;
  passwordHint: string;
  name: string;
  code: string;
  signIn: string;
  signUp: string;
  sendCode: string;
  sendResetCode: string;
  setPassword: string;
  useCode: string;
  usePassword: string;
  forgot: string;
  newHere: string;
  haveAccount: string;
  codeSent: string;
  resend: string;
  back: string;
  errorLogin: string;
  errorCode: string;
  errorTooMany: string;
  errorTaken: string;
  errorPassword: string;
  errorBlocked: string;
  errorGeneric: string;
  required: string;
  invalidEmail: string;
}

export const SIGN_IN_TEXTS: SignInTexts = {
  signInTitle: 'Sign in',
  signUpTitle: 'Create your account',
  codeTitle: 'Sign in with a code',
  resetTitle: 'Set a new password',
  email: 'Email',
  password: 'Password',
  newPassword: 'New password',
  passwordHint: 'At least 10 characters',
  name: 'Your name',
  code: '6-digit code',
  signIn: 'Sign in',
  signUp: 'Create account',
  sendCode: 'Email me a code',
  sendResetCode: 'Email me a reset code',
  setPassword: 'Set password and sign in',
  useCode: 'Email me a code instead',
  usePassword: 'Use my password instead',
  forgot: 'Forgot password?',
  newHere: 'New here? Create an account',
  haveAccount: 'Already have an account? Sign in',
  codeSent: 'If {email} has an account, a code is on its way. It works for 5 minutes.',
  resend: 'Send a new code',
  back: 'Back to sign in',
  errorLogin: 'That email and password do not match.',
  errorCode: 'That code is wrong or has expired.',
  errorTooMany: 'Too many tries. Wait a few minutes and try again.',
  errorTaken: 'An account with this email already exists. Sign in instead.',
  errorPassword: 'Use 10 to 128 characters for the password.',
  errorBlocked: 'This account is blocked. Contact support.',
  errorGeneric: 'Something went wrong. Please try again.',
  required: 'Required',
  invalidEmail: 'Enter a valid email',
};

type Mode = 'password' | 'code' | 'reset' | 'signup';

/**
 * The sign-in card (TDD 9.1): password, emailed code, forgotten password and, where allowed,
 * sign-up. Talks to the stateless auth API and goes back to `returnTo` when done. No redirect to
 * another site, so the page keeps the app's own look.
 */
@Component({
  selector: 'fs-sign-in',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  template: `
    <section class="card" aria-labelledby="fs-sign-in-title">
      @if (busy()) {
        <mat-progress-bar mode="indeterminate" class="progress" />
      }
      <h1 id="fs-sign-in-title">{{ heading() }}</h1>
      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
      @if (notice(); as message) {
        <p class="notice" role="status">{{ message }}</p>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        @if (mode() === 'signup') {
          <mat-form-field appearance="outline">
            <mat-label>{{ t().name }}</mat-label>
            <input matInput formControlName="name" autocomplete="name" maxlength="80" />
            <mat-error>{{ t().required }}</mat-error>
          </mat-form-field>
        }
        <mat-form-field appearance="outline">
          <mat-label>{{ t().email }}</mat-label>
          <input
            matInput
            formControlName="email"
            type="email"
            autocomplete="username"
            inputmode="email"
            [readonly]="codeSent()"
          />
          <mat-error>{{
            form.controls.email.hasError('email') ? t().invalidEmail : t().required
          }}</mat-error>
        </mat-form-field>
        @if (mode() === 'password' || mode() === 'signup') {
          <mat-form-field appearance="outline">
            <mat-label>{{ t().password }}</mat-label>
            <input
              matInput
              formControlName="password"
              type="password"
              [attr.autocomplete]="mode() === 'signup' ? 'new-password' : 'current-password'"
            />
            @if (mode() === 'signup') {
              <mat-hint>{{ t().passwordHint }}</mat-hint>
            }
            <mat-error>{{ mode() === 'signup' ? t().passwordHint : t().required }}</mat-error>
          </mat-form-field>
        }
        @if ((mode() === 'code' || mode() === 'reset') && codeSent()) {
          <mat-form-field appearance="outline">
            <mat-label>{{ t().code }}</mat-label>
            <input
              matInput
              formControlName="code"
              autocomplete="one-time-code"
              inputmode="numeric"
              maxlength="6"
            />
            <mat-error>{{ t().required }}</mat-error>
          </mat-form-field>
        }
        @if (mode() === 'reset' && codeSent()) {
          <mat-form-field appearance="outline">
            <mat-label>{{ t().newPassword }}</mat-label>
            <input
              matInput
              formControlName="password"
              type="password"
              autocomplete="new-password"
            />
            <mat-hint>{{ t().passwordHint }}</mat-hint>
            <mat-error>{{ t().passwordHint }}</mat-error>
          </mat-form-field>
        }
        <button mat-flat-button type="submit" class="primary" [disabled]="busy()">
          {{ action() }}
        </button>
      </form>
      <nav class="links">
        @switch (mode()) {
          @case ('password') {
            <button mat-button type="button" (click)="switchTo('code')">{{ t().useCode }}</button>
            <button mat-button type="button" (click)="switchTo('reset')">{{ t().forgot }}</button>
            @if (allowSignUp()) {
              <button mat-button type="button" (click)="switchTo('signup')">
                {{ t().newHere }}
              </button>
            }
          }
          @case ('signup') {
            <button mat-button type="button" (click)="switchTo('password')">
              {{ t().haveAccount }}
            </button>
          }
          @default {
            @if (codeSent()) {
              <button mat-button type="button" (click)="resend()" [disabled]="busy()">
                {{ t().resend }}
              </button>
            }
            <button mat-button type="button" (click)="switchTo('password')">
              {{ mode() === 'code' ? t().usePassword : t().back }}
            </button>
          }
        }
      </nav>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .card {
      position: relative;
      overflow: hidden;
      max-inline-size: 420px;
      margin-inline: auto;
      padding: 32px 28px 20px;
      border-radius: var(--mat-sys-corner-extra-large);
      background: var(--mat-sys-surface-container-low);
      color: var(--mat-sys-on-surface);
      box-shadow: var(--mat-sys-level1);
    }
    .progress {
      position: absolute;
      inset-block-start: 0;
      inset-inline: 0;
    }
    h1 {
      margin: 0 0 20px;
      font: var(--mat-sys-headline-small);
      letter-spacing: 0;
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    mat-form-field {
      inline-size: 100%;
    }
    .primary {
      margin-block-start: 8px;
      block-size: 48px;
      font: var(--mat-sys-label-large);
    }
    .links {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 4px;
      margin-block-start: 16px;
    }
    .error,
    .notice {
      margin: 0 0 16px;
      padding: 12px 14px;
      border-radius: var(--mat-sys-corner-medium);
      font: var(--mat-sys-body-medium);
    }
    .error {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .notice {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    @media (max-width: 480px) {
      .card {
        padding: 24px 16px 12px;
        border-radius: var(--mat-sys-corner-large);
      }
    }
  `,
})
export class SignIn {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Customers may create an account here; back-office accounts are made by staff. */
  readonly allowSignUp = input(false);
  /** Words in the app's language; English where missing. */
  readonly texts = input<Partial<SignInTexts>>({});

  protected readonly t = computed<SignInTexts>(() => ({ ...SIGN_IN_TEXTS, ...this.texts() }));
  protected readonly mode = signal<Mode>(
    this.route.snapshot.queryParamMap.get('mode') === 'signup' ? 'signup' : 'password',
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly codeSent = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    email: new FormControl('', { nonNullable: true }),
    password: new FormControl('', { nonNullable: true }),
    code: new FormControl('', { nonNullable: true }),
  });

  protected readonly heading = computed(() => {
    const t = this.t();
    switch (this.mode()) {
      case 'signup':
        return t.signUpTitle;
      case 'code':
        return t.codeTitle;
      case 'reset':
        return t.resetTitle;
      default:
        return t.signInTitle;
    }
  });

  protected readonly action = computed(() => {
    const t = this.t();
    switch (this.mode()) {
      case 'signup':
        return t.signUp;
      case 'code':
        return this.codeSent() ? t.signIn : t.sendCode;
      case 'reset':
        return this.codeSent() ? t.setPassword : t.sendResetCode;
      default:
        return t.signIn;
    }
  });

  constructor() {
    this.applyValidators();
  }

  protected switchTo(mode: Mode): void {
    this.mode.set(mode);
    this.codeSent.set(false);
    this.error.set(null);
    this.notice.set(null);
    this.form.controls.password.reset('');
    this.form.controls.code.reset('');
    this.applyValidators();
  }

  protected async submit(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const { name, email, password, code } = this.form.getRawValue();
    const address = email.trim();
    this.busy.set(true);
    this.error.set(null);
    try {
      switch (this.mode()) {
        case 'password':
          await this.auth.loginWithPassword(address, password);
          break;
        case 'signup':
          await this.auth.register(address, password, name.trim());
          break;
        case 'code':
          if (!this.codeSent()) {
            await this.send(address, 'LOGIN');
            return;
          }
          await this.auth.loginWithCode(address, code.trim());
          break;
        case 'reset':
          if (!this.codeSent()) {
            await this.send(address, 'PASSWORD_RESET');
            return;
          }
          await this.auth.resetPassword(address, code.trim(), password);
          break;
      }
      await this.router.navigateByUrl(this.returnTo(), { replaceUrl: true });
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async resend(): Promise<void> {
    const email = this.form.controls.email.value.trim();
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.send(email, this.mode() === 'reset' ? 'PASSWORD_RESET' : 'LOGIN');
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private async send(email: string, purpose: 'LOGIN' | 'PASSWORD_RESET'): Promise<void> {
    await this.auth.requestCode(email, purpose);
    this.codeSent.set(true);
    this.notice.set(this.t().codeSent.replace('{email}', email));
    this.applyValidators();
  }

  /** Only a path inside this app, never another site. */
  private returnTo(): string {
    const target = this.route.snapshot.queryParamMap.get('returnTo') ?? '/';
    return target.startsWith('/') && !target.startsWith('//') ? target : '/';
  }

  private applyValidators(): void {
    const { name, email, password, code } = this.form.controls;
    const mode = this.mode();
    const sent = this.codeSent();
    email.setValidators([Validators.required, Validators.email]);
    name.setValidators(mode === 'signup' ? [Validators.required, Validators.maxLength(80)] : []);
    const newPassword = mode === 'signup' || (mode === 'reset' && sent);
    password.setValidators(
      newPassword
        ? [Validators.required, Validators.minLength(10), Validators.maxLength(128)]
        : mode === 'password'
          ? [Validators.required]
          : [],
    );
    code.setValidators(
      (mode === 'code' || mode === 'reset') && sent
        ? [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]
        : [],
    );
    for (const control of [name, email, password, code]) control.updateValueAndValidity();
  }

  private describe(error: unknown): string {
    const t = this.t();
    if (!(error instanceof HttpErrorResponse)) return t.errorGeneric;
    const code = (error.error as { code?: string } | null)?.code;
    if (error.status === 429) return t.errorTooMany;
    if (code === 'LOGIN_FAILED') return t.errorLogin;
    if (code === 'CODE_INVALID') return t.errorCode;
    if (code === 'EMAIL_TAKEN') return t.errorTaken;
    if (code === 'PASSWORD_INVALID') return t.errorPassword;
    if (code === 'USER_BLOCKED' || error.status === 403) return t.errorBlocked;
    if (error.status === 401) return t.errorLogin;
    return t.errorGeneric;
  }
}
