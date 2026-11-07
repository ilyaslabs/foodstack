import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { PriceChange, PriceChangeClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';

/**
 * Price changes waiting for the platform's approval (PRD ST-6, BB-4), oldest first. Shown when
 * price approval is on in Ordering settings.
 */
@Component({
  selector: 'app-price-approvals-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Price approvals</h1>
      <p>
        Price changes by store owners wait here. Customers keep the current price until you approve.
        Turn approval on or off in
        <a routerLink="/settings/ordering">Ordering</a>.
      </p>
    </header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else {
      <div class="list">
        @for (change of changes(); track change.id) {
          <mat-card appearance="outlined" class="change">
            <div class="row">
              <div class="what">
                <strong>
                  {{ locale.pick(change.itemName) }}
                  @if (change.optionName) {
                    · {{ locale.pick(change.optionName) }} (extra)
                  }
                </strong>
                <span>
                  {{ change.brandName }}
                  @if (change.storeId) {
                    · {{ change.branchName ?? 'Branch' }} only
                  } @else {
                    · every branch
                  }
                  @if (change.requestedAt) {
                    · asked {{ change.requestedAt * 1000 | date: 'medium' }}
                  }
                </span>
              </div>
              <div
                class="prices"
                [attr.aria-label]="'From ' + money(change.currentMinor) + ' to ' + proposed(change)"
              >
                <span class="old">{{ money(change.currentMinor) }}</span>
                <fs-icon name="chevron_right" />
                <span class="new">{{ proposed(change) }}</span>
              </div>
              <div class="buttons">
                <button
                  mat-button
                  type="button"
                  (click)="decide(change, false)"
                  [disabled]="busy() === change.id"
                >
                  Reject
                </button>
                <button
                  mat-flat-button
                  type="button"
                  (click)="decide(change, true)"
                  [disabled]="busy() === change.id"
                >
                  Approve
                </button>
              </div>
            </div>
          </mat-card>
        } @empty {
          <mat-card appearance="outlined" class="empty">
            <fs-icon name="check_circle" />
            <h2>Nothing to approve</h2>
            <p>When a store owner changes a price, it shows up here.</p>
          </mat-card>
        }
      </div>
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      max-width: 960px;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 16px 20px;
      flex-wrap: wrap;
    }
    .what {
      display: flex;
      flex-direction: column;
      flex: 1 1 260px;
      min-width: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .what strong {
      font: var(--mat-sys-title-medium);
      color: var(--mat-sys-on-surface);
    }
    .prices {
      display: flex;
      align-items: center;
      gap: 4px;
      font: var(--mat-sys-title-medium);
    }
    .old {
      color: var(--mat-sys-on-surface-variant);
      text-decoration: line-through;
    }
    .new {
      color: var(--mat-sys-primary);
    }
    .buttons {
      display: flex;
      gap: 8px;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 8px;
      padding: 40px 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .empty h2 {
      font: var(--mat-sys-title-large);
      color: var(--mat-sys-on-surface);
      margin: 0;
    }
    .empty p {
      margin: 0;
    }
  `,
})
export class PriceApprovalsPage implements OnInit {
  protected readonly locale = inject(LocaleStore);
  private readonly client = inject(PriceChangeClient);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly changes = signal<PriceChange[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      this.changes.set(await firstValueFrom(this.client.pending()));
    } catch (error) {
      this.snackBar.open(describeError(error, 'Price changes could not be loaded.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  protected money(minor: number): string {
    return this.locale.money(minor);
  }

  protected proposed(change: PriceChange): string {
    return change.proposedMinor === null ? 'Brand price' : this.money(change.proposedMinor);
  }

  protected async decide(change: PriceChange, approve: boolean): Promise<void> {
    this.busy.set(change.id);
    try {
      await firstValueFrom(
        approve ? this.client.approve(change.id) : this.client.reject(change.id),
      );
      this.changes.update((list) => list.filter((c) => c.id !== change.id));
      this.snackBar.open(
        approve ? 'Approved. Customers see the new price now.' : 'Rejected.',
        undefined,
        {
          duration: 3000,
        },
      );
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work. Try again.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.busy.set(null);
    }
  }
}
