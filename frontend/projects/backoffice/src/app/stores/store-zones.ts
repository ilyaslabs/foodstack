import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { FormsModule } from '@angular/forms';

import { GeoClient, LatLng } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { StoreContext } from './store-context';
import { areaKm2, circle, contains, selfIntersects } from './zones/polygon';
import { ZONE_COLOURS, ZoneMap } from './zones/zone-map';

interface ZoneRow {
  name: string;
  polygon: LatLng[];
  charge: string;
  minOrder: string;
  freeAbove: string;
}

/**
 * Delivery zones drawn on a map (PRD ST-11): each zone is an area the owner outlines, with its own
 * charge, minimum order and free-delivery threshold. Where zones overlap, the first in the list
 * sets the charge.
 */
@Component({
  selector: 'app-store-zones',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    Icon,
    ZoneMap,
  ],
  template: `
    <mat-card appearance="outlined" class="card">
      @if (context.store()?.deliveryMode === 'OPERATOR') {
        <section class="card-section">
          <h2>Platform riders deliver for this branch</h2>
          <p class="section-hint">
            Customers pay the platform's delivery rate and see the branch within the platform's
            delivery distance. Switch to your own riders on the Profile tab to draw your own zones.
          </p>
        </section>
      } @else {
        <section class="card-section">
          <h2>Delivery zones</h2>
          <p class="section-hint">
            Draw the areas you deliver to. Pick a zone below, then click the map to add corners,
            drag a corner to move it, or click a corner to remove it. Where zones overlap, the first
            in the list sets the charge. Up to 20 zones.
          </p>
          @if (!context.store()?.location) {
            <div class="banner error">
              <fs-icon name="warning" /><span
                >Set the branch location on the Profile tab first.</span
              >
            </div>
          }
          <app-zone-map
            [branch]="context.store()?.location ?? null"
            [zones]="rows()"
            [selected]="selected()"
            (changed)="reshape($event)"
          />
          @if (rows()[selected()]; as zone) {
            <div class="shape-tools">
              <span class="editing">
                <span class="dot" [style.background]="colour(selected())"></span>
                Editing <strong>{{ zone.name || 'zone ' + (selected() + 1) }}</strong> ·
                {{ zone.polygon.length }} corners
                @if (zone.polygon.length >= 3) {
                  · about {{ area(zone.polygon) }} km²
                }
              </span>
              <span class="spacer"></span>
              <mat-form-field appearance="outline" subscriptSizing="dynamic" class="km">
                <mat-label>Radius</mat-label>
                <input
                  matInput
                  type="number"
                  min="0.5"
                  max="50"
                  step="0.5"
                  [(ngModel)]="circleKm"
                />
                <span matTextSuffix>km</span>
              </mat-form-field>
              <button
                mat-stroked-button
                type="button"
                (click)="startFromCircle()"
                [disabled]="!context.store()?.location"
              >
                Start from a circle
              </button>
              <button mat-button type="button" (click)="reshape([])">Clear shape</button>
            </div>
            @if (problem(zone.polygon); as problem) {
              <div class="banner error" role="status">
                <fs-icon name="warning" /><span>{{ problem }}</span>
              </div>
            }
          }
          @for (zone of rows(); track $index; let i = $index) {
            <div class="zone" [class.selected]="i === selected()">
              <button
                type="button"
                class="pick"
                [style.--zone-colour]="colour(i)"
                [attr.aria-pressed]="i === selected()"
                [attr.aria-label]="'Edit the shape of zone ' + (i + 1)"
                (click)="selected.set(i)"
              >
                {{ i + 1 }}
              </button>
              <div class="fields">
                <mat-form-field appearance="outline" subscriptSizing="dynamic" class="name">
                  <mat-label>Name</mat-label>
                  <input
                    matInput
                    maxlength="60"
                    [value]="zone.name"
                    (input)="set(i, 'name', $event)"
                    (focus)="selected.set(i)"
                  />
                </mat-form-field>
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Charge</mat-label>
                  <input
                    matInput
                    inputmode="decimal"
                    [value]="zone.charge"
                    (input)="set(i, 'charge', $event)"
                    (focus)="selected.set(i)"
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
                    (focus)="selected.set(i)"
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
                    (focus)="selected.set(i)"
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
              [disabled]="rows().length >= 20 || !context.store()?.location"
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
    .zone.selected {
      background: color-mix(in srgb, var(--mat-sys-primary) 6%, transparent);
      border-radius: 12px;
    }
    .pick {
      display: inline-grid;
      place-items: center;
      inline-size: 36px;
      block-size: 36px;
      flex: none;
      margin-inline-start: 8px;
      border-radius: 50%;
      border: 3px solid var(--zone-colour);
      background: transparent;
      color: var(--mat-sys-on-surface);
      font: var(--mat-sys-label-large);
      cursor: pointer;
    }
    .selected .pick {
      background: var(--zone-colour);
      color: #fff;
    }
    .shape-tools {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 12px;
      font: var(--mat-sys-body-medium);
    }
    .editing {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }
    .dot {
      inline-size: 12px;
      block-size: 12px;
      border-radius: 50%;
    }
    .spacer {
      flex: 1;
    }
    .km {
      inline-size: 120px;
    }
    .fields {
      display: grid;
      grid-template-columns: 1.4fr repeat(3, minmax(0, 1fr));
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
    @media (max-width: 599.98px) {
      .zone {
        flex-wrap: wrap;
        justify-content: space-between;
        padding-inline: 8px;
      }
      .pick {
        margin-inline-start: 0;
      }
      .fields {
        order: 3;
        flex-basis: 100%;
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
  protected readonly selected = signal(-1);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected circleKm = 3;

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
        polygon: z.polygon ?? [],
        charge: toMajor(z.chargeMinor, currency),
        minOrder: toMajor(z.minOrderMinor, currency),
        freeAbove: toMajor(z.freeDeliveryAboveMinor, currency),
      })),
    );
    this.selected.set(zones.length ? 0 : -1);
  }

  protected colour(index: number): string {
    return ZONE_COLOURS[index % ZONE_COLOURS.length];
  }

  protected area(polygon: LatLng[]): string {
    const km2 = areaKm2(polygon);
    return km2 < 10 ? km2.toFixed(1) : String(Math.round(km2));
  }

  /** What is wrong with an outline, in words an owner can act on; null when it is fine. */
  protected problem(polygon: LatLng[]): string | null {
    if (polygon.length === 0) return 'Click the map to draw this zone, or start from a circle.';
    if (polygon.length < 3) return 'A zone needs at least 3 corners.';
    if (polygon.length > 200) return 'A zone can have at most 200 corners.';
    if (selfIntersects(polygon))
      return 'Two edges of this zone cross. Drag a corner so the outline does not cross itself.';
    return null;
  }

  /** A new zone starts as a circle a little larger than the last one, ready to reshape. */
  protected add(): void {
    const location = this.context.store()?.location;
    if (!location) return;
    const radius = 3 + this.rows().length * 2;
    const last = this.rows().at(-1);
    this.rows.update((rows) => [
      ...rows,
      {
        name: rows.length ? `Zone ${rows.length + 1}` : 'Nearby',
        polygon: circle(location, radius),
        charge: last?.charge ?? '',
        minOrder: last?.minOrder ?? '',
        freeAbove: '',
      },
    ]);
    this.selected.set(this.rows().length - 1);
  }

  protected startFromCircle(): void {
    const location = this.context.store()?.location;
    const km = Number(this.circleKm);
    if (!location || !(km > 0 && km <= 50)) return;
    this.reshape(circle(location, km));
  }

  protected reshape(polygon: LatLng[]): void {
    const index = this.selected();
    this.rows.update((rows) => rows.map((r, i) => (i === index ? { ...r, polygon } : r)));
  }

  protected remove(index: number): void {
    this.rows.update((rows) => rows.filter((_, i) => i !== index));
    this.selected.update((selected) =>
      Math.min(index < selected ? selected - 1 : selected, this.rows().length - 1),
    );
  }

  protected set(
    index: number,
    field: 'name' | 'charge' | 'minOrder' | 'freeAbove',
    event: Event,
  ): void {
    const value = (event.target as HTMLInputElement).value;
    this.rows.update((rows) => rows.map((r, i) => (i === index ? { ...r, [field]: value } : r)));
  }

  protected async save(): Promise<void> {
    const store = this.context.store()!;
    const currency = this.currency();
    const zones = [];
    for (const [i, row] of this.rows().entries()) {
      const charge = toMinor(row.charge, currency);
      if (!row.name.trim() || charge === null) {
        this.selected.set(i);
        this.error.set(`Zone ${i + 1}: give it a name and a charge (0 for free).`);
        return;
      }
      const problem = this.problem(row.polygon);
      if (problem) {
        this.selected.set(i);
        this.error.set(`Zone ${i + 1}: ${problem}`);
        return;
      }
      zones.push({
        name: row.name.trim(),
        center: null,
        radiusMeters: null,
        polygon: row.polygon,
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
      const outside =
        zones.length > 0 &&
        store.location &&
        !zones.some((z) => contains(z.polygon, store.location!));
      this.snackBar.open(
        outside ? 'Saved. Note: the branch itself is outside every zone.' : 'Delivery zones saved.',
        undefined,
        { duration: 4000 },
      );
    } catch (error) {
      this.error.set(describeError(error, 'The zones could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
