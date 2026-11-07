import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  LedgerClient,
  LedgerParty,
  OperatorSummary,
  PayoutReport,
  PayoutRow,
  StoreClient,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import {
  OPERATOR_ACCOUNTS,
  PayoutColumn,
  columnTotal,
  directionLabel,
  iso,
  payoutColumns,
  presetPeriod,
} from './finance-labels';
import { StatementDialog } from './statement-dialog';

type Preset = 'this-week' | 'last-week' | 'this-month' | 'last-month' | 'custom';

/**
 * Money between the operator and its branches and riders (PRD FI-1, FI-3, FI-7, BB-8): netted
 * balances for a period with who pays whom, downloads as CSV and PDF, statements, and settling
 * with a reference. Finance sees every branch and rider and the operator's own earnings; a brand
 * owner sees the brand's branches with their totals, a branch owner their branches.
 */
@Component({
  selector: 'app-finance-page',
  imports: [
    RouterLink,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSnackBarModule,
    MatTooltipModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>{{ finance() ? 'Finance' : 'Payouts' }}</h1>
      <p>
        @if (finance()) {
          What the operator owes each branch and rider, and what they owe it, netted for the period.
          Settle a balance once the money has moved, with its bank or payment reference.
        } @else {
          What the operator owes your branches, and what they owe it, netted for the period.
        }
      </p>
    </header>

    <div class="filters">
      <mat-button-toggle-group
        [value]="preset()"
        (change)="choose($event.value)"
        aria-label="Period"
        class="presets"
        hideSingleSelectionIndicator
      >
        <mat-button-toggle value="this-week">This week</mat-button-toggle>
        <mat-button-toggle value="last-week">Last week</mat-button-toggle>
        <mat-button-toggle value="this-month">This month</mat-button-toggle>
        <mat-button-toggle value="last-month">Last month</mat-button-toggle>
      </mat-button-toggle-group>
      <div class="dates">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>From</mat-label>
          <input matInput type="date" [ngModel]="from()" (ngModelChange)="setFrom($event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>To</mat-label>
          <input matInput type="date" [ngModel]="to()" (ngModelChange)="setTo($event)" />
        </mat-form-field>
      </div>
    </div>
    <div class="filters">
      @if (finance()) {
        <mat-button-toggle-group
          [value]="party()"
          (change)="setParty($event.value)"
          aria-label="Show"
        >
          <mat-button-toggle value="STORE">Branches</mat-button-toggle>
          <mat-button-toggle value="RIDER">Riders</mat-button-toggle>
        </mat-button-toggle-group>
      }
      @if (party() === 'STORE' && brands().length > 1) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="brand">
          <mat-label>Brand</mat-label>
          <mat-select [value]="brandId()" (selectionChange)="setBrand($event.value)">
            @if (finance()) {
              <mat-option value="">All brands</mat-option>
            }
            @for (brand of brands(); track brand.id) {
              <mat-option [value]="brand.id">{{ brand.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      <span class="spacer"></span>
      <button mat-stroked-button type="button" (click)="download('csv')" [disabled]="!report()">
        <fs-icon name="download" /> CSV
      </button>
      <button mat-stroked-button type="button" (click)="download('pdf')" [disabled]="!report()">
        <fs-icon name="download" /> PDF
      </button>
    </div>

    @if (report(); as r) {
      <section class="tiles" aria-label="Totals">
        <div class="tile">
          <span class="label">{{ finance() ? 'We pay out' : 'Due to you' }}</span>
          <strong>{{ locale.money(r.operatorPaysMinor, r.currency) }}</strong>
          <span class="hint"
            >{{ count('OPERATOR_PAYS') }} {{ partyNoun(count('OPERATOR_PAYS')) }}</span
          >
        </div>
        <div class="tile">
          <span class="label">{{ finance() ? 'We receive' : 'You owe' }}</span>
          <strong [class.owed]="!finance() && r.partiesPayMinor > 0">
            {{ locale.money(r.partiesPayMinor, r.currency) }}
          </strong>
          <span class="hint">{{ count('PARTY_PAYS') }} {{ partyNoun(count('PARTY_PAYS')) }}</span>
        </div>
        @if (operator(); as o) {
          <div class="tile">
            <span class="label">Operator earnings</span>
            <strong>{{ locale.money(o.netMinor, o.currency) }}</strong>
            <span class="hint">{{ earningsLine(o) }}</span>
          </div>
        }
      </section>
    }

    <mat-card appearance="outlined" class="list">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (report(); as r) {
        <div class="row head" aria-hidden="true">
          <span class="name">{{ party() === 'RIDER' ? 'Rider' : 'Branch' }}</span>
          <span class="num">Opening</span>
          @for (column of columns(); track column.label) {
            <span class="num">{{ column.label }}</span>
          }
          <span class="num">Net</span>
          <span></span>
        </div>
        @for (row of r.rows; track row.accountId) {
          <div class="row">
            <span class="name">
              @if (row.partyType === 'STORE' && session.can('store:payment-collection:manage')) {
                <a
                  class="branch-link"
                  [routerLink]="['/stores', row.partyId]"
                  [queryParams]="{ tab: 'payments' }"
                  ><strong>{{ row.name }}</strong></a
                >
              } @else {
                <strong>{{ row.name }}</strong>
              }
              @if (brandName(row.brandId); as brand) {
                <span class="muted">{{ brand }}</span>
              }
            </span>
            <span class="num" data-label="Opening">{{
              locale.money(row.openingMinor, r.currency)
            }}</span>
            @for (column of columns(); track column.label) {
              <span class="num" [attr.data-label]="column.label">
                {{ locale.money(total(row, column), r.currency) }}
              </span>
            }
            <span class="num net" data-label="Net">
              <strong>{{ locale.money(abs(row.dueMinor), r.currency) }}</strong>
              <span class="chip" [attr.data-direction]="row.direction">
                {{ direction(row) }}
              </span>
            </span>
            <span class="actions">
              <button mat-button type="button" (click)="statement(row)">Statement</button>
              @if (canSettle() && row.direction !== 'SETTLED') {
                <button
                  mat-flat-button
                  type="button"
                  (click)="settle(row)"
                  [disabled]="periodOpen()"
                  [matTooltip]="periodOpen() ? 'Settle once the period is over' : ''"
                >
                  Settle
                </button>
              }
            </span>
          </div>
        } @empty {
          <p class="empty"><fs-icon name="account_balance" /> Nothing moved in this period</p>
        }
        @if (r.rows.length > 1) {
          <div class="row total">
            <span class="name"><strong>Total</strong></span>
            <span class="num" data-label="Opening">{{
              locale.money(r.totalOpeningMinor, r.currency)
            }}</span>
            @for (column of columns(); track column.label) {
              <span class="num" [attr.data-label]="column.label">
                {{ locale.money(totalOf(r, column), r.currency) }}
              </span>
            }
            <span class="num net" data-label="Net">
              <strong>{{ locale.money(abs(r.totalDueMinor), r.currency) }}</strong>
            </span>
            <span></span>
          </div>
        }
      }
    </mat-card>
    <p class="footnote">
      Balances are what the operator owes: positive, the operator pays; negative, the
      {{ party() === 'RIDER' ? 'rider' : 'branch' }} pays the operator. The CSV and PDF list every
      kind of line separately.
    </p>
  `,
  styles: `
    :host {
      display: block;
      max-inline-size: 1180px;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 16px;
      margin-block-end: 12px;
    }
    .presets {
      max-inline-size: 100%;
      overflow-x: auto;
    }
    .presets mat-button-toggle {
      white-space: nowrap;
    }
    .dates {
      display: flex;
      gap: 12px;
    }
    .dates mat-form-field {
      inline-size: 170px;
    }
    .brand {
      min-inline-size: 220px;
    }
    .spacer {
      flex: 1;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      margin-block: 8px 16px;
    }
    .tile {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 16px 20px;
      border-radius: 16px;
      background: var(--mat-sys-surface-container);
    }
    .tile .label {
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
    }
    .tile strong {
      font: var(--mat-sys-headline-small);
      font-variant-numeric: tabular-nums;
    }
    .tile .hint {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .owed {
      color: var(--mat-sys-error);
    }
    .row {
      display: grid;
      grid-template-columns:
        minmax(140px, 2fr) repeat(var(--fs-columns), minmax(72px, 1fr)) minmax(100px, 1.2fr)
        200px;
      align-items: center;
      gap: 12px;
      padding: 12px 20px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .row.head {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
      padding-block: 10px;
    }
    .row.head .num {
      white-space: normal;
    }
    .row.total {
      background: var(--mat-sys-surface-container-low);
      border-block-end: none;
    }
    .name {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
    }
    .branch-link {
      color: inherit;
      text-decoration: none;
    }
    .branch-link:hover strong,
    .branch-link:focus-visible strong {
      text-decoration: underline;
    }
    .name strong {
      font: var(--mat-sys-body-large);
      font-weight: 500;
      overflow-wrap: anywhere;
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .num {
      text-align: end;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
      font: var(--mat-sys-body-medium);
    }
    .net {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 4px;
    }
    .chip {
      font: var(--mat-sys-label-small);
      padding: 2px 8px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .chip[data-direction='OPERATOR_PAYS'] {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .chip[data-direction='PARTY_PAYS'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .actions {
      display: flex;
      gap: 4px;
      justify-content: flex-end;
    }
    .empty {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .footnote {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin-block: 12px 0;
    }
    @media (max-width: 1199.98px) {
      .row.head {
        display: none;
      }
      .row {
        grid-template-columns: 1fr 1fr;
        gap: 6px 16px;
        padding: 14px 16px;
      }
      .name,
      .actions {
        grid-column: 1 / -1;
      }
      .actions {
        justify-content: flex-start;
        margin-inline-start: -12px;
      }
      .num {
        display: flex;
        justify-content: space-between;
        gap: 8px;
        text-align: start;
      }
      .num::before {
        content: attr(data-label);
        color: var(--mat-sys-on-surface-variant);
        font: var(--mat-sys-body-small);
      }
      .net {
        flex-direction: row;
        align-items: center;
        grid-column: 1 / -1;
      }
      .net strong {
        margin-inline-start: auto;
      }
    }
    @media (max-width: 599.98px) {
      .row {
        grid-template-columns: 1fr;
      }
      .presets {
        inline-size: 100%;
      }
      .presets mat-button-toggle {
        flex: 1;
        --mat-standard-button-toggle-label-text-size: 13px;
      }
      .presets ::ng-deep .mat-button-toggle-label-content {
        padding-inline: 6px;
      }
    }
  `,
  host: { '[style.--fs-columns]': 'columns().length + 1' },
})
export class FinancePage implements OnInit {
  private readonly client = inject(LedgerClient);
  private readonly stores = inject(StoreClient);
  protected readonly session = inject(Session);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly locale = inject(LocaleStore);

  protected readonly finance = computed(() => this.session.can('ledger:read'));
  protected readonly canSettle = computed(() => this.session.can('ledger:settle'));
  protected readonly preset = signal<Preset>('last-week');
  protected readonly from = signal(presetPeriod('last-week', new Date()).from);
  protected readonly to = signal(presetPeriod('last-week', new Date()).to);
  protected readonly party = signal<LedgerParty>('STORE');
  protected readonly brandId = signal<string>('');
  protected readonly brands = signal<{ id: string; name: string }[]>([]);
  protected readonly report = signal<PayoutReport | null>(null);
  protected readonly operator = signal<OperatorSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly columns = computed(() => payoutColumns(this.party()));
  /** Settling waits until the last day of the period is over. */
  protected readonly periodOpen = computed(() => this.to() >= iso(new Date()));

  async ngOnInit(): Promise<void> {
    await this.loadBrands();
    await this.load();
  }

  protected choose(preset: Preset): void {
    if (preset === 'custom') return;
    const period = presetPeriod(preset, new Date());
    this.preset.set(preset);
    this.from.set(period.from);
    this.to.set(period.to);
    void this.load();
  }

  protected setFrom(value: string): void {
    if (!value) return;
    this.preset.set('custom');
    this.from.set(value);
    if (value > this.to()) this.to.set(value);
    void this.load();
  }

  protected setTo(value: string): void {
    if (!value) return;
    this.preset.set('custom');
    this.to.set(value);
    if (value < this.from()) this.from.set(value);
    void this.load();
  }

  protected setParty(party: LedgerParty): void {
    this.party.set(party);
    void this.load();
  }

  protected setBrand(brandId: string): void {
    this.brandId.set(brandId);
    void this.load();
  }

  protected total(row: PayoutRow, column: PayoutColumn): number {
    return columnTotal(row.kinds, column);
  }

  protected totalOf(report: PayoutReport, column: PayoutColumn): number {
    return columnTotal(report.totalKinds, column);
  }

  protected abs(value: number): number {
    return Math.abs(value);
  }

  protected direction(row: PayoutRow): string {
    return directionLabel(row, this.finance());
  }

  protected count(direction: 'OPERATOR_PAYS' | 'PARTY_PAYS'): number {
    return this.report()?.rows.filter((row) => row.direction === direction).length ?? 0;
  }

  protected partyNoun(count: number): string {
    const noun = this.party() === 'RIDER' ? 'rider' : 'branch';
    return count === 1 ? noun : noun === 'branch' ? 'branches' : 'riders';
  }

  protected brandName(brandId: string | null): string | null {
    if (!brandId || this.brands().length < 2) return null;
    return this.brands().find((brand) => brand.id === brandId)?.name ?? null;
  }

  protected earningsLine(summary: OperatorSummary): string {
    return Object.entries(summary.accounts)
      .filter(([, value]) => value !== 0)
      .map(
        ([account, value]) =>
          `${OPERATOR_ACCOUNTS[account] ?? account} ${this.locale.money(value, summary.currency)}`,
      )
      .join(' · ');
  }

  protected statement(row: PayoutRow): void {
    this.dialog.open(StatementDialog, {
      data: {
        accountId: row.accountId,
        name: row.name,
        from: this.from(),
        to: this.to(),
        currency: this.report()?.currency ?? this.locale.currency(),
      },
      width: '560px',
      maxWidth: '94vw',
    });
  }

  protected async settle(row: PayoutRow): Promise<void> {
    const currency = this.report()?.currency ?? this.locale.currency();
    const amount = this.locale.money(Math.abs(row.dueMinor), currency);
    const who =
      row.direction === 'OPERATOR_PAYS'
        ? `You paid ${row.name} ${amount}`
        : `${row.name} paid you ${amount}`;
    const reference = await confirmAction(this.dialog, {
      title: `Settle ${row.name}`,
      message: `${who} for everything up to ${this.to()}. The balance for the period goes to zero.`,
      confirm: 'Settle',
      inputLabel: 'Bank or payment reference',
      inputHint: 'Shown on the statement and the settlement list',
      inputRequired: true,
    });
    if (reference === null) return;
    try {
      await firstValueFrom(this.client.settle(row.accountId, this.to(), reference));
      this.snackBar.open(`${row.name} is settled up to ${this.to()}`, 'Close', { duration: 4000 });
      await this.load();
    } catch (error) {
      this.snackBar.open(describeError(error, 'The balance could not be settled.'), 'Close', {
        duration: 6000,
      });
    }
  }

  protected async download(format: 'csv' | 'pdf'): Promise<void> {
    try {
      const blob = await firstValueFrom(this.client.download(this.query(), format));
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      const who = this.party() === 'RIDER' ? 'rider' : this.brandId() ? 'brand' : 'branch';
      link.href = url;
      link.download = `${who}-payouts-${this.from()}-to-${this.to()}.${format}`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      this.snackBar.open(describeError(error, 'The report could not be downloaded.'), 'Close', {
        duration: 6000,
      });
    }
  }

  private query() {
    return {
      party: this.party(),
      from: this.from(),
      to: this.to(),
      brandId: this.party() === 'STORE' ? this.brandId() || null : null,
    };
  }

  private async loadBrands(): Promise<void> {
    // the branch list is for operator staff who manage stores and for owners; finance alone sees no filter
    if (!this.session.can('store:manage') && !this.session.can('store:portal')) return;
    try {
      const own =
        this.session.me()?.brandIds ?? (await firstValueFrom(this.session.me$)).brandIds ?? [];
      const list = await firstValueFrom(this.stores.list());
      const brands = new Map<string, string>();
      for (const store of list) {
        if (this.finance() || own.includes(store.brandId))
          brands.set(store.brandId, store.brandName);
      }
      this.brands.set(
        [...brands]
          .map(([id, name]) => ({ id, name }))
          .sort((a, b) => a.name.localeCompare(b.name)),
      );
      if (!this.finance() && own.length === 1) this.brandId.set(own[0]!);
    } catch {
      this.brands.set([]);
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      const [report, operator] = await Promise.all([
        firstValueFrom(this.client.payouts(this.query())),
        this.finance()
          ? firstValueFrom(this.client.operator(this.from(), this.to()))
          : Promise.resolve(null),
      ]);
      this.report.set(report);
      this.operator.set(operator);
    } catch (error) {
      this.report.set(null);
      this.snackBar.open(describeError(error, 'The balances could not be loaded.'), 'Close', {
        duration: 6000,
      });
    } finally {
      this.loading.set(false);
    }
  }
}
