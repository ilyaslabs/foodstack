import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { City, CityClient, Rider, RiderInput, Vehicle } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';

export const VEHICLE_LABELS: Record<Vehicle, string> = {
  MOTORBIKE: 'Motorbike',
  SCOOTER: 'Scooter',
  BICYCLE: 'Bicycle',
  CAR: 'Car',
};

export interface RiderFormData {
  title: string;
  /** Editing this rider; adding when absent. */
  rider?: Rider;
  save: (input: RiderInput) => Observable<Rider>;
}

/** Adding a rider (with their sign-in) or changing one's details. */
@Component({
  selector: 'app-rider-form-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <form id="rider-form" class="fields" (ngSubmit)="submit()">
        <mat-form-field appearance="outline" class="span-2">
          <mat-label>Full name</mat-label>
          <input matInput name="name" required maxlength="80" [(ngModel)]="name" />
        </mat-form-field>
        @if (!data.rider) {
          <mat-form-field appearance="outline" class="span-2">
            <mat-label>Email (signs in with it)</mat-label>
            <input matInput name="email" type="email" required [(ngModel)]="email" />
            <mat-hint>An existing account becomes a rider and keeps its password</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline" class="span-2">
            <mat-label>First password</mat-label>
            <input
              matInput
              name="password"
              type="text"
              autocomplete="new-password"
              minlength="10"
              [(ngModel)]="password"
            />
            <mat-hint>For a new account, at least 10 characters; give it to the rider</mat-hint>
          </mat-form-field>
        }
        <mat-form-field appearance="outline">
          <mat-label>Phone</mat-label>
          <input matInput name="phone" type="tel" required [(ngModel)]="phone" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Vehicle</mat-label>
          <mat-select name="vehicle" [(ngModel)]="vehicle">
            @for (v of vehicles; track v[0]) {
              <mat-option [value]="v[0]">{{ v[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Number plate</mat-label>
          <input matInput name="plate" maxlength="20" [(ngModel)]="plate" />
        </mat-form-field>
        @if (cities().length) {
          <mat-form-field appearance="outline">
            <mat-label>City</mat-label>
            <mat-select name="cityId" [(ngModel)]="cityId">
              <mat-option value="">Any city</mat-option>
              @for (c of cities(); track c.id) {
                <mat-option [value]="c.id" [disabled]="!c.active && c.id !== cityId">{{
                  locale.pick(c.name)
                }}</mat-option>
              }
            </mat-select>
            <mat-hint>Platform jobs come only from branches in this city</mat-hint>
          </mat-form-field>
        } @else {
          <mat-form-field appearance="outline">
            <mat-label>City</mat-label>
            <input matInput name="city" maxlength="60" [(ngModel)]="city" />
          </mat-form-field>
        }
      </form>
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="rider-form" [disabled]="saving()">
        {{ data.rider ? 'Save' : 'Add rider' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .fields {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 4px 12px;
      padding-block-start: 4px;
    }
    .span-2 {
      grid-column: 1 / -1;
    }
    @media (max-width: 599.98px) {
      .fields {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    mat-form-field {
      width: 100%;
    }
    .error {
      margin-block-start: 8px;
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class RiderFormDialog {
  protected readonly data = inject<RiderFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<RiderFormDialog, Rider>);

  protected readonly vehicles = Object.entries(VEHICLE_LABELS) as [Vehicle, string][];
  protected name = this.data.rider?.name ?? '';
  protected email = '';
  protected password = '';
  protected phone = this.data.rider?.phone ?? '';
  protected vehicle: Vehicle = this.data.rider?.vehicle ?? 'MOTORBIKE';
  protected plate = this.data.rider?.plate ?? '';
  protected city = this.data.rider?.city ?? '';
  protected cityId = this.data.rider?.cityId ?? '';
  protected readonly locale = inject(LocaleStore);
  protected readonly cities = signal<City[]>([]);

  constructor() {
    firstValueFrom(inject(CityClient).list()).then(
      (cities) => this.cities.set(cities),
      () => this.cities.set([]),
    );
  }
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected async submit(): Promise<void> {
    if (!this.name.trim() || !this.phone.trim() || (!this.data.rider && !this.email.trim())) {
      this.error.set('Fill in the name, phone and email.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const saved = await firstValueFrom(
        this.data.save({
          email: this.data.rider ? null : this.email.trim(),
          initialPassword: this.data.rider ? null : this.password || null,
          name: this.name.trim(),
          phone: this.phone.trim(),
          vehicle: this.vehicle,
          plate: this.plate.trim() || null,
          city: this.cities().length ? null : this.city.trim() || null,
          cityId: this.cities().length ? this.cityId || null : null,
          version: this.data.rider?.version ?? null,
        }),
      );
      this.ref.close(saved);
    } catch (error) {
      this.error.set(describeError(error, 'The rider could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
