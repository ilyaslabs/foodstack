import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { GeoClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

/**
 * The operator's own delivery rate, used for branches the operator's riders deliver for:
 * a base charge plus a charge per km of road distance, up to a farthest distance.
 */
@Component({
  selector: 'app-delivery-pricing-page',
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
      <h1>Delivery pricing</h1>
      <p>
        What customers pay when your riders deliver. Branches that deliver themselves set their own
        charges.
      </p>
    </header>

    @if (loaded()) {
      <mat-card appearance="outlined">
        <form (ngSubmit)="save()">
          <div class="card-section">
            <mat-slide-toggle name="enabled" [(ngModel)]="enabled"
              >Your riders deliver</mat-slide-toggle
            >
            <p class="section-hint">
              Turn this off to stop operator delivery everywhere. Branches with operator delivery
              then offer pickup only.
            </p>
          </div>
          <div class="card-section">
            <h2>Rate</h2>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Base charge</mat-label>
                <input matInput name="base" inputmode="decimal" [(ngModel)]="base" required />
                <span matTextSuffix>&nbsp;{{ currency() }}</span>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Per km</mat-label>
                <input matInput name="perKm" inputmode="decimal" [(ngModel)]="perKm" required />
                <span matTextSuffix>&nbsp;{{ currency() }}</span>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Farthest delivery</mat-label>
                <input
                  matInput
                  name="maxKm"
                  type="number"
                  min="1"
                  max="50"
                  step="0.5"
                  [(ngModel)]="maxKm"
                  required
                />
                <span matTextSuffix>&nbsp;km</span>
                <mat-hint>1 to 50 km</mat-hint>
              </mat-form-field>
            </div>
            @if (examples().length) {
              <div class="examples" aria-label="Examples">
                @for (example of examples(); track example.km) {
                  <div class="example">
                    <span class="km">{{ example.km }} km</span>
                    <span class="charge">{{ example.charge }}</span>
                  </div>
                }
              </div>
              <p class="section-hint">
                Distance is the estimated road distance from the branch to the customer.
              </p>
            }
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
      <p>The delivery rate could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 880px;
    }
    .examples {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .example {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 8px 14px;
      border-radius: 12px;
      background: var(--mat-sys-surface-container-high);
      min-inline-size: 96px;
    }
    .km {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .charge {
      font: var(--mat-sys-title-small);
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class DeliveryPricingPage implements OnInit {
  private readonly geo = inject(GeoClient);
  private readonly locale = inject(LocaleStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly currency = this.locale.currency;
  protected readonly loaded = signal(false);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected enabled = true;
  protected readonly baseText = signal('');
  protected readonly perKmText = signal('');
  protected readonly maxKmValue = signal(10);
  private etag = '';

  protected get base(): string {
    return this.baseText();
  }
  protected set base(value: string) {
    this.baseText.set(value);
  }
  protected get perKm(): string {
    return this.perKmText();
  }
  protected set perKm(value: string) {
    this.perKmText.set(value);
  }
  protected get maxKm(): number {
    return this.maxKmValue();
  }
  protected set maxKm(value: number) {
    this.maxKmValue.set(value);
  }

  /** A few sample distances so the operator sees what customers would pay. */
  protected readonly examples = computed(() => {
    const base = toMinor(this.baseText(), this.currency());
    const perKm = toMinor(this.perKmText(), this.currency());
    const max = Number(this.maxKmValue());
    if (base === null || perKm === null || !max) return [];
    return [1, 3, 5, 10, 15, 25]
      .filter((km) => km <= max)
      .map((km) => ({ km, charge: this.locale.money(base + Math.round(perKm * km)) }));
  });

  async ngOnInit(): Promise<void> {
    try {
      const { value, etag } = await firstValueFrom(this.geo.operatorPricing());
      this.etag = etag;
      this.enabled = value.enabled;
      this.base = toMajor(value.baseChargeMinor, this.currency());
      this.perKm = toMajor(value.perKmMinor, this.currency());
      this.maxKm = value.maxDistanceMeters / 1000;
      this.loaded.set(true);
    } catch {
      this.loadError.set(true);
    }
  }

  protected async save(): Promise<void> {
    const base = toMinor(this.base, this.currency());
    const perKm = toMinor(this.perKm, this.currency());
    const maxDistanceMeters = Math.round(Number(this.maxKm) * 1000);
    if (base === null || perKm === null) {
      this.error.set('Enter the base charge and the charge per km as amounts, such as 100.');
      return;
    }
    if (!(maxDistanceMeters >= 1000 && maxDistanceMeters <= 50_000)) {
      this.error.set('The farthest delivery must be between 1 and 50 km.');
      return;
    }
    this.error.set('');
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.geo.updateOperatorPricing(
          { enabled: this.enabled, baseChargeMinor: base, perKmMinor: perKm, maxDistanceMeters },
          this.etag,
        ),
      );
      this.etag = saved.etag;
      this.snackBar.open('Delivery pricing saved. New quotes use it right away.', undefined, {
        duration: 4000,
      });
    } catch (error) {
      this.error.set(describeError(error, 'The delivery pricing could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
