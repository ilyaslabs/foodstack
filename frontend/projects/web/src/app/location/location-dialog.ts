import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { Icon } from '@foodstack/ui';

import { Ui } from '../i18n';
import { LocationPicker } from './location-picker';

/** "Where should we deliver?" from the header, on any page. */
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
  `,
})
export class LocationDialog {
  protected readonly ui = inject(Ui);
  protected readonly dialog = inject(MatDialogRef<LocationDialog>);
}
