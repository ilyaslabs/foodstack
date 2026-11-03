import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { DeliverySettingsClient } from '@foodstack/api-client';
import { LocaleStore, formatMoney } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

type CodeRule = 'never' | 'always' | 'above';

/**
 * What operator riders earn and how dispatch finds them (PRD DR-3 to DR-7, OF-8): pay per
 * delivery, how long an offer lasts, how far to look, when support is alerted, when the customer's
 * delivery code is asked for, and how long rider routes are kept.
 */
@Component({
  selector: 'app-delivery-settings-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Rider pay and dispatch</h1>
      <p>
        How your riders are paid and how deliveries find them. What customers pay for delivery is
        set on the Delivery pricing page.
      </p>
    </header>

    @if (loaded()) {
      <mat-card appearance="outlined">
        <form (ngSubmit)="save()">
          <div class="card-section">
            <h2>Rider pay</h2>
            <p class="section-hint">
              Per delivery: a share of the delivery charge, never less than the minimum. Tips go to
              the rider in full on top. Store riders are paid by their store.
            </p>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Share of delivery charge (%)</mat-label>
                <input matInput name="share" inputmode="decimal" [(ngModel)]="share" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Minimum per delivery ({{ currency() }})</mat-label>
                <input matInput name="minimum" inputmode="decimal" [(ngModel)]="minimum" />
              </mat-form-field>
            </div>
            <p class="example">{{ example() }}</p>
          </div>
          <div class="card-section">
            <h2>Finding a rider</h2>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Time to accept an offer (seconds)</mat-label>
                <input
                  matInput
                  name="offer"
                  type="number"
                  min="10"
                  max="300"
                  [(ngModel)]="offerSeconds"
                />
                <mat-hint>Then the next rider is asked</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Search radius (km)</mat-label>
                <input matInput name="radius" inputmode="decimal" [(ngModel)]="radiusKm" />
                <mat-hint>Grows when nobody is near</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Start searching before ready (min)</mat-label>
                <input
                  matInput
                  name="lead"
                  type="number"
                  min="0"
                  max="60"
                  [(ngModel)]="leadMinutes"
                />
                <mat-hint>So the rider arrives as the food is ready</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Alert support after (minutes)</mat-label>
                <input
                  matInput
                  name="alert"
                  type="number"
                  min="1"
                  max="60"
                  [(ngModel)]="alertMinutes"
                />
                <mat-hint>When no rider has taken it</mat-hint>
              </mat-form-field>
            </div>
          </div>
          <div class="card-section">
            <h2>Proof of delivery</h2>
            <p class="section-hint">
              The customer sees a 4-digit code on their order page and gives it to the rider at the
              door.
            </p>
            <mat-button-toggle-group
              name="codeRule"
              aria-label="When to ask for the code"
              [(ngModel)]="codeRule"
              class="rule"
            >
              <mat-button-toggle value="never">Never</mat-button-toggle>
              <mat-button-toggle value="above">Above an order total</mat-button-toggle>
              <mat-button-toggle value="always">Every delivery</mat-button-toggle>
            </mat-button-toggle-group>
            @if (codeRule === 'above') {
              <div class="grid-3">
                <mat-form-field appearance="outline">
                  <mat-label>Ask for orders above ({{ currency() }})</mat-label>
                  <input matInput name="codeAbove" inputmode="decimal" [(ngModel)]="codeAbove" />
                </mat-form-field>
              </div>
            }
          </div>
          <div class="card-section">
            <h2>Privacy</h2>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Keep rider routes (days)</mat-label>
                <input
                  matInput
                  name="retention"
                  type="number"
                  min="1"
                  max="365"
                  [(ngModel)]="retentionDays"
                />
                <mat-hint>Older positions are deleted</mat-hint>
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
    .example {
      margin: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .rule {
      align-self: flex-start;
      flex-wrap: wrap;
    }
  `,
})
export class DeliverySettingsPage implements OnInit {
  private readonly client = inject(DeliverySettingsClient);
  private readonly locale = inject(LocaleStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly currency = this.locale.currency;
  protected readonly loaded = signal(false);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected share = '';
  protected minimum = '';
  protected offerSeconds = 30;
  protected radiusKm = '';
  protected leadMinutes = 0;
  protected alertMinutes = 5;
  protected codeRule: CodeRule = 'never';
  protected codeAbove = '';
  protected retentionDays = 30;
  private etag = '';

  async ngOnInit(): Promise<void> {
    try {
      const { value, etag } = await firstValueFrom(this.client.get());
      this.etag = etag;
      this.share = String(value.riderPayBasisPoints / 100);
      this.minimum = toMajor(value.riderPayMinMinor, this.currency());
      this.offerSeconds = value.offerTimeoutSeconds;
      this.radiusKm = String(value.searchRadiusMeters / 1000);
      this.leadMinutes = value.searchLeadMinutes;
      this.alertMinutes = Math.max(1, Math.round(value.noRiderAlertSeconds / 60));
      this.codeRule =
        value.proofCodeAboveMinor === null
          ? 'never'
          : value.proofCodeAboveMinor === 0
            ? 'always'
            : 'above';
      this.codeAbove = value.proofCodeAboveMinor
        ? toMajor(value.proofCodeAboveMinor, this.currency())
        : '';
      this.retentionDays = value.locationRetentionDays;
      this.loaded.set(true);
    } catch {
      this.loadError.set(true);
    }
  }

  /** A worked example with a typical charge, so the percent means something. */
  protected example(): string {
    const share = Number((this.share || '0').replace(',', '.'));
    const min = toMinor(this.minimum || '0', this.currency()) ?? 0;
    const charge = toMinor('250', this.currency()) ?? 0;
    if (!Number.isFinite(share)) return '';
    const pay = Math.max(Math.round((charge * share) / 100), min);
    const language = this.locale.language();
    return `Example: a delivery charged ${formatMoney(charge, this.currency(), language)} pays the rider ${formatMoney(pay, this.currency(), language)}.`;
  }

  protected async save(): Promise<void> {
    const share = Number((this.share || '').replace(',', '.'));
    const minimum = toMinor(this.minimum || '0', this.currency());
    const radius = Number((this.radiusKm || '').replace(',', '.'));
    const codeAbove = this.codeRule === 'above' ? toMinor(this.codeAbove, this.currency()) : null;
    if (!Number.isFinite(share) || share < 0 || share > 100 || minimum === null) {
      this.error.set('The rider share must be 0 to 100 % and the minimum a number of 0 or more.');
      return;
    }
    if (!Number.isFinite(radius) || radius <= 0) {
      this.error.set('The search radius must be more than 0 km.');
      return;
    }
    if (this.codeRule === 'above' && !codeAbove) {
      this.error.set('Enter the order total above which the code is asked.');
      return;
    }
    this.error.set('');
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.client.update(
          {
            riderPayBasisPoints: Math.round(share * 100),
            riderPayMinMinor: minimum,
            offerTimeoutSeconds: Math.round(this.offerSeconds),
            searchRadiusMeters: Math.round(radius * 1000),
            searchLeadMinutes: Math.round(this.leadMinutes),
            noRiderAlertSeconds: Math.round(this.alertMinutes) * 60,
            proofCodeAboveMinor:
              this.codeRule === 'never' ? null : this.codeRule === 'always' ? 0 : codeAbove,
            locationRetentionDays: Math.round(this.retentionDays),
          },
          this.etag,
        ),
      );
      this.etag = saved.etag;
      this.snackBar.open('Saved. New deliveries use these rules.', undefined, { duration: 4000 });
    } catch (error) {
      this.error.set(describeError(error, 'The settings could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
