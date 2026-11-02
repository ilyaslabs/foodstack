import { Component, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { OrderClient, OrderRow } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';
import { statusKey, tone } from './order-status';

/** The customer's orders, newest first (PRD 4.5 OR-8). */
@Component({
  selector: 'app-orders-page',
  imports: [RouterLink, MatButtonModule, MatProgressBarModule, Icon],
  template: `
    <div class="page">
      <h1>{{ ui.t('orders') }}</h1>
      @if (loading() && !rows().length) {
        <mat-progress-bar mode="indeterminate" />
      } @else if (!rows().length) {
        <section class="empty">
          <fs-icon name="receipt" class="empty-icon" />
          <h2>{{ ui.t('noOrders') }}</h2>
          <p>{{ ui.t('noOrdersText') }}</p>
          <a mat-flat-button routerLink="/">{{ ui.t('findFood') }}</a>
        </section>
      } @else {
        <ul class="list">
          @for (row of rows(); track row.id) {
            <li>
              <a class="row" [routerLink]="['/orders', row.id]">
                @if (logo(row); as url) {
                  <img class="logo" [src]="url" alt="" width="48" height="48" />
                } @else {
                  <span class="logo fallback" aria-hidden="true">{{
                    row.storeName.charAt(0)
                  }}</span>
                }
                <span class="text">
                  <span class="name">{{ row.storeName }}</span>
                  <span class="items">{{ items(row) }}</span>
                  <span class="meta">{{ date(row.placedAt) }} · {{ row.number }}</span>
                </span>
                <span class="end">
                  <span class="chip" [attr.data-tone]="tone(row.status)">{{
                    ui.t(statusKey(row.status))
                  }}</span>
                  <span class="total">{{ locale.money(row.totalMinor, row.currency) }}</span>
                </span>
              </a>
            </li>
          }
        </ul>
        @if (next()) {
          <button
            mat-stroked-button
            type="button"
            class="more"
            [disabled]="loading()"
            (click)="load()"
          >
            {{ ui.t('loadMore') }}
          </button>
        }
      }
    </div>
  `,
  styles: `
    .page {
      max-width: 760px;
      margin-inline: auto;
      padding: 24px 24px 64px;
    }
    @media (max-width: 599.98px) {
      .page {
        padding: 16px 16px 48px;
      }
    }
    h1 {
      font: var(--mat-sys-headline-medium);
      font-weight: 600;
      margin: 0 0 20px;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 16px;
      border-radius: 20px;
      border: 1px solid var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface-container-low);
      color: inherit;
      text-decoration: none;
      transition: box-shadow 0.15s;
    }
    .row:hover {
      box-shadow: var(--mat-sys-level1);
    }
    .row:focus-visible {
      outline: 3px solid var(--mat-sys-primary);
      outline-offset: 2px;
    }
    .logo {
      inline-size: 48px;
      block-size: 48px;
      border-radius: 14px;
      object-fit: cover;
      flex: none;
    }
    .fallback {
      display: grid;
      place-items: center;
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
      font: var(--mat-sys-title-large);
    }
    .text {
      flex: 1;
      min-inline-size: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .name {
      font: var(--mat-sys-title-medium);
    }
    .items,
    .meta {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .meta {
      font: var(--mat-sys-body-small);
    }
    .end {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 6px;
      flex: none;
    }
    .total {
      font: var(--mat-sys-title-small);
    }
    .chip {
      padding: 2px 10px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .chip[data-tone='waiting'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .chip[data-tone='done'] {
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface);
    }
    .chip[data-tone='stopped'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .more {
      display: block;
      margin: 20px auto 0;
    }
    .empty {
      text-align: center;
      padding: 48px 16px;
    }
    .empty p {
      color: var(--mat-sys-on-surface-variant);
    }
    .empty-icon {
      --fs-icon-size: 56px;
      color: var(--mat-sys-outline);
    }
  `,
})
export class OrdersPage {
  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  private readonly orders = inject(OrderClient);

  protected readonly rows = signal<OrderRow[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly statusKey = statusKey;
  protected readonly tone = tone;

  constructor() {
    afterNextRender(() => void this.load());
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const page = await firstValueFrom(this.orders.mine(this.next()));
      this.rows.update((rows) => [...rows, ...page.items]);
      this.next.set(page.next);
    } finally {
      this.loading.set(false);
    }
  }

  protected logo(row: OrderRow): string | null {
    return row.logoUrls['thumb'] ?? row.logoUrls['card'] ?? Object.values(row.logoUrls)[0] ?? null;
  }

  protected items(row: OrderRow): string {
    const names = row.itemNames.map((name) => this.locale.pick(name)).join(', ');
    const count =
      row.itemCount === 1
        ? this.ui.t('itemCountOne')
        : this.ui.t('itemsCount', { n: this.ui.number(row.itemCount) });
    return names ? `${count} · ${names}` : count;
  }

  protected date(epochSeconds: number): string {
    try {
      return new Intl.DateTimeFormat(this.locale.language(), {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(new Date(epochSeconds * 1000));
    } catch {
      return new Date(epochSeconds * 1000).toLocaleString();
    }
  }
}
