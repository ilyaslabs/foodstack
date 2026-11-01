import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';

export interface ConfirmData {
  title: string;
  message: string;
  confirm: string;
  /** Destructive actions get the error colour. */
  danger?: boolean;
  /** Shows an optional text box; its text is the result. */
  inputLabel?: string;
  inputHint?: string;
  /** The confirm button stays disabled until something is typed. */
  inputRequired?: boolean;
}

/** A small confirmation dialog, optionally asking for a short note. */
@Component({
  selector: 'app-confirm-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.inputLabel) {
        <mat-form-field appearance="outline">
          <mat-label>{{ data.inputLabel }}</mat-label>
          <input
            matInput
            maxlength="200"
            [required]="!!data.inputRequired"
            [(ngModel)]="text"
            (keyup.enter)="close()"
          />
          @if (data.inputHint) {
            <mat-hint>{{ data.inputHint }}</mat-hint>
          }
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="undefined">Cancel</button>
      <button
        mat-flat-button
        type="button"
        [class.danger]="data.danger"
        [disabled]="data.inputRequired && !text.trim()"
        (click)="close()"
      >
        {{ data.confirm }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 16px;
    }
    mat-form-field {
      width: 100%;
    }
    .danger {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmDialog, string>>(MatDialogRef);
  protected text = '';

  protected close(): void {
    if (this.data.inputRequired && !this.text.trim()) {
      return;
    }
    this.ref.close(this.text.trim());
  }
}

/** Opens the dialog; resolves to the typed note ('' when none) or null when cancelled. */
export async function confirmAction(dialog: MatDialog, data: ConfirmData): Promise<string | null> {
  const result = await firstValueFrom(
    dialog.open(ConfirmDialog, { data, width: '440px', maxWidth: '92vw' }).afterClosed(),
  );
  return typeof result === 'string' ? result : null;
}
