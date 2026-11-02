import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import {
  OrderClient,
  OrderPage,
  OrderRow,
  OrderStatus,
  StoreClient,
  StoreSummary,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Session } from '../shared/session';
import { OrderDetailDialog } from './order-detail-dialog';
import { STATUS_LABELS, dateTime, statusTone } from './order-labels';

const FILTERS: (OrderStatus | null)[] = [
  null,
  'CONFIRMED',
  'ACCEPTED',
  'PREPARING',
  'READY',
  'PICKED_UP',
  'DELIVERED',
  'COLLECTED',
  'REJECTED',
  'CANCELLED',
  'FAILED',
  'PAYMENT_PENDING',
];

/**
 * Every order, newest first: support sees all branches and can filter by status (PRD OF-6); store
 * staff see their own branch's history.
 */
@Component({
  selector: 'app-orders-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatSelectModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Orders</h1>
      <p>
        {{
          operator()
            ? 'Every order on the platform. Open one to see its steps, cancel or refund it.'
            : 'Your branch’s orders, newest first.'
        }}
      </p>
    </header>

    <div class="filters">
      @if (operator()) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Status</mat-label>
          <mat-select [value]="status()" (selectionChange)="status.set($event.value); reset()">
            @for (option of filters; track option) {
              <mat-option [value]="option">{{ option ? labels[option] : 'All' }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (storeList().length > 1 || operator()) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Branch</mat-label>
          <mat-select [value]="storeId()" (selectionChange)="storeId.set($event.value); reset()">
            @if (operator()) {
              <mat-option [value]="null">All branches</mat-option>
            }
            @for (store of storeList(); track store.id) {
              <mat-option [value]="store.id"
                >{{ store.brandName
                }}{{ store.branchName ? ' · ' + store.branchName : '' }}</mat-option
              >
            }
          </mat-select>
        </mat-form-field>
      }
    </div>

    <mat-card appearance="outlined">
      @if (loading() && !rows().length) {
        <mat-progress-bar mode="indeterminate" />
      }
      <div class="table" role="table" aria-label="Orders">
        <div class="row head" role="row">
          <span role="columnheader">Order</span>
          <span role="columnheader">Branch</span>
          <span role="columnheader">Customer</span>
          <span role="columnheader">Status</span>
          <span role="columnheader" class="end">Total</span>
        </div>
        @for (row of rows(); track row.id) {
          <button type="button" class="row" role="row" (click)="open(row)">
            <span role="cell">
              <strong>#{{ row.number }}</strong>
              <span class="muted">{{ dateTime(row.placedAt) }}</span>
            </span>
            <span role="cell">{{ row.storeName }}</span>
            <span role="cell">
              {{ row.customerName }}
              <span class="muted"
                >{{ row.fulfilment === 'PICKUP' ? 'Pickup' : 'Delivery' }} ·
                {{ row.paymentMethod === 'CARD' ? 'Card' : 'Cash' }}</span
              >
            </span>
            <span role="cell"
              ><span class="status" [attr.data-tone]="tone(row.status)">{{
                labels[row.status]
              }}</span></span
            >
            <span role="cell" class="end">{{ locale.money(row.totalMinor, row.currency) }}</span>
          </button>
        } @empty {
          @if (!loading()) {
            <p class="empty"><fs-icon name="receipt" /> No orders yet</p>
          }
        }
      </div>
      @if (next()) {
        <div class="more">
          <button mat-stroked-button type="button" [disabled]="loading()" (click)="load()">
            Show more
          </button>
        </div>
      }
    </mat-card>
  `,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      margin-block-end: 16px;
    }
    .filters mat-form-field {
      min-inline-size: 220px;
    }
    .table {
      display: flex;
      flex-direction: column;
    }
    .row {
      display: grid;
      grid-template-columns: 1.2fr 1.2fr 1.4fr 1fr 0.8fr;
      gap: 16px;
      align-items: center;
      padding: 12px 20px;
      border: 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
      background: transparent;
      color: inherit;
      font: var(--mat-sys-body-medium);
      text-align: start;
    }
    button.row {
      cursor: pointer;
    }
    button.row:hover {
      background: var(--mat-sys-surface-container-low);
    }
    button.row:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: -2px;
    }
    .head {
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
    }
    .row > span {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .end {
      text-align: end;
      align-items: flex-end;
    }
    .status {
      align-self: flex-start;
      padding: 2px 10px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .status[data-tone='new'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .status[data-tone='done'] {
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface);
    }
    .status[data-tone='stopped'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    @media (max-width: 719.98px) {
      .row {
        grid-template-columns: 1fr auto;
        gap: 4px 12px;
        padding: 12px 16px;
      }
      .head {
        display: none;
      }
      .row > span:nth-child(2),
      .row > span:nth-child(3) {
        grid-column: 1;
      }
    }
    .empty {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 24px;
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 16px;
    }
  `,
})
export class OrdersPage implements OnInit {
  private readonly orders = inject(OrderClient);
  private readonly stores = inject(StoreClient);
  private readonly dialog = inject(MatDialog);
  private readonly session = inject(Session);
  protected readonly locale = inject(LocaleStore);

  protected readonly operator = computed(() => this.session.can('order:manage'));
  protected readonly storeList = signal<StoreSummary[]>([]);
  protected readonly storeId = signal<string | null>(null);
  protected readonly status = signal<OrderStatus | null>(null);
  protected readonly rows = signal<OrderRow[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly labels = STATUS_LABELS;
  protected readonly filters = FILTERS;
  protected readonly dateTime = dateTime;
  protected readonly tone = statusTone;

  async ngOnInit(): Promise<void> {
    await firstValueFrom(this.session.me$).catch(() => null);
    try {
      this.storeList.set(await firstValueFrom(this.stores.list()));
    } catch {
      this.storeList.set([]);
    }
    if (!this.operator()) this.storeId.set(this.storeList()[0]?.id ?? null);
    await this.load();
  }

  protected reset(): void {
    this.rows.set([]);
    this.next.set(null);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const page: OrderPage = this.operator()
        ? await firstValueFrom(
            this.orders.all({ status: this.status(), storeId: this.storeId() }, this.next()),
          )
        : this.storeId()
          ? await firstValueFrom(this.orders.storeHistory(this.storeId()!, this.next()))
          : { items: [], next: null };
      this.rows.update((rows) => [...rows, ...page.items]);
      this.next.set(page.next);
    } finally {
      this.loading.set(false);
    }
  }

  protected async open(row: OrderRow): Promise<void> {
    const changed = await firstValueFrom(
      this.dialog
        .open(OrderDetailDialog, { data: row.id, width: '880px', maxWidth: 'calc(100vw - 32px)' })
        .afterClosed(),
    );
    if (changed) this.reset();
  }
}
