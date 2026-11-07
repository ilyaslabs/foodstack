import { Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  Brand,
  BrandClient,
  City,
  CityClient,
  Commission,
  CommissionChange,
  StoreClient,
  StoreDetails,
  StoreSummary,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { Session } from '../shared/session';
import { StoreContext } from './store-context';
import { StoreOwners } from './store-owners';
import { STATUS_LABELS } from './store-status';

/**
 * The brand around the open branch (PRD BB-1, BB-5, BB-8, BB-11): its branches, its owners, the
 * commission with the dates it changes, and whether branch managers may run promotions.
 * Operator staff and the brand's owners see it.
 */
@Component({
  selector: 'app-store-brand',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    Icon,
    StoreOwners,
  ],
  template: `
    <div class="stack">
      <mat-card appearance="outlined">
        <section class="card-section">
          <div class="title-row">
            <div>
              <h2>Branches</h2>
              <p class="section-hint">
                Every branch shares the brand's menu. A branch can change its own prices and add its
                own items on the Menu tab.
              </p>
            </div>
            <button mat-stroked-button type="button" (click)="addBranch()">
              <fs-icon name="add" /> Add branch
            </button>
          </div>
          @if (branches() === null) {
            <mat-progress-bar mode="indeterminate" />
          }
          <ul class="rows" role="list">
            @for (branch of branches() ?? []; track branch.id) {
              <li>
                <a
                  class="row"
                  [routerLink]="['/stores', branch.id]"
                  [class.current]="branch.id === store()?.id"
                >
                  <span class="badge" aria-hidden="true"><fs-icon name="storefront" /></span>
                  <span class="main">
                    <strong>{{ branch.branchName ?? branch.brandName }}</strong>
                    <span>{{ cityName(branch) }}</span>
                  </span>
                  @if (branch.id === store()?.id) {
                    <span class="chip plain">This branch</span>
                  }
                  <span
                    class="chip"
                    [class.live]="branch.status === 'LIVE'"
                    [class.paused]="branch.status === 'PAUSED'"
                    >{{ statusLabels[branch.status] }}</span
                  >
                  <fs-icon name="chevron_right" class="chevron" />
                </a>
              </li>
            }
          </ul>
        </section>
      </mat-card>

      <app-store-owners scope="brand" />

      @if (brand(); as b) {
        <mat-card appearance="outlined">
          <section class="card-section">
            <h2>Branch promotions</h2>
            <mat-slide-toggle
              [checked]="b.branchPromotionsAllowed"
              (change)="setBranchPromotions($event.checked)"
              [disabled]="saving()"
            >
              Branch managers may run coupons for their own branch
            </mat-slide-toggle>
            <p class="section-hint">
              Brand owners and the platform can always run coupons for any branch of the brand.
            </p>
          </section>
        </mat-card>

        <mat-card appearance="outlined">
          <section class="card-section">
            <h2>Commission</h2>
            <p class="section-hint">
              What the platform keeps from each order's food, after the store's own discounts. An
              order pays the commission in force when it is placed.
            </p>
            <div class="now">
              <div class="figure">
                <span class="label">Brand, now</span>
                <strong>{{ describe(b.commission) }}</strong>
              </div>
              @if (store(); as s) {
                <div class="figure">
                  <span class="label">This branch, now</span>
                  <strong>{{ describe(s.commission) }}</strong>
                  @if (branchHasOwn()) {
                    <span class="label">Own rate</span>
                  } @else {
                    <span class="label">Same as the brand</span>
                  }
                </div>
              }
            </div>
            @if (history().length) {
              <table class="history">
                <caption class="visually-hidden">
                  Commission changes
                </caption>
                <thead>
                  <tr>
                    <th scope="col">From</th>
                    <th scope="col">For</th>
                    <th scope="col">Commission</th>
                  </tr>
                </thead>
                <tbody>
                  @for (change of history(); track $index) {
                    <tr [class.planned]="change.effectiveFrom * 1000 > nowMillis">
                      <td>
                        {{
                          change.effectiveFrom
                            ? (change.effectiveFrom * 1000 | date: 'mediumDate')
                            : 'Start'
                        }}
                        @if (change.effectiveFrom * 1000 > nowMillis) {
                          <span class="chip plain">Planned</span>
                        }
                      </td>
                      <td>{{ change.scope }}</td>
                      <td>
                        {{
                          change.commission ? describe(change.commission) : 'Back to the brand rate'
                        }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </section>
          @if (session.operator()) {
            <form class="card-section" (ngSubmit)="saveCommission()">
              <h2>Change commission</h2>
              <div class="grid-3">
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>For</mat-label>
                  <select matNativeControl name="target" [(ngModel)]="target">
                    <option value="brand">Every branch of the brand</option>
                    <option value="branch">This branch only</option>
                    @if (branchHasOwn()) {
                      <option value="reset">This branch: back to the brand rate</option>
                    }
                  </select>
                </mat-form-field>
                @if (target !== 'reset') {
                  <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Percent of food</mat-label>
                    <input matInput name="percent" inputmode="decimal" [(ngModel)]="percent" />
                    <span matTextSuffix>%</span>
                  </mat-form-field>
                  <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Fixed fee per order</mat-label>
                    <input matInput name="fixed" inputmode="decimal" [(ngModel)]="fixed" />
                    <span matTextSuffix>&nbsp;{{ locale.currency() }}</span>
                  </mat-form-field>
                }
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Starts on</mat-label>
                  <input matInput type="date" name="from" [(ngModel)]="from" [min]="today" />
                  <mat-hint>Empty starts now. A date starts at midnight, your time.</mat-hint>
                </mat-form-field>
              </div>
              <p class="section-hint">
                A change replaces any change planned on or after its start date.
              </p>
              <div class="inline-actions">
                <button mat-flat-button type="submit" [disabled]="saving()">
                  {{ saving() ? 'Saving…' : 'Save commission' }}
                </button>
              </div>
            </form>
          }
        </mat-card>
      }
    </div>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: block;
      padding-block: 16px;
    }
    .stack {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 880px;
    }
    .title-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 8px;
      border-radius: 12px;
      color: inherit;
      text-decoration: none;
    }
    .row:hover,
    .row:focus-visible {
      background: var(--mat-sys-surface-container-high);
    }
    .badge {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 12px;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      flex: none;
    }
    .main {
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      min-width: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .main strong {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface);
    }
    .chevron {
      color: var(--mat-sys-on-surface-variant);
    }
    .now {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }
    .figure {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-surface-container);
      min-width: 200px;
    }
    .figure strong {
      font: var(--mat-sys-title-large);
    }
    .label {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .history {
      width: 100%;
      border-collapse: collapse;
      font: var(--mat-sys-body-medium);
    }
    .history th {
      text-align: start;
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
      padding: 8px 8px 8px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .history td {
      padding: 8px 8px 8px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .history tr.planned td {
      color: var(--mat-sys-primary);
    }
    .inline-actions {
      display: flex;
      justify-content: flex-end;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class StoreBrand {
  protected readonly session = inject(Session);
  protected readonly locale = inject(LocaleStore);
  private readonly context = inject(StoreContext);
  private readonly brands = inject(BrandClient);
  private readonly stores = inject(StoreClient);
  private readonly cityClient = inject(CityClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly store = this.context.store;
  protected readonly brand = signal<Brand | null>(null);
  protected readonly branches = signal<StoreSummary[] | null>(null);
  protected readonly cities = signal<City[]>([]);
  protected readonly saving = signal(false);
  protected readonly nowMillis = Date.now();
  protected readonly today = new Date(Date.now() - new Date().getTimezoneOffset() * 60_000)
    .toISOString()
    .slice(0, 10);

  protected target: 'brand' | 'branch' | 'reset' = 'brand';
  protected percent = '';
  protected fixed = '';
  protected from = '';

  protected readonly branchHasOwn = computed(() => {
    const changes = this.store()?.commissionChanges ?? [];
    const current = [...changes].filter((c) => c.effectiveFrom * 1000 <= this.nowMillis).pop();
    return !!current?.commission;
  });

  /** Brand and branch changes together, newest first. */
  protected readonly history = computed(() => {
    const brand = (this.brand()?.commissionChanges ?? []).map((c) => ({ ...c, scope: 'Brand' }));
    const branch = (this.store()?.commissionChanges ?? []).map((c) => ({
      ...c,
      scope: 'This branch',
    }));
    return [...brand, ...branch].sort(
      (a, b) => b.effectiveFrom - a.effectiveFrom,
    ) as (CommissionChange & {
      scope: string;
    })[];
  });

  constructor() {
    firstValueFrom(this.cityClient.list()).then(
      (cities) => this.cities.set(cities),
      () => this.cities.set([]),
    );
    let loadedFor = '';
    effect(() => {
      const store = this.store();
      if (!store || loadedFor === store.brand.id) return;
      loadedFor = store.brand.id;
      void this.load(store);
    });
  }

  private async load(store: StoreDetails): Promise<void> {
    try {
      const [brand, branches] = await Promise.all([
        firstValueFrom(this.brands.get(store.brand.id)),
        firstValueFrom(this.brands.branches(store.brand.id)),
      ]);
      this.fill(brand.value);
      this.branches.set(branches);
    } catch (error) {
      this.branches.set([]);
      this.snackBar.open(describeError(error, 'The brand could not be loaded.'), 'Dismiss', {
        duration: 8000,
      });
    }
  }

  private fill(brand: Brand): void {
    this.brand.set(brand);
    this.percent = String(brand.commission.percentBasisPoints / 100);
    this.fixed = toMajor(brand.commission.fixedMinor, this.locale.currency());
  }

  protected cityName(branch: StoreSummary): string {
    const city = this.cities().find((c) => c.id === branch.cityId);
    return city ? this.locale.pick(city.name) : (branch.city ?? '');
  }

  protected describe(commission: Commission): string {
    const percent = `${(commission.percentBasisPoints / 100).toLocaleString('en', { maximumFractionDigits: 2 })} %`;
    return commission.fixedMinor
      ? `${percent} + ${this.locale.money(commission.fixedMinor)}`
      : percent;
  }

  protected async setBranchPromotions(allowed: boolean): Promise<void> {
    const brand = this.brand()!;
    this.saving.set(true);
    try {
      this.fill(await firstValueFrom(this.brands.setBranchPromotions(brand.id, allowed)));
      this.snackBar.open(
        allowed ? 'Branch managers can run coupons now.' : 'Only brand owners run coupons now.',
        undefined,
        { duration: 3000 },
      );
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work. Try again.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.saving.set(false);
    }
  }

  protected async saveCommission(): Promise<void> {
    const store = this.store()!;
    const brand = this.brand()!;
    let commission: Commission | null = null;
    if (this.target !== 'reset') {
      const percent = Number(String(this.percent).replace(',', '.'));
      const fixed = this.fixed.trim() === '' ? 0 : toMinor(this.fixed, this.locale.currency());
      if (!Number.isFinite(percent) || percent < 0 || percent > 100 || fixed === null) {
        this.snackBar.open(
          'Enter a commission from 0 to 100 % and a fixed fee of 0 or more.',
          'Dismiss',
          {
            duration: 6000,
          },
        );
        return;
      }
      commission = { percentBasisPoints: Math.round(percent * 100), fixedMinor: fixed };
    }
    const effectiveFrom = this.from
      ? Math.floor(new Date(`${this.from}T00:00:00`).getTime() / 1000)
      : null;
    this.saving.set(true);
    try {
      if (this.target === 'brand') {
        this.fill(
          await firstValueFrom(this.brands.updateCommission(brand.id, commission!, effectiveFrom)),
        );
      } else {
        this.context.set(
          await firstValueFrom(this.stores.updateCommission(store.id, commission, effectiveFrom)),
        );
      }
      this.from = '';
      this.snackBar.open(
        effectiveFrom ? 'Commission change planned.' : 'Commission saved.',
        undefined,
        { duration: 3000 },
      );
    } catch (error) {
      this.snackBar.open(describeError(error, 'The commission could not be saved.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.saving.set(false);
    }
  }

  protected async addBranch(): Promise<void> {
    const store = this.store()!;
    const created = await firstValueFrom(
      this.dialog
        .open<NewBranchDialog, NewBranchData, StoreDetails>(NewBranchDialog, {
          data: { brandId: store.brand.id, legalName: store.legalName, cities: this.cities() },
          width: '640px',
          maxWidth: 'calc(100vw - 32px)',
        })
        .afterClosed(),
    );
    if (!created) return;
    this.branches.update((list) => [...(list ?? []), summaryOf(created)]);
    this.snackBar.open(
      'Branch added as a draft. Open it to finish its hours and delivery area.',
      undefined,
      {
        duration: 5000,
      },
    );
  }
}

function summaryOf(store: StoreDetails): StoreSummary {
  return {
    id: store.id,
    brandId: store.brand.id,
    brandName: store.brand.name,
    branchName: store.branchName,
    city: store.address.city,
    status: store.status,
    temporarilyClosed: store.temporarilyClosed,
    deliveryMode: store.deliveryMode,
    createdAt: store.createdAt,
    logoUrls: store.brand.logo?.urls ?? {},
    cityId: store.cityId,
    branchCount: store.brand.branchCount,
  };
}

interface NewBranchData {
  brandId: string;
  legalName: string;
  cities: City[];
}

/** A new branch of the brand, in draft. */
@Component({
  selector: 'app-new-branch-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  template: `
    <h2 mat-dialog-title>Add a branch</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <div class="grid-2">
          <mat-form-field appearance="outline">
            <mat-label>Branch name</mat-label>
            <input matInput name="branchName" [(ngModel)]="branchName" maxlength="60" required />
            <mat-hint>Usually the area, such as "DHA Phase 5"</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Legal name</mat-label>
            <input matInput name="legalName" [(ngModel)]="legalName" maxlength="120" required />
            <mat-hint>The company running this branch</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline" class="span-2">
            <mat-label>Street address</mat-label>
            <input matInput name="line1" [(ngModel)]="line1" maxlength="160" required />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Area</mat-label>
            <input matInput name="area" [(ngModel)]="area" maxlength="80" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Town</mat-label>
            <input matInput name="town" [(ngModel)]="town" maxlength="80" required />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Latitude</mat-label>
            <input matInput name="lat" [(ngModel)]="lat" inputmode="decimal" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Longitude</mat-label>
            <input matInput name="lng" [(ngModel)]="lng" inputmode="decimal" />
          </mat-form-field>
          @if (data.cities.length) {
            <mat-form-field appearance="outline">
              <mat-label>City</mat-label>
              <mat-select name="cityId" [(ngModel)]="cityId">
                <mat-option value="">Nearest to the location</mat-option>
                @for (city of activeCities; track city.id) {
                  <mat-option [value]="city.id">{{ locale.pick(city.name) }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          <mat-form-field appearance="outline">
            <mat-label>Preparation time</mat-label>
            <input
              matInput
              type="number"
              name="prep"
              min="5"
              max="120"
              [(ngModel)]="prepMinutes"
              required
            />
            <span matTextSuffix>&nbsp;min</span>
          </mat-form-field>
        </div>
        <mat-slide-toggle name="pickup" [(ngModel)]="pickupEnabled"
          >Customers can collect orders</mat-slide-toggle
        >
      </mat-dialog-content>
      @if (saving()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Add branch</button>
      </mat-dialog-actions>
    </form>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .grid-2 {
      padding-block-start: 4px;
      margin-block-end: 8px;
    }
    .span-2 {
      grid-column: 1 / -1;
    }
    .error {
      margin: 8px 24px 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class NewBranchDialog {
  protected readonly data = inject<NewBranchData>(MAT_DIALOG_DATA);
  protected readonly locale = inject(LocaleStore);
  private readonly brands = inject(BrandClient);
  private readonly ref = inject(MatDialogRef<NewBranchDialog, StoreDetails>);

  protected readonly activeCities = this.data.cities.filter((c) => c.active);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected branchName = '';
  protected legalName = this.data.legalName;
  protected line1 = '';
  protected area = '';
  protected town = '';
  protected lat = '';
  protected lng = '';
  protected cityId = '';
  protected prepMinutes = 20;
  protected pickupEnabled = true;

  protected async save(): Promise<void> {
    this.error.set(null);
    if (
      !this.branchName.trim() ||
      !this.legalName.trim() ||
      !this.line1.trim() ||
      !this.town.trim()
    ) {
      this.error.set('Enter the branch name, legal name, street address and town.');
      return;
    }
    const hasLocation = this.lat.trim() !== '' && this.lng.trim() !== '';
    this.saving.set(true);
    try {
      const { value } = await firstValueFrom(
        this.brands.addBranch(this.data.brandId, {
          branchName: this.branchName.trim(),
          legalName: this.legalName.trim(),
          taxId: null,
          address: {
            line1: this.line1.trim(),
            area: this.area.trim() || null,
            city: this.town.trim(),
            postalCode: null,
          },
          location: hasLocation ? { lat: Number(this.lat), lng: Number(this.lng) } : null,
          cityId: this.cityId || null,
          prepMinutes: Number(this.prepMinutes),
          pickupEnabled: this.pickupEnabled,
        }),
      );
      this.ref.close(value);
    } catch (error) {
      this.error.set(describeError(error, 'The branch could not be added.'));
    } finally {
      this.saving.set(false);
    }
  }
}
