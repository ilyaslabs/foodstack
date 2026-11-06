import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { City, CityClient, CityInput, GeoClient, OperatorPricing, Translated } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { TranslatedField, cleanTranslated, hasDefault } from '../shared/translated-field';

/**
 * The operator's cities (PRD AD-4). Each branch and rider belongs to one; dispatch offers a job
 * only to riders of the branch's city. A city may have its own operator delivery rate, else the
 * operator-wide rate applies.
 */
@Component({
  selector: 'app-cities-page',
  imports: [MatButtonModule, MatCardModule, MatDialogModule, MatProgressBarModule, MatSnackBarModule, Icon],
  template: `
    <header class="fs-page-header head">
      <div>
        <h1>Cities</h1>
        <p>
          Where you operate. Branches and riders belong to a city, and your riders only get jobs in
          their own city.
        </p>
      </div>
      <button mat-flat-button type="button" (click)="edit(null)" [disabled]="!operatorPricing()">
        <fs-icon name="add" /> Add city
      </button>
    </header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else {
      <div class="list">
        @for (city of cities(); track city.id) {
          <mat-card appearance="outlined" class="city">
            <div class="row">
              <span class="badge" aria-hidden="true"><fs-icon name="location_city" /></span>
              <div class="who">
                <strong>{{ locale.pick(city.name) }}</strong>
                <span>
                  @if (city.pricing; as p) {
                    Own rate: {{ locale.money(p.baseChargeMinor) }} +
                    {{ locale.money(p.perKmMinor) }}/km, up to {{ p.maxDistanceMeters / 1000 }} km
                  } @else {
                    Uses your operator-wide delivery rate
                  }
                </span>
              </div>
              <span class="chip" [class.live]="city.active">{{
                city.active ? 'Active' : 'Inactive'
              }}</span>
              <button mat-button type="button" (click)="edit(city)">Edit</button>
            </div>
          </mat-card>
        } @empty {
          <mat-card appearance="outlined" class="empty">
            <fs-icon name="location_city" />
            <h2>No cities yet</h2>
            <p>
              Without cities every rider can get any job. Add the cities you operate in to keep
              riders local and to price delivery per city.
            </p>
          </mat-card>
        }
      </div>
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      max-width: 880px;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 16px 20px;
      flex-wrap: wrap;
    }
    .badge {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 12px;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .who {
      display: flex;
      flex-direction: column;
      flex: 1 1 220px;
      min-width: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .who strong {
      font: var(--mat-sys-title-medium);
      color: var(--mat-sys-on-surface);
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 8px;
      padding: 40px 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .empty h2 {
      font: var(--mat-sys-title-large);
      color: var(--mat-sys-on-surface);
      margin: 0;
    }
    .empty p {
      max-width: 480px;
      margin: 0;
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class CitiesPage implements OnInit {
  protected readonly locale = inject(LocaleStore);
  private readonly client = inject(CityClient);
  private readonly geo = inject(GeoClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly cities = signal<City[]>([]);
  protected readonly loading = signal(true);
  protected readonly operatorPricing = signal<OperatorPricing | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const [cities, pricing] = await Promise.all([
        firstValueFrom(this.client.list()),
        firstValueFrom(this.geo.operatorPricing()),
      ]);
      this.cities.set(cities);
      this.operatorPricing.set(pricing.value);
    } catch (error) {
      this.snackBar.open(describeError(error, 'Cities could not be loaded.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  protected async edit(city: City | null): Promise<void> {
    const saved = await firstValueFrom(
      this.dialog
        .open<CityDialog, CityDialogData, City>(CityDialog, {
          data: { city, operatorPricing: this.operatorPricing()! },
          width: '640px',
          maxWidth: 'calc(100vw - 32px)',
          autoFocus: 'first-tabbable',
        })
        .afterClosed(),
    );
    if (!saved) return;
    this.cities.update((list) => {
      const others = list.filter((c) => c.id !== saved.id);
      return [...others, saved].sort((a, b) =>
        this.locale.pick(a.name).localeCompare(this.locale.pick(b.name)),
      );
    });
    this.snackBar.open(city ? 'City saved.' : 'City added.', undefined, { duration: 3000 });
  }
}

interface CityDialogData {
  city: City | null;
  operatorPricing: OperatorPricing;
}

/** Adds or edits one city, with an optional own delivery rate. */
@Component({
  selector: 'app-city-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    TranslatedField,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.city ? 'Edit city' : 'Add a city' }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <div class="stack">
          <app-translated-field label="Name" [required]="true" [maxLength]="60" [(value)]="name" />
          <div class="grid-2">
            <mat-form-field appearance="outline">
              <mat-label>Centre latitude</mat-label>
              <input matInput name="lat" inputmode="decimal" [(ngModel)]="lat" required />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Centre longitude</mat-label>
              <input matInput name="lng" inputmode="decimal" [(ngModel)]="lng" required />
            </mat-form-field>
          </div>
          <p class="hint">
            A new branch without a chosen city gets the nearest one within 60 km of this centre.
          </p>
          <mat-slide-toggle name="active" [(ngModel)]="active">Active</mat-slide-toggle>
          <p class="hint">Inactive cities cannot be chosen for new branches or riders.</p>
          <mat-slide-toggle name="ownPricing" [(ngModel)]="ownPricing"
            >Own delivery rate</mat-slide-toggle
          >
          @if (ownPricing) {
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Base charge</mat-label>
                <input matInput name="base" inputmode="decimal" [(ngModel)]="base" required />
                <span matTextSuffix>&nbsp;{{ locale.currency() }}</span>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Per km</mat-label>
                <input matInput name="perKm" inputmode="decimal" [(ngModel)]="perKm" required />
                <span matTextSuffix>&nbsp;{{ locale.currency() }}</span>
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
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Minimum order</mat-label>
                <input matInput name="minOrder" inputmode="decimal" [(ngModel)]="minOrder" />
                <span matTextSuffix>&nbsp;{{ locale.currency() }}</span>
                <mat-hint>Empty for none</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Free delivery from</mat-label>
                <input matInput name="freeAbove" inputmode="decimal" [(ngModel)]="freeAbove" />
                <span matTextSuffix>&nbsp;{{ locale.currency() }}</span>
                <mat-hint>Empty for never</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Peak charge</mat-label>
                <span matTextPrefix>×&nbsp;</span>
                <input
                  matInput
                  name="peak"
                  type="number"
                  min="1"
                  max="3"
                  step="0.1"
                  [(ngModel)]="peakFactor"
                />
              </mat-form-field>
            </div>
            <p class="hint">
              Peak hours are the same times as your operator-wide rate. Turn this off to use the
              operator-wide rate in this city.
            </p>
          }
        </div>
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
          {{ data.city ? 'Save' : 'Add city' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .stack {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-block-start: 4px;
    }
    .hint {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: -6px 0 4px;
    }
    .error {
      margin: 8px 24px 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class CityDialog {
  protected readonly data = inject<CityDialogData>(MAT_DIALOG_DATA);
  protected readonly locale = inject(LocaleStore);
  private readonly client = inject(CityClient);
  private readonly ref = inject(MatDialogRef<CityDialog, City>);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected name: Translated;
  protected lat: string;
  protected lng: string;
  protected active: boolean;
  protected ownPricing: boolean;
  protected base: string;
  protected perKm: string;
  protected maxKm: number;
  protected minOrder: string;
  protected freeAbove: string;
  protected peakFactor: number;

  constructor() {
    const city = this.data.city;
    const currency = this.locale.currency();
    const pricing = city?.pricing ?? this.data.operatorPricing;
    this.name = { ...(city?.name ?? {}) };
    this.lat = city ? String(city.centre.lat) : '';
    this.lng = city ? String(city.centre.lng) : '';
    this.active = city?.active ?? true;
    this.ownPricing = !!city?.pricing;
    this.base = toMajor(pricing.baseChargeMinor, currency);
    this.perKm = toMajor(pricing.perKmMinor, currency);
    this.maxKm = pricing.maxDistanceMeters / 1000;
    this.minOrder = pricing.minOrderMinor ? toMajor(pricing.minOrderMinor, currency) : '';
    this.freeAbove = toMajor(pricing.freeDeliveryAboveMinor, currency);
    this.peakFactor = pricing.peakMultiplierPercent / 100;
  }

  protected async save(): Promise<void> {
    this.error.set(null);
    const lat = Number(this.lat);
    const lng = Number(this.lng);
    if (!hasDefault(this.name, this.locale.locale().defaultLanguage)) {
      this.error.set('Enter the city name.');
      return;
    }
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      this.error.set('Enter the centre as latitude and longitude, such as 31.5204 and 74.3587.');
      return;
    }
    const currency = this.locale.currency();
    let pricing: CityInput['pricing'] = null;
    if (this.ownPricing) {
      const base = toMinor(this.base, currency);
      const perKm = toMinor(this.perKm, currency);
      if (base === null || perKm === null) {
        this.error.set('Enter the base charge and the charge per km.');
        return;
      }
      pricing = {
        baseChargeMinor: base,
        perKmMinor: perKm,
        maxDistanceMeters: Math.round(Number(this.maxKm) * 1000),
        minOrderMinor: toMinor(this.minOrder, currency) ?? 0,
        freeDeliveryAboveMinor: toMinor(this.freeAbove, currency),
        peakMultiplierPercent: Math.round(Number(this.peakFactor || 1) * 100),
        peakWindows: this.data.city?.pricing?.peakWindows ?? this.data.operatorPricing.peakWindows,
      };
    }
    const input: CityInput = {
      name: cleanTranslated(this.name),
      centre: { lat, lng },
      active: this.active,
      pricing,
    };
    this.saving.set(true);
    try {
      const city = this.data.city;
      const saved = city
        ? await firstValueFrom(this.client.update(city.id, input, city.version))
        : (await firstValueFrom(this.client.create(input))).value;
      this.ref.close(saved);
    } catch (error) {
      this.error.set(describeError(error, 'The city could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
