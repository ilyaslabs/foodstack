import { HttpErrorResponse } from '@angular/common/http';
import { Component, afterNextRender, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialog } from '@angular/material/dialog';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import {
  MeClient,
  NotificationClient,
  NotificationPreferences,
  SavedAddress,
} from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { Icon } from '@foodstack/ui';

import { AddressBook, addressIcon, addressTitle } from '../addresses/address-book';
import { AddressEditor } from '../addresses/address-editor';
import { Ui, UiKey } from '../i18n';
import { LocationStore } from '../location-store';

/** Saved addresses (PRD ID-6), email choices (PRD NO-3) and password (TDD 9.1). */
@Component({
  selector: 'app-account-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    Icon,
  ],
  template: `
    <div class="page">
      <h1>{{ ui.t('account') }}</h1>

      <section class="card" id="addresses">
        <div class="card-head">
          <h2>{{ ui.t('savedAddresses') }}</h2>
          <button mat-stroked-button type="button" (click)="editAddress()">
            <fs-icon name="add" /> {{ ui.t('addAddress') }}
          </button>
        </div>
        @if (book.addresses(); as list) {
          @for (address of list; track address.id) {
            <div class="address">
              <span class="address-icon"><fs-icon [name]="icon(address)" /></span>
              <div class="address-text">
                <div class="address-title">
                  <strong>{{ title(address) }}</strong>
                  @if (address.preferred) {
                    <span class="badge">{{ ui.t('preferredBadge') }}</span>
                  }
                </div>
                <span class="muted">{{ address.addressLine }}</span>
                @if (address.instructions) {
                  <span class="muted small">{{ address.instructions }}</span>
                }
              </div>
              <div class="address-actions">
                <button mat-button type="button" (click)="editAddress(address)">
                  {{ ui.t('edit') }}
                </button>
                @if (!address.preferred) {
                  <button mat-button type="button" (click)="makePreferred(address)">
                    {{ ui.t('makePreferred') }}
                  </button>
                }
                <button
                  mat-icon-button
                  type="button"
                  (click)="removeAddress(address)"
                  [attr.aria-label]="ui.t('deleteAddress') + ': ' + title(address)"
                >
                  <fs-icon name="delete" />
                </button>
              </div>
            </div>
          } @empty {
            <p class="muted">{{ ui.t('noSavedAddresses') }}</p>
          }
        }
        @if (addressMessage(); as key) {
          <p class="status error" role="alert">{{ ui.t(key) }}</p>
        }
      </section>

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
      font-size: 32px;
      line-height: 40px;
      font-weight: 800;
      letter-spacing: -0.03em;
      margin: 0;
    }
    h2 {
      font-size: 20px;
      line-height: 28px;
      font-weight: 800;
      margin: 0 0 16px;
    }
    .card {
      padding: 24px;
      border-radius: var(--fs-radius-card);
      background: var(--mat-sys-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      box-shadow: var(--fs-shadow-card);
    }
    .card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-block-end: 8px;
    }
    .card-head h2 {
      margin: 0;
    }
    .address {
      display: flex;
      align-items: center;
      gap: 14px;
      padding-block: 14px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .address:last-of-type {
      border-block-end: 0;
    }
    .address-icon {
      display: grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 50%;
      flex: none;
      color: var(--mat-sys-primary);
      background: color-mix(in srgb, var(--mat-sys-primary) 12%, transparent);
    }
    .address-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
      min-width: 0;
    }
    .address-title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .badge {
      padding: 2px 8px;
      border-radius: 999px;
      font: var(--mat-sys-label-small);
      color: var(--mat-sys-on-primary-container);
      background: var(--mat-sys-primary-container);
    }
    .muted {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
    }
    .small {
      font: var(--mat-sys-body-small);
    }
    .address-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      justify-content: flex-end;
    }
    @media (max-width: 599.98px) {
      .address {
        flex-wrap: wrap;
      }
      .address-actions {
        inline-size: 100%;
        justify-content: flex-start;
        padding-inline-start: 54px;
      }
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
  private readonly dialogs = inject(MatDialog);
  private readonly locations = inject(LocationStore);
  protected readonly book = inject(AddressBook);
  protected readonly addressMessage = signal<UiKey | null>(null);

  protected readonly preferences = signal<NotificationPreferences | null>(null);
  protected readonly prefsMessage = signal<UiKey | null>(null);
  protected readonly passwordMessage = signal<{ key: UiKey; error: boolean } | null>(null);
  protected readonly busy = signal(false);
  protected current = '';
  protected next = '';

  constructor() {
    afterNextRender(async () => {
      void this.book.load(true);
      this.preferences.set(await firstValueFrom(this.notifications.preferences()));
    });
  }

  protected title(address: SavedAddress): string {
    return addressTitle(this.ui, address);
  }

  protected icon(address: SavedAddress) {
    return addressIcon(address);
  }

  protected editAddress(address?: SavedAddress): void {
    this.addressMessage.set(null);
    this.dialogs.open(AddressEditor, {
      data: { address, start: address ? null : this.locations.location() },
      width: '560px',
      maxWidth: '96vw',
      autoFocus: false,
    });
  }

  protected async makePreferred(address: SavedAddress): Promise<void> {
    this.addressMessage.set(null);
    try {
      await this.book.save({ ...address, preferred: true }, address.id);
    } catch {
      this.addressMessage.set('addressSaveFailed');
    }
  }

  protected async removeAddress(address: SavedAddress): Promise<void> {
    this.addressMessage.set(null);
    try {
      await this.book.remove(address.id);
      if (this.locations.location()?.addressId === address.id) {
        const {
          addressId: _,
          addressLine: __,
          instructions: ___,
          ...plain
        } = this.locations.location()!;
        this.locations.set(plain);
      }
    } catch {
      this.addressMessage.set('addressSaveFailed');
    }
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
