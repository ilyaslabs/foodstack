import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { SavedAddress } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { AddressBook, addressIcon, addressTitle, locationOf } from '../addresses/address-book';
import { Ui } from '../i18n';
import { LocationStore } from '../location-store';
import { LocationPicker } from './location-picker';

/** "Where should we deliver?" from the header, on any page: saved addresses first, then search. */
@Component({
  selector: 'app-location-dialog',
  imports: [MatButtonModule, MatDialogModule, Icon, LocationPicker],
  template: `
    <div class="head">
      <h2 mat-dialog-title>{{ ui.t('whereTo') }}</h2>
      <button mat-icon-button type="button" mat-dialog-close [attr.aria-label]="ui.t('cancel')">
        <fs-icon name="close" />
      </button>
    </div>
    <mat-dialog-content>
      <app-location-picker (picked)="dialog.close(true)" />
      @if (book.addresses(); as list) {
        <div class="saved">
          <h3>{{ ui.t('savedAddresses') }}</h3>
          @for (address of list; track address.id) {
            <button
              type="button"
              class="address"
              [class.current]="locations.location()?.addressId === address.id"
              (click)="choose(address)"
            >
              <fs-icon [name]="icon(address)" class="address-icon" />
              <span class="address-text">
                <strong>{{ title(address) }}</strong>
                <span class="muted">{{ address.addressLine }}</span>
              </span>
              @if (locations.location()?.addressId === address.id) {
                <fs-icon name="check" class="tick" />
              }
            </button>
          }
          <button mat-button type="button" class="add" (click)="add()">
            <fs-icon name="add" /> {{ ui.t('addAddress') }}
          </button>
        </div>
      }
    </mat-dialog-content>
  `,
  styles: `
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding-inline-end: 12px;
    }
    mat-dialog-content {
      padding-block-end: 24px;
    }
    .saved {
      margin-block-start: 20px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    h3 {
      margin: 0 0 4px;
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .address {
      display: flex;
      align-items: center;
      gap: 14px;
      inline-size: 100%;
      padding: 12px;
      border: 0;
      border-radius: 12px;
      background: none;
      color: inherit;
      font: inherit;
      text-align: start;
      cursor: pointer;
    }
    .address:hover,
    .address:focus-visible {
      background: var(--mat-sys-surface-container);
    }
    .address.current {
      background: color-mix(in srgb, var(--mat-sys-primary) 10%, transparent);
    }
    .address-icon {
      color: var(--mat-sys-primary);
    }
    .address-text {
      display: flex;
      flex-direction: column;
      min-width: 0;
      flex: 1;
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .tick {
      color: var(--mat-sys-primary);
    }
    .add {
      align-self: flex-start;
    }
  `,
})
export class LocationDialog {
  protected readonly ui = inject(Ui);
  protected readonly dialog = inject(MatDialogRef<LocationDialog>);
  protected readonly book = inject(AddressBook);
  protected readonly locations = inject(LocationStore);
  private readonly dialogs = inject(MatDialog);

  constructor() {
    void this.book.load();
  }

  protected title(address: SavedAddress): string {
    return addressTitle(this.ui, address);
  }

  protected icon(address: SavedAddress) {
    return addressIcon(address);
  }

  protected choose(address: SavedAddress): void {
    this.locations.set(locationOf(address, `${this.title(address)} · ${address.addressLine}`));
    this.dialog.close(true);
  }

  protected async add(): Promise<void> {
    // the editor (with its map) loads only when asked for, to keep the first page light
    const { AddressEditor } = await import('../addresses/address-editor');
    this.dialogs
      .open(AddressEditor, {
        data: { start: this.locations.location() },
        width: '560px',
        maxWidth: '96vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((saved?: SavedAddress) => {
        if (saved) this.choose(saved);
      });
  }
}
