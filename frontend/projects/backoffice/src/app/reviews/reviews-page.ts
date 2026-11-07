import { Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { ModerationFilter, Review, ReviewClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { ReviewActions } from './review-actions';
import { ReviewItem } from './review-item';

/**
 * The moderation queue (PRD RV-3): reviews whose blocked words were masked wait here; moderators
 * keep them shown or hide them with a reason, and can show hidden ones again.
 */
@Component({
  selector: 'app-reviews-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
    ReviewItem,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Reviews</h1>
      <p>
        Reviews with blocked words are published with those words starred out and wait here. Keep
        them shown, or hide them with a reason the branch will see. Hidden reviews stop counting in
        the rating.
      </p>
    </header>
    <mat-button-toggle-group
      [value]="filter()"
      (change)="setFilter($event.value)"
      aria-label="Show"
      class="filters"
      hideSingleSelectionIndicator
    >
      <mat-button-toggle value="FLAGGED">To check</mat-button-toggle>
      <mat-button-toggle value="HIDDEN">Hidden</mat-button-toggle>
      <mat-button-toggle value="ALL">All</mat-button-toggle>
    </mat-button-toggle-group>
    <mat-card appearance="outlined" class="list">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @for (review of items(); track review.id) {
        <app-review-item
          [review]="review"
          [moderator]="true"
          [showStore]="true"
          [busy]="busy()"
          (hide)="act('hide', $event)"
          (show)="act('show', $event)"
          (keep)="act('keep', $event)"
        />
      } @empty {
        @if (!loading()) {
          <div class="empty">
            <fs-icon name="check_circle" />
            <p>
              {{
                filter() === 'FLAGGED'
                  ? 'Nothing to check. New flagged reviews show up here.'
                  : 'No reviews here.'
              }}
            </p>
          </div>
        }
      }
      @if (next()) {
        <div class="more">
          <button mat-stroked-button type="button" (click)="load(next())" [disabled]="loading()">
            Show more
          </button>
        </div>
      }
    </mat-card>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .filters {
      align-self: flex-start;
    }
    .list {
      overflow: hidden;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 48px 16px;
      text-align: center;
      color: var(--mat-sys-on-surface-variant);
      --fs-icon-size: 40px;
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 12px;
    }
  `,
})
export class ReviewsPage implements OnInit {
  private readonly reviews = inject(ReviewClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly actions = new ReviewActions(this.reviews, inject(MatDialog), this.snackBar);

  protected readonly filter = signal<ModerationFilter>('FLAGGED');
  protected readonly items = signal<Review[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);

  ngOnInit(): void {
    void this.load(null);
  }

  protected setFilter(filter: ModerationFilter): void {
    this.filter.set(filter);
    void this.load(null);
  }

  protected async load(before: string | null): Promise<void> {
    this.loading.set(true);
    try {
      const page = await firstValueFrom(this.reviews.moderation(this.filter(), null, before, 20));
      this.items.set(before ? [...this.items(), ...page.items] : page.items);
      this.next.set(page.next);
    } catch (error) {
      this.snackBar.open(describeError(error, 'Reviews could not be loaded.'), undefined, {
        duration: 5000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  protected async act(action: 'hide' | 'show' | 'keep', review: Review): Promise<void> {
    this.busy.set(true);
    try {
      const updated = await this.actions.run(action, review);
      if (!updated) return;
      // the queue drops what was handled; other views show the new state
      const stays =
        this.filter() === 'ALL' ||
        (this.filter() === 'FLAGGED' && updated.flagged) ||
        (this.filter() === 'HIDDEN' && updated.status === 'HIDDEN');
      this.items.set(
        stays
          ? this.items().map((r) => (r.id === updated.id ? updated : r))
          : this.items().filter((r) => r.id !== updated.id),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
