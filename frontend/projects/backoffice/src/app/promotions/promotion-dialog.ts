import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import {
  City,
  PaymentMethod,
  Promotion,
  PromotionClient,
  PromotionInput,
  PromotionType,
  Translated,
} from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { TranslatedField } from '../shared/translated-field';

/** A branch a brand's coupon can be limited to. */
export interface PromotionBranch {
  id: string;
  brandId: string;
  name: string;
}

export interface PromotionDialogData {
  /** Brands the user may make coupons for. */
  brands: { id: string; name: string }[];
  /** The branches the user can see, for limiting a brand's coupon (PRD BB-11). */
  branches: PromotionBranch[];
  /** Whether the user may only make coupons for their own branches of this brand. */
  ownBranchesOnly: (brandId: string) => boolean;
  /** The operator's cities, for limiting a platform coupon. */
  cities: City[];
  /** Operator staff may make platform-wide coupons and set payment rules. */
  operator: boolean;
  /** The coupon to edit, with its ETag, or null for a new one. */
  editing: { promotion: Promotion; etag: string } | null;
}

/** A coupon: its code and what it takes off, when it works, and for whom (PRD 4.8). */
@Component({
  selector: 'app-promotion-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    TranslatedField,
  ],
  template: `
    <h2 mat-dialog-title>
      {{ data.editing ? 'Edit coupon ' + data.editing.promotion.code : 'New coupon' }}
    </h2>
    <mat-dialog-content>
      <form id="promotion-form" (ngSubmit)="save()" class="form">
        <div class="grid-2">
          <mat-form-field appearance="outline">
            <mat-label>Code</mat-label>
            <input
              matInput
              name="code"
              required
              maxlength="20"
              autocapitalize="characters"
              [disabled]="!!data.editing"
              [(ngModel)]="code"
            />
            <mat-hint>3 to 20 letters, digits or hyphens</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Who offers it</mat-label>
            <mat-select
              name="brand"
              [disabled]="!!data.editing"
              [ngModel]="brandId ?? ''"
              (ngModelChange)="brandChanged($event)"
            >
              @if (data.operator) {
                <!-- mat-select shows null as no choice, so the platform is the empty string here -->
                <mat-option value="">The platform (you pay the discount)</mat-option>
              }
              @for (brand of data.brands; track brand.id) {
                <mat-option [value]="brand.id">{{ brand.name }} (the store pays)</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>
        @if (brandId !== null) {
          <mat-form-field appearance="outline">
            <mat-label>Branches</mat-label>
            <mat-select name="storeIds" multiple [(ngModel)]="storeIds" [required]="branchOnly()">
              @for (branch of branchesOf(brandId); track branch.id) {
                <mat-option [value]="branch.id">{{ branch.name }}</mat-option>
              }
            </mat-select>
            <mat-hint>
              {{
                branchOnly()
                  ? 'Your own branches. The brand owner makes coupons for the others.'
                  : 'Empty for every branch of the brand'
              }}
            </mat-hint>
          </mat-form-field>
        } @else if (data.cities.length) {
          <mat-form-field appearance="outline">
            <mat-label>Cities</mat-label>
            <mat-select name="cities" multiple [(ngModel)]="cities">
              @for (city of data.cities; track city.id) {
                <mat-option [value]="city.id">{{ locale.pick(city.name) }}</mat-option>
              }
              @for (name of legacyCities; track name) {
                <mat-option [value]="name">{{ name }}</mat-option>
              }
            </mat-select>
            <mat-hint>Empty for every city</mat-hint>
          </mat-form-field>
        }
        <app-translated-field label="What customers see" [required]="true" [(value)]="title" />

        <mat-button-toggle-group
          name="type"
          aria-label="Discount type"
          [(ngModel)]="type"
          class="type"
        >
          <mat-button-toggle value="PERCENT">Percent off</mat-button-toggle>
          <mat-button-toggle value="FIXED">Amount off</mat-button-toggle>
          <mat-button-toggle value="FREE_DELIVERY">Free delivery</mat-button-toggle>
        </mat-button-toggle-group>

        <div class="grid-3">
          @if (type === 'PERCENT') {
            <mat-form-field appearance="outline">
              <mat-label>Percent off</mat-label>
              <input matInput name="percent" inputmode="decimal" required [(ngModel)]="percent" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>At most ({{ currency }})</mat-label>
              <input matInput name="maxDiscount" inputmode="decimal" [(ngModel)]="maxDiscount" />
              <mat-hint>Empty for no cap</mat-hint>
            </mat-form-field>
          } @else if (type === 'FIXED') {
            <mat-form-field appearance="outline">
              <mat-label>Amount off ({{ currency }})</mat-label>
              <input matInput name="amount" inputmode="decimal" required [(ngModel)]="amount" />
            </mat-form-field>
          }
          <mat-form-field appearance="outline">
            <mat-label>Minimum food total ({{ currency }})</mat-label>
            <input matInput name="minOrder" inputmode="decimal" [(ngModel)]="minOrder" />
          </mat-form-field>
        </div>

        <div class="grid-2">
          <mat-form-field appearance="outline">
            <mat-label>Starts</mat-label>
            <input matInput type="datetime-local" name="startsAt" [(ngModel)]="startsAt" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Ends</mat-label>
            <input matInput type="datetime-local" name="endsAt" [(ngModel)]="endsAt" />
            <mat-hint>Empty to keep it running</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Uses in total</mat-label>
            <input matInput type="number" min="1" name="totalLimit" [(ngModel)]="totalLimit" />
            <mat-hint>Empty for no limit</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Uses per customer</mat-label>
            <input
              matInput
              type="number"
              min="1"
              name="perCustomerLimit"
              [(ngModel)]="perCustomerLimit"
            />
          </mat-form-field>
        </div>
        <mat-checkbox name="firstOrderOnly" [(ngModel)]="firstOrderOnly"
          >Only a customer's first order</mat-checkbox
        >

        @if (data.operator && brandId === null) {
          <mat-form-field appearance="outline">
            <mat-label>Payment methods</mat-label>
            <mat-select name="paymentMethods" multiple [(ngModel)]="paymentMethods">
              <mat-option value="CARD">Card</mat-option>
              <mat-option value="CASH">Cash</mat-option>
            </mat-select>
            <mat-hint>Empty for every method</mat-hint>
          </mat-form-field>
        }
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="promotion-form" [disabled]="saving()">
        Save
      </button>
    </mat-dialog-actions>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-block-start: 8px;
    }
    .type {
      align-self: flex-start;
      flex-wrap: wrap;
    }
    .error {
      color: var(--mat-sys-error);
      margin: 0;
    }
  `,
})
export class PromotionDialog {
  protected readonly data = inject<PromotionDialogData>(MAT_DIALOG_DATA);
  private readonly client = inject(PromotionClient);
  private readonly ref = inject(MatDialogRef<PromotionDialog, boolean>);
  protected readonly locale = inject(LocaleStore);
  protected readonly currency = this.locale.currency();

