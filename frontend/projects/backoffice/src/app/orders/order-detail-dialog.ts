import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatRadioModule } from '@angular/material/radio';

import { Order, OrderClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { STATUS_LABELS, clock, dateTime, statusTone } from './order-labels';

/** One order for support: what was bought, every step with who did it, cancel and refund (PRD OF-6). */
@Component({
  selector: 'app-order-detail-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatRadioModule,
    Icon,
  ],
  template: `
    @if (order(); as o) {
      <h2 mat-dialog-title>
        Order #{{ o.number }}
        <span class="status" [attr.data-tone]="tone(o.status)">{{ labels[o.status] }}</span>
      </h2>
      <mat-dialog-content>
        <p class="muted">
          {{ o.store.name }} · {{ o.customerName }}
          @if (o.contactPhone) {
            · <a [href]="'tel:' + o.contactPhone" dir="ltr">{{ o.contactPhone }}</a>
          }
          · {{ dateTime(o.placedAt, o.store.timezone) }}
        </p>
        <div class="grid">
          <section>
            <h3>Items</h3>
            <ul class="lines">
              @for (line of o.lines; track $index) {
                <li>
                  <span>{{ line.quantity }}× {{ locale.pick(line.name) }}</span>
                  <span>{{ money(line.lineTotalMinor) }}</span>
                </li>
              }
            </ul>
            <dl class="totals">
              <div>
                <dt>Food</dt>
                <dd>{{ money(o.totals.subtotalMinor) }}</dd>
              </div>
              @if (o.totals.itemsDiscountMinor + o.totals.deliveryDiscountMinor) {
                <div>
                  <dt>Discount {{ o.coupon ? '(' + o.coupon.code + ')' : '' }}</dt>
                  <dd>
                    −{{ money(o.totals.itemsDiscountMinor + o.totals.deliveryDiscountMinor) }}
                  </dd>
                </div>
              }
              @if (o.totals.deliveryChargeMinor) {
                <div>
                  <dt>Delivery</dt>
                  <dd>{{ money(o.totals.deliveryChargeMinor) }}</dd>
                </div>
              }
              @if (o.totals.serviceFeeMinor) {
                <div>
                  <dt>Service fee</dt>
                  <dd>{{ money(o.totals.serviceFeeMinor) }}</dd>
                </div>
              }
              @if (o.totals.taxMinor) {
                <div>
                  <dt>Tax</dt>
                  <dd>{{ money(o.totals.taxMinor) }}</dd>
                </div>
              }
              @if (o.totals.tipMinor) {
                <div>
                  <dt>Tip</dt>
                  <dd>{{ money(o.totals.tipMinor) }}</dd>
                </div>
              }
              <div class="grand">
                <dt>Total</dt>
                <dd>{{ money(o.totals.totalMinor) }}</dd>
              </div>
            </dl>
            <p class="muted">
              {{ o.paymentMethod === 'CARD' ? 'Card' : 'Cash' }}
              @if (o.paidMinor) {
                · paid {{ money(o.paidMinor) }}
              }
              @if (o.refundRequestedMinor) {
                · refund asked {{ money(o.refundRequestedMinor) }}
              }
              @if (o.refundedMinor) {
                · refunded {{ money(o.refundedMinor) }}
              }
            </p>
          </section>
          <section>
            <h3>Timeline</h3>
            <ol class="timeline">
              @for (step of o.timeline; track $index) {
                <li>
                  <span class="when">{{ clock(step.at, o.store.timezone) }}</span>
                  <span>
                    <strong>{{ labels[step.status] }}</strong>
                    @if (step.actorRole) {
                      <span class="muted"> by {{ step.actorRole.toLowerCase() }}</span>
                    }
                    @if (step.note) {
                      <span class="note">“{{ step.note }}”</span>
                    }
                  </span>
                </li>
              }
            </ol>
          </section>
        </div>
        @if (refunding()) {
          <form class="refund" (ngSubmit)="refund()">
            <h3>Refund to the card</h3>
            <div class="refund-fields">
              <mat-form-field appearance="outline">
                <mat-label>Amount ({{ o.currency }})</mat-label>
                <input matInput name="amount" inputmode="decimal" required [(ngModel)]="amount" />
                <mat-hint>Up to {{ money(refundable()) }}</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Reason</mat-label>
                <input matInput name="reason" required maxlength="200" [(ngModel)]="reason" />
              </mat-form-field>
            </div>
            <mat-radio-group
              class="funder"
              name="fundedBy"
              aria-label="Who pays the refund"
              [(ngModel)]="fundedBy"
            >
              <span class="funder-label">Who pays it</span>
              <mat-radio-button value="STORE">The store</mat-radio-button>
              <mat-radio-button value="OPERATOR">We do</mat-radio-button>
            </mat-radio-group>
            <p class="funder-hint">
              {{
                fundedBy === 'STORE'
                  ? 'Taken from what we owe the store for this order.'
                  : 'The store keeps its full share; we bear the refund.'
              }}
            </p>
            <div class="refund-actions">
              <button mat-button type="button" (click)="refunding.set(false)">Cancel</button>
              <button mat-flat-button type="submit" [disabled]="busy() || !reason.trim()">
                Refund
              </button>
            </div>
          </form>
        }
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions>
        @if (o.actions.includes('CANCEL')) {
          <button mat-button type="button" class="danger" [disabled]="busy()" (click)="cancel()">
            Cancel order
          </button>
        }
        @if (o.actions.includes('REFUND') && !refunding()) {
          <button mat-button type="button" [disabled]="busy()" (click)="startRefund()">
            <fs-icon name="credit_card" /> Refund
          </button>
        }
        <span class="spacer"></span>
        <button mat-flat-button type="button" [mat-dialog-close]="changed">Close</button>
      </mat-dialog-actions>
    } @else {
      <mat-dialog-content><mat-progress-bar mode="indeterminate" /></mat-dialog-content>
    }
  `,
  styles: `
    h2 {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .status {
      font: var(--mat-sys-label-large);
      padding: 2px 10px;
      border-radius: 999px;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .status[data-tone='new'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .status[data-tone='stopped'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 24px;
    }
    @media (max-width: 719.98px) {
      .grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    h3 {
      font: var(--mat-sys-title-small);
      margin: 8px 0;
    }
    .lines {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .lines li,
    .totals div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding-block: 4px;
    }
    .totals {
      margin: 8px 0;
      padding-block-start: 8px;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
    .totals dd {
      margin: 0;
    }
    .totals dt {
      color: var(--mat-sys-on-surface-variant);
    }
    .grand {
      font: var(--mat-sys-title-small);
    }
    .timeline {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .timeline li {
      display: flex;
      gap: 12px;
      padding-block: 6px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .when {
      min-inline-size: 64px;
      color: var(--mat-sys-on-surface-variant);
      font-variant-numeric: tabular-nums;
    }
    .note {
      display: block;
      color: var(--mat-sys-on-surface-variant);
    }
    .refund {
      margin-block-start: 16px;
      padding: 16px;
      border-radius: 16px;
      background: var(--mat-sys-surface-container);
    }
    .refund-fields {
      display: grid;
      grid-template-columns: 1fr 2fr;
      gap: 12px;
    }
    .funder {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
    }
    .funder-label {
      font: var(--mat-sys-label-large);
      margin-inline-end: 4px;
    }
    .funder-hint {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 8px;
    }
    .refund-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
    .error {
      color: var(--mat-sys-error);
    }
    .danger {
      --mat-button-text-label-text-color: var(--mat-sys-error);
    }
    .spacer {
      flex: 1;
    }
  `,
})
export class OrderDetailDialog {
  private readonly orderId = inject<string>(MAT_DIALOG_DATA);
  private readonly orders = inject(OrderClient);
  private readonly dialog = inject(MatDialog);
  protected readonly ref = inject(MatDialogRef<OrderDetailDialog, boolean>);
  protected readonly locale = inject(LocaleStore);

