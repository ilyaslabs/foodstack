import { Component, computed, inject, input } from '@angular/core';
import { DatePipe } from '@angular/common';

import { Earnings } from '@foodstack/api-client';
import { LocaleStore, formatMoney } from '@foodstack/ui';

/** A rider's earnings: totals per day as bars, and the latest deliveries (PRD DR-7). */
@Component({
  selector: 'app-earnings-view',
  imports: [DatePipe],
  template: `
    @let e = earnings();
    <dl class="totals">
      <div>
        <dt>Deliveries</dt>
        <dd>{{ total().deliveries }}</dd>
      </div>
      <div>
        <dt>Earned</dt>
        <dd>{{ money(total().earningMinor) }}</dd>
      </div>
      <div>
        <dt>Tips</dt>
        <dd>{{ money(total().tipMinor) }}</dd>
      </div>
      <div>
        <dt>Cash collected</dt>
        <dd>{{ money(total().cashCollectedMinor) }}</dd>
      </div>
    </dl>
    <div
      class="bars"
      role="img"
      [attr.aria-label]="'Earnings per day, last ' + e.days.length + ' days'"
    >
      @for (day of e.days; track day.date) {
        <div class="bar">
          <span class="amount">{{ short(day.earningMinor + day.tipMinor) }}</span>
          <span
            class="fill"
            [style.block-size.%]="max() ? ((day.earningMinor + day.tipMinor) / max()) * 100 : 0"
          ></span>
          <span class="day">{{ day.date | date: 'EEE' }}</span>
        </div>
      }
    </div>
    <h3>Latest deliveries</h3>
    @if (e.recent.length === 0) {
      <p class="none">No deliveries in this period.</p>
    } @else {
      <ul class="recent">
        @for (row of e.recent; track row.orderId) {
          <li>
            <div>
              <strong>{{ row.storeName }}</strong>
              <span
                >Order {{ row.orderNumber }} · {{ row.at * 1000 | date: 'EEE d MMM, HH:mm' }} ·
                {{ (row.distanceMeters / 1000).toFixed(1) }} km</span
              >
            </div>
            <div class="money">
              <strong>{{ money(row.earningMinor + row.tipMinor) }}</strong>
              @if (row.tipMinor > 0) {
                <span>incl. {{ money(row.tipMinor) }} tip</span>
              }
              @if (row.cashCollectedMinor > 0) {
                <span>cash {{ money(row.cashCollectedMinor) }}</span>
              }
            </div>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .totals {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 8px;
      margin: 0;
    }
    @media (max-width: 599.98px) {
      .totals {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .totals div {
      padding: 12px 16px;
      border-radius: 16px;
      background: var(--mat-sys-surface-container);
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 4px 0 0;
      font: var(--mat-sys-title-large);
    }
    .bars {
      display: flex;
      align-items: stretch;
      gap: 8px;
      block-size: 160px;
      padding: 8px 0 0;
    }
    .bar {
      flex: 1;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      align-items: center;
      gap: 4px;
      min-inline-size: 0;
    }
    .fill {
      inline-size: 100%;
      max-inline-size: 40px;
      min-block-size: 2px;
      border-radius: 8px 8px 2px 2px;
      background: var(--mat-sys-primary);
    }
    .amount {
      font: var(--mat-sys-label-small);
      color: var(--mat-sys-on-surface-variant);
      white-space: nowrap;
    }
    .day {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    h3 {
      margin: 8px 0 0;
      font: var(--mat-sys-title-medium);
    }
    .none {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .recent {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .recent li {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .recent div {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
    }
    .recent span {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .money {
      align-items: flex-end;
      text-align: end;
      flex: none;
    }
  `,
})
export class EarningsView {
  private readonly locale = inject(LocaleStore);
  readonly earnings = input.required<Earnings>();

  protected readonly total = computed(() =>
    this.earnings().days.reduce(
      (sum, d) => ({
        deliveries: sum.deliveries + d.deliveries,
        earningMinor: sum.earningMinor + d.earningMinor,
        tipMinor: sum.tipMinor + d.tipMinor,
        cashCollectedMinor: sum.cashCollectedMinor + d.cashCollectedMinor,
      }),
      { deliveries: 0, earningMinor: 0, tipMinor: 0, cashCollectedMinor: 0 },
    ),
  );

  protected readonly max = computed(() =>
    Math.max(0, ...this.earnings().days.map((d) => d.earningMinor + d.tipMinor)),
  );

  protected money(minor: number): string {
    return formatMoney(minor, this.earnings().currency, this.locale.language());
  }

  /** Bar labels: whole units only, so they fit. */
  protected short(minor: number): string {
    if (minor === 0) return '';
    const text = this.money(minor);
    return text.replace(/[.,]00(?=\D*$)/, '');
  }
}
