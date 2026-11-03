import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';

import { DeliveryJob, LiveRider } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { VEHICLE_LABELS } from './rider-form-dialog';

export interface AssignData {
  job: DeliveryJob;
  riders: LiveRider[];
}

export interface AssignResult {
  riderId: string | null;
  reason: string;
}

/** Support gives a delivery to a rider of their choice, nearest first, or sends it back to search. */
@Component({
  selector: 'app-assign-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    Icon,
  ],
  template: `
    <h2 mat-dialog-title>Assign order {{ data.job.orderNumber }}</h2>
    <mat-dialog-content>
      <p class="hint">Online riders nearest to {{ data.job.storeName }} first.</p>
      <mat-radio-group class="riders" [(ngModel)]="riderId" name="rider" aria-label="Rider">
        @for (r of choices(); track r.riderId) {
          <mat-radio-button [value]="r.riderId" [disabled]="r.busy">
            <span class="choice">
              <strong>{{ r.name }}</strong>
              <span>
                {{ vehicles[r.vehicle] }} · {{ r.km }} km away{{ r.busy ? ' · on a delivery' : '' }}
              </span>
            </span>
          </mat-radio-button>
        } @empty {
          <p class="none"><fs-icon name="info" /> No rider is online who can take this delivery.</p>
        }
        @if (data.job.riderId || data.job.status === 'OFFERED') {
          <mat-radio-button [value]="''">
            <span class="choice">
              <strong>Nobody now</strong>
              <span>Take it off the current rider and search again</span>
            </span>
          </mat-radio-button>
        }
      </mat-radio-group>
      <mat-form-field appearance="outline" class="reason">
        <mat-label>Reason</mat-label>
        <input matInput name="reason" required maxlength="200" [(ngModel)]="reason" />
        <mat-hint>Kept with the order's history</mat-hint>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="button"
        [disabled]="riderId() === null || !reason.trim()"
        (click)="done()"
      >
        Assign
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .hint {
      margin: 0 0 8px;
      color: var(--mat-sys-on-surface-variant);
    }
    .riders {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-block-end: 16px;
    }
    .choice {
      display: flex;
      flex-direction: column;
    }
    .choice span {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .none {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--mat-sys-on-surface-variant);
    }
    .reason {
      width: 100%;
    }
  `,
})
export class AssignDialog {
  protected readonly data = inject<AssignData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<AssignDialog, AssignResult>);

  protected readonly vehicles = VEHICLE_LABELS;
  protected readonly riderId = signal<string | null>(null);
  protected reason = '';

  protected readonly choices = computed(() => {
    const job = this.data.job;
    return this.data.riders
      .filter((r) => r.lat !== null && r.lng !== null && r.riderId !== job.riderId)
      .filter((r) => (job.mode === 'OPERATOR' ? r.kind === 'OPERATOR' : r.storeId === job.storeId))
      .map((r) => ({ ...r, meters: distance(job.pickupLat, job.pickupLng, r.lat!, r.lng!) }))
      .sort((a, b) => Number(a.busy) - Number(b.busy) || a.meters - b.meters)
      .map((r) => ({ ...r, km: (r.meters / 1000).toFixed(1) }));
  });

  protected done(): void {
    const id = this.riderId();
    if (id === null || !this.reason.trim()) return;
    this.ref.close({ riderId: id || null, reason: this.reason.trim() });
  }
}

/** Straight-line distance in metres. */
export function distance(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLng = (lng2 - lng1) * rad;
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
