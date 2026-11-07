import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { LedgerClient, StatementLine } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { KIND_LABELS, TYPE_LABELS } from './finance-labels';

export interface StatementData {
  accountId: string;
  name: string;
  from: string;
  to: string;
  currency: string;
}

/** An account's transactions for the period, newest first (PRD FI-1). */
@Component({
  selector: 'app-statement-dialog',
  imports: [DatePipe, MatButtonModule, MatDialogModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>{{ data.name }}</h2>
    <mat-dialog-content>
      <p class="muted">
        {{ data.from }} to {{ data.to }} · balance now
        <strong [class.owed]="balance() < 0">{{ locale.money(balance(), data.currency) }}</strong>
      </p>
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
      <ul>
        @for (line of lines(); track line.transactionId) {
          <li>
            <div class="row">
              <span class="what">{{ type(line) }}</span>
              <span class="amount" [class.owed]="line.amountMinor < 0">
                {{ locale.money(line.amountMinor, data.currency) }}
              </span>
            </div>
            <div class="muted">
              {{ line.at | date: 'medium' }}
              @if (line.note) {
                · {{ line.note }}
              }
            </div>
            <div class="kinds">
              @for (kind of kinds(line); track kind[0]) {
                <span>{{ label(kind[0]) }} {{ locale.money(kind[1], data.currency) }}</span>
              }
            </div>
          </li>
        } @empty {
          @if (!loading()) {
            <li class="muted">Nothing in this period.</li>
          }
        }
      </ul>
      @if (next()) {
        <button mat-stroked-button type="button" (click)="load()">Show more</button>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    li {
      padding: 12px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .row {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      font: var(--mat-sys-body-large);
    }
    .amount {
      font-variant-numeric: tabular-nums;
      font-weight: 500;
    }
    .owed {
      color: var(--mat-sys-error);
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 8px;
    }
    .kinds {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class StatementDialog implements OnInit {
  protected readonly data = inject<StatementData>(MAT_DIALOG_DATA);
  private readonly client = inject(LedgerClient);
  protected readonly locale = inject(LocaleStore);

  protected readonly lines = signal<StatementLine[]>([]);
  protected readonly balance = signal(0);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected type(line: StatementLine): string {
    return TYPE_LABELS[line.type] ?? line.type;
  }

  protected label(kind: string): string {
    return KIND_LABELS[kind] ?? kind;
  }

  protected kinds(line: StatementLine): [string, number][] {
    return Object.entries(line.kinds);
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const page = await firstValueFrom(
        this.client.statement(this.data.accountId, {
          from: this.data.from,
          to: this.data.to,
          before: this.next() ?? undefined,
          limit: 30,
        }),
      );
      this.lines.update((lines) => [...lines, ...page.lines]);
      this.balance.set(page.balanceMinor);
      this.next.set(page.nextBefore);
    } catch (error) {
      this.error.set(describeError(error, 'The statement could not be loaded.'));
    } finally {
      this.loading.set(false);
    }
  }
}
