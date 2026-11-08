import { Component, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { MeClient } from '@foodstack/api-client';

import { describeError } from '../shared/errors';

const PASSWORD_MIN = 10;

/** The signed-in person changes their own password (PRD ID-1), proving they know the current one. */
@Component({
  selector: 'app-password-page',
  imports: [FormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  template: `
    <header class="fs-page-header">
      <h1>Change password</h1>
      <p>
        Choose a password only you know, and keep it in a password manager. If you were given a
        first password, change it here after you sign in.
      </p>
    </header>

    <mat-card appearance="outlined">
      <form #form="ngForm" (ngSubmit)="save(form)">
        <div class="card-section">
          <mat-form-field appearance="outline">
            <mat-label>Current password</mat-label>
            <input
              matInput
              name="current"
              type="password"
              autocomplete="current-password"
              required
              [(ngModel)]="current"
            />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>New password</mat-label>
            <input
              matInput
              name="next"
              type="password"
              autocomplete="new-password"
              required
              [minlength]="min"
              maxlength="128"
              [(ngModel)]="next"
            />
            <mat-hint>At least {{ min }} characters</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>New password again</mat-label>
            <input
              matInput
              name="again"
              type="password"
              autocomplete="new-password"
              required
              [(ngModel)]="again"
            />
          </mat-form-field>
        </div>
        @if (error()) {
          <div class="banner error" role="alert">{{ error() }}</div>
        }
        @if (done()) {
          <div class="banner ok" role="status">
            Your password is changed. Use the new one next time you sign in.
          </div>
        }
        <div class="actions">
          <button mat-flat-button type="submit" [disabled]="busy()">Change password</button>
        </div>
      </form>
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 560px;
    }
    .banner + .actions {
      margin-block-start: 16px;
    }
  `,
})
export class PasswordPage {
  private readonly me = inject(MeClient);

  protected readonly min = PASSWORD_MIN;
  protected current = '';
  protected next = '';
  protected again = '';
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly done = signal(false);

  protected async save(form: NgForm): Promise<void> {
    this.done.set(false);
    if (!this.current || !this.next) {
      this.error.set('Fill in your current password and a new one.');
      return;
    }
    if (this.next.length < PASSWORD_MIN) {
      this.error.set(`The new password needs at least ${PASSWORD_MIN} characters.`);
      return;
    }
    if (this.next !== this.again) {
      this.error.set('The two new passwords are not the same.');
      return;
    }
    if (this.next === this.current) {
      this.error.set('The new password is the same as the current one.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.me.changePassword(this.current, this.next));
      this.current = '';
      this.next = '';
      this.again = '';
      form.resetForm();
      this.done.set(true);
    } catch (error) {
      this.error.set(describeError(error, 'The password could not be changed.'));
    } finally {
      this.busy.set(false);
    }
  }
}
