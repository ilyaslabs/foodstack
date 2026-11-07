import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { RatingSummary, Review, ReviewClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { ReviewActions } from '../reviews/review-actions';
import { ReviewItem } from '../reviews/review-item';
import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import { StoreContext } from './store-context';

/** The branch's rating and reviews, newest first, with its one reply each (PRD RV-2, RV-4). */
@Component({
  selector: 'app-store-reviews',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
    ReviewItem,
  ],
  template: `
    @if (summary(); as s) {
      <div class="summary">
        <div class="score">
          <strong>{{ s.count ? s.average.toFixed(1) : '–' }}</strong>
          <span class="muted">{{ s.count }} {{ s.count === 1 ? 'review' : 'reviews' }}</span>
        </div>
        <ol class="bars" aria-label="Reviews per star">
          @for (row of bars(); track row.star) {
            <li>
              <span>{{ row.star }}<fs-icon name="star" /></span>
              <span class="track"
                ><span class="fill" [style.inline-size.%]="row.percent"></span
              ></span>
              <span class="n">{{ row.count }}</span>
            </li>
          }
        </ol>
        @if (s.brandCount > s.count) {
          <div class="brand">
            <span class="muted">Whole brand</span>
            <strong>{{ s.brandAverage.toFixed(1) }}</strong>
            <span class="muted">{{ s.brandCount }} reviews</span>
          </div>
        }
      </div>
    }
    <mat-card appearance="outlined" class="list">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @for (review of items(); track review.id) {
        <app-review-item
          [review]="review"
          [canReply]="canReply()"
          [moderator]="moderator()"
          [busy]="busy()"
          (reply)="reply($event.review, $event.text)"
          (hide)="act('hide', $event)"
          (show)="act('show', $event)"
          (keep)="act('keep', $event)"
        />
      } @empty {
        @if (!loading()) {
          <div class="empty">
            <fs-icon name="rate_review" />
            <p>
              No reviews yet. Customers can rate an order for 14 days after it is delivered or
              collected.
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
      padding-block: 16px;
    }
    .summary {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      gap: 24px;
      align-items: center;
      padding: 20px;
      border-radius: 16px;
      border: 1px solid var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface-container-low);
    }
    .score,
    .brand {
      display: flex;
      flex-direction: column;
      align-items: center;
      min-inline-size: 96px;
    }
    .score strong {
      font: var(--mat-sys-display-small);
    }
    .brand strong {
      font: var(--mat-sys-headline-small);
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
    .bars {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .bars li {
      display: grid;
      grid-template-columns: 36px minmax(0, 1fr) 40px;
      gap: 8px;
      align-items: center;
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .bars li > span:first-child {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      --fs-icon-size: 14px;
    }
    .bars fs-icon {
      color: #f5a623;
    }
    .track {
      block-size: 8px;
      border-radius: 4px;
      background: var(--mat-sys-surface-container-highest);
      overflow: hidden;
    }
    .fill {
      display: block;
      block-size: 100%;
      background: #f5a623;
    }
    .n {
      text-align: end;
    }
    .list {
      overflow: hidden;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 40px 16px;
      text-align: center;
      color: var(--mat-sys-on-surface-variant);
      --fs-icon-size: 40px;
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 12px;
    }
    @media (max-width: 599.98px) {
      .summary {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class StoreReviews implements OnInit {
  private readonly context = inject(StoreContext);
  private readonly reviews = inject(ReviewClient);
  private readonly session = inject(Session);
  private readonly snackBar = inject(MatSnackBar);
  private readonly actions = new ReviewActions(this.reviews, inject(MatDialog), this.snackBar);

  protected readonly summary = signal<RatingSummary | null>(null);
  protected readonly items = signal<Review[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly moderator = computed(() => this.session.can('review:moderate'));
  /** Replies come from the branch's own people. */
  protected readonly canReply = computed(() => this.session.storePortal());

  protected readonly bars = computed(() => {
    const s = this.summary();
    if (!s) return [];
    const most = Math.max(1, ...s.stars);
    return [5, 4, 3, 2, 1].map((star) => ({
      star,
      count: s.stars[star - 1] ?? 0,
      percent: ((s.stars[star - 1] ?? 0) / most) * 100,
    }));
  });

  async ngOnInit(): Promise<void> {
    const store = this.context.store();
    if (!store) return;
    void firstValueFrom(this.reviews.summary(store.id))
      .then((s) => this.summary.set(s))
      .catch(() => this.summary.set(null));
    await this.load(null);
  }

  protected async load(before: string | null): Promise<void> {
    const store = this.context.store();
    if (!store) return;
    this.loading.set(true);
    try {
      const page = await firstValueFrom(this.reviews.forStore(store.id, before, 20));
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

  protected async reply(review: Review, text: string): Promise<void> {
    this.busy.set(true);
    try {
      this.replace(await firstValueFrom(this.reviews.reply(review.id, text)));
      this.snackBar.open('Reply posted.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The reply could not be posted.'), undefined, {
        duration: 5000,
      });
    } finally {
      this.busy.set(false);
    }
  }

  protected async act(action: 'hide' | 'show' | 'keep', review: Review): Promise<void> {
    this.busy.set(true);
    try {
      const updated = await this.actions.run(action, review);
      if (updated) this.replace(updated);
    } finally {
      this.busy.set(false);
    }
  }

  private replace(review: Review): void {
    this.items.set(this.items().map((r) => (r.id === review.id ? review : r)));
  }
}
