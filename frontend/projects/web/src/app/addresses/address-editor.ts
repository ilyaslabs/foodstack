import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { AddressLabel, LatLng, SavedAddress } from '@foodstack/api-client';
import { FsMap, Icon } from '@foodstack/ui';

import { Ui, UiKey } from '../i18n';
import { CustomerLocation } from '../location-store';
import { LocationPicker } from '../location/location-picker';
import { AddressBook } from './address-book';

export interface AddressEditorData {
  /** The address to edit; none to add one. */
  address?: SavedAddress;
  /** Where a new address starts, such as the header's location. */
  start?: CustomerLocation | null;
}

/**
 * Add or edit a saved address (PRD ID-6): find the area, move the map so the pin sits on the door,
 * pick Home, Work or a name of one's own, and leave notes for the rider. Closes with the saved
 * address.
 */
@Component({
  selector: 'app-address-editor',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    FsMap,
    Icon,
    LocationPicker,
  ],
  template: `
    <div class="head">
      <h2 mat-dialog-title>{{ ui.t(data.address ? 'editAddress' : 'addAddress') }}</h2>
      <button mat-icon-button type="button" mat-dialog-close [attr.aria-label]="ui.t('cancel')">
        <fs-icon name="close" />
      </button>
    </div>
    <mat-dialog-content>
      <form id="address-form" class="form" (ngSubmit)="save()">
        <app-location-picker [remember]="false" (picked)="found($event)" />
        <div class="map-wrap">
          <fs-map
            class="map"
            [center]="pin()"
            [zoom]="17"
            [centerPin]="true"
            [label]="ui.t('moveMapHint')"
            [failedText]="ui.t('mapFailed')"
            (moved)="pin.set($event)"
          />
          <p class="hint"><fs-icon name="info" /> {{ ui.t('moveMapHint') }}</p>
        </div>

        <mat-button-toggle-group
          class="labels"
          name="label"
          [value]="label()"
          (change)="label.set($event.value)"
          [attr.aria-label]="ui.t('addressLabel')"
        >
          <mat-button-toggle value="HOME"
            ><fs-icon name="home" /> {{ ui.t('labelHome') }}</mat-button-toggle
          >
          <mat-button-toggle value="WORK"
            ><fs-icon name="work" /> {{ ui.t('labelWork') }}</mat-button-toggle
          >
          <mat-button-toggle value="OTHER"
            ><fs-icon name="place" /> {{ ui.t('labelOther') }}</mat-button-toggle
          >
        </mat-button-toggle-group>
        @if (labelTaken()) {
          <p class="error" role="alert">{{ ui.t('addressLabelTaken') }}</p>
        }
        @if (label() === 'OTHER') {
          <mat-form-field appearance="outline">
            <mat-label>{{ ui.t('addressName') }}</mat-label>
            <input matInput name="name" maxlength="40" [(ngModel)]="name" />
            <mat-hint>{{ ui.t('addressNameHint') }}</mat-hint>
          </mat-form-field>
        }
        <mat-form-field appearance="outline">
          <mat-label>{{ ui.t('addressLine') }}</mat-label>
          <input
            matInput
            name="addressLine"
            autocomplete="street-address"
            maxlength="200"
            required
            [(ngModel)]="addressLine"
          />
          @if (submitted() && !addressLine.trim()) {
            <mat-error>{{ ui.t('addressRequired') }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>{{ ui.t('instructions') }}</mat-label>
          <input matInput name="instructions" maxlength="300" [(ngModel)]="instructions" />
        </mat-form-field>
        @if (!data.address?.preferred) {
          <mat-checkbox name="preferred" [(ngModel)]="preferred">{{
            ui.t('preferredAddress')
          }}</mat-checkbox>
        }
        @if (error(); as key) {
          <p class="error" role="alert">{{ ui.t(key) }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ ui.t('cancel') }}</button>
      <button mat-flat-button type="submit" form="address-form" [disabled]="busy() || labelTaken()">
        {{ ui.t('save') }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding-inline-end: 12px;
    }
    .form {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-block: 4px;
    }
    .map-wrap {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .map {
      block-size: 240px;
      border-radius: 16px;
      overflow: hidden;
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .hint {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .hint fs-icon {
      --fs-icon-size: 16px;
    }
    .labels {
      align-self: flex-start;
      --mat-button-toggle-shape: 20px;
    }
    .labels fs-icon {
      --fs-icon-size: 18px;
      vertical-align: -3px;
      margin-inline-end: 4px;
    }
    .error {
      margin: 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class AddressEditor {
  protected readonly ui = inject(Ui);
  protected readonly data = inject<AddressEditorData>(MAT_DIALOG_DATA);
  private readonly dialog = inject(MatDialogRef<AddressEditor, SavedAddress>);
  private readonly book = inject(AddressBook);

  protected readonly pin = signal<LatLng | null>(null);
  protected readonly label = signal<AddressLabel>('HOME');
  protected readonly busy = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<UiKey | null>(null);
  protected readonly labelTaken = computed(
    () =>
      this.label() !== 'OTHER' &&
      this.book.taken(this.label() as 'HOME' | 'WORK', this.data.address?.id),
  );

  protected name = '';
  protected addressLine = '';
  protected instructions = '';
  protected preferred = false;

  constructor() {
    const address = this.data.address;
    if (address) {
      this.pin.set({ lat: address.lat, lng: address.lng });
      this.label.set(address.label);
      this.name = address.name ?? '';
      this.addressLine = address.addressLine;
      this.instructions = address.instructions ?? '';
    } else {
      const start = this.data.start;
      if (start) this.pin.set({ lat: start.lat, lng: start.lng });
      if (start?.addressLine) this.addressLine = start.addressLine;
      else if (start?.label) this.addressLine = start.label;
      if (start?.instructions) this.instructions = start.instructions;
      const first = !this.book.taken('HOME') ? 'HOME' : !this.book.taken('WORK') ? 'WORK' : 'OTHER';
      this.label.set(first);
    }
  }

  protected found(location: CustomerLocation): void {
    this.pin.set({ lat: location.lat, lng: location.lng });
    if (!this.addressLine.trim() && location.label) this.addressLine = location.label;
  }

  protected async save(): Promise<void> {
    this.submitted.set(true);
    this.error.set(null);
    const pin = this.pin();
    if (!pin) {
      this.error.set('pinRequired');
      return;
    }
    if (!this.addressLine.trim() || this.labelTaken()) return;
    this.busy.set(true);
    try {
      const saved = await this.book.save(
        {
          label: this.label(),
          name: this.label() === 'OTHER' ? this.name.trim() || null : null,
          lat: pin.lat,
          lng: pin.lng,
          addressLine: this.addressLine.trim(),
          instructions: this.instructions.trim() || null,
          preferred: this.preferred,
          version: this.data.address?.version,
        },
        this.data.address?.id,
      );
      this.dialog.close(saved);
    } catch (error) {
      const code = error instanceof HttpErrorResponse ? error.error?.code : null;
      this.error.set(
        code === 'ADDRESS_LABEL_TAKEN'
          ? 'addressLabelTaken'
          : code === 'ADDRESS_LIMIT'
            ? 'addressLimit'
            : code === 'LOCATION_INVALID'
              ? 'pinRequired'
              : 'addressSaveFailed',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
