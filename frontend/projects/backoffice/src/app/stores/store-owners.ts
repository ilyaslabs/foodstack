import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { BrandClient, StoreClient, StoreOwner } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import { StoreContext } from './store-context';

/**
 * Who can sign in for this branch (PRD BB-5): its managers, or the brand's owners. Operator staff
 * give both; a brand owner gives branch managers.
 */
@Component({
  selector: 'app-store-owners',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    Icon,
  ],
  template: `
    <div class="stack">
      <mat-card appearance="outlined">
        <section class="card-section">
          <h2>{{ scope() === 'brand' ? 'Brand owners' : 'Branch managers' }}</h2>
          <p class="section-hint">
            @if (scope() === 'brand') {
              They run every branch of the brand, its menu and its branch managers.
            } @else {
              They run this branch only: orders, hours, sold-out items and delivery area.
            }
          </p>
          @for (owner of owners(); track owner.userId) {
            <div class="owner">
              <span class="avatar" aria-hidden="true">{{
                (owner.displayName ?? owner.email ?? '?').charAt(0)
              }}</span>
              <div class="who">
                <strong>{{ owner.displayName ?? 'Unknown user' }}</strong>
                <span>{{ owner.email }}</span>
              </div>
              @if (canEdit()) {
                <button mat-button type="button" (click)="remove(owner)">Remove access</button>
              }
            </div>
          } @empty {
            <p class="none">
              {{ scope() === 'brand' ? 'No brand owner yet.' : 'No branch manager yet.' }}
            </p>
          }
        </section>
      </mat-card>
      @if (canEdit()) {
      <mat-card appearance="outlined">
        <form [formGroup]="form" (ngSubmit)="add()">
          <section class="card-section">
            <h2>Give access</h2>
            <p class="section-hint">
              For a new email an account is created with the starting password; share it with them
              safely. An existing account keeps its password.
            </p>
            <div class="grid-3">
              <mat-form-field appearance="outline">
                <mat-label>Email</mat-label>
                <input matInput type="email" formControlName="email" required />
                <mat-error>Enter a valid email</mat-error>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Name</mat-label>
                <input matInput formControlName="displayName" maxlength="80" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Starting password</mat-label>
                <input
                  matInput
                  formControlName="initialPassword"
                  type="password"
                  autocomplete="new-password"
                />
                <mat-hint>At least 12 characters</mat-hint>
              </mat-form-field>
            </div>
          </section>
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="saving()">
              <fs-icon name="add" /> {{ scope() === 'brand' ? 'Add brand owner' : 'Add manager' }}
            </button>
          </div>
        </form>
      </mat-card>
      }
    </div>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: block;
      padding-block: 16px;
    }
    .stack {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 880px;
    }
    .owner {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 50%;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      font: var(--mat-sys-title-small);
      text-transform: uppercase;
    }
    .who {
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .who strong {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface);
    }
    .none {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
  `,
})
export class StoreOwners {
  /** Branch managers of the open branch, or owners of its brand. */
  readonly scope = input<'branch' | 'brand'>('branch');

  private readonly context = inject(StoreContext);
  private readonly session = inject(Session);
  private readonly client = inject(StoreClient);
  private readonly brands = inject(BrandClient);

  protected readonly canEdit = computed(
    () =>
      this.session.operator() ||
      (this.scope() === 'branch' && this.session.ownsBrand(this.context.store()?.brand.id)),
  );
  private readonly snackBar = inject(MatSnackBar);

  protected readonly owners = signal<StoreOwner[]>([]);
  protected readonly saving = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
    displayName: [''],
    initialPassword: [''],
  });

  constructor() {
    effect(() => {
      const store = this.context.store();
      if (store) void this.load();
    });
  }

  private async load(): Promise<void> {
    const store = this.context.store()!;
    this.owners.set(
      await firstValueFrom(
        this.scope() === 'brand'
          ? this.brands.owners(store.brand.id)
          : this.client.owners(store.id),
      ),
    );
  }

  protected async add(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const store = this.context.store()!;
    this.saving.set(true);
    try {
      const v = this.form.getRawValue();
      const owner = { ...v, email: v.email.trim() };
      await firstValueFrom(
        this.scope() === 'brand'
          ? this.brands.addOwner(store.brand.id, owner)
          : this.client.addOwner(store.id, owner),
      );
      this.form.reset();
      await this.load();
      this.snackBar.open('Access given. They can sign in now.', undefined, { duration: 4000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'Access could not be given.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(owner: StoreOwner): Promise<void> {
    const store = this.context.store()!;
    try {
      await firstValueFrom(
        this.scope() === 'brand'
          ? this.brands.removeOwner(store.brand.id, owner.userId)
          : this.client.removeOwner(store.id, owner.userId),
      );
      await this.load();
      this.snackBar.open('Access removed.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'Access could not be removed.'), 'Dismiss', {
        duration: 8000,
      });
    }
  }
}