  protected readonly saving = signal(false);
  protected readonly error = signal('');

  private readonly existing = this.data.editing?.promotion;
  protected code = this.existing?.code ?? '';
  protected brandId: string | null = this.existing
    ? this.existing.brandId
    : this.data.operator
      ? null
      : (this.data.brands[0]?.id ?? null);
  protected title: Translated = { ...(this.existing?.title ?? {}) };
  protected type: PromotionType = this.existing?.type ?? 'PERCENT';
  protected percent = this.existing?.percentBasisPoints
    ? String(this.existing.percentBasisPoints / 100)
    : '';
  protected maxDiscount = toMajor(this.existing?.maxDiscountMinor, this.currency);
  protected amount = toMajor(this.existing?.amountMinor || null, this.currency);
  protected minOrder = toMajor(this.existing?.minOrderMinor || null, this.currency);
  protected startsAt = local(this.existing?.startsAt ?? null);
  protected endsAt = local(this.existing?.endsAt ?? null);
  protected totalLimit: number | null = this.existing?.totalLimit ?? null;
  protected perCustomerLimit: number | null = this.existing?.perCustomerLimit ?? null;
  protected firstOrderOnly = this.existing?.firstOrderOnly ?? false;
  protected paymentMethods: PaymentMethod[] = [...(this.existing?.paymentMethods ?? [])];
  protected storeIds: string[] = this.existing
    ? [...this.existing.storeIds]
    : this.defaultBranches(this.brandId);
  protected cities: string[] = [...(this.existing?.cities ?? [])];
  /** City names on coupons made before cities had ids (M3), kept so an edit does not drop them. */
  protected readonly legacyCities = this.cities.filter(
    (c) => !this.data.cities.some((city) => city.id === c),
  );

