import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { GeoClient, PeakWindow, WEEKDAYS, Weekday } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

/**
 * The operator's own delivery rate, used for branches the operator's riders deliver for:
 * a base charge plus a charge per km of road distance, up to a farthest distance, with a minimum
 * order, a free-delivery threshold and dearer peak hours (PRD DR-1b).
 */
@Component({
  selector: 'app-delivery-pricing-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    Icon,
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
          <div class="card-section">
            <h2>Order rules</h2>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Minimum order</mat-label>
                <input matInput name="minOrder" inputmode="decimal" [(ngModel)]="minOrder" />
                <span matTextSuffix>&nbsp;{{ currency() }}</span>
                <mat-hint>Empty for no minimum</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Free delivery from</mat-label>
                <input matInput name="freeAbove" inputmode="decimal" [(ngModel)]="freeAbove" />
                <span matTextSuffix>&nbsp;{{ currency() }}</span>
                <mat-hint>Basket subtotal; empty for never free</mat-hint>
              </mat-form-field>
            </div>
            <p class="section-hint">
              On free deliveries you still pay the rider, so set the threshold above your usual
              basket.
            </p>
          </div>
          <div class="card-section">
            <h2>Peak hours</h2>
            <p class="section-hint">
              At these times the delivery charge is multiplied, to bring more riders out. Times are
              in {{ timezone() }}.
            </p>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Peak charge</mat-label>
                <span matTextPrefix>×&nbsp;</span>
                <input
                  matInput
                  name="peakFactor"
                  type="number"
                  min="1"
                  max="3"
                  step="0.1"
                  [(ngModel)]="peakFactor"
                />
                <mat-hint
                  >1 to 3; 1.5 makes a {{ sample().normal }} delivery {{ sample().peak }}</mat-hint
                >
              </mat-form-field>
            </div>
            <ul class="windows" role="list">
              @for (w of windows(); track $index; let i = $index) {
                <li class="window">
                  <mat-button-toggle-group
                    multiple
                    hideMultipleSelectionIndicator
                    [attr.aria-label]="'Days of peak hours ' + (i + 1)"
                    [value]="w.days"
                    (change)="setDays(i, $event.value)"
                  >
                    @for (d of weekdays; track d) {
                      <mat-button-toggle [value]="d">{{ dayLabels[d] }}</mat-button-toggle>
                    }
                  </mat-button-toggle-group>
                  <div class="times">
                    <mat-form-field appearance="outline" subscriptSizing="dynamic">
                      <mat-label>From</mat-label>
                      <input
                        matInput
                        type="time"
                        [name]="'start' + i"
                        [ngModel]="w.start"
                        (ngModelChange)="setTime(i, 'start', $event)"
                      />
                    </mat-form-field>
                    <mat-form-field appearance="outline" subscriptSizing="dynamic">
                      <mat-label>Until</mat-label>
                      <input
                        matInput
                        type="time"
                        [name]="'end' + i"
                        [ngModel]="w.end"
                        (ngModelChange)="setTime(i, 'end', $event)"
                      />
                    </mat-form-field>
                    <button
                      mat-icon-button
                      type="button"
                      [attr.aria-label]="'Remove peak hours ' + (i + 1)"
                      (click)="removeWindow(i)"
                    >
                      <fs-icon name="delete" />
                    </button>
                  </div>
                  <span class="window-note">{{ describe(w) }}</span>
                </li>
              }
            </ul>
            <button
              mat-stroked-button
              type="button"
              class="add-window"
              [disabled]="windows().length >= 10"
              (click)="addWindow()"
            >
              <fs-icon name="add" /> Add peak hours
            </button>
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
    .windows {
      list-style: none;
      margin: 8px 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .window {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 16px;
      padding: 12px;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
    }
    .window mat-button-toggle-group {
      flex-wrap: wrap;
    }
    .times {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .times mat-form-field {
      inline-size: 132px;
    }
    .window-note {
      flex-basis: 100%;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .add-window {
      align-self: flex-start;
      margin-block-start: 8px;
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
  protected minOrder = '';
  protected freeAbove = '';
  protected readonly peakFactorValue = signal(1.5);
  protected readonly windows = signal<PeakWindow[]>([]);
  protected readonly weekdays = WEEKDAYS;
  protected readonly dayLabels: Record<Weekday, string> = {
    MONDAY: 'Mon',
    TUESDAY: 'Tue',
    WEDNESDAY: 'Wed',
    THURSDAY: 'Thu',
    FRIDAY: 'Fri',
    SATURDAY: 'Sat',
    SUNDAY: 'Sun',
  };
  protected readonly timezone = computed(() => this.locale.locale().timezone);
  private etag = '';

  protected get peakFactor(): number {
    return this.peakFactorValue();
  }
  protected set peakFactor(value: number) {
    this.peakFactorValue.set(value);
  }

  /** A 3 km delivery at the normal and the peak charge. */
  protected readonly sample = computed(() => {
    const base = toMinor(this.baseText(), this.currency()) ?? 0;
    const perKm = toMinor(this.perKmText(), this.currency()) ?? 0;
    const normal = base + perKm * 3;
    return {
      normal: this.locale.money(normal),
      peak: this.locale.money(Math.round(normal * 1.5)),
    };
  });

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
      this.minOrder = value.minOrderMinor ? toMajor(value.minOrderMinor, this.currency()) : '';
      this.freeAbove =
        value.freeDeliveryAboveMinor !== null
          ? toMajor(value.freeDeliveryAboveMinor, this.currency())
          : '';
      this.peakFactor = (value.peakMultiplierPercent ?? 100) / 100;
      this.windows.set(value.peakWindows ?? []);
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
    const minOrderMinor = this.minOrder.trim() ? toMinor(this.minOrder, this.currency()) : 0;
    const freeDeliveryAboveMinor = this.freeAbove.trim()
      ? toMinor(this.freeAbove, this.currency())
      : null;
    if (minOrderMinor === null || (this.freeAbove.trim() && !freeDeliveryAboveMinor)) {
      this.error.set(
        'Enter the minimum order and the free delivery amount as amounts, or leave them empty.',
      );
      return;
    }
    const peakMultiplierPercent = Math.round(Number(this.peakFactor) * 100);
    if (!(peakMultiplierPercent >= 100 && peakMultiplierPercent <= 300)) {
      this.error.set('The peak charge must be between ×1 and ×3.');
      return;
    }
    if (this.windows().some((w) => !w.start || !w.end || w.start === w.end)) {
      this.error.set('Give every peak hours window a start and a different end time.');
      return;
    }
    this.error.set('');
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.geo.updateOperatorPricing(
          {
            enabled: this.enabled,
            baseChargeMinor: base,
            perKmMinor: perKm,
            maxDistanceMeters,
            minOrderMinor,
            freeDeliveryAboveMinor,
            peakMultiplierPercent,
            peakWindows: this.windows(),
          },
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

  protected addWindow(): void {
    this.windows.update((list) => [
      ...list,
      { days: ['FRIDAY', 'SATURDAY'], start: '19:00', end: '22:00' },
    ]);
  }

  protected removeWindow(index: number): void {
    this.windows.update((list) => list.filter((_, i) => i !== index));
  }

  protected setDays(index: number, days: Weekday[]): void {
    const ordered = WEEKDAYS.filter((d) => days.includes(d));
    this.windows.update((list) => list.map((w, i) => (i === index ? { ...w, days: ordered } : w)));
  }

  protected setTime(index: number, field: 'start' | 'end', value: string): void {
    this.windows.update((list) => list.map((w, i) => (i === index ? { ...w, [field]: value } : w)));
  }

  /** What a window means in words, so a window past midnight is not a surprise. */
  protected describe(w: PeakWindow): string {
    const days =
      w.days.length === 0 || w.days.length === 7
        ? 'Every day'
        : w.days.map((d) => this.dayLabels[d]).join(', ');
    const overnight = w.start && w.end && w.end < w.start ? ', running past midnight' : '';
    return `${days}, ${w.start || '–'} to ${w.end || '–'}${overnight}`;
  }
}
