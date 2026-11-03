import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { DeliveryJob, DeliveryProof, MediaClient } from '@foodstack/api-client';
import { Icon, LocaleStore, formatMoney } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';

/**
 * Handing the order over (PRD DR-6): the code the customer reads out when the order needs one, the
 * cash taken for a cash order, and an optional photo of where it was left.
 */
@Component({
  selector: 'app-delivered-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    Icon,
  ],
  template: `
    <h2 mat-dialog-title>Hand over order {{ job.orderNumber }}</h2>
    <mat-dialog-content>
      <form id="delivered" class="fields" (ngSubmit)="confirm()">
        @if (job.needsCode) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Delivery code</mat-label>
            <input
              matInput
              name="code"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="4"
              required
              [(ngModel)]="code"
              cdkFocusInitial
            />
            <mat-hint>Ask the customer for the 4-digit code on their order page</mat-hint>
          </mat-form-field>
        }
        @if (job.cashToCollectMinor > 0) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Cash collected ({{ job.currency }})</mat-label>
            <input matInput name="cash" inputmode="decimal" required [(ngModel)]="cash" />
            <mat-hint>The order total is {{ due }}</mat-hint>
          </mat-form-field>
        }
        <div class="photo">
          <input
            #file
            type="file"
            accept="image/*"
            capture="environment"
            hidden
            (change)="pick(file.files)"
          />
          <button mat-stroked-button type="button" (click)="file.click()" [disabled]="uploading()">
            <fs-icon name="photo_camera" />
            {{ photoId() ? 'Retake photo' : 'Add a photo (optional)' }}
          </button>
          @if (photoId()) {
            <span class="ok"><fs-icon name="check_circle" /> Photo added</span>
          }
        </div>
        @if (uploading()) {
          <mat-progress-bar mode="indeterminate" />
        }
        @if (error()) {
          <div class="error" role="alert">{{ error() }}</div>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="delivered" [disabled]="uploading()">
        Delivered
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .fields {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-block-start: 4px;
      min-inline-size: min(360px, 80vw);
    }
    mat-form-field {
      width: 100%;
      margin-block-end: 12px;
    }
    .photo {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
    }
    .ok {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--mat-sys-primary);
      font: var(--mat-sys-label-large);
    }
    .error {
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class DeliveredDialog {
  protected readonly job = inject<DeliveryJob>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DeliveredDialog, DeliveryProof>);
  private readonly media = inject(MediaClient);
  private readonly locale = inject(LocaleStore);

  protected code = '';
  protected cash = toMajor(this.job.cashToCollectMinor, this.job.currency);
  protected readonly due = formatMoney(
    this.job.cashToCollectMinor,
    this.job.currency,
    this.locale.language(),
  );
  protected readonly photoId = signal<string | null>(null);
  protected readonly uploading = signal(false);
  protected readonly error = signal('');

  protected async pick(files: FileList | null): Promise<void> {
    const file = files?.[0];
    if (!file) return;
    this.uploading.set(true);
    this.error.set('');
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'RIDER_DOCUMENT'));
      this.photoId.set(asset.id);
    } catch (error) {
      this.error.set(describeError(error, 'The photo could not be uploaded. Try again.'));
    } finally {
      this.uploading.set(false);
    }
  }

  protected confirm(): void {
    const code = this.code.trim();
    if (this.job.needsCode && !/^\d{4}$/.test(code)) {
      this.error.set('Enter the 4-digit code the customer has.');
      return;
    }
    let cash: number | null = null;
    if (this.job.cashToCollectMinor > 0) {
      cash = toMinor(this.cash, this.job.currency);
      if (cash === null) {
        this.error.set('Enter the cash you collected.');
        return;
      }
    }
    const photo = this.photoId();
    this.ref.close({
      code: this.job.needsCode ? code : null,
      photoUrl: photo ? `media:${photo}` : null,
      cashCollectedMinor: cash,
    });
  }
}
