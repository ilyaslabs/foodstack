import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import { StoreClient, Translated } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { TranslatedField, cleanTranslated } from '../shared/translated-field';
import { toMinor } from '../shared/money';

/** Onboard a restaurant (PRD ST-1, BB-1): its brand and first branch, as a draft. */
@Component({
  selector: 'app-onboard-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    Icon,
    TranslatedField,
  ],
  template: `
    <a mat-button routerLink="/stores" class="back"><fs-icon name="arrow_back" /> Stores</a>
    <header class="fs-page-header">
      <h1>Onboard a restaurant</h1>
      <p>Creates the restaurant as a draft. Add its menu and delivery area, then make it live.</p>
    </header>

    <mat-card appearance="outlined" class="form-card">
      <form [formGroup]="form" (ngSubmit)="save()">
        <section class="card-section">
          <h2>Restaurant</h2>
          <div class="grid-2">
            <mat-form-field appearance="outline">
              <mat-label>Brand name</mat-label>
              <input matInput formControlName="brandName" maxlength="80" required />
              <mat-hint>What customers see, such as "Spice Route".</mat-hint>
              <mat-error>Enter the brand name</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Branch name</mat-label>
              <input matInput formControlName="branchName" maxlength="60" />
              <mat-hint>Optional, such as "Gulberg". Leave empty for a single restaurant.</mat-hint>
            </mat-form-field>
          </div>
          <app-translated-field
            label="Short description"
            [multiline]="true"
            [maxLength]="500"
            [(value)]="description"
          />
          <mat-form-field appearance="outline">
            <mat-label>Cuisines</mat-label>
            <input matInput formControlName="cuisines" placeholder="pakistani, bbq, rice" />
            <mat-hint>Separate with commas. Customers filter by these.</mat-hint>
          </mat-form-field>
        </section>

        <section class="card-section">
          <h2>Business</h2>
          <div class="grid-2">
            <mat-form-field appearance="outline">
              <mat-label>Legal name</mat-label>
              <input matInput formControlName="legalName" maxlength="120" required />
              <mat-error>Enter the registered business name</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Tax number</mat-label>
              <input matInput formControlName="taxId" maxlength="40" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Commission</mat-label>
              <input matInput formControlName="commissionPercent" inputmode="decimal" required />
              <span matTextSuffix>%</span>
              <mat-hint>Taken from each order's subtotal.</mat-hint>
              <mat-error>0 to 100</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Fixed fee per order</mat-label>
              <input matInput formControlName="commissionFixed" inputmode="decimal" />
              <span matTextSuffix>{{ locale.currency() }}</span>
            </mat-form-field>
          </div>
        </section>

        <section class="card-section">
          <h2>Branch</h2>
          <div class="grid-2">
            <mat-form-field appearance="outline">
              <mat-label>Street address</mat-label>
              <input matInput formControlName="line1" maxlength="160" required />
              <mat-error>Enter the street address</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Area</mat-label>
              <input matInput formControlName="area" maxlength="80" />
              <mat-hint>Shown on store cards, such as "Gulberg III".</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>City</mat-label>
              <input matInput formControlName="city" maxlength="80" required />
              <mat-error>Enter the city</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Average preparation time</mat-label>
              <input
                matInput
                type="number"
                formControlName="prepMinutes"
                min="5"
                max="120"
                required
              />
              <span matTextSuffix>min</span>
              <mat-error>5 to 120 minutes</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Latitude</mat-label>
              <input matInput formControlName="lat" inputmode="decimal" placeholder="31.5204" />
              <mat-error>-90 to 90</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Longitude</mat-label>
              <input matInput formControlName="lng" inputmode="decimal" placeholder="74.3587" />
              <mat-error>-180 to 180</mat-error>
            </mat-form-field>
          </div>
          <p class="section-hint">
            The location is needed to go live. Copy it from any map app (long-press the entrance).
          </p>
          <mat-slide-toggle formControlName="pickupEnabled"
            >Customers can collect orders</mat-slide-toggle
          >
        </section>

        @if (error(); as error) {
          <div class="banner error" role="alert">
            <fs-icon name="error" /><span>{{ error }}</span>
          </div>
        }
        <div class="actions">
          <a mat-button routerLink="/stores">Cancel</a>
          <button mat-flat-button type="submit" [disabled]="saving()">
            {{ saving() ? 'Creating…' : 'Create draft' }}
          </button>
        </div>
      </form>
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .back {
      margin: -8px 0 8px -12px;
    }
    .form-card {
      max-width: 880px;
    }
  `,
})
export class OnboardPage {
  protected readonly locale = inject(LocaleStore);
  private readonly client = inject(StoreClient);
  private readonly router = inject(Router);

  protected readonly description = signal<Translated>({});
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    brandName: ['', [Validators.required, Validators.maxLength(80)]],
    branchName: [''],
    cuisines: [''],
    legalName: ['', [Validators.required]],
    taxId: [''],
    commissionPercent: ['15', [Validators.required, Validators.min(0), Validators.max(100)]],
    commissionFixed: [''],
    line1: ['', [Validators.required]],
    area: [''],
    city: ['', [Validators.required]],
    prepMinutes: [20, [Validators.required, Validators.min(5), Validators.max(120)]],
    lat: ['', [Validators.min(-90), Validators.max(90)]],
    lng: ['', [Validators.min(-180), Validators.max(180)]],
    pickupEnabled: [true],
  });

  protected async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const hasLocation = v.lat.trim() !== '' && v.lng.trim() !== '';
    this.saving.set(true);
    this.error.set(null);
    try {
      const created = await firstValueFrom(
        this.client.onboard({
          brandName: v.brandName.trim(),
          branchName: v.branchName.trim() || null,
          legalName: v.legalName.trim(),
          taxId: v.taxId.trim() || null,
          description: cleanTranslated(this.description()),
          cuisines: v.cuisines
            .split(',')
            .map((c) => c.trim())
            .filter(Boolean),
          address: {
            line1: v.line1.trim(),
            area: v.area.trim() || null,
            city: v.city.trim(),
            postalCode: null,
          },
          location: hasLocation ? { lat: Number(v.lat), lng: Number(v.lng) } : null,
          pickupEnabled: v.pickupEnabled,
          prepMinutes: Number(v.prepMinutes),
          commission: {
            percentBasisPoints: Math.round(Number(v.commissionPercent) * 100),
            fixedMinor: toMinor(v.commissionFixed, this.locale.currency()) ?? 0,
          },
        }),
      );
      await this.router.navigate(['/stores', created.value.id]);
    } catch (error) {
      this.error.set(describeError(error, 'The restaurant could not be created.'));
    } finally {
      this.saving.set(false);
    }
  }
}
