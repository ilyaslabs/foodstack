import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import {
  MediaClient,
  Order,
  OrderReviewState,
  ReviewClient,
  RiderTag,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui, UiKey } from '../i18n';
import { StarInput, Stars } from './stars';

const TAGS: RiderTag[] = [
  'FRIENDLY',
  'ON_TIME',
  'CAREFUL_WITH_FOOD',
  'FOLLOWED_INSTRUCTIONS',
  'LATE',
  'RUDE',
  'FOOD_DAMAGED',
  'HARD_TO_REACH',
];

/**
 * Rating a finished order (PRD RV-1): stars, words and a photo for the store, and stars and tags
 * for the rider, sent together. Once sent, the card shows what the customer gave.
 */
@Component({
  selector: 'app-rate-order',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    Icon,
    StarInput,
    Stars,
  ],
  template: `
    @if (state(); as s) {
      @if (s.canRateStore || s.canRateRider) {
        <section class="card rate" aria-labelledby="rate-title">
          <header>
            <span class="badge"><fs-icon name="rate_review" /></span>
            <div>
              <h2 id="rate-title">{{ ui.t('rateTitle') }}</h2>
              <p>{{ ui.t('rateIntro') }}</p>
            </div>
          </header>
          <form (ngSubmit)="send()">
            @if (s.canRateStore) {
              <div class="part">
                <h3>{{ ui.t('rateStore', { store: order().store.name }) }}</h3>
                <app-star-input
                  [(value)]="storeStars"
                  [label]="ui.t('rateStore', { store: order().store.name })"
                />
                <mat-form-field appearance="outline" class="text">
                  <textarea
                    matInput
                    name="text"
                    rows="3"
                    maxlength="1000"
                    [(ngModel)]="text"
                    [placeholder]="ui.t('reviewPlaceholder')"
                    [attr.aria-label]="ui.t('reviewPlaceholder')"
                  ></textarea>
                  <mat-hint>{{ ui.t('reviewHint') }}</mat-hint>
                  <mat-hint align="end">{{ text.length }}/1000</mat-hint>
                </mat-form-field>
                <div class="photo">
                  @if (photoUrl(); as url) {
                    <img [src]="url" alt="" width="72" height="72" />
                    <button mat-button type="button" (click)="clearPhoto()">
                      <fs-icon name="delete" /> {{ ui.t('removePhoto') }}
                    </button>
                  } @else {
                    <label class="add-photo" [class.busy]="uploading()">
                      <input
                        type="file"
                        accept="image/jpeg,image/png,image/webp"
                        (change)="upload($event)"
                        [disabled]="uploading()"
                      />
                      <fs-icon name="photo_camera" />
                      {{ uploading() ? ui.t('photoUploading') : ui.t('addPhoto') }}
                    </label>
                  }
                </div>
              </div>
            }
            @if (s.canRateRider) {
              <div class="part">
                <h3>{{ ui.t('rateRider', { name: s.riderName ?? '' }) }}</h3>
                <app-star-input
                  [(value)]="riderStars"
                  [label]="ui.t('rateRider', { name: s.riderName ?? '' })"
                />
                <div
                  class="tags"
                  role="group"
                  [attr.aria-label]="ui.t('rateRider', { name: s.riderName ?? '' })"
                >
                  @for (tag of allTags; track tag) {
                    <button
                      type="button"
                      class="tag"
                      [class.on]="tags.includes(tag)"
                      [attr.aria-pressed]="tags.includes(tag)"
                      (click)="toggle(tag)"
                    >
                      @if (tags.includes(tag)) {
                        <fs-icon name="check" />
                      }
                      {{ ui.t(tagKey(tag)) }}
                    </button>
                  }
                </div>
              </div>
            }
            @if (error(); as message) {
              <p class="error" role="alert">{{ message }}</p>
            }
            <div class="actions">
              @if (s.closesAt) {
                <span class="closes">{{ ui.t('ratingClosesOn', { date: date(s.closesAt) }) }}</span>
              }
              <button mat-flat-button type="submit" [disabled]="sending() || uploading()">
                {{ ui.t('sendRating') }}
              </button>
            </div>
          </form>
          @if (sending()) {
            <mat-progress-bar mode="indeterminate" />
          }
        </section>
      } @else if (s.storeReview || s.riderRating) {
        <section class="card rated" aria-labelledby="rated-title">
          <h2 id="rated-title">
            <fs-icon name="check_circle" /> {{ ui.t(justSent() ? 'ratingThanks' : 'yourReview') }}
          </h2>
          @if (s.storeReview; as r) {
            <div class="given">
              <app-stars [value]="r.rating" />
              @if (r.text) {
                <p>{{ r.text }}</p>
              }
              @if (r.photoThumbUrl) {
                <img [src]="r.photoThumbUrl" alt="" width="72" height="72" />
              }
              @if (r.reply) {
                <blockquote>
                  <strong>{{ ui.t('storeReply') }}</strong>
                  {{ r.reply.text }}
                </blockquote>
              }
            </div>
          }
          @if (s.riderRating && s.riderName) {
            <p class="muted">
              {{ ui.t('yourRiderRating', { name: s.riderName, n: s.riderRating }) }}
            </p>
          }
        </section>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      margin-block-end: 24px;
    }
    :host:empty {
      display: none;
    }
    .card {
      padding: 24px;
      border-radius: var(--fs-radius-card);
      background: var(--mat-sys-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      box-shadow: var(--fs-shadow-card);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    @media (max-width: 599.98px) {
      .card {
        padding: 20px 16px;
      }
    }
    header {
      display: flex;
      gap: 16px;
      align-items: flex-start;
    }
    .badge {
      display: inline-grid;
      place-items: center;
      inline-size: 48px;
      block-size: 48px;
      border-radius: 16px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      flex: none;
    }
    h2 {
      margin: 0;
      font: var(--mat-sys-title-large);
      display: flex;
      align-items: center;
      gap: 8px;
    }
    h3 {
      margin: 0;
      font: var(--mat-sys-title-medium);
    }
    header p,
    .muted,
    .closes {
      margin: 4px 0 0;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .part {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding-block-start: 16px;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
    .text {
      inline-size: 100%;
      margin-block-start: 8px;
    }
    .photo {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .photo img,
    .given img {
      border-radius: 12px;
      object-fit: cover;
    }
    .add-photo {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border-radius: 999px;
      border: 1px dashed var(--mat-sys-outline);
      color: var(--mat-sys-primary);
      font: var(--mat-sys-label-large);
      cursor: pointer;
    }
    .add-photo:focus-within {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: 2px;
    }
    .add-photo.busy {
      opacity: 0.7;
      cursor: progress;
    }
    .add-photo input {
      position: absolute;
      inline-size: 1px;
      block-size: 1px;
      opacity: 0;
    }
    .tags {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-block-start: 4px;
    }
    .tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-block-size: 36px;
      padding: 0 14px;
      border-radius: 8px;
      border: 1px solid var(--mat-sys-outline);
      background: transparent;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-label-large);
      cursor: pointer;
      --fs-icon-size: 18px;
    }
    .tag.on {
      border-color: transparent;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .tag:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: 2px;
    }
    .actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .actions button {
      margin-inline-start: auto;
    }
    .error {
      margin: 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
    .rated h2 fs-icon {
      color: var(--fs-success, #2e7d32);
    }
    .given {
      display: flex;
      flex-direction: column;
      gap: 8px;
      --fs-stars-size: 20px;
    }
    .given p {
      margin: 0;
      font: var(--mat-sys-body-large);
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
  `,
})
export class RateOrder {
  readonly order = input.required<Order>();

