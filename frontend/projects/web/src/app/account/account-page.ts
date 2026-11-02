import { HttpErrorResponse } from '@angular/common/http';
import { Component, afterNextRender, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import { MeClient, NotificationClient, NotificationPreferences } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';

import { Ui, UiKey } from '../i18n';

/** Email choices (PRD NO-3) and password (TDD 9.1). */
@Component({
  selector: 'app-account-page',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  template: `
    <div class="page">
      <h1>{{ ui.t('account') }}</h1>

      <section class="card">
        <h2>{{ ui.t('emailChoices') }}</h2>
        @if (preferences(); as p) {
          <mat-slide-toggle
            [checked]="p.orderEmails"
            (change)="save({ orderEmails: $event.checked })"
          >
            {{ ui.t('orderEmails') }}
          </mat-slide-toggle>
          <p class="hint">{{ ui.t('orderEmailsHint') }}</p>
          <mat-slide-toggle
            [checked]="p.marketingEmails"
            (change)="save({ marketingEmails: $event.checked })"
          >
            {{ ui.t('marketingEmails') }}
          </mat-slide-toggle>
        }
        @if (prefsMessage(); as key) {
          <p class="status" role="status">{{ ui.t(key) }}</p>
        }
      </section>

      <section class="card">
        <h2>{{ ui.t('changePassword') }}</h2>
        <form (ngSubmit)="changePassword()" class="password">
          <mat-form-field appearance="outline">
            <mat-label>{{ ui.t('currentPassword') }}</mat-label>
            <input
              matInput
              type="password"
              name="current"
              autocomplete="current-password"
              required
              [(ngModel)]="current"
            />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ ui.t('newPassword') }}</mat-label>
            <input
              matInput
              type="password"
              name="next"
              autocomplete="new-password"
              minlength="10"
              maxlength="128"
              required
              [(ngModel)]="next"
            />
            <mat-hint>{{ ui.t('passwordHint') }}</mat-hint>
          </mat-form-field>
          <div class="row">
            <button
              mat-flat-button
              type="submit"
              [disabled]="busy() || !current || next.length < 10"
            >
              {{ ui.t('save') }}
            </button>
            @if (passwordMessage(); as message) {
              <span [class.error]="message.error" role="status">{{ ui.t(message.key) }}</span>
            }
          </div>
        </form>
      </section>

      <section class="card">
        <h2>{{ ui.t('security') }}</h2>
        <button mat-stroked-button type="button" (click)="signOutEverywhere()">
          {{ ui.t('signOutEverywhere') }}
        </button>
      </section>
    </div>
  `,
  styles: `
    .page {
      max-width: 640px;
      margin-inline: auto;
      padding: 24px 24px 64px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    @media (max-width: 599.98px) {
      .page {
        padding: 16px 16px 48px;
      }
    }
    h1 {
      font: var(--mat-sys-headline-medium);
      font-weight: 600;
      margin: 0;
    }
    h2 {
      font: var(--mat-sys-title-large);
      margin: 0 0 16px;
    }
    .card {
      padding: 24px;
      border-radius: 24px;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .hint {
      margin: 4px 0 16px;
      padding-inline-start: 52px;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .status {
      margin: 12px 0 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .password {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-block-start: 8px;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class AccountPage {
  protected readonly ui = inject(Ui);
  private readonly notifications = inject(NotificationClient);
  private readonly me = inject(MeClient);
  private readonly auth = inject(AuthService);

  protected readonly preferences = signal<NotificationPreferences | null>(null);
  protected readonly prefsMessage = signal<UiKey | null>(null);
  protected readonly passwordMessage = signal<{ key: UiKey; error: boolean } | null>(null);
  protected readonly busy = signal(false);
  protected current = '';
  protected next = '';

  constructor() {
    afterNextRender(async () => {
      this.preferences.set(await firstValueFrom(this.notifications.preferences()));
    });
  }

  protected async save(update: Partial<NotificationPreferences>): Promise<void> {
    try {
      this.preferences.set(await firstValueFrom(this.notifications.updatePreferences(update)));
      this.prefsMessage.set('saved');
    } catch {
      this.prefsMessage.set('saveFailed');
    }
  }

  protected async changePassword(): Promise<void> {
    this.busy.set(true);
    this.passwordMessage.set(null);
    try {
      await firstValueFrom(this.me.changePassword(this.current, this.next));
      this.current = '';
      this.next = '';
      this.passwordMessage.set({ key: 'passwordChanged', error: false });
    } catch (error) {
      const wrong = error instanceof HttpErrorResponse && error.error?.code === 'PASSWORD_WRONG';
      this.passwordMessage.set({ key: wrong ? 'passwordWrong' : 'saveFailed', error: true });
    } finally {
      this.busy.set(false);
    }
  }

  protected async signOutEverywhere(): Promise<void> {
    try {
      await firstValueFrom(this.me.revokeTokens());
    } finally {
      this.auth.signOut();
    }
  }
}
