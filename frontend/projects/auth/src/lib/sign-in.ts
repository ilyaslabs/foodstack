import {
  Component,
  ElementRef,
  OnInit,
  PLATFORM_ID,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { AuthService, SecondStep } from './auth.service';
import { QrCode } from './qr-code';
import { SocialScripts } from './social-scripts';

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
  twoStepTitle: string;
  twoStepIntro: string;
  twoStepCode: string;
  twoStepRecoveryHint: string;
  verify: string;
  enrollTitle: string;
  enrollIntro: string;
  enrollQr: string;
  enrollKey: string;
  enrollCode: string;
  enrollConfirm: string;
  copyKey: string;
  copied: string;
  recoveryTitle: string;
  recoveryIntro: string;
  copyCodes: string;
  downloadCodes: string;
  recoveryDone: string;
  startAgain: string;
  or: string;
  continueWithApple: string;
  errorTwoStep: string;
  errorExpired: string;
  errorSocial: string;
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
  twoStepTitle: 'Two-step sign-in',
  twoStepIntro: 'Enter the 6-digit code your authenticator app shows for this account.',
  twoStepCode: 'Code',
  twoStepRecoveryHint: 'Lost your phone? Enter one of your recovery codes instead.',
  verify: 'Verify',
  enrollTitle: 'Set up two-step sign-in',
  enrollIntro:
    'Your role needs a code from an authenticator app each time you sign in. Scan the QR code with an app such as Google Authenticator, Microsoft Authenticator or 1Password, then enter the code it shows.',
  enrollQr: 'QR code for your authenticator app',
  enrollKey: 'Can’t scan it? Type this key into the app:',
  enrollCode: '6-digit code from the app',
  enrollConfirm: 'Turn on and sign in',
  copyKey: 'Copy key',
  copied: 'Copied',
  recoveryTitle: 'Save your recovery codes',
  recoveryIntro:
    'Each code signs you in once if you lose your phone. Keep them somewhere safe, such as a password manager. They are not shown again.',
  copyCodes: 'Copy codes',
  downloadCodes: 'Download',
  recoveryDone: 'I’ve saved them, continue',
  startAgain: 'Start again',
  or: 'or',
  continueWithApple: 'Continue with Apple',
  errorTwoStep: 'That code is not correct. Check the app and try again.',
  errorExpired: 'The sign-in took too long. Start again.',
  errorSocial: 'That sign-in did not work. Try again, or use your email.',
};

type Mode = 'password' | 'code' | 'reset' | 'signup' | 'totp' | 'enroll' | 'recovery';

interface Providers {
  googleClientIds: string[];
  appleClientIds: string[];
}

/**
 * The sign-in card (TDD 9.1): password, emailed code, forgotten password and, where allowed,
 * sign-up and Google or Apple (PRD ID-2). When the account has two-step sign-in (PRD ID-5) the
 * card asks for the authenticator code, or walks through setting the app up when the role
 * requires it, and shows the recovery codes once. Talks to the stateless auth API and goes back
 * to `returnTo` when done. No redirect to another site, so the page keeps the app's own look.
 */
