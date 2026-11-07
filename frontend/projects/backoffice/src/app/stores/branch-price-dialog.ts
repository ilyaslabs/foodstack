import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { BranchPrice, EditorItem, StoreClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

export interface BranchPriceData {
  storeId: string;
  branchName: string;
  currency: string;
  item: EditorItem;
  /** The branch's own prices for this item and its options. */
  prices: BranchPrice[];
  /** Whether the operator approves price changes first. */
  approval: boolean;
}

/** What happened: how many prices changed now and how many wait for approval. */
export interface BranchPriceResult {
  applied: number;
  pending: number;
}

interface Row {
  optionId: string | null;
  label: string;
  brandMinor: number;
  value: string;
  initial: string;
}

/**
 * One dish's prices at one branch (PRD BB-4): the dish and each option's extra price. An empty
 * field uses the brand's price.
 */
@Component({
  selector: 'app-branch-price-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  template: `
    <h2 mat-dialog-title>Prices at {{ data.branchName }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <p class="lead">
          <strong>{{ locale.pick(data.item.name) }}</strong
          >. Leave a field empty to use the brand's price.
          @if (data.approval) {
            The platform approves price changes before customers see them.
          }
        </p>
        @for (row of rows; track row.optionId) {
          <mat-form-field appearance="outline">
            <mat-label>{{ row.label }}</mat-label>
            <input
              matInput
              inputmode="decimal"
              [name]="row.optionId ?? 'item'"
              [(ngModel)]="row.value"
            />
            <span matTextSuffix>&nbsp;{{ data.currency }}</span>
            <mat-hint>Brand price {{ locale.money(row.brandMinor, data.currency) }}</mat-hint>
          </mat-form-field>
        }
      </mat-dialog-content>
      @if (saving()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ data.approval ? 'Ask for approval' : 'Save prices' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    mat-form-field {
      width: 100%;
    }
    .lead {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 16px;
    }
    .error {
      margin: 8px 24px 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class BranchPriceDialog {
  protected readonly data = inject<BranchPriceData>(MAT_DIALOG_DATA);
  protected readonly locale = inject(LocaleStore);
  private readonly stores = inject(StoreClient);
  private readonly ref = inject(MatDialogRef<BranchPriceDialog, BranchPriceResult>);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly rows: Row[];

  constructor() {
    const own = (optionId: string | null) =>
      this.data.prices.find((p) => p.optionId === optionId)?.priceMinor ?? null;
    const row = (optionId: string | null, label: string, brandMinor: number): Row => {
      const value = own(optionId) === null ? '' : toMajor(own(optionId), this.data.currency);
      return { optionId, label, brandMinor, value, initial: value };
    };
    this.rows = [row(null, 'Dish price', this.data.item.priceMinor)];
    for (const group of this.data.item.modifierGroups) {
      for (const option of group.options) {
        if (!option.id) continue;
        this.rows.push(
          row(
            option.id,
            `${this.locale.pick(group.name)}: ${this.locale.pick(option.name)} (extra)`,
            option.priceDeltaMinor,
          ),
        );
      }
    }
  }

  protected async save(): Promise<void> {
    this.error.set(null);
    const changed = this.rows.filter((r) => r.value.trim() !== r.initial.trim());
    const values = changed.map((r) => ({
      row: r,
      minor: r.value.trim() === '' ? null : toMinor(r.value, this.data.currency),
    }));
    if (values.some((v) => v.row.value.trim() !== '' && v.minor === null)) {
      this.error.set('Enter prices as numbers of 0 or more, such as 850.');
      return;
    }
    this.saving.set(true);
    const result: BranchPriceResult = { applied: 0, pending: 0 };
    try {
      for (const { row, minor } of values) {
        const outcome = await firstValueFrom(
          this.stores.setBranchPrice(this.data.storeId, this.data.item.id, row.optionId, minor),
        );
        if (outcome === 'PENDING') result.pending++;
        else result.applied++;
      }
      this.ref.close(result);
    } catch (error) {
      this.error.set(describeError(error, 'The prices could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
