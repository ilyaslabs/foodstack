import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { StoreCard as Card } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';
import { cuisineLabel } from './cuisine';
import { foodGradient } from './food-colour';

/** One store in search results: cover, logo, name, cuisines, open state, distance and prep time. */
@Component({
  selector: 'app-store-card',
  imports: [RouterLink, Icon],
  template: `
    @let card = store();
    <a
      class="card"
      [routerLink]="['/stores', card.slug ?? card.storeId]"
      [class.closed]="!card.openNow"
    >
      <div class="cover">
        @if (cover(); as url) {
          <img [src]="url" alt="" loading="lazy" />
        } @else {
          <div class="cover-fallback" aria-hidden="true" [style.background]="fallback()">
            <span class="cover-word">{{ card.brandName }}</span>
          </div>
        }
        @if (!card.openNow) {
          <div class="shade">
            <span class="state">{{
              card.temporarilyClosed ? ui.t('closedForNow') : ui.t('closedNow')
            }}</span>
          </div>
        }
        <span class="time"><fs-icon name="schedule" /> {{ ui.minutes(card.prepMinutes) }}</span>
        @if (logo(); as url) {
          <img class="logo" [src]="url" alt="" width="44" height="44" loading="lazy" />
        }
      </div>
      <div class="text">
        <div class="title">
          <h3>{{ card.brandName }}</h3>
          @if (card.ratingCount) {
            <span
              class="rating"
              [attr.aria-label]="ui.t('starsLabel', { n: ui.number(card.ratingAverage ?? 0, 1) })"
              ><fs-icon name="star" /> {{ ui.number(card.ratingAverage ?? 0, 1) }}
              <span class="count">({{ ui.number(card.ratingCount) }})</span></span
            >
          }
        </div>
        <p class="cuisines">{{ cuisines() }}</p>
        <p class="meta">
          <span>{{ ui.distance(card.distanceMeters) }}</span>
          @if (card.branchName) {
            <span aria-hidden="true">·</span>
            <span class="branch">{{ card.branchName }}</span>
          }
          @if (card.pickupEnabled) {
            <span aria-hidden="true">·</span>
            <span>{{ ui.t('pickup') }}</span>
          }
        </p>
      </div>
    </a>
  `,
  styles: `
    :host {
      display: block;
    }
    .card {
      display: flex;
      flex-direction: column;
      gap: 12px;
      block-size: 100%;
      color: inherit;
      text-decoration: none;
      border-radius: var(--fs-radius-card);
    }
    .card:focus-visible {
      outline: 3px solid var(--mat-sys-primary);
      outline-offset: 4px;
    }
    .cover {
      position: relative;
      aspect-ratio: 16 / 9;
      border-radius: var(--fs-radius-card);
      overflow: hidden;
      background: var(--mat-sys-surface-container-high);
    }
    .cover img:first-child,
    .cover-fallback {
      inline-size: 100%;
      block-size: 100%;
      object-fit: cover;
      display: block;
      transition: transform 250ms ease;
    }
    .card:hover .cover img:first-child,
    .card:hover .cover-fallback {
      transform: scale(1.04);
    }
    .cover-fallback {
      display: flex;
      align-items: flex-end;
      padding: 16px 18px;
      box-sizing: border-box;
    }
    .cover-word {
      color: rgb(255 255 255 / 92%);
      font-size: 30px;
      line-height: 1.05;
      font-weight: 900;
      letter-spacing: -0.03em;
      text-shadow: 0 2px 12px rgb(0 0 0 / 15%);
      overflow: hidden;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
    .shade {
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      background: rgb(0 0 0 / 50%);
    }
    .state {
      padding: 6px 14px;
      border-radius: 999px;
      background: rgb(255 255 255 / 95%);
      color: #1a1a1a;
      font-size: 14px;
      font-weight: 700;
    }
    .time {
      position: absolute;
      inset-block-end: 10px;
      inset-inline-end: 10px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 10px;
      border-radius: 999px;
      background: var(--mat-sys-surface);
      color: var(--mat-sys-on-surface);
      font-size: 13px;
      font-weight: 700;
      box-shadow: var(--fs-shadow-card);
    }
    .title {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px;
    }
    .title h3 {
      min-inline-size: 0;
    }
    .rating {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      flex: none;
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface);
    }
    .rating fs-icon {
      --fs-icon-size: 16px;
      color: #f5a623;
      align-self: center;
    }
    .rating .count {
      color: var(--mat-sys-on-surface-variant);
      font-weight: 400;
    }
    .time fs-icon {
      --fs-icon-size: 15px;
    }
    .logo {
      position: absolute;
      inset-block-start: 10px;
      inset-inline-start: 10px;
      inline-size: 44px;
      block-size: 44px;
      border-radius: 12px;
      object-fit: cover;
      background: var(--mat-sys-surface);
      box-shadow: var(--fs-shadow-card);
    }
    .text {
      min-width: 0;
      padding-inline: 2px;
    }
    h3 {
      font-size: 17px;
      line-height: 22px;
      font-weight: 800;
      letter-spacing: -0.01em;
      margin: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    p {
      margin: 3px 0 0;
      font-size: 14px;
      line-height: 20px;
      color: var(--mat-sys-on-surface-variant);
    }
    .cuisines {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
    }
  `,
})
export class StoreCard {
  protected readonly ui = inject(Ui);
  private readonly locale = inject(LocaleStore);

  readonly store = input.required<Card>();

  protected readonly cover = computed(() => {
    const urls = this.store().coverUrls;
    return urls['card'] ?? urls['hero'] ?? Object.values(urls)[0] ?? null;
  });
  protected readonly logo = computed(() => {
    const urls = this.store().logoUrls;
    return urls['thumb'] ?? urls['card'] ?? Object.values(urls)[0] ?? null;
  });
  protected readonly fallback = computed(() => foodGradient(this.store().brandName));
  protected readonly cuisines = computed(() => {
    const description = this.locale.pick(this.store().description);
    const cuisines = this.store().cuisines.map(cuisineLabel).join(' · ');
    return cuisines || description;
  });
}