  protected readonly ui = inject(Ui);
  private readonly locale = inject(LocaleStore);
  private readonly reviews = inject(ReviewClient);
  private readonly media = inject(MediaClient);

  protected readonly state = signal<OrderReviewState | null>(null);
  protected readonly sending = signal(false);
  protected readonly uploading = signal(false);
  protected readonly justSent = signal(false);
  protected readonly error = signal('');
  protected readonly photoUrl = signal<string | null>(null);
  protected readonly allTags = TAGS;

  protected storeStars = 0;
  protected riderStars = 0;
  protected text = '';
  protected tags: RiderTag[] = [];
  private photoId: string | null = null;

  protected readonly orderId = computed(() => this.order().id);

  async ngOnInit(): Promise<void> {
    try {
      this.state.set(await firstValueFrom(this.reviews.forOrder(this.orderId())));
    } catch {
      // ratings are extra: the order page works without them
      this.state.set(null);
    }
  }

  protected toggle(tag: RiderTag): void {
    this.tags = this.tags.includes(tag) ? this.tags.filter((t) => t !== tag) : [...this.tags, tag];
  }

  protected tagKey(tag: RiderTag): UiKey {
    return `tag_${tag}` as UiKey;
  }

  protected date(epochSeconds: number): string {
    try {
      return new Intl.DateTimeFormat(this.locale.language(), { dateStyle: 'medium' }).format(
        new Date(epochSeconds * 1000),
      );
    } catch {
      return new Date(epochSeconds * 1000).toDateString();
    }
  }

  protected async upload(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.uploading.set(true);
    this.error.set('');
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'REVIEW'));
      this.photoId = asset.id;
      const variant = asset.variants['thumb'] ?? Object.values(asset.variants)[0];
      this.photoUrl.set(variant?.url ?? URL.createObjectURL(file));
    } catch {
      this.error.set(this.ui.t('photoFailed'));
    } finally {
      this.uploading.set(false);
    }
  }

  protected clearPhoto(): void {
    this.photoId = null;
    this.photoUrl.set(null);
  }

  protected async send(): Promise<void> {
    const s = this.state();
    if (!s) return;
    const store = s.canRateStore && this.storeStars > 0;
    const rider = s.canRateRider && this.riderStars > 0;
    if (!store && !rider) {
      this.error.set(this.ui.t('pickStars'));
      return;
    }
    this.sending.set(true);
    this.error.set('');
    try {
      const next = await firstValueFrom(
        this.reviews.submit({
          orderId: s.orderId,
          storeRating: store ? this.storeStars : null,
          text: store && this.text.trim() ? this.text.trim() : null,
          photoMediaId: store ? this.photoId : null,
          riderRating: rider ? this.riderStars : null,
          riderTags: rider ? this.tags : [],
        }),
      );
      this.justSent.set(true);
      this.state.set(next);
    } catch {
      this.error.set(this.ui.t('ratingFailed'));
    } finally {
      this.sending.set(false);
    }
  }
}
