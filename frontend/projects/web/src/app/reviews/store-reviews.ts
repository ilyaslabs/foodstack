import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';

import { RatingSummary, Review, ReviewClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';
import { Stars } from './stars';

/** A branch's rating and its published reviews, newest first (PRD RV-4, BB-10). */
@Component({
  selector: 'app-store-reviews',
  imports: [MatButtonModule, Stars],
  template: `
    <section class="reviews" id="reviews" aria-labelledby="reviews-title">
      <h2 id="reviews-title">{{ ui.t('reviewsTitle') }}</h2>
      @if (summary(); as s) {
        @if (s.count > 0) {
          <div class="summary">
            <div class="score">
              <strong>{{ ui.number(s.average, 1) }}</strong>
              <app-stars [value]="s.average" />
              <span>{{ countLabel(s.count) }}</span>
            </div>
            <ol class="bars" aria-hidden="true">
              @for (row of bars(); track row.star) {
                <li>
                  <span class="star">{{ ui.number(row.star) }}★</span>
                  <span class="track"
                    ><span class="fill" [style.inline-size.%]="row.percent"></span
                  ></span>
                  <span class="n">{{ ui.number(row.count) }}</span>
                </li>
              }
            </ol>
          </div>
          @if (s.brandCount > s.count) {
            <p class="brand">
              {{
                ui.t('brandRating', {
                  brand: brandName(),
                  avg: ui.number(s.brandAverage, 1),
                  n: ui.number(s.brandCount),
                })
              }}
            </p>
          }
        } @else {
          <p class="none">{{ ui.t('noReviewsYet') }}</p>
        }
      }
      @if (items().length) {
        <ul class="list">
          @for (review of items(); track review.id) {
            <li class="review">
              <div class="head">
                <span class="avatar" aria-hidden="true">{{ name(review).charAt(0) }}</span>
                <div>
                  <strong>{{ name(review) }}</strong>
                  <span class="when">{{ date(review.createdAt) }}</span>
                </div>
                <app-stars [value]="review.rating" />
              </div>
              @if (review.text) {
                <p class="text">{{ review.text }}</p>
              }
              @if (review.photoThumbUrl) {
                <a [href]="review.photoUrl ?? review.photoThumbUrl" target="_blank" rel="noopener">
                  <img [src]="review.photoThumbUrl" alt="" width="96" height="96" loading="lazy" />
                </a>
              }
              @if (review.reply; as reply) {
                <blockquote>
                  <strong>{{ ui.t('storeReply') }}</strong>
                  {{ reply.text }}
                </blockquote>
              }
            </li>
          }
        </ul>
        @if (next()) {
          <button mat-stroked-button type="button" (click)="more()" [disabled]="loading()">
            {{ ui.t('moreReviews') }}
          </button>
        }
      }
    </section>
  `,
  styles: `
    .reviews {
      display: flex;
      flex-direction: column;
      gap: 16px;
      margin-block-start: 40px;
      scroll-margin-top: 88px;
    }
    h2 {
      margin: 0;
      font: var(--mat-sys-headline-small);
    }
    .summary {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 24px;
      align-items: center;
      padding: 20px;
      border-radius: var(--fs-radius-card);
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .score {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      min-inline-size: 112px;
      --fs-stars-size: 18px;
    }
    .score strong {
      font: var(--mat-sys-display-small);
      line-height: 1;
    }
    .score span,
    .when,
    .brand,
    .none {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
    }
    .brand,
    .none {
      margin: 0;
    }
    .bars {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .bars li {
      display: grid;
      grid-template-columns: 32px minmax(0, 1fr) 40px;
      align-items: center;
      gap: 8px;
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
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
      border-radius: 4px;
    }
    .n {
      text-align: end;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
    }
    .review {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding-block: 16px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .head {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      gap: 12px;
      align-items: center;
    }
    .head div {
      display: flex;
      flex-direction: column;
    }
    .head strong {
      font: var(--mat-sys-title-small);
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 50%;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      font: var(--mat-sys-title-medium);
    }
    .text {
      margin: 0;
      font: var(--mat-sys-body-large);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    img {
      border-radius: 12px;
      object-fit: cover;
    }
    blockquote {
      margin: 0;
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-surface-container);
      font: var(--mat-sys-body-medium);
    }
    blockquote strong {
      display: block;
      font: var(--mat-sys-label-large);
      margin-block-end: 4px;
    }
    button {
      align-self: flex-start;
    }
    @media (max-width: 599.98px) {
      .summary {
        grid-template-columns: 1fr;
        gap: 16px;
      }
    }
  `,
})
export class StoreReviews {
  readonly storeId = input.required<string>();
  readonly brandName = input('');
  readonly summary = input<RatingSummary | null>(null);

  protected readonly ui = inject(Ui);
  private readonly locale = inject(LocaleStore);
  private readonly reviews = inject(ReviewClient);

  protected readonly items = signal<Review[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(false);

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

  constructor() {
    effect(() => {
      const id = this.storeId();
      untracked(() => void this.load(id, null));
    });
  }

  /** A customer who deleted their account is stored as "Deleted user"; say it in the page's language. */
  protected name(review: Review): string {
    return review.customerName === 'Deleted user' ? this.ui.t('deletedUser') : review.customerName;
  }

  protected countLabel(n: number): string {
    return n === 1
      ? this.ui.t('reviewsCountOne')
      : this.ui.t('reviewsCount', { n: this.ui.number(n) });
  }

  protected date(epochSeconds: number): string {
    try {
      return new Intl.DateTimeFormat(this.locale.language(), { dateStyle: 'medium' }).format(
        new Date(epochSeconds * 1000),
      );
    } catch {
      return '';
    }
  }

  protected more(): void {
    void this.load(this.storeId(), this.next());
  }

  private async load(storeId: string, before: string | null): Promise<void> {
    this.loading.set(true);
    try {
      const page = await firstValueFrom(this.reviews.forStore(storeId, before, 10));
      this.items.set(before ? [...this.items(), ...page.items] : page.items);
      this.next.set(page.next);
    } catch {
      // reviews are extra: the menu works without them
    } finally {
      this.loading.set(false);
    }
  }
}
