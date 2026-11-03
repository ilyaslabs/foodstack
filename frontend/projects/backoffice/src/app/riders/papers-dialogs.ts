import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import {
  MediaClient,
  PayoutMethod,
  RiderClient,
  RiderDocumentType,
  RiderPapers,
  RiderPayout,
} from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { describeError } from '../shared/errors';

export const DOCUMENT_LABELS: Record<RiderDocumentType, string> = {
  NATIONAL_ID: 'National ID card',
  PASSPORT: 'Passport',
  DRIVING_LICENCE: 'Driving licence',
  VEHICLE_REGISTRATION: 'Vehicle registration',
  OTHER: 'Other document',
};

export const PAYOUT_LABELS: Record<PayoutMethod, string> = {
  BANK: 'Bank account',
  MOBILE_WALLET: 'Mobile wallet',
};

const DIALOG_STYLES = `
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
`;

interface Photo {
  id: string;
  url: string | null;
  name: string;
}

/** Adding an ID or vehicle document with photos of its front and back. */
@Component({
  selector: 'app-document-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    Icon,
  ],
  template: `
    <h2 mat-dialog-title>Add a document</h2>
    <mat-dialog-content>
      <form id="document-form" class="fields" (ngSubmit)="submit()">
        <mat-form-field appearance="outline" class="span-2">
          <mat-label>Document</mat-label>
          <mat-select name="type" [(ngModel)]="type">
            @for (t of types; track t[0]) {
              <mat-option [value]="t[0]">{{ t[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Document number</mat-label>
          <input
            matInput
            name="number"
            required
            minlength="3"
            maxlength="40"
            autocomplete="off"
            [(ngModel)]="number"
          />
          <mat-hint>Kept encrypted; staff see the last 4</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Expires on</mat-label>
          <input matInput name="expiresOn" type="date" [(ngModel)]="expiresOn" />
          <mat-hint>Leave empty if it does not expire</mat-hint>
        </mat-form-field>
        @for (side of sides; track side.key) {
          <div class="photo span-2">
            <span class="thumb" aria-hidden="true">
              @if (photos()[side.key]; as p) {
                @if (p.url) {
                  <img [src]="p.url" alt="" />
                } @else {
                  <fs-icon name="check_circle" />
                }
              } @else {
                <fs-icon name="photo_camera" />
              }
            </span>
            <span class="photo-text">
              <span class="photo-title">{{ side.label }}</span>
              <span class="photo-meta">{{ photos()[side.key]?.name ?? side.hint }}</span>
            </span>
            <button
              mat-stroked-button
              type="button"
              [disabled]="uploading()"
              (click)="picker.click()"
            >
              <fs-icon name="upload" /> {{ photos()[side.key] ? 'Replace' : 'Upload' }}
            </button>
            <input
              #picker
              type="file"
              hidden
              accept="image/png,image/jpeg,image/webp"
              (change)="upload($event, side.key)"
            />
          </div>
        }
      </form>
      @if (uploading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="submit"
        form="document-form"
        [disabled]="saving() || uploading()"
      >
        Add document
      </button>
    </mat-dialog-actions>
  `,
  styles:
    DIALOG_STYLES +
    `
    .photo {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px;
      margin-block-end: 8px;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
    }
    .thumb {
      display: grid;
      place-items: center;
      flex: none;
      inline-size: 64px;
      block-size: 44px;
      overflow: hidden;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .thumb img {
      inline-size: 100%;
      block-size: 100%;
      object-fit: cover;
    }
    .photo-text {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-inline-size: 0;
    }
    .photo-title {
      font: var(--mat-sys-title-small);
    }
    .photo-meta {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
})
export class DocumentDialog {
  private readonly riderId = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DocumentDialog, RiderPapers>);
  private readonly riders = inject(RiderClient);
  private readonly media = inject(MediaClient);

  protected readonly types = Object.entries(DOCUMENT_LABELS) as [RiderDocumentType, string][];
  protected readonly sides = [
    { key: 'front' as const, label: 'Front photo', hint: 'Required. A clear, readable photo' },
    { key: 'back' as const, label: 'Back photo', hint: 'If the document has a back side' },
  ];
  protected type: RiderDocumentType = 'NATIONAL_ID';
  protected number = '';
  protected expiresOn = '';
  protected readonly photos = signal<{ front?: Photo; back?: Photo }>({});
  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected async upload(event: Event, side: 'front' | 'back'): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.uploading.set(true);
    this.error.set('');
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'RIDER_DOCUMENT'));
      const variants = Object.values(asset.variants ?? {});
      const smallest = variants.sort((a, b) => a.width - b.width)[0];
      this.photos.update((p) => ({
        ...p,
        [side]: { id: asset.id, url: smallest?.url ?? null, name: file.name },
      }));
    } catch (error) {
      this.error.set(describeError(error, 'The photo could not be uploaded.'));
    } finally {
      this.uploading.set(false);
    }
  }

  protected async submit(): Promise<void> {
    const front = this.photos().front;
    if (this.number.trim().length < 3 || !front) {
      this.error.set('Enter the document number and upload a photo of the front.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const papers = await firstValueFrom(
        this.riders.addDocument(this.riderId, {
          type: this.type,
          number: this.number.trim(),
          frontMediaId: front.id,
          backMediaId: this.photos().back?.id ?? null,
          expiresOn: this.expiresOn || null,
        }),
      );
      this.ref.close(papers);
    } catch (error) {
      this.error.set(describeError(error, 'The document could not be added.'));
    } finally {
      this.saving.set(false);
    }
  }
}

export interface PayoutDialogData {
  riderId: string;
  payout: RiderPayout | null;
}

/** Where a rider's pay goes. The whole account number is entered again on every change. */
@Component({
  selector: 'app-payout-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title>Payout details</h2>
    <mat-dialog-content>
      <form id="payout-form" class="fields" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Paid to</mat-label>
          <mat-select name="method" [(ngModel)]="method">
            @for (m of methods; track m[0]) {
              <mat-option [value]="m[0]">{{ m[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>{{ method === 'BANK' ? 'Bank' : 'Wallet provider' }}</mat-label>
          <input matInput name="provider" required maxlength="60" [(ngModel)]="provider" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="span-2">
          <mat-label>Account holder's name</mat-label>
          <input matInput name="accountName" required maxlength="80" [(ngModel)]="accountName" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="span-2">
          <mat-label>{{
            method === 'BANK' ? 'Account number or IBAN' : 'Wallet number'
          }}</mat-label>
          <input
            matInput
            name="account"
            required
            minlength="4"
            maxlength="48"
            autocomplete="off"
            [(ngModel)]="account"
          />
          <mat-hint>
            @if (data.payout) {
              Enter the full number again. Saved one ends in {{ data.payout.accountLast4 }}
            } @else {
              Kept encrypted; staff see the last 4
            }
          </mat-hint>
        </mat-form-field>
      </form>
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="payout-form" [disabled]="saving()">Save</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class PayoutDialog {
  protected readonly data = inject<PayoutDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PayoutDialog, RiderPapers>);
  private readonly riders = inject(RiderClient);

  protected readonly methods = Object.entries(PAYOUT_LABELS) as [PayoutMethod, string][];
  protected method: PayoutMethod = this.data.payout?.method ?? 'BANK';
  protected provider = this.data.payout?.provider ?? '';
  protected accountName = this.data.payout?.accountName ?? '';
  protected account = '';
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected async submit(): Promise<void> {
    if (!this.provider.trim() || !this.accountName.trim() || this.account.trim().length < 4) {
      this.error.set('Fill in the provider, the account holder and the full account number.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const papers = await firstValueFrom(
        this.riders.setPayout(this.data.riderId, {
          method: this.method,
          provider: this.provider.trim(),
          accountName: this.accountName.trim(),
          account: this.account.trim(),
        }),
      );
      this.ref.close(papers);
    } catch (error) {
      this.error.set(describeError(error, 'The payout details were not saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
