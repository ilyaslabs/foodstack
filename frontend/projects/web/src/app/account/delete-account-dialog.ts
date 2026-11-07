import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { MeClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { Ui, UiKey } from '../i18n';

/**
 * Confirms an account deletion (PRD ID-7), then says when it will run. Closes with {@code true}
 * once the deletion is scheduled, so the page signs out.
 */
@Component({
  selector: 'app-delete-account-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    @if (scheduledOn(); as date) {
      <h2 mat-dialog-title>{{ ui.t('deleteScheduledTitle') }}</h2>
      <mat-dialog-content>
        <p>{{ ui.t('deleteScheduledBody', { date }) }}</p>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-flat-button type="button" [mat-dialog-close]="true">{{ ui.t('gotIt') }}</button>
      </mat-dialog-actions>
    } @else {
      <h2 mat-dialog-title>{{ ui.t('deleteConfirmTitle') }}</h2>
      <mat-dialog-content>
        <p>{{ ui.t('deleteConfirmBody', { days: ui.number(data.graceDays) }) }}</p>
        @if (error(); as key) {
          <p class="error" role="alert">{{ ui.t(key) }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="false" [disabled]="busy()">
          {{ ui.t('cancel') }}
        </button>
        <button
          mat-flat-button
          type="button"
          class="danger"
          (click)="confirm()"
          [disabled]="busy()"
        >
          {{ ui.t('deleteConfirmButton') }}
        </button>
      </mat-dialog-actions>
    }
  `,
  styles: `
    p {
      margin: 0;
    }
    .error {
      margin-block-start: 12px;
      color: var(--mat-sys-error);
    }
    .danger {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
})
export class DeleteAccountDialog {
  protected readonly ui = inject(Ui);
  protected readonly data = inject<{ graceDays: number }>(MAT_DIALOG_DATA);
  private readonly me = inject(MeClient);
  private readonly locale = inject(LocaleStore);
  private readonly ref = inject(MatDialogRef<DeleteAccountDialog, boolean>);

  protected readonly busy = signal(false);
  protected readonly error = signal<UiKey | null>(null);
  protected readonly scheduledOn = signal<string | null>(null);

  protected async confirm(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const deletion = await firstValueFrom(this.me.requestDeletion());
      this.ref.disableClose = true;
      this.scheduledOn.set(this.date(deletion.deleteAfter ?? 0));
    } catch (error) {
      const staff =
        error instanceof HttpErrorResponse && error.error?.code === 'ACCOUNT_HAS_STAFF_ROLES';
      this.error.set(staff ? 'deleteStaff' : 'deleteFailed');
    } finally {
      this.busy.set(false);
    }
  }

  private date(epochSeconds: number): string {
    try {
      return new Intl.DateTimeFormat(this.locale.language(), { dateStyle: 'long' }).format(
        new Date(epochSeconds * 1000),
      );
    } catch {
      return new Date(epochSeconds * 1000).toDateString();
    }
  }
}
