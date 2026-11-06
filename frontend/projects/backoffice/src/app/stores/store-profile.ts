import { Component, effect, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  Brand,
  BrandClient,
  DeliveryMode,
  MediaClient,
  StoreClient,
  Translated,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import { TranslatedField, cleanTranslated } from '../shared/translated-field';
import { toMajor, toMinor } from '../shared/money';
import { StoreContext } from './store-context';

/** Brand (name, story, images) and branch (address, location, delivery) details. */
@Component({
  selector: 'app-store-profile',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatRadioModule,
    MatSelectModule,
    MatSlideToggleModule,
    Icon,
    TranslatedField,
  ],
  template: `
    <div class="stack">
      <mat-card appearance="outlined">
        <form [formGroup]="brandForm" (ngSubmit)="saveBrand()">
          <section class="card-section">
            <h2>Brand</h2>
            <p class="section-hint">Shared by every branch of the brand.</p>
            <mat-form-field appearance="outline">
              <mat-label>Brand name</mat-label>
              <input matInput formControlName="name" maxlength="80" required />
              <mat-error>Enter the brand name</mat-error>
            </mat-form-field>
            <app-translated-field
              label="Description"
              [multiline]="true"
              [maxLength]="500"
              [(value)]="description"
            />
            <mat-form-field appearance="outline">
              <mat-label>Cuisines</mat-label>
              <input matInput formControlName="cuisines" />
              <mat-hint>Separate with commas, such as "pakistani, bbq".</mat-hint>
            </mat-form-field>
          </section>
          <section class="card-section">
            <h2>Images</h2>
            <div class="images">
              @for (slot of imageSlots; track slot.key) {
                <div class="image-slot">
                  <div class="image-box" [class.cover]="slot.key === 'cover'">
                    @if (images()[slot.key]; as image) {
                      <img [src]="image.url" [alt]="slot.label" />
                    } @else {
                      <fs-icon name="image" />
                    }
                  </div>
                  <div class="image-text">
                    <strong>{{ slot.label }}</strong>
                    <span>{{ slot.hint }}</span>
                    <div class="image-buttons">
                      <button
                        mat-stroked-button
                        type="button"
                        (click)="input.click()"
                        [disabled]="uploading()"
                      >
                        <fs-icon name="upload" /> {{ images()[slot.key] ? 'Replace' : 'Upload' }}
                      </button>
                      @if (images()[slot.key]) {
                        <button mat-button type="button" (click)="clearImage(slot.key)">
                          Remove
                        </button>
                      }
                    </div>
                  </div>
                  <input
                    #input
                    type="file"
                    hidden
                    accept="image/png,image/jpeg,image/webp"
                    (change)="upload(slot.key, $event)"
                  />
                </div>
              }
            </div>
            @if (uploading()) {
              <mat-progress-bar mode="indeterminate" />
            }
          </section>
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="savingBrand() || uploading()">
              {{ savingBrand() ? 'Saving…' : 'Save brand' }}
            </button>
          </div>
        </form>
      </mat-card>

      <mat-card appearance="outlined">
        <form [formGroup]="branchForm" (ngSubmit)="saveBranch()">
          <section class="card-section">
            <h2>Branch</h2>
            <div class="grid-2">
              <mat-form-field appearance="outline">
                <mat-label>Branch name</mat-label>
                <input matInput formControlName="branchName" maxlength="60" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Average preparation time</mat-label>
                <input matInput type="number" formControlName="prepMinutes" min="5" max="120" />
                <span matTextSuffix>min</span>
                <mat-error>5 to 120 minutes</mat-error>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Street address</mat-label>
                <input matInput formControlName="line1" maxlength="160" required />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Area</mat-label>
                <input matInput formControlName="area" maxlength="80" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>City</mat-label>
                <input matInput formControlName="city" maxlength="80" required />
              </mat-form-field>
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Time zone</mat-label>
                <mat-select formControlName="timezone">
                  @for (zone of zones(); track zone) {
                    <mat-option [value]="zone">{{ zone.replaceAll('_', ' ') }}</mat-option>
                  }
                </mat-select>
                <mat-hint>Set from the location. Used for hours and tickets.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Latitude</mat-label>
                <input matInput formControlName="lat" inputmode="decimal" />
                <mat-error>-90 to 90</mat-error>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Longitude</mat-label>
                <input matInput formControlName="lng" inputmode="decimal" />
                <mat-error>-180 to 180</mat-error>
              </mat-form-field>
            </div>
          </section>
          <section class="card-section">
            <h2>Fulfilment</h2>
            <mat-radio-group formControlName="deliveryMode" class="radios">
              <mat-radio-button value="SELF">
                <span class="radio-title">Our own riders</span>
                <span class="radio-hint"
                  >You set delivery zones and charges on the Delivery tab.</span
                >
              </mat-radio-button>
              <mat-radio-button
                value="OPERATOR"
                [disabled]="!context.store()?.operatorDeliveryAllowed"
              >
                <span class="radio-title">Platform riders</span>
                <span class="radio-hint">
                  @if (context.store()?.operatorDeliveryAllowed) {
                    Customers pay the platform's delivery rate.
                  } @else {
                    Not enabled for this branch by the platform.
                  }
                </span>
              </mat-radio-button>
            </mat-radio-group>
            <mat-slide-toggle formControlName="pickupEnabled"
              >Customers can collect orders</mat-slide-toggle
            >
          </section>
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="savingBranch()">
              {{ savingBranch() ? 'Saving…' : 'Save branch' }}
            </button>
          </div>
        </form>
      </mat-card>

      @if (session.operator()) {
        <mat-card appearance="outlined">
          <form [formGroup]="adminForm" (ngSubmit)="saveAdmin()">
            <section class="card-section">
              <h2>Platform settings</h2>
              <p class="section-hint">Only the platform team sees and edits these.</p>
              <div class="grid-2">
                <mat-form-field appearance="outline">
                  <mat-label>Legal name</mat-label>
                  <input matInput formControlName="legalName" maxlength="120" required />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Tax number</mat-label>
                  <input matInput formControlName="taxId" maxlength="40" />
                </mat-form-field>
                <mat-form-field appearance="outline" class="span-2" subscriptSizing="dynamic">
                  <mat-label>Web address</mat-label>
                  <span matTextPrefix>/stores/</span>
                  <input matInput formControlName="slug" maxlength="60" required />
                  <mat-hint
                    >Lower-case letters, digits and hyphens. Old links stop working when this
                    changes.</mat-hint
                  >
                  <mat-error>Use 3 to 60 lower-case letters, digits and single hyphens</mat-error>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Commission</mat-label>
                  <input matInput formControlName="commissionPercent" inputmode="decimal" />
                  <span matTextSuffix>%</span>
                  <mat-hint>For every branch of the brand.</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Fixed fee per order</mat-label>
                  <input matInput formControlName="commissionFixed" inputmode="decimal" />
                  <span matTextSuffix>{{ locale.currency() }}</span>
                </mat-form-field>
              </div>
              <mat-slide-toggle formControlName="operatorDeliveryAllowed">
                Branch may use platform riders
              </mat-slide-toggle>
            </section>
            <div class="actions">
              <button mat-flat-button type="submit" [disabled]="savingAdmin()">
                {{ savingAdmin() ? 'Saving…' : 'Save platform settings' }}
              </button>
            </div>
          </form>
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
    .images {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 16px;
    }
    @media (max-width: 719.98px) {
      .images {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .image-slot {
      display: flex;
      gap: 16px;
      align-items: center;
    }
    .image-box {
      display: grid;
      place-items: center;
      inline-size: 88px;
      block-size: 88px;
      flex: none;
      border-radius: 16px;
      overflow: hidden;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .image-box.cover {
      inline-size: 140px;
    }
    .image-box img {
      inline-size: 100%;
      block-size: 100%;
      object-fit: cover;
    }
    .image-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .image-text strong {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface);
    }
    .image-buttons {
      display: flex;
      gap: 4px;
      margin-block-start: 6px;
    }
    .radios {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .radio-title {
      display: block;
      font: var(--mat-sys-body-large);
    }
    .radio-hint {
      display: block;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class StoreProfile {
  protected readonly context = inject(StoreContext);
  protected readonly session = inject(Session);
  protected readonly locale = inject(LocaleStore);
  private readonly stores = inject(StoreClient);
  private readonly brands = inject(BrandClient);
  private readonly media = inject(MediaClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly imageSlots = [
    { key: 'logo', label: 'Logo', hint: 'Square, at least 512 px.' },
    { key: 'cover', label: 'Cover photo', hint: 'Wide food photo, at least 1600 px.' },
  ] as const;

  protected readonly description = signal<Translated>({});
  protected readonly images = signal<
    Record<'logo' | 'cover', { mediaId: string; url: string } | null>
  >({
    logo: null,
    cover: null,
  });
  protected readonly uploading = signal(false);
  protected readonly savingBrand = signal(false);
  protected readonly savingBranch = signal(false);
  protected readonly savingAdmin = signal(false);
  private brandEtag = '';

  protected readonly brandForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(80)]],
    cuisines: [''],
  });

  protected readonly branchForm = this.fb.group({
    branchName: [''],
    prepMinutes: [20, [Validators.min(5), Validators.max(120)]],
    line1: ['', Validators.required],
    area: [''],
    city: ['', Validators.required],
    lat: ['', [Validators.min(-90), Validators.max(90)]],
    lng: ['', [Validators.min(-180), Validators.max(180)]],
    deliveryMode: ['SELF' as DeliveryMode],
    pickupEnabled: [false],
    timezone: [''],
  });

  /** Every zone the browser knows, with the branch's own first if the browser lacks it. */
  protected readonly zones = signal<string[]>(knownZones());

  protected readonly adminForm = this.fb.group({
    legalName: ['', Validators.required],
    taxId: [''],
    slug: [
      '',
      [
        Validators.required,
        Validators.minLength(3),
        Validators.maxLength(60),
        Validators.pattern(/^[a-z0-9]+(-[a-z0-9]+)*$/),
      ],
    ],
    commissionPercent: [''],
    commissionFixed: [''],
    operatorDeliveryAllowed: [false],
  });

  constructor() {
    let loadedFor = '';
    effect(() => {
      const store = this.context.store();
      if (!store) return;
      this.fillBranch();
      if (loadedFor !== store.brand.id) {
        loadedFor = store.brand.id;
        void this.loadBrand(store.brand.id);
      }
    });
  }

  private async loadBrand(brandId: string): Promise<void> {
    const { value, etag } = await firstValueFrom(this.brands.get(brandId));
    this.fillBrand(value, etag);
  }

  private fillBrand(brand: Brand, etag: string): void {
    this.brandEtag = etag;
    this.brandForm.reset({ name: brand.name, cuisines: brand.cuisines.join(', ') });
    this.description.set({ ...brand.description });
    this.images.set({
      logo: brand.logo
        ? {
            mediaId: brand.logo.mediaId,
            url: brand.logo.urls['card'] ?? Object.values(brand.logo.urls)[0],
          }
        : null,
      cover: brand.cover
        ? {
            mediaId: brand.cover.mediaId,
            url: brand.cover.urls['card'] ?? Object.values(brand.cover.urls)[0],
          }
        : null,
    });
    this.adminForm.patchValue({
      commissionPercent: String(brand.commission.percentBasisPoints / 100),
      commissionFixed: toMajor(brand.commission.fixedMinor, this.locale.currency()),
    });
  }

  private fillBranch(): void {
    const s = this.context.store()!;
    this.branchForm.reset({
      branchName: s.branchName ?? '',
      prepMinutes: s.prepMinutes,
      line1: s.address.line1,
      area: s.address.area ?? '',
      city: s.address.city,
      lat: s.location ? String(s.location.lat) : '',
      lng: s.location ? String(s.location.lng) : '',
      deliveryMode: s.deliveryMode,
      pickupEnabled: s.pickupEnabled,
      timezone: s.timezone,
    });
    if (s.timezone && !this.zones().includes(s.timezone))
      this.zones.update((z) => [s.timezone, ...z]);
    this.adminForm.patchValue({
      legalName: s.legalName,
      taxId: s.taxId ?? '',
      slug: s.slug ?? '',
      operatorDeliveryAllowed: s.operatorDeliveryAllowed,
    });
  }

  protected async upload(slot: 'logo' | 'cover', event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'STORE'));
      const url = asset.variants['card']?.url ?? Object.values(asset.variants)[0]?.url ?? '';
      this.images.update((images) => ({ ...images, [slot]: { mediaId: asset.id, url } }));
      this.brandForm.markAsDirty();
    } catch (error) {
      this.snackBar.open(describeError(error, 'The image could not be uploaded.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.uploading.set(false);
      input.value = '';
    }
  }

  protected clearImage(slot: 'logo' | 'cover'): void {
    this.images.update((images) => ({ ...images, [slot]: null }));
  }

  protected async saveBrand(): Promise<void> {
    if (this.brandForm.invalid) {
      this.brandForm.markAllAsTouched();
      return;
    }
    const store = this.context.store()!;
    const v = this.brandForm.getRawValue();
    this.savingBrand.set(true);
    try {
      const saved = await firstValueFrom(
        this.brands.update(
          store.brand.id,
          {
            name: v.name.trim(),
            description: cleanTranslated(this.description()),
            cuisines: v.cuisines
              .split(',')
              .map((c) => c.trim())
              .filter(Boolean),
            logoMediaId: this.images().logo?.mediaId ?? null,
            coverMediaId: this.images().cover?.mediaId ?? null,
          },
          this.brandEtag,
        ),
      );
      this.fillBrand(saved.value, saved.etag);
      await this.context.load(store.id);
      this.snackBar.open('Brand saved.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The brand could not be saved.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.savingBrand.set(false);
    }
  }

  protected async saveBranch(): Promise<void> {
    if (this.branchForm.invalid) {
      this.branchForm.markAllAsTouched();
      return;
    }
    const store = this.context.store()!;
    const v = this.branchForm.getRawValue();
    const hasLocation = v.lat.trim() !== '' && v.lng.trim() !== '';
    this.savingBranch.set(true);
    try {
      this.context.set(
        await firstValueFrom(
          this.stores.updateBranch(
            store.id,
            {
              branchName: v.branchName.trim() || null,
              address: {
                line1: v.line1.trim(),
                area: v.area.trim() || null,
                city: v.city.trim(),
                postalCode: store.address.postalCode,
              },
              location: hasLocation ? { lat: Number(v.lat), lng: Number(v.lng) } : null,
              prepMinutes: Number(v.prepMinutes),
              deliveryMode: v.deliveryMode,
              pickupEnabled: v.pickupEnabled,
              timezone: v.timezone || null,
            },
            this.context.etag(),
          ),
        ),
      );
      this.snackBar.open('Branch saved.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The branch could not be saved.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.savingBranch.set(false);
    }
  }

  protected async saveAdmin(): Promise<void> {
    const store = this.context.store()!;
    const v = this.adminForm.getRawValue();
    const percent = Number(v.commissionPercent);
    if (this.adminForm.controls.slug.invalid) {
      this.adminForm.controls.slug.markAsTouched();
      this.snackBar.open('Check the web address.', 'Dismiss', { duration: 6000 });
      return;
    }
    if (this.adminForm.invalid || !Number.isFinite(percent) || percent < 0 || percent > 100) {
      this.snackBar.open('Enter a commission from 0 to 100 %.', 'Dismiss', { duration: 6000 });
      return;
    }
    this.savingAdmin.set(true);
    try {
      await firstValueFrom(
        this.brands.updateCommission(store.brand.id, {
          percentBasisPoints: Math.round(percent * 100),
          fixedMinor: toMinor(v.commissionFixed, this.locale.currency()) ?? 0,
        }),
      );
      this.context.set(
        await firstValueFrom(
          this.stores.updateAdmin(
            store.id,
            {
              legalName: v.legalName.trim(),
              taxId: v.taxId.trim() || null,
              operatorDeliveryAllowed: v.operatorDeliveryAllowed,
            },
            this.context.etag(),
          ),
        ),
      );
      const slug = v.slug.trim();
      if (slug !== store.slug) {
        this.context.set(
          await firstValueFrom(this.stores.updateSlug(store.id, slug, this.context.etag())),
        );
      }
      await this.loadBrand(store.brand.id);
      this.snackBar.open('Platform settings saved.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The settings could not be saved.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.savingAdmin.set(false);
    }
  }
}

function knownZones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf(key: string): string[] }).supportedValuesOf(
      'timeZone',
    );
  } catch {
    return [];
  }
}
