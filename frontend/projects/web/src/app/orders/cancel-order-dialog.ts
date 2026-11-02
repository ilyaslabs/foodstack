import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';

import { Ui } from '../i18n';

@Component({
  selector: 'app-cancel-order-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ ui.t('cancelOrderTitle') }}</h2>
    <mat-dialog-content>
      <p>{{ ui.t('cancelOrderText') }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">{{ ui.t('keepOrder') }}</button>
      <button mat-flat-button type="button" class="danger" [mat-dialog-close]="true">
        {{ ui.t('cancelOrder') }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .danger {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
})
export class CancelOrderDialog {
  protected readonly ui = inject(Ui);
}
