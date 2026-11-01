import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  filter,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { firstValueFrom } from 'rxjs';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
} from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { AddressSuggestion, GeoClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { Ui } from '../i18n';
import { CustomerLocation, LocationStore } from '../location-store';

/** Address search with suggestions, or the device location; saves the pick in LocationStore. */
@Component({
  selector: 'app-location-picker',
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    Icon,
  ],
  template: `
    <div class="picker">
      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="field">
        <fs-icon matPrefix name="place" class="prefix" />
        <input
          matInput
          type="search"
          autocomplete="off"
          [placeholder]="ui.t('addressPlaceholder')"
          [attr.aria-label]="ui.t('whereTo')"
          [matAutocomplete]="suggestions"
          (input)="typed($event)"
        />
        @if (searching()) {
          <mat-spinner matSuffix diameter="20" class="spinner" />
        }
        <mat-autocomplete
          #suggestions="matAutocomplete"
          [displayWith]="label"
          (optionSelected)="pick($event)"
        >
          @for (option of options(); track option.label) {
            <mat-option [value]="option">{{ option.label }}</mat-option>
          }
        </mat-autocomplete>
      </mat-form-field>
      <button
        mat-stroked-button
        type="button"
        class="locate"
        (click)="locate()"
        [disabled]="locating()"
      >
        <fs-icon name="my_location" />
        {{ locating() ? ui.t('locating') : ui.t('useMyLocation') }}
      </button>
    </div>
    @if (message()) {
      <p class="message" role="status">{{ message() }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
      inline-size: 100%;
    }
    .picker {
      display: flex;
      gap: 12px;
      align-items: center;
    }
    .field {
      flex: 1;
      min-width: 0;
      --mat-form-field-outlined-container-shape: 28px;
      --mat-form-field-container-height: 52px;
      --mat-form-field-outlined-outline-color: var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface);
      border-radius: 28px;
    }
    .prefix {
      margin-inline: 14px 4px;
      color: var(--mat-sys-primary);
    }
    .spinner {
      margin-inline-end: 12px;
    }
    .locate {
      flex: none;
      --mat-button-outlined-container-height: 52px;
      --mat-button-outlined-container-shape: 28px;
      background: var(--mat-sys-surface);
    }
    .message {
      margin: 8px 4px 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    @media (max-width: 599.98px) {
      .picker {
        flex-direction: column;
        align-items: stretch;
      }
    }
  `,
})
export class LocationPicker {
  protected readonly ui = inject(Ui);
  private readonly geo = inject(GeoClient);
  private readonly locations = inject(LocationStore);

  /** Emits after a location is saved. */
  readonly picked = output<CustomerLocation>();

  protected readonly options = signal<AddressSuggestion[]>([]);
  protected readonly searching = signal(false);
  protected readonly locating = signal(false);
  protected readonly message = signal('');

  private readonly queries = new Subject<string>();
  protected readonly label = (option: AddressSuggestion | string | null) =>
    typeof option === 'string' ? option : (option?.label ?? '');

  constructor() {
    this.queries
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => this.message.set('')),
        filter((query) => query.length >= 3 || (this.options.set([]), false)),
        tap(() => this.searching.set(true)),
        switchMap((query) => this.geo.searchAddresses(query).pipe(catchError(() => of(null)))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((options) => {
        this.searching.set(false);
        this.options.set(options ?? []);
        if (options === null) this.message.set(this.ui.t('addressSearchDown'));
        else if (options.length === 0) this.message.set(this.ui.t('noAddressMatch'));
      });
  }

  protected typed(event: Event): void {
    this.queries.next((event.target as HTMLInputElement).value.trim());
  }

  protected pick(event: MatAutocompleteSelectedEvent): void {
    const option = event.option.value as AddressSuggestion;
    this.save({ lat: option.lat, lng: option.lng, label: option.label });
  }

  protected async locate(): Promise<void> {
    this.locating.set(true);
    this.message.set('');
    try {
      const position = await this.locations.locate();
      let label = '';
      try {
        label =
          (await firstValueFrom(this.geo.reverse(position.lat, position.lng)))[0]?.label ?? '';
      } catch {
        // no address for the point: show "your current location"
      }
      this.save({ ...position, label });
    } catch {
      this.message.set(this.ui.t('locationDenied'));
    } finally {
      this.locating.set(false);
    }
  }

  private save(location: CustomerLocation): void {
    this.locations.set(location);
    this.picked.emit(location);
  }
}
