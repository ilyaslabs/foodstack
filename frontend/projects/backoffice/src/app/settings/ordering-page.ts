import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { OrderingSettingsClient, PaymentClient, PaymentOptions } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

/** How customers pay, the service fee, tax, tips and how long stores have to accept (PRD 4.5). */
@Component({
  selector: 'app-ordering-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Ordering</h1>
      <p>
        How customers pay and what an order adds on top of the food. Changes apply to the next
        checkout.
      </p>
    </header>

    @if (loaded()) {
      <mat-card appearance="outlined">
        <form (ngSubmit)="save()">
          <div class="card-section">
            <h2>Payment methods</h2>
            <mat-slide-toggle name="cash" [(ngModel)]="cashEnabled"
              >Cash when the order arrives or is collected</mat-slide-toggle
            >
            <mat-slide-toggle name="card" [(ngModel)]="cardEnabled"
              >Card, on the payment gateway's page</mat-slide-toggle
            >
            @if (cardEnabled && gateway() && !gateway()!.card) {
              <div class="banner warn" role="status">
                No card gateway is set up on the server (PAYMENT_GATEWAY), so customers will not see
                card payment yet.
              </div>
            }
          </div>
          <div class="card-section">
            <h2>Fees and tax</h2>
            <p class="section-hint">
              Added to every order. The service fee is a fixed amount, a percent of the food, or
              both.
            </p>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Service fee ({{ currency() }})</mat-label>
                <input matInput name="serviceFee" inputmode="decimal" [(ngModel)]="serviceFee" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Service fee (% of food)</mat-label>
                <input
                  matInput
                  name="serviceFeePercent"
                  inputmode="decimal"
                  [(ngModel)]="serviceFeePercent"
                />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Tax (%)</mat-label>
                <input matInput name="tax" inputmode="decimal" [(ngModel)]="taxPercent" />
                <mat-hint>On food, after discounts</mat-hint>
              </mat-form-field>
            </div>
          </div>
          <div class="card-section">
            <h2>Tips and timing</h2>
            <mat-slide-toggle name="tips" [(ngModel)]="tipsEnabled"
              >Customers can tip the rider (all of it goes to the rider)</mat-slide-toggle
            >
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Time to accept (minutes)</mat-label>
                <input
                  matInput
                  name="acceptMinutes"
                  type="number"
                  min="1"
                  max="60"
                  [(ngModel)]="acceptMinutes"
                />
                <mat-hint>Unanswered orders are rejected and refunded after this</mat-hint>
              </mat-form-field>
            </div>
          </div>
          <div class="card-section">
            <h2>Menu prices</h2>
            <mat-slide-toggle name="priceApproval" [(ngModel)]="priceApprovalRequired"
              >Store owners' price changes need your approval</mat-slide-toggle
            >
            <p class="section-hint">
              Covers brand menu prices and each branch's own prices. Until you approve a change on
              the Price approvals page, customers keep seeing the old price. New dishes start with
              the price they are added with.
            </p>
          </div>
          <div class="card-section">
            <h2>Branches collecting card payments</h2>
            <mat-slide-toggle name="directCollection" [(ngModel)]="directCollectionEnabled"
              >Finance may let a branch take card payments into its own merchant
              account</mat-slide-toggle
            >
            <p class="section-hint">
              Card payments go to your account unless finance allows a branch on its Payments tab
              and the branch connects its account. You then collect your commission and fees from
              the branch. When a branch owes you more than its credit limit, its card payments come
              back to your account until it settles.
            </p>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Default credit limit ({{ currency() }})</mat-label>
                <input matInput name="creditLimit" inputmode="decimal" [(ngModel)]="creditLimit" />
                <mat-hint>Unless a branch has its own limit</mat-hint>
              </mat-form-field>
            </div>
          </div>
          @if (error()) {
            <div class="banner error" role="alert">{{ error() }}</div>
          }
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
          </div>
        </form>
      </mat-card>
    } @else if (loadError()) {
      <p>The settings could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 880px;
    }
    .banner {
      margin: 0;
    }
    .banner.warn {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
  `,
})
export class OrderingPage implements OnInit {
  private readonly client = inject(OrderingSettingsClient);
  private readonly payments = inject(PaymentClient);
  private readonly locale = inject(LocaleStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly currency = this.locale.currency;
  protected readonly loaded = signal(false);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly gateway = signal<PaymentOptions | null>(null);

  protected cashEnabled = true;
  protected cardEnabled = false;
  protected tipsEnabled = true;
  protected serviceFee = '';
  protected serviceFeePercent = '';
  protected taxPercent = '';
  protected acceptMinutes = 5;
  protected priceApprovalRequired = false;
  protected directCollectionEnabled = false;
  protected creditLimit = '';
  private etag = '';

  async ngOnInit(): Promise<void> {
    try {
      const [{ value, etag }, options] = await Promise.all([
        firstValueFrom(this.client.get()),
        firstValueFrom(this.payments.options()).catch(() => null),
      ]);
      this.etag = etag;
      this.gateway.set(options);
      this.cashEnabled = value.cashEnabled;
      this.cardEnabled = value.cardEnabled;
      this.tipsEnabled = value.tipsEnabled;
      this.serviceFee = toMajor(value.serviceFeeMinor, this.currency());
      this.serviceFeePercent = percent(value.serviceFeeBasisPoints);
      this.taxPercent = percent(value.taxBasisPoints);
      this.acceptMinutes = Math.round(value.acceptTimeoutSeconds / 60);
      this.priceApprovalRequired = value.priceApprovalRequired ?? false;
      this.directCollectionEnabled = value.directCollectionEnabled ?? false;
      this.creditLimit = toMajor(value.creditLimitMinor ?? 5_000_000, this.currency());
      this.loaded.set(true);
    } catch {
      this.loadError.set(true);
    }
  }

  protected async save(): Promise<void> {
    const fee = this.serviceFee.trim() === '' ? 0 : toMinor(this.serviceFee, this.currency());
    const feeBp = basisPoints(this.serviceFeePercent);
    const taxBp = basisPoints(this.taxPercent);
    if (!this.cashEnabled && !this.cardEnabled) {
      this.error.set('Keep at least one way to pay.');
      return;
    }
    if (fee === null || feeBp === null || taxBp === null) {
      this.error.set('Fees and tax must be numbers of 0 or more.');
      return;
    }
    const limit = toMinor(this.creditLimit, this.currency());
    if (limit === null) {
      this.error.set('The credit limit must be a number of 0 or more.');
      return;
    }
    if (!(this.acceptMinutes >= 1 && this.acceptMinutes <= 60)) {
      this.error.set('The time to accept must be 1 to 60 minutes.');
      return;
    }
    this.error.set('');
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.client.update(
          {
            cashEnabled: this.cashEnabled,
            cardEnabled: this.cardEnabled,
            serviceFeeMinor: fee,
            serviceFeeBasisPoints: feeBp,
            taxBasisPoints: taxBp,
            tipsEnabled: this.tipsEnabled,
            acceptTimeoutSeconds: Math.round(this.acceptMinutes) * 60,
            priceApprovalRequired: this.priceApprovalRequired,
            directCollectionEnabled: this.directCollectionEnabled,
            creditLimitMinor: limit,
          },
          this.etag,
        ),
      );
      this.etag = saved.etag;
      this.snackBar.open('Saved. The next checkout uses these rules.', undefined, {
        duration: 4000,
      });
    } catch (error) {
      this.error.set(describeError(error, 'The settings could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}

function percent(basisPoints: number): string {
  return String((basisPoints ?? 0) / 100);
}

function basisPoints(value: string): number | null {
  const trimmed = (value ?? '').trim().replace(',', '.');
  if (trimmed === '') return 0;
  const number = Number(trimmed);
  return Number.isFinite(number) && number >= 0 && number <= 100 ? Math.round(number * 100) : null;
}
