import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { GeoClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { StoreContext } from './store-context';

interface ZoneRow {
  name: string;
  radiusKm: string;
  charge: string;
  minOrder: string;
  freeAbove: string;
}

/**
 * Delivery zones as rings around the branch (PRD ST-11): the first ring that contains the
 * customer sets the charge, so list them from the nearest out.
 */
@Component({
  selector: 'app-store-zones',
  imports: [MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule, Icon],
  template: `
    <mat-card appearance="outlined" class="card">
      @if (context.store()?.deliveryMode === 'OPERATOR') {
        <section class="card-section">
          <h2>Platform riders deliver for this branch</h2>
          <p class="section-hint">
            Customers pay the platform's delivery rate and see the branch within the platform's
            delivery distance. Switch to your own riders on the Profile tab to set your own zones.
          </p>
        </section>
      } @else {
        <section class="card-section">
          <h2>Delivery zones</h2>
          <p class="section-hint">
            Rings around the branch location. A customer gets the charge of the first ring they fall
            in, so order them from the nearest out. Up to 20 zones.
          </p>
          @if (!context.store()?.location) {
            <div class="banner error">
              <fs-icon name="warning" /><span
                >Set the branch location on the Profile tab first.</span
              >
            </div>
          }
          @for (zone of rows(); track $index; let i = $index) {
            <div class="zone">
              <span class="ring" aria-hidden="true">{{ i + 1 }}</span>
              <div class="fields">
                <mat-form-field appearance="outline" subscriptSizing="dynamic" class="name">
                  <mat-label>Name</mat-label>
                  <input
                    matInput
                    maxlength="60"
                    [value]="zone.name"
                    (input)="set(i, 'name', $event)"
                  />
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Up to</mat-label>
                  <input
                    matInput
                    inputmode="decimal"
                    [value]="zone.radiusKm"
                    (input)="set(i, 'radiusKm', $event)"
                  />
                  <span matTextSuffix>km</span>
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Charge</mat-label>
                  <input
                    matInput
                    inputmode="decimal"
                    [value]="zone.charge"
                    (input)="set(i, 'charge', $event)"
                  />
                  <span matTextSuffix>{{ currency() }}</span>
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Minimum order</mat-label>
                  <input
                    matInput
                    inputmode="decimal"
                    [value]="zone.minOrder"
                    (input)="set(i, 'minOrder', $event)"
                  />
                  <span matTextSuffix>{{ currency() }}</span>
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic" floatLabel="always">
                  <mat-label>Free above</mat-label>
                  <input
                    matInput
                    inputmode="decimal"
                    [value]="zone.freeAbove"
                    (input)="set(i, 'freeAbove', $event)"
                    placeholder="Never"
                  />
                  <span matTextSuffix>{{ currency() }}</span>
                </mat-form-field>
              </div>
              <button mat-icon-button type="button" (click)="remove(i)" aria-label="Remove zone">
                <fs-icon name="delete" />
              </button>
            </div>
          } @empty {
            <p class="none">No zones yet: the branch only takes pickup orders.</p>
          }
          <div>
            <button
              mat-stroked-button
              type="button"
              (click)="add()"
              [disabled]="rows().length >= 20"
            >
              <fs-icon name="add" /> Add zone
            </button>
          </div>
        </section>
        @if (error(); as error) {
          <div class="banner error" role="alert">
            <fs-icon name="error" /><span>{{ error }}</span>
          </div>
        }
        <div class="actions">
          <button
            mat-flat-button
            type="button"
            (click)="save()"
            [disabled]="saving() || !context.store()?.location"
          >
            {{ saving() ? 'Saving…' : 'Save zones' }}
          </button>
        </div>
      }
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: block;
      padding-block: 16px;
    }
    .card {
      max-width: 1000px;
    }
    .zone {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .ring {
      display: inline-grid;
      place-items: center;
      inline-size: 36px;
      block-size: 36px;
      flex: none;
      border-radius: 50%;
      border: 2px solid var(--mat-sys-primary);
      color: var(--mat-sys-primary);
      font: var(--mat-sys-label-large);
    }
    .fields {
      display: grid;
      grid-template-columns: 1.4fr repeat(4, minmax(0, 1fr));
      gap: 12px;
      flex: 1 1 auto;
    }
    @media (max-width: 899.98px) {
      .fields {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
      .name {
        grid-column: 1 / -1;
      }
    }
    .none {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
    .banner {
      margin-inline: 0;
    }
  `,
})
export class StoreZones {
  protected readonly context = inject(StoreContext);
  private readonly geo = inject(GeoClient);
  private readonly locale = inject(LocaleStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly currency = computed(() => this.locale.currency());
  protected readonly rows = signal<ZoneRow[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    let loadedFor = '';
    effect(() => {
      const store = this.context.store();
      if (!store || loadedFor === store.id) return;
      loadedFor = store.id;
      void this.load(store.id);
    });
  }

  private async load(storeId: string): Promise<void> {
    const zones = await firstValueFrom(this.geo.zones(storeId));
    const currency = this.currency();
    this.rows.set(
      zones.map((z) => ({
        name: z.name,
        radiusKm: z.radiusMeters ? String(z.radiusMeters / 1000) : '',
        charge: toMajor(z.chargeMinor, currency),
        minOrder: toMajor(z.minOrderMinor, currency),
        freeAbove: toMajor(z.freeDeliveryAboveMinor, currency),
      })),
    );
  }

  protected add(): void {
    const last = this.rows().at(-1);
    const radius = last ? Number(last.radiusKm) + 2 : 3;
    this.rows.update((rows) => [
      ...rows,
      {
        name: `Up to ${radius} km`,
        radiusKm: String(radius),
        charge: last?.charge ?? '',
        minOrder: last?.minOrder ?? '',
        freeAbove: '',
      },
    ]);
  }

  protected remove(index: number): void {
    this.rows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected set(index: number, field: keyof ZoneRow, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.rows.update((rows) => rows.map((r, i) => (i === index ? { ...r, [field]: value } : r)));
  }

  protected async save(): Promise<void> {
    const store = this.context.store()!;
    const currency = this.currency();
    const zones = [];
    for (const [i, row] of this.rows().entries()) {
      const km = Number(row.radiusKm);
      const charge = toMinor(row.charge, currency);
      if (!row.name.trim() || !Number.isFinite(km) || km <= 0 || km > 50 || charge === null) {
        this.error.set(
          `Zone ${i + 1}: give it a name, a distance from 0.1 to 50 km and a charge (0 for free).`,
        );
        return;
      }
      zones.push({
        name: row.name.trim(),
        center: store.location,
        radiusMeters: Math.round(km * 1000),
        chargeMinor: charge,
        minOrderMinor: toMinor(row.minOrder, currency) ?? 0,
        freeDeliveryAboveMinor: toMinor(row.freeAbove, currency),
      });
    }
    this.error.set(null);
    this.saving.set(true);
    try {
      await firstValueFrom(this.geo.replaceZones(store.id, zones));
      await this.context.refreshReadiness();
      this.snackBar.open('Delivery zones saved.', undefined, { duration: 3000 });
    } catch (error) {
      this.error.set(describeError(error, 'The zones could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
