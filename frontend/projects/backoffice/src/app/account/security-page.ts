import { Component, OnInit, inject, signal } from '@angular/core';
import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TwoStepClient, TwoStepEnrollment, TwoStepStatus } from '@foodstack/api-client';
import { QrCode } from '@foodstack/auth';

import { describeError } from '../shared/errors';

type Step = 'status' | 'enroll' | 'codes' | 'confirm-disable' | 'confirm-codes';

/**
 * The signed-in person's two-step sign-in (PRD ID-5): turn an authenticator app on, get new
 * recovery codes, or turn it off where the role allows. Every change asks for a current code.
 */
@Component({
  selector: 'app-security-page',
  imports: [
    FormsModule,
    NgTemplateOutlet,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
    QrCode,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Two-step sign-in</h1>
      <p>
        After your password, the back office asks for a code from an authenticator app on your
        phone, so a leaked password alone cannot open your account.
      </p>
    </header>

    @if (status(); as s) {
      <mat-card appearance="outlined">
        @switch (step()) {
          @case ('enroll') {
            <form (ngSubmit)="confirm()">
              <div class="card-section">
                <h2>Add this account to your authenticator app</h2>
                <p class="section-hint">
                  Scan the QR code with an app such as Google Authenticator, Microsoft Authenticator
                  or 1Password, then enter the 6-digit code it shows.
                </p>
                @if (enrollment(); as setup) {
                  <div class="enroll">
                    <fs-qr-code [value]="setup.uri" label="QR code for your authenticator app" />
                    <div class="key">
                      <span>Can’t scan it? Type this key into the app:</span>
                      <code>{{ grouped(setup.secret) }}</code>
                    </div>
                  </div>
                }
                <mat-form-field appearance="outline" class="code">
                  <mat-label>6-digit code from the app</mat-label>
                  <input
                    matInput
                    name="code"
                    [(ngModel)]="code"
                    autocomplete="one-time-code"
                    inputmode="numeric"
                    maxlength="6"
                    required
                  />
                </mat-form-field>
              </div>
              @if (error()) {
                <div class="banner error" role="alert">{{ error() }}</div>
              }
              <div class="actions">
                <button mat-button type="button" (click)="back()">Cancel</button>
                <button mat-flat-button type="submit" [disabled]="busy()">Turn on</button>
              </div>
            </form>
          }
          @case ('codes') {
            <div class="card-section">
              <h2>Save your recovery codes</h2>
              <p class="section-hint">
                Each code signs you in once if you lose your phone. Keep them somewhere safe, such
                as a password manager. They are not shown again, and any older codes no longer work.
              </p>
              <ol class="codes" aria-label="Recovery codes">
                @for (c of recoveryCodes(); track c) {
                  <li>
                    <code>{{ c }}</code>
                  </li>
                }
              </ol>
            </div>
            <div class="actions">
              <button mat-stroked-button type="button" (click)="copyCodes()">Copy codes</button>
              <button mat-stroked-button type="button" (click)="download()">Download</button>
              <button mat-flat-button type="button" (click)="back()">Done</button>
            </div>
          }
          @case ('confirm-disable') {
            <ng-container *ngTemplateOutlet="askCode; context: { action: 'Turn off' }" />
          }
          @case ('confirm-codes') {
            <ng-container *ngTemplateOutlet="askCode; context: { action: 'Get new codes' }" />
          }
          @default {
            <div class="card-section">
              <div class="state">
                <span class="chip" [class.live]="s.enabled" [class.closed]="!s.enabled">
                  {{ s.enabled ? 'On' : 'Off' }}
                </span>
                <span>
                  @if (s.enabled) {
                    You enter a code from your authenticator app each time you sign in.
                    {{ s.recoveryCodesLeft }} of 10 recovery codes left.
                  } @else {
                    You sign in with your password or an emailed code only.
                  }
                </span>
              </div>
              @if (s.required) {
                <p class="section-hint">Your role requires two-step sign-in, so it stays on.</p>
              }
              @if (s.enabled && s.recoveryCodesLeft <= 3) {
                <div class="banner warn" role="status">
                  Few recovery codes left. Get new ones so a lost phone does not lock you out.
                </div>
              }
            </div>
            @if (error()) {
              <div class="banner error" role="alert">{{ error() }}</div>
            }
            <div class="actions">
              @if (s.enabled) {
                @if (!s.required) {
                  <button mat-button type="button" (click)="ask('confirm-disable')">
                    Turn off
                  </button>
                }
                <button mat-stroked-button type="button" (click)="ask('confirm-codes')">
                  New recovery codes
                </button>
              } @else {
                <button mat-flat-button type="button" (click)="start()" [disabled]="busy()">
                  Turn on
                </button>
              }
            </div>
          }
        }
      </mat-card>
    } @else if (loadError()) {
      <p>Two-step sign-in could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }

    <ng-template #askCode let-action="action">
      <form (ngSubmit)="step() === 'confirm-disable' ? disable() : newCodes()">
        <div class="card-section">
          <h2>{{ action }}</h2>
          <p class="section-hint">Enter the code your authenticator app shows now.</p>
          <mat-form-field appearance="outline" class="code">
            <mat-label>Code</mat-label>
            <input
              matInput
              name="code"
              [(ngModel)]="code"
              autocomplete="one-time-code"
              inputmode="numeric"
              maxlength="12"
              required
            />
            <mat-hint>Or one of your recovery codes</mat-hint>
          </mat-form-field>
        </div>
        @if (error()) {
          <div class="banner error" role="alert">{{ error() }}</div>
        }
        <div class="actions">
          <button mat-button type="button" (click)="back()">Cancel</button>
          <button mat-flat-button type="submit" [disabled]="busy()">{{ action }}</button>
        </div>
      </form>
    </ng-template>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 720px;
    }
    .state {
      display: flex;
      align-items: center;
      gap: 12px;
      font: var(--mat-sys-body-large);
    }
    .chip {
      flex: none;
      padding: 4px 12px;
      border-radius: 999px;
      font: var(--mat-sys-label-large);
    }
    .chip.live {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .chip.closed {
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .enroll {
      display: flex;
      align-items: center;
      gap: 24px;
      flex-wrap: wrap;
    }
    .key {
      display: flex;
      flex-direction: column;
      gap: 6px;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      min-inline-size: 0;
    }
    code {
      font-family: 'Roboto Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 15px;
      letter-spacing: 0.04em;
      color: var(--mat-sys-on-surface);
      overflow-wrap: anywhere;
    }
    .code {
      max-inline-size: 280px;
    }
    .codes {
      display: grid;
      grid-template-columns: repeat(2, max-content);
      gap: 8px 40px;
      margin: 0;
      padding: 16px 16px 16px 40px;
      border-radius: var(--mat-sys-corner-medium);
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .banner.warn {
      margin: 4px 0 0;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    @media (max-width: 599.98px) {
      .codes {
        grid-template-columns: max-content;
      }
    }
  `,
})
export class SecurityPage implements OnInit {
  private readonly client = inject(TwoStepClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly document = inject(DOCUMENT);

  protected readonly status = signal<TwoStepStatus | null>(null);
  protected readonly loadError = signal(false);
  protected readonly step = signal<Step>('status');
  protected readonly enrollment = signal<TwoStepEnrollment | null>(null);
  protected readonly recoveryCodes = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected code = '';

  async ngOnInit(): Promise<void> {
    try {
      this.status.set(await firstValueFrom(this.client.status()));
    } catch {
      this.loadError.set(true);
    }
  }

  protected async start(): Promise<void> {
    await this.run(async () => {
      this.enrollment.set(await firstValueFrom(this.client.enroll()));
      this.code = '';
      this.step.set('enroll');
    }, 'The authenticator app could not be set up.');
  }

  protected async confirm(): Promise<void> {
    await this.run(async () => {
      this.recoveryCodes.set(await firstValueFrom(this.client.confirm(this.code.trim())));
      this.enrollment.set(null);
      this.step.set('codes');
      await this.reload();
    }, 'That code is not correct.');
  }

  protected ask(step: 'confirm-disable' | 'confirm-codes'): void {
    this.code = '';
    this.error.set('');
    this.step.set(step);
  }

  protected async disable(): Promise<void> {
    await this.run(async () => {
      await firstValueFrom(this.client.disable(this.code.trim()));
      this.step.set('status');
      await this.reload();
      this.snackBar.open('Two-step sign-in is off', undefined, { duration: 4000 });
    }, 'That code is not correct.');
  }

  protected async newCodes(): Promise<void> {
    await this.run(async () => {
      this.recoveryCodes.set(await firstValueFrom(this.client.newRecoveryCodes(this.code.trim())));
      this.step.set('codes');
      await this.reload();
    }, 'That code is not correct.');
  }

  protected back(): void {
    this.code = '';
    this.error.set('');
    this.recoveryCodes.set([]);
    this.step.set('status');
  }

  protected grouped(secret: string): string {
    return secret.replace(/(.{4})/g, '$1 ').trim();
  }

  protected async copyCodes(): Promise<void> {
    try {
      await this.document.defaultView?.navigator.clipboard.writeText(
        this.recoveryCodes().join('\n'),
      );
      this.snackBar.open('Recovery codes copied', undefined, { duration: 3000 });
    } catch {
      // the codes stay on screen to copy by hand
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

  private async reload(): Promise<void> {
    this.status.set(await firstValueFrom(this.client.status()));
  }

  private async run(work: () => Promise<void>, fallback: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await work();
    } catch (error) {
      this.error.set(describeError(error, fallback));
    } finally {
      this.busy.set(false);
    }
  }
}