  protected branchesOf(brandId: string): PromotionBranch[] {
    return this.data.branches.filter((b) => b.brandId === brandId);
  }

  protected branchOnly(): boolean {
    return this.brandId !== null && this.data.ownBranchesOnly(this.brandId);
  }

  protected brandChanged(value: string): void {
    this.brandId = value || null;
    this.storeIds = this.defaultBranches(this.brandId);
  }

  /** A branch manager's coupon starts on their own branches; a brand owner's on every branch. */
  private defaultBranches(brandId: string | null): string[] {
    return brandId !== null && this.data.ownBranchesOnly(brandId)
      ? this.branchesOf(brandId).map((b) => b.id)
      : [];
  }

  protected async save(): Promise<void> {
    const percent = this.type === 'PERCENT' ? Number(this.percent) : null;
    if (this.type === 'PERCENT' && !(percent! > 0 && percent! <= 100)) {
      this.error.set('Percent off must be more than 0 and at most 100.');
      return;
    }
    if (this.branchOnly() && this.storeIds.length === 0) {
      this.error.set('Choose the branches the coupon works at.');
      return;
    }
    const amount = this.type === 'FIXED' ? toMinor(this.amount, this.currency) : null;
    if (this.type === 'FIXED' && !amount) {
      this.error.set('Enter the amount off.');
      return;
    }
    const input: PromotionInput = {
      brandId: this.brandId,
      code: this.code.trim().toUpperCase(),
      title: this.title,
      type: this.type,
      percentBasisPoints: percent !== null ? Math.round(percent * 100) : null,
      maxDiscountMinor: this.type === 'PERCENT' ? toMinor(this.maxDiscount, this.currency) : null,
      amountMinor: amount,
      minOrderMinor: toMinor(this.minOrder, this.currency) ?? 0,
      startsAt: epoch(this.startsAt),
      endsAt: epoch(this.endsAt),
      totalLimit: this.totalLimit || null,
      perCustomerLimit: this.perCustomerLimit || null,
      firstOrderOnly: this.firstOrderOnly,
      storeIds: this.brandId !== null ? this.storeIds : (this.existing?.storeIds ?? []),
      cities: this.brandId === null ? this.cities : [],
      paymentMethods: this.brandId === null ? this.paymentMethods : [],
    };
    this.error.set('');
    this.saving.set(true);
    try {
      const editing = this.data.editing;
      await firstValueFrom(
        editing
          ? this.client.update(editing.promotion.id, input, editing.etag)
          : this.client.create(input),
      );
      this.ref.close(true);
    } catch (error) {
      this.error.set(describeError(error, 'The coupon could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}

/** Epoch seconds to the value of a datetime-local input, in the browser's time zone. */
function local(epochSeconds: number | null): string {
  if (!epochSeconds) return '';
  const date = new Date(epochSeconds * 1000);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function epoch(value: string): number | null {
  if (!value) return null;
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? Math.floor(time / 1000) : null;
}
