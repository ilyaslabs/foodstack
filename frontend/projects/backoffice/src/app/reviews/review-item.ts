import { Component, computed, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { Review } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

/**
 * One review in the back office. The branch can reply once; moderators see the text before
 * masking and hide, show or keep it.
 */
@Component({
  selector: 'app-review-item',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, Icon],
  template: `
    @let r = review();
    <article class="review" [class.hidden]="r.status === 'HIDDEN'">
      <div class="head">
        <span class="stars" [attr.aria-label]="r.rating + ' out of 5 stars'">
          @for (n of five; track n) {
            <fs-icon [name]="n <= r.rating ? 'star' : 'star_border'" [class.on]="n <= r.rating" />
          }
        </span>
        <strong>{{ r.customerName }}</strong>
        <span class="muted">
          {{ r.createdAt * 1000 | date: 'medium' }}
          @if (r.orderNumber) {
            · order {{ r.orderNumber }}
          }
          @if (showStore()) {
            · {{ r.storeName }}
          }
        </span>
        <span class="chips">
          @if (r.flagged) {
            <span class="chip flag"><fs-icon name="flag" /> Blocked words</span>
          }
          @if (r.status === 'HIDDEN') {
            <span class="chip off"><fs-icon name="visibility_off" /> Hidden</span>
          }
        </span>
      </div>
      @if (r.text) {
        <p class="text">{{ r.text }}</p>
      }
      @if (r.originalText && r.originalText !== r.text) {
        <p class="original"><span>As written:</span> {{ r.originalText }}</p>
      }
      @if (r.photoThumbUrl) {
        <a [href]="r.photoUrl ?? r.photoThumbUrl" target="_blank" rel="noopener">
          <img [src]="r.photoThumbUrl" alt="Photo from the customer" width="96" height="96" />
        </a>
      }
      @if (r.status === 'HIDDEN' && r.hiddenReason) {
        <p class="reason"><fs-icon name="info" /> Hidden by the platform: {{ r.hiddenReason }}</p>
      }
      @if (r.reply; as reply) {
        <blockquote>
          <strong>Your reply</strong>
          {{ reply.text }}
        </blockquote>
      } @else if (canReply()) {
        @if (replying()) {
          <form class="reply" (ngSubmit)="sendReply()">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Public reply</mat-label>
              <textarea
                matInput
                name="reply"
                rows="2"
                maxlength="1000"
                [(ngModel)]="text"
              ></textarea>
            </mat-form-field>
            <div class="actions">
              <button mat-button type="button" (click)="replying.set(false)">Cancel</button>
              <button mat-flat-button type="submit" [disabled]="busy() || !text.trim()">
                Post reply
              </button>
            </div>
            <p class="muted">Customers see your reply under the review. You can reply once.</p>
          </form>
        } @else {
          <div class="actions start">
            <button mat-stroked-button type="button" (click)="replying.set(true)">
              <fs-icon name="reply" /> Reply
            </button>
          </div>
        }
      }
      @if (moderator()) {
        <div class="actions start">
          @if (r.status === 'PUBLISHED') {
            <button mat-stroked-button type="button" (click)="hide.emit(r)" [disabled]="busy()">
              <fs-icon name="visibility_off" /> Hide
            </button>
            @if (r.flagged) {
              <button mat-button type="button" (click)="keep.emit(r)" [disabled]="busy()">
                <fs-icon name="check" /> Keep shown
              </button>
            }
          } @else {
            <button mat-stroked-button type="button" (click)="show.emit(r)" [disabled]="busy()">
              <fs-icon name="visibility" /> Show again
            </button>
          }
        </div>
      }
    </article>
  `,
  styles: `
    .review {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 16px 20px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .review.hidden {
      background: var(--mat-sys-surface-container-low);
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
    }
    .stars {
      display: inline-flex;
      --fs-icon-size: 18px;
      color: var(--mat-sys-outline-variant);
    }
    .stars .on {
      color: #f5a623;
    }
    .head strong {
      font: var(--mat-sys-title-small);
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
      margin: 0;
    }
    .chips {
      display: inline-flex;
      gap: 6px;
      margin-inline-start: auto;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 8px;
      border-radius: 8px;
      font: var(--mat-sys-label-small);
      --fs-icon-size: 14px;
    }
    .chip.flag {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .chip.off {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .text {
      margin: 0;
      font: var(--mat-sys-body-large);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .original {
      margin: 0;
      padding: 8px 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container);
      font: var(--mat-sys-body-medium);
      overflow-wrap: anywhere;
    }
    .original span {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .reason {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
      --fs-icon-size: 18px;
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
    .reply {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .reply mat-form-field {
      inline-size: 100%;
    }
    .actions {
      display: flex;
      gap: 8px;
      justify-content: flex-end;
      flex-wrap: wrap;
    }
    .actions.start {
      justify-content: flex-start;
    }
    .actions fs-icon {
      --fs-icon-size: 18px;
    }
  `,
})
export class ReviewItem {
  readonly review = input.required<Review>();
  /** The branch may reply. */
  readonly canReply = input(false);
  readonly moderator = input(false);
  readonly busy = input(false);
  readonly showStore = input(false);
  readonly reply = output<{ review: Review; text: string }>();
  readonly hide = output<Review>();
  readonly show = output<Review>();
  readonly keep = output<Review>();

  protected readonly five = [1, 2, 3, 4, 5];
  protected readonly replying = signal(false);
  protected text = '';
  protected readonly id = computed(() => this.review().id);

  protected sendReply(): void {
    const text = this.text.trim();
    if (!text) return;
    this.reply.emit({ review: this.review(), text });
  }
}