@Component({
  selector: 'fs-sign-in',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    QrCode,
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
      @switch (mode()) {
        @case ('totp') {
          <p class="intro">{{ t().twoStepIntro }}</p>
          <form [formGroup]="form" (ngSubmit)="verify()" novalidate>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>{{ t().twoStepCode }}</mat-label>
              <input
                matInput
                formControlName="code"
                autocomplete="one-time-code"
                inputmode="numeric"
                maxlength="12"
              />
              <mat-hint>{{ t().twoStepRecoveryHint }}</mat-hint>
              <mat-error>{{ t().required }}</mat-error>
            </mat-form-field>
            <button mat-flat-button type="submit" class="primary" [disabled]="busy()">
              {{ t().verify }}
            </button>
          </form>
          <nav class="links">
            <button mat-button type="button" (click)="switchTo('password')">
              {{ t().back }}
            </button>
          </nav>
        }
        @case ('enroll') {
          <p class="intro">{{ t().enrollIntro }}</p>
          @if (enrollment(); as setup) {
            <div class="enroll">
              <fs-qr-code [value]="setup.uri" [label]="t().enrollQr" />
              <div class="key">
                <span>{{ t().enrollKey }}</span>
                <code>{{ grouped(setup.secret) }}</code>
                <button mat-button type="button" (click)="copy(setup.secret)">
                  {{ copiedKey() ? t().copied : t().copyKey }}
                </button>
              </div>
            </div>
            <form [formGroup]="form" (ngSubmit)="confirm()" novalidate>
              <mat-form-field appearance="outline">
                <mat-label>{{ t().enrollCode }}</mat-label>
                <input
                  matInput
                  formControlName="code"
                  autocomplete="one-time-code"
                  inputmode="numeric"
                  maxlength="6"
                />
                <mat-error>{{ t().required }}</mat-error>
              </mat-form-field>
              <button mat-flat-button type="submit" class="primary" [disabled]="busy()">
                {{ t().enrollConfirm }}
              </button>
            </form>
          }
          <nav class="links">
            <button mat-button type="button" (click)="switchTo('password')">
              {{ t().startAgain }}
            </button>
          </nav>
        }
        @case ('recovery') {
          <p class="intro">{{ t().recoveryIntro }}</p>
          <ol class="codes" aria-label="Recovery codes">
            @for (code of recoveryCodes(); track code) {
              <li>
                <code>{{ code }}</code>
              </li>
            }
          </ol>
          <div class="code-actions">
            <button
              mat-stroked-button
              type="button"
              (click)="
                copy(
                  recoveryCodes().join(
                    '
'
                  )
                )
              "
            >
              {{ copiedKey() ? t().copied : t().copyCodes }}
            </button>
            <button mat-stroked-button type="button" (click)="download()">
              {{ t().downloadCodes }}
            </button>
          </div>
          <button mat-flat-button type="button" class="primary wide" (click)="done()">
            {{ t().recoveryDone }}
          </button>
        }
        @default {
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
                <button mat-button type="button" (click)="switchTo('code')">
                  {{ t().useCode }}
                </button>
                <button mat-button type="button" (click)="switchTo('reset')">
                  {{ t().forgot }}
                </button>
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
          @if (social() && ((google() && !googleFailed()) || apple())) {
            <div class="divider" role="separator">
              <span>{{ t().or }}</span>
            </div>
            <div class="social">
              @if (google()) {
                <div #googleButton class="google"></div>
              }
              @if (apple()) {
                <button
                  mat-stroked-button
                  type="button"
                  class="apple"
                  (click)="withApple()"
                  [disabled]="busy()"
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true" class="apple-logo">
                    <path
                      fill="currentColor"
                      d="M16.37 12.62c-.02-2.13 1.74-3.15 1.82-3.2-.99-1.45-2.54-1.65-3.09-1.67-1.31-.13-2.57.78-3.23.78-.67 0-1.69-.76-2.78-.74-1.43.02-2.75.83-3.49 2.11-1.49 2.58-.38 6.4 1.07 8.5.71 1.02 1.55 2.17 2.65 2.13 1.07-.04 1.47-.69 2.76-.69 1.28 0 1.65.69 2.77.67 1.15-.02 1.87-1.04 2.57-2.07.81-1.18 1.14-2.33 1.16-2.39-.03-.01-2.21-.85-2.23-3.37zM14.27 6.36c.58-.71.98-1.69.87-2.67-.84.03-1.86.56-2.46 1.27-.54.62-1.01 1.62-.89 2.58.94.07 1.9-.48 2.48-1.18z"
                    />
                  </svg>
                  {{ t().continueWithApple }}
                </button>
              }
            </div>
          }
        }
      }
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
    .intro {
      margin: -8px 0 20px;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .enroll {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      margin-block-end: 20px;
    }
    .key {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      text-align: center;
    }
    code {
      font-family: 'Roboto Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 15px;
      letter-spacing: 0.04em;
      color: var(--mat-sys-on-surface);
      overflow-wrap: anywhere;
    }
    .codes {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 8px 16px;
      margin: 0 0 16px;
      padding: 16px 16px 16px 36px;
      border-radius: var(--mat-sys-corner-medium);
      background: var(--mat-sys-surface-container-highest);
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .code-actions {
      display: flex;
      gap: 8px;
      justify-content: center;
      margin-block-end: 8px;
    }
    .wide {
      inline-size: 100%;
    }
    .divider {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-block: 12px;
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .divider::before,
    .divider::after {
      content: '';
      flex: 1;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
    .social {
      display: flex;
      flex-direction: column;
      align-items: stretch;
      gap: 12px;
      padding-block-end: 8px;
    }
    .google {
      display: flex;
      justify-content: center;
      min-block-size: 44px;
    }
    .apple {
      --mat-button-outlined-label-text-color: var(--mat-sys-on-surface);
      block-size: 44px;
      border-radius: 22px;
      font: var(--mat-sys-label-large);
    }
    .apple-logo {
      inline-size: 18px;
      block-size: 18px;
      margin-inline-end: 8px;
      vertical-align: -3px;
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
      .codes {
        padding-inline-start: 28px;
      }
      .card {
        padding: 24px 16px 12px;
        border-radius: var(--mat-sys-corner-large);
      }
    }
  `,
})
export class SignIn implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);
  private readonly scripts = inject(SocialScripts);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  /** Customers may create an account here; back-office accounts are made by staff. */
  readonly allowSignUp = input(false);
  /** Offer Google and Apple when the operator switched them on (PRD ID-2). */
  readonly social = input(false);
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
  protected readonly enrollment = signal<{ secret: string; uri: string } | null>(null);
  protected readonly recoveryCodes = signal<string[]>([]);
  protected readonly copiedKey = signal(false);
  private readonly providers = signal<Providers>({ googleClientIds: [], appleClientIds: [] });
  protected readonly google = computed(() => this.providers().googleClientIds[0] ?? null);
  protected readonly apple = computed(() => this.providers().appleClientIds[0] ?? null);
  // Google's script blocked or offline: the button goes, the email sign-in stays
  protected readonly googleFailed = signal(false);
  private readonly googleButton = viewChild<ElementRef<HTMLElement>>('googleButton');
  private challenge = '';

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
      case 'totp':
        return t.twoStepTitle;
      case 'enroll':
        return t.enrollTitle;
      case 'recovery':
        return t.recoveryTitle;
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
    // Google draws its own button, once the placeholder is on the page
    effect(() => {
      const element = this.googleButton()?.nativeElement;
      const clientId = this.google();
      if (!element || !clientId) return;
      void this.scripts
        .renderGoogle(clientId, element, (token) => void this.withGoogle(token), {
          width: element.clientWidth || 320,
          dark:
            this.document.defaultView?.matchMedia?.('(prefers-color-scheme: dark)').matches ??
            false,
          locale: this.document.documentElement.lang || undefined,
        })
        .catch(() => this.googleFailed.set(true));
    });
  }

  ngOnInit(): void {
    if (this.browser) void this.loadProviders();
  }

  protected switchTo(mode: Mode): void {
    this.mode.set(mode);
    this.codeSent.set(false);
    this.enrollment.set(null);
    this.copiedKey.set(false);
    if (mode !== 'totp' && mode !== 'enroll') this.challenge = '';
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
      let next: SecondStep | null = null;
      switch (this.mode()) {
        case 'password':
          next = await this.auth.loginWithPassword(address, password);
          break;
        case 'signup':
          next = await this.auth.register(address, password, name.trim());
          break;
        case 'code':
          if (!this.codeSent()) {
            await this.send(address, 'LOGIN');
            return;
          }
          next = await this.auth.loginWithCode(address, code.trim());
          break;
        case 'reset':
          if (!this.codeSent()) {
            await this.send(address, 'PASSWORD_RESET');
            return;
          }
          next = await this.auth.resetPassword(address, code.trim(), password);
          break;
      }
      await this.proceed(next);
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  /** The authenticator code (or a recovery code) for the second step. */
  protected async verify(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    await this.run(async () => {
      await this.auth.completeTwoStep(this.challenge, this.form.controls.code.value.trim());
      await this.done();
    });
  }

  /** Confirms the new authenticator app; the recovery codes come next. */
  protected async confirm(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    await this.run(async () => {
      const codes = await this.auth.confirmEnrollment(
        this.challenge,
        this.form.controls.code.value.trim(),
      );
      this.challenge = '';
      this.recoveryCodes.set(codes);
      this.mode.set('recovery');
      this.copiedKey.set(false);
      this.notice.set(null);
    });
  }

  protected async done(): Promise<void> {
    await this.router.navigateByUrl(this.returnTo(), { replaceUrl: true });
  }

  /** The secret in groups of four, easier to type. */
  protected grouped(secret: string): string {
    return secret.replace(/(.{4})/g, '$1 ').trim();
  }

  protected async copy(text: string): Promise<void> {
    try {
      await this.document.defaultView?.navigator.clipboard.writeText(text);
      this.copiedKey.set(true);
    } catch {
      // the text stays on screen to copy by hand
    }
  }

  protected download(): void {
    const view = this.document.defaultView;
    if (!view) return;
    const blob = new Blob([`${this.recoveryCodes().join('\n')}\n`], { type: 'text/plain' });
    const link = this.document.createElement('a');
    link.href = view.URL.createObjectURL(blob);
    link.download = `recovery-codes-${view.location.hostname}.txt`;
    link.click();
    view.URL.revokeObjectURL(link.href);
  }

  protected async withApple(): Promise<void> {
    const clientId = this.apple();
    if (!clientId || this.busy()) return;
    let result;
    try {
      result = await this.scripts.signInWithApple(clientId);
    } catch {
      return; // closed the popup
    }
    await this.run(async () =>
      this.proceed(await this.auth.loginWithSocial('apple', result.idToken, result.name)),
    );
  }

  private async withGoogle(idToken: string): Promise<void> {
    await this.run(async () => this.proceed(await this.auth.loginWithSocial('google', idToken)));
  }

  /** Signed in, or on to the second step the account still has to take. */
  private async proceed(next: SecondStep | null): Promise<void> {
    if (!next) {
      await this.done();
      return;
    }
    this.challenge = next.challenge;
    this.form.controls.code.reset('');
    this.form.controls.password.reset('');
    this.notice.set(null);
    this.codeSent.set(false);
    this.mode.set(next.next === 'TOTP' ? 'totp' : 'enroll');
    this.applyValidators();
    if (next.next === 'TOTP_ENROLL') {
      this.enrollment.set(await this.auth.startEnrollment(next.challenge));
    }
  }

  private async run(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await work();
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private async loadProviders(): Promise<void> {
    if (!this.social()) return;
    try {
      this.providers.set(await this.auth.providers());
    } catch {
      // the email sign-in still works
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
      ((mode === 'code' || mode === 'reset') && sent) || mode === 'enroll'
        ? [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]
        : mode === 'totp'
          ? // six digits, or a recovery code like k3f9x-2m7qa
            [
              Validators.required,
              Validators.pattern(/^\s*(\d{6}|[a-z0-9]{5}[\s-]?[a-z0-9]{5})\s*$/i),
            ]
          : [],
    );
    if (mode === 'totp' || mode === 'enroll') email.setValidators([]);
    for (const control of [name, email, password, code]) control.updateValueAndValidity();
  }

  private describe(error: unknown): string {
    const t = this.t();
    if (!(error instanceof HttpErrorResponse)) return t.errorGeneric;
    const code = (error.error as { code?: string } | null)?.code;
    if (error.status === 429) return t.errorTooMany;
    if (code === 'TOTP_INVALID') return t.errorTwoStep;
    if (code === 'CHALLENGE_INVALID') return t.errorExpired;
    if (code?.startsWith('SOCIAL_')) return t.errorSocial;
    if (code === 'LOGIN_FAILED') return t.errorLogin;
    if (code === 'CODE_INVALID') return t.errorCode;
    if (code === 'EMAIL_TAKEN') return t.errorTaken;
    if (code === 'PASSWORD_INVALID') return t.errorPassword;
    if (code === 'USER_BLOCKED' || error.status === 403) return t.errorBlocked;
    if (error.status === 401) return t.errorLogin;
    return t.errorGeneric;
  }
}
