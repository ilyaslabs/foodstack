import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

import { Rider } from '@foodstack/api-client';

const CHOICES = [10, 15, 20, 30, 45, 60];

export interface AcceptData {
  number: string;
  prepMinutes: number;
  /** The branch's own riders, for a delivery it makes itself; none for pickup or operator delivery. */
  riders?: Rider[];
  /** Only choosing the rider, for an order accepted already. */
  riderOnly?: boolean;
}

export interface AcceptResult {
  minutes: number;
  storeRiderId: string | null;
}

/**
 * Accepting an order: how long the kitchen needs, which the customer then sees as the ready time,
 * and for a delivery the branch makes itself, which of its riders takes it (or decide later).
 */
@Component({
  selector: 'app-accept-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title>
      {{ data.riderOnly ? 'Rider for order ' + data.number : 'Accept order ' + data.number }}
    </h2>
    <mat-dialog-content>
      @if (!data.riderOnly) {
        <p>How long until it is ready?</p>
        <mat-button-toggle-group
          name="minutes"
          aria-label="Minutes to prepare"
          [(ngModel)]="minutes"
          class="choices"
        >
          @for (choice of choices; track choice) {
            <mat-button-toggle [value]="choice">{{ choice }} min</mat-button-toggle>
          }
        </mat-button-toggle-group>
      }
      @if (data.riders) {
        <mat-form-field appearance="outline" class="rider">
          <mat-label>Rider</mat-label>
          <mat-select name="rider" [(ngModel)]="riderId">
            @if (!data.riderOnly) {
              <mat-option [value]="null">Choose later</mat-option>
            }
            @for (rider of data.riders; track rider.id) {
              <mat-option [value]="rider.id" [disabled]="rider.status !== 'ACTIVE'">
                {{ rider.name }}
                @if (rider.activeOrderIds.length > 0) {
                  · on a delivery
                } @else if (rider.online) {
                  · online
                } @else {
                  · offline
                }
              </mat-option>
            }
          </mat-select>
          @if (data.riders.length === 0) {
            <mat-hint>Add your riders on the branch page, Riders tab</mat-hint>
          }
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">Cancel</button>
      <button
        mat-flat-button
        type="button"
        [disabled]="data.riderOnly && !riderId"
        [mat-dialog-close]="result()"
      >
        {{ data.riderOnly ? 'Assign' : 'Accept' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .choices {
      flex-wrap: wrap;
    }
    .rider {
      width: 100%;
      margin-block-start: 20px;
    }
  `,
})
export class AcceptDialog {
  protected readonly data = inject<AcceptData>(MAT_DIALOG_DATA);
  protected readonly choices = CHOICES.includes(this.data.prepMinutes)
    ? CHOICES
    : [...CHOICES, this.data.prepMinutes].sort((a, b) => a - b);
  protected minutes = this.data.prepMinutes || 20;
  protected riderId: string | null =
    this.data.riders?.find((r) => r.online && r.activeOrderIds.length === 0)?.id ?? null;

  protected result(): AcceptResult {
    return { minutes: this.minutes, storeRiderId: this.riderId };
  }
}
