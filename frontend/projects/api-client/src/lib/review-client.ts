import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';

export type RiderTag =
  | 'FRIENDLY'
  | 'ON_TIME'
  | 'CAREFUL_WITH_FOOD'
  | 'FOLLOWED_INSTRUCTIONS'
  | 'LATE'
  | 'RUDE'
  | 'FOOD_DAMAGED'
  | 'HARD_TO_REACH';

/** A store review as the caller may see it (PRD 4.9). */
export interface Review {
  id: string;
  storeId: string;
  storeName: string;
  rating: number;
  text: string | null;
  photoThumbUrl: string | null;
  photoUrl: string | null;
  customerName: string;
  /** Epoch seconds. */
  createdAt: number;
  reply: { text: string; at: number } | null;
  status: 'PUBLISHED' | 'HIDDEN';
  /** For the branch and moderators. */
  orderNumber: string | null;
  /** Moderators: in the queue for blocked words. */
  flagged: boolean;
  /** Moderators: the text before masking. */
  originalText: string | null;
  hiddenReason: string | null;
}

export interface ReviewPage {
  items: Review[];
  next: string | null;
}

/** A branch's rating with its brand's (PRD RV-4, BB-10). */
export interface RatingSummary {
  storeId: string;
  count: number;
  average: number;
  /** Reviews with 1 to 5 stars. */
  stars: number[];
  brandId: string | null;
  brandCount: number;
  brandAverage: number;
}

/** What the customer can still rate on an order. */
export interface OrderReviewState {
  orderId: string;
  canRateStore: boolean;
  canRateRider: boolean;
  riderName: string | null;
  closesAt: number | null;
  storeReview: Review | null;
  riderRating: number | null;
}

export interface SubmitReview {
  orderId: string;
  storeRating: number | null;
  text?: string | null;
  photoMediaId?: string | null;
  riderRating?: number | null;
  riderTags?: RiderTag[];
}

export interface RiderRatingSummary {
  riderId: string;
  count: number;
  average: number;
  tags: Partial<Record<RiderTag, number>>;
}

export type ModerationFilter = 'FLAGGED' | 'HIDDEN' | 'ALL';

/** Ratings and reviews (PRD 4.9). */
@Injectable({ providedIn: 'root' })
export class ReviewClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/reviews${path}`;
  }

  forOrder(orderId: string): Observable<OrderReviewState> {
    return this.http.get<OrderReviewState>(this.url(`/orders/${encodeURIComponent(orderId)}`));
  }

  submit(review: SubmitReview): Observable<OrderReviewState> {
    return this.http.post<OrderReviewState>(this.url(), review);
  }

  forStore(storeId: string, before?: string | null, limit = 20): Observable<ReviewPage> {
    let params = new HttpParams().set('limit', limit);
    if (before) params = params.set('before', before);
    return this.http.get<ReviewPage>(this.url(`/stores/${encodeURIComponent(storeId)}`), {
      params,
    });
  }

  summary(storeId: string): Observable<RatingSummary> {
    return this.http.get<RatingSummary>(this.url(`/stores/${encodeURIComponent(storeId)}/summary`));
  }

  reply(reviewId: string, text: string): Observable<Review> {
    return this.http.post<Review>(this.url(`/${reviewId}/reply`), { text });
  }

  moderation(
    filter: ModerationFilter,
    storeId?: string | null,
    before?: string | null,
    limit = 20,
  ): Observable<ReviewPage> {
    let params = new HttpParams().set('filter', filter).set('limit', limit);
    if (storeId) params = params.set('storeId', storeId);
    if (before) params = params.set('before', before);
    return this.http.get<ReviewPage>(this.url('/moderation'), { params });
  }

  hide(reviewId: string, reason: string): Observable<Review> {
    return this.http.post<Review>(this.url(`/${reviewId}/hide`), { reason });
  }

  show(reviewId: string): Observable<Review> {
    return this.http.post<Review>(this.url(`/${reviewId}/show`), null);
  }

  keep(reviewId: string): Observable<Review> {
    return this.http.post<Review>(this.url(`/${reviewId}/keep`), null);
  }

  rider(riderId: string): Observable<RiderRatingSummary> {
    return this.http.get<RiderRatingSummary>(this.url(`/riders/${encodeURIComponent(riderId)}`));
  }
}
