import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { CardCollection, MerchantAccount, PaymentClient, StoreClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { Session } from '../shared/session';
import { StoreContext } from './store-context';

interface ConnectData {
  scope: 'brands' | 'stores';
  ownerId: string;
  title: string;
  gateway: string;
  webhookUrl: string | null;
}

/** The gateway's keys for a merchant account, tried by the gateway before they are kept. */
@Component({
  selector: 'app-connect-account-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="hint">
        Create a webhook endpoint in your {{ gatewayName }} dashboard pointing at the address below,
        then paste your secret key and the endpoint's signing secret. We try the key before saving
        it, and never show it again.
      </p>
      @if (data.webhookUrl) {
        <div class="url">
          <span class="label">Webhook address</span>
          <code>{{ data.webhookUrl }}</code>
        </div>
      }
      <form id="connect" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Secret key</mat-label>
          <input
            matInput
            name="secretKey"
            [(ngModel)]="secretKey"
            autocomplete="off"
            spellcheck="false"
            required
          />
          <mat-hint>{{
            data.gateway === 'stripe'
              ? 'Starts with sk_live_ or sk_test_'
              : 'From the gateway dashboard'
          }}</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Webhook signing secret</mat-label>
          <input
            matInput
            name="webhookSecret"
            [(ngModel)]="webhookSecret"
            autocomplete="off"
            spellcheck="false"
            required
          />
          <mat-hint>{{
            data.gateway === 'stripe' ? 'Starts with whsec_' : 'Shown with the endpoint'
          }}</mat-hint>
        </mat-form-field>
      </form>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="submit"
        form="connect"
        [disabled]="busy() || !secretKey.trim() || !webhookSecret.trim()"
      >
        {{ busy() ? 'Checking…' : 'Check and connect' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .hint {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 16px;
    }
    .url {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px;
      margin-block-end: 16px;
      border-radius: 12px;
      background: var(--mat-sys-surface-container);
    }
    .url .label {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    code {
      font:
        500 13px/1.4 'Roboto Mono',
        ui-monospace,
        monospace;
      overflow-wrap: anywhere;
      user-select: all;
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .error {
      color: var(--mat-sys-error);
      margin: 12px 0 0;
    }
  `,
})
export class ConnectAccountDialog {
  protected readonly data = inject<ConnectData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConnectAccountDialog, MerchantAccount>);
  private readonly payments = inject(PaymentClient);

  protected secretKey = '';
  protected webhookSecret = '';
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly gatewayName =
    this.data.gateway === 'stripe'
      ? 'Stripe'
      : this.data.gateway === 'sandbox'
        ? 'sandbox'
        : 'gateway';

  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const account = await firstValueFrom(
        this.payments.connect(
          this.data.scope,
          this.data.ownerId,
          this.secretKey.trim(),
          this.webhookSecret.trim(),
        ),
      );
      this.ref.close(account);
    } catch (error) {
      this.error.set(describeError(error, 'The account could not be connected.'));
    } finally {
      this.busy.set(false);
    }
  }
}

/**
 * Card payments of a branch (PRD DP-1 to DP-8, BB-9): whether the operator or the branch collects,
 * the brand's and the branch's merchant accounts, and, for finance, the permission and credit limit.
 */
@Component({
  selector: 'app-store-payments',
  imports: [
    DatePipe,
    NgTemplateOutlet,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    @if (collection(); as c) {
      <div class="status" [attr.data-state]="state()" role="status">
        <fs-icon
          [name]="
            state() === 'direct' ? 'check_circle' : state() === 'suspended' ? 'warning' : 'info'
          "
        />
        <div>
          <strong>{{ headline() }}</strong>
          <span>{{ explanation() }}</span>
        </div>
      </div>

      @if (canManage()) {
        <mat-card appearance="outlined" class="card">
          <form class="card-section" (ngSubmit)="saveCollection()">
            <h2>Who collects</h2>
            <p class="section-hint">
              Allow the branch to take card payments into its own or its brand's merchant account.
              Commission, the operator's delivery charge, service fee and tips are then owed by the
              branch and netted in payouts.
            </p>
            @if (!c.enabled) {
              <p class="note">
                Direct collection is off for the platform. Switch it on in Settings, Ordering first.
              </p>
            }
            <mat-button-toggle-group
              name="mode"
              [(ngModel)]="mode"
              aria-label="Who collects card payments"
              hideSingleSelectionIndicator
            >
              <mat-button-toggle value="OPERATOR">The operator</mat-button-toggle>
              <mat-button-toggle value="DIRECT" [disabled]="!c.enabled"
                >The branch</mat-button-toggle
              >
            </mat-button-toggle-group>
            <div class="fields">
              <mat-form-field appearance="outline">
                <mat-label>Credit limit ({{ currency() }})</mat-label>
                <input
                  matInput
                  name="creditLimit"
                  inputmode="decimal"
                  [(ngModel)]="creditLimit"
                  placeholder="Platform default"
                />
                <mat-hint>Empty uses the platform default</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline" class="reason">
                <mat-label>Reason</mat-label>
                <input matInput name="reason" [(ngModel)]="reason" maxlength="500" required />
                <mat-hint>Kept in the audit log</mat-hint>
              </mat-form-field>
            </div>
            @if (collectionNote(); as note) {
              <p class="muted note-line">{{ note }}</p>
            }
            <div class="actions">
              <button mat-flat-button type="submit" [disabled]="saving() || !reason.trim()">
                Save
              </button>
            </div>
          </form>
        </mat-card>
      }

      <mat-card appearance="outlined" class="card">
        <section class="card-section">
          <h2>Merchant accounts</h2>
          <p class="section-hint">
            The branch's own account is used when it has one; otherwise the brand's, shared by all
            its branches. Payments already taken stay on the account they were taken on, so refunds
            go back the same way.
          </p>
          <div class="accounts">
            @if (c.brandId) {
              <ng-container
                *ngTemplateOutlet="
                  account;
                  context: {
                    title: 'Brand account',
                    account: c.brandAccount,
                    scope: 'brands',
                    ownerId: c.brandId,
                    canConnect: c.canConnectBrand,
                    webhookUrl: c.brandWebhookUrl,
                    inUse: c.accountId !== null && c.accountId === c.brandAccount?.id,
                  }
                "
              />
            }
            <ng-container
              *ngTemplateOutlet="
                account;
                context: {
                  title: 'This branch\\'s account',
                  account: c.storeAccount,
                  scope: 'stores',
                  ownerId: c.storeId,
                  canConnect: c.canConnectStore,
                  webhookUrl: c.storeWebhookUrl,
                  inUse: c.accountId !== null && c.accountId === c.storeAccount?.id,
                }
              "
            />
          </div>
        </section>
      </mat-card>
    } @else if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }

    <ng-template
      #account
      let-title="title"
      let-account="account"
      let-scope="scope"
      let-ownerId="ownerId"
      let-canConnect="canConnect"
      let-webhookUrl="webhookUrl"
      let-inUse="inUse"
    >
      <div class="account">
        <div class="account-head">
          <span class="icon" aria-hidden="true"><fs-icon name="credit_card" /></span>
          <div class="who">
            <strong>{{ title }}</strong>
            @if (account) {
              <span>{{ gatewayName() }} key ending {{ account.keyHint }}</span>
            } @else {
              <span>Not connected</span>
            }
          </div>
          @if (account) {
            <span class="chip" [attr.data-status]="account.status">{{
              account.status === 'ACTIVE' ? (inUse ? 'In use' : 'Ready') : 'Refused by gateway'
            }}</span>
          }
        </div>
        @if (account?.verifiedAt) {
          <p class="muted">Checked {{ account.verifiedAt * 1000 | date: 'medium' }}</p>
        }
        @if (account?.lastError) {
          <p class="error">{{ account.lastError }}</p>
        }
        @if (canConnect) {
          <div class="account-actions">
            <button
              mat-stroked-button
              type="button"
              (click)="connect(scope, ownerId, title, webhookUrl)"
            >
              <fs-icon name="key" /> {{ account ? 'Replace keys' : 'Connect' }}
            </button>
            @if (account) {
              <button mat-button type="button" (click)="disconnect(scope, ownerId, title)">
                Disconnect
              </button>
            }
          </div>
        }
      </div>
    </ng-template>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .status {
      display: flex;
      gap: 12px;
      padding: 14px 16px;
      border-radius: 12px;
      background: var(--mat-sys-surface-container);
      color: var(--mat-sys-on-surface);
    }
    .status > div {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font: var(--mat-sys-body-medium);
    }
    .status strong {
      font: var(--mat-sys-title-small);
    }
    .status[data-state='direct'] {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .status[data-state='suspended'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .note {
      padding: 10px 12px;
      border-radius: 10px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      font: var(--mat-sys-body-medium);
      margin: 0 0 12px;
    }
    .fields {
      display: grid;
      grid-template-columns: minmax(0, 240px) minmax(0, 1fr);
      gap: 12px 16px;
      margin-block-start: 16px;
    }
    mat-button-toggle-group {
      inline-size: fit-content;
    }
    .muted.note-line {
      margin-block-start: 12px;
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      margin-block-start: 8px;
    }
    .accounts {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 12px;
    }
    .account {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 16px;
      border-radius: 16px;
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .account-head {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .icon {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 12px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      flex: none;
    }
    .who {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
      flex: 1;
    }
    .who strong {
      font: var(--mat-sys-title-small);
    }
    .who span,
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
    .chip {
      font: var(--mat-sys-label-small);
      padding: 2px 8px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      white-space: nowrap;
    }
    .chip[data-status='ACTIVE'] {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .chip[data-status='FAILED'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .account-actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .error {
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-small);
      margin: 0;
    }
    @media (max-width: 599.98px) {
      .fields {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class StorePayments implements OnInit {
  protected readonly context = inject(StoreContext);
  private readonly stores = inject(StoreClient);
  private readonly payments = inject(PaymentClient);
  private readonly session = inject(Session);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly locale = inject(LocaleStore);

  protected readonly collection = signal<CardCollection | null>(null);
  protected readonly error = signal('');
  protected readonly saving = signal(false);
  protected readonly currency = computed(() => this.locale.currency());
  protected readonly canManage = computed(() =>
    this.session.can('store:payment-collection:manage'),
  );

  protected mode: 'OPERATOR' | 'DIRECT' = 'OPERATOR';
  protected creditLimit = '';
  protected reason = '';

  protected readonly state = computed(() => {
    const c = this.collection();
    if (!c) return 'operator';
    if (c.collectedBy === 'STORE') return 'direct';
    if (c.mode === 'DIRECT' && c.suspended) return 'suspended';
    return 'operator';
  });

  protected readonly headline = computed(() => {
    const c = this.collection();
    if (!c) return '';
    if (c.collectedBy === 'STORE') {
      return c.accountId === c.storeAccount?.id
        ? "Card payments go to this branch's account"
        : "Card payments go to the brand's account";
    }
    if (c.mode === 'DIRECT' && c.suspended) return 'Suspended: over the credit limit';
    return "Card payments go to the operator's account";
  });

  protected readonly explanation = computed(() => {
    const c = this.collection();
    if (!c) return '';
    if (c.collectedBy === 'STORE') {
      return 'The branch owes the operator its commission and fees, netted in payouts.';
    }
    if (c.mode === 'DIRECT' && c.suspended) {
      return 'The operator collects again until the balance is settled.';
    }
    if (c.mode === 'DIRECT' && !c.enabled) {
      return 'The branch is allowed, but direct collection is off for the platform.';
    }
    if (c.mode === 'DIRECT') {
      return 'The branch is allowed to collect. Connect a merchant account to start.';
    }
    return 'The operator pays the branch its share in payouts.';
  });

  protected readonly collectionNote = computed(() => {
    const store = this.context.store();
    const p = store?.paymentCollection;
    if (!p?.changedAt) return '';
    const when = new Date(p.changedAt * 1000).toLocaleDateString();
    return `Last changed ${when}${p.reason ? ': ' + p.reason : ''}`;
  });

  async ngOnInit(): Promise<void> {
    const store = this.context.store();
    if (store?.paymentCollection) {
      this.mode = store.paymentCollection.mode;
      this.creditLimit =
        store.paymentCollection.creditLimitMinor === null
          ? ''
          : toMajor(store.paymentCollection.creditLimitMinor, this.currency());
    }
    await this.load();
  }

  protected gatewayName(): string {
    const gateway = this.collection()?.gateway;
    return gateway === 'stripe' ? 'Stripe' : gateway === 'sandbox' ? 'Sandbox' : 'Gateway';
  }

  private async load(): Promise<void> {
    const store = this.context.store();
    if (!store) return;
    try {
      this.collection.set(await firstValueFrom(this.payments.collection(store.id)));
      this.error.set('');
    } catch (error) {
      this.error.set(describeError(error, 'Card payments could not be loaded.'));
    }
  }

  protected async saveCollection(): Promise<void> {
    const store = this.context.store();
    if (!store) return;
    const limit =
      this.creditLimit.trim() === '' ? null : toMinor(this.creditLimit, this.currency());
    if (this.creditLimit.trim() !== '' && limit === null) {
      this.snackBar.open('The credit limit must be a number of 0 or more.', undefined, {
        duration: 4000,
      });
      return;
    }
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.stores.setPaymentCollection(store.id, this.mode, limit, this.reason.trim()),
      );
      this.context.set(saved);
      this.reason = '';
      this.snackBar.open('Saved. It applies to the next card payment.', undefined, {
        duration: 4000,
      });
      // payment hears about the change through an event; read it again shortly after
      setTimeout(() => void this.load(), 1500);
    } catch (error) {
      this.snackBar.open(describeError(error, 'The change could not be saved.'), undefined, {
        duration: 5000,
      });
    } finally {
      this.saving.set(false);
    }
  }

  protected async connect(
    scope: 'brands' | 'stores',
    ownerId: string,
    title: string,
    webhookUrl: string | null,
  ): Promise<void> {
    const ref = this.dialog.open<ConnectAccountDialog, ConnectData, MerchantAccount>(
      ConnectAccountDialog,
      {
        data: {
          scope,
          ownerId,
          title: `Connect ${title.toLowerCase()}`,
          gateway: this.collection()?.gateway ?? '',
          webhookUrl,
        },
        width: '520px',
        maxWidth: '95vw',
      },
    );
    const account = await firstValueFrom(ref.afterClosed());
    if (account) {
      this.snackBar.open('Connected. The gateway accepted the key.', undefined, { duration: 4000 });
      await this.load();
    }
  }

  protected async disconnect(
    scope: 'brands' | 'stores',
    ownerId: string,
    title: string,
  ): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: `Disconnect the ${title.toLowerCase()}?`,
      message:
        "New card payments go to the next account, or to the operator's. Refunds of payments already taken still go through this account.",
      confirm: 'Disconnect',
      danger: true,
    });
    if (ok === null) return;
    try {
      await firstValueFrom(this.payments.disconnect(scope, ownerId));
      await this.load();
    } catch (error) {
      this.snackBar.open(
        describeError(error, 'The account could not be disconnected.'),
        undefined,
        {
          duration: 5000,
        },
      );
    }
  }
}
