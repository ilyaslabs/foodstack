import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';

import { StaffRole, UserAccount, UsersClient } from '@foodstack/api-client';

import { describeError } from '../shared/errors';

interface RoleChoice {
  role: StaffRole;
  label: string;
  hint: string;
}

/** The roles an administrator gives here, with what each one may do. */
export const STAFF_ROLE_CHOICES: RoleChoice[] = [
  {
    role: 'SUPPORT',
    label: 'Support',
    hint: 'Orders, refunds, dispatch, reviews and the email log.',
  },
  {
    role: 'FINANCE',
    label: 'Finance',
    hint: 'Payouts, settlements and how branches collect card payments.',
  },
  {
    role: 'ADMIN',
    label: 'Admin',
    hint: 'Everything, including settings and other people’s accounts.',
  },
];

const PASSWORD_MIN = 10;

/** An administrator makes a staff account (PRD ID-4). Owners and riders are added from their own pages. */
@Component({
  selector: 'app-new-user-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
  ],
  template: `
    <h2 mat-dialog-title>New staff account</h2>
    <mat-dialog-content>
      <p class="intro">
        For your own team. Restaurant owners are added on their branch page, and riders on Riders.
      </p>
      <form id="new-user-form" class="fields" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Full name</mat-label>
          <input matInput name="name" required maxlength="80" [(ngModel)]="name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Email (signs in with it)</mat-label>
          <input matInput name="email" type="email" required maxlength="254" [(ngModel)]="email" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>First password</mat-label>
          <input
            matInput
            name="password"
            type="text"
            autocomplete="new-password"
            required
            [minlength]="min"
            maxlength="128"
            [(ngModel)]="password"
          />
          <mat-hint
            >At least {{ min }} characters. Give it to them; they change it after signing
            in.</mat-hint
          >
        </mat-form-field>
        <fieldset class="roles">
          <legend>Role</legend>
          <mat-radio-group name="role" [(ngModel)]="role" aria-label="Role">
            @for (choice of choices; track choice.role) {
              <mat-radio-button [value]="choice.role">
                <span class="role-label">{{ choice.label }}</span>
                <span class="role-hint">{{ choice.hint }}</span>
              </mat-radio-button>
            }
          </mat-radio-group>
        </fieldset>
      </form>
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="new-user-form" [disabled]="saving()">
        Make account
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .intro {
      margin: 0 0 12px;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
    }
    .fields {
      display: grid;
      gap: 4px;
      padding-block-start: 4px;
    }
    mat-form-field {
      width: 100%;
    }
    .roles {
      margin: 12px 0 0;
      padding: 0;
      border: 0;
    }
    legend {
      margin-block-end: 4px;
      font: var(--mat-sys-title-small);
    }
    mat-radio-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .role-label {
      display: block;
      font: var(--mat-sys-body-large);
    }
    .role-hint {
      display: block;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
    .error {
      margin-block-start: 12px;
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class NewUserDialog {
  private readonly client = inject(UsersClient);
  private readonly ref = inject(MatDialogRef<NewUserDialog, UserAccount>);

  protected readonly choices = STAFF_ROLE_CHOICES;
  protected readonly min = PASSWORD_MIN;
  protected name = '';
  protected email = '';
  protected password = '';
  protected role: StaffRole = 'SUPPORT';
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected async submit(): Promise<void> {
    const name = this.name.trim();
    const email = this.email.trim();
    if (!name || !email) {
      this.error.set('Fill in the name and the email.');
      return;
    }
    if (this.password.length < PASSWORD_MIN) {
      this.error.set(`The first password needs at least ${PASSWORD_MIN} characters.`);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const user = await firstValueFrom(
        this.client.create({
          email,
          password: this.password,
          displayName: name,
          roles: [this.role],
        }),
      );
      this.ref.close(user);
    } catch (error) {
      this.error.set(describeError(error, 'The account could not be made.'));
    } finally {
      this.saving.set(false);
    }
  }
}
