import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

const CHOICES = [10, 15, 20, 30, 45, 60];

/** Accepting an order: how long the kitchen needs, which the customer then sees as the ready time. */
@Component({
  selector: 'app-accept-dialog',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Accept order {{ data.number }}</h2>
    <mat-dialog-content>
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
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">Cancel</button>
      <button mat-flat-button type="button" [mat-dialog-close]="minutes">Accept</button>
    </mat-dialog-actions>
  `,
  styles: `
    .choices {
      flex-wrap: wrap;
    }
  `,
})
export class AcceptDialog {
  protected readonly data = inject<{ number: string; prepMinutes: number }>(MAT_DIALOG_DATA);
  protected readonly choices = CHOICES.includes(this.data.prepMinutes)
    ? CHOICES
    : [...CHOICES, this.data.prepMinutes].sort((a, b) => a - b);
  protected minutes = this.data.prepMinutes || 20;
}