  protected readonly order = signal<Order | null>(null);
  protected readonly busy = signal(false);
  protected readonly refunding = signal(false);
  protected readonly error = signal('');
  protected readonly labels = STATUS_LABELS;
  protected readonly clock = clock;
  protected readonly dateTime = dateTime;
  protected readonly tone = statusTone;
  protected changed = false;
  protected amount = '';
  protected reason = '';
  /** Who bears the refund once the order is done (ilyas, 2026-11-06): the store by default. */
  protected fundedBy: 'STORE' | 'OPERATOR' = 'STORE';

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.order.set(await firstValueFrom(this.orders.get(this.orderId)));
    } catch (error) {
      this.error.set(describeError(error, 'The order could not be loaded.'));
    }
  }

  protected money(minor: number): string {
    return this.locale.money(minor, this.order()?.currency);
  }

  protected refundable(): number {
    const o = this.order();
    return o ? Math.max(0, o.paidMinor - o.refundRequestedMinor) : 0;
  }

  protected startRefund(): void {
    this.amount = toMajor(this.refundable(), this.order()?.currency ?? 'USD');
    this.reason = '';
    this.fundedBy = 'STORE';
    this.refunding.set(true);
  }

  protected async refund(): Promise<void> {
    const o = this.order();
    if (!o) return;
    const minor = toMinor(this.amount, o.currency);
    if (minor === null || minor <= 0 || minor > this.refundable()) {
      this.error.set(`Enter an amount up to ${this.money(this.refundable())}.`);
      return;
    }
    await this.run(() =>
      firstValueFrom(this.orders.refund(o.id, minor, this.reason.trim(), this.fundedBy)),
    );
    this.refunding.set(false);
  }

  protected async cancel(): Promise<void> {
    const o = this.order();
    if (!o) return;
    const reason = await confirmAction(this.dialog, {
      title: `Cancel order #${o.number}?`,
      message:
        o.paymentMethod === 'CARD' && o.paidMinor
          ? 'The customer and the store are told, and the card payment is refunded in full.'
          : 'The customer and the store are told.',
      confirm: 'Cancel order',
      danger: true,
      inputLabel: 'Reason',
      inputRequired: true,
    });
    if (reason === null) return;
    await this.run(() => firstValueFrom(this.orders.cancel(o.id, reason)));
  }

  private async run(work: () => Promise<Order>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      this.order.set(await work());
      this.changed = true;
    } catch (error) {
      this.error.set(describeError(error, 'That did not work. Try again.'));
    } finally {
      this.busy.set(false);
    }
  }
}
