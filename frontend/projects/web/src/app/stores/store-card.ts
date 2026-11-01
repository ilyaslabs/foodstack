import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { StoreCard as Card } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';
import { cuisineLabel } from './cuisine';

/** One store in search results: cover, logo, name, cuisines, open state, distance and prep time. */
@Component({
  selector: 'app-store-card',
  imports: [RouterLink, Icon],
  template: `
    @let card = store();
    <a class="card" [routerLink]="['/stores', card.storeId]" [class.closed]="!card.openNow">
      <div class="cover">
        @if (cover(); as url) {
          <img [src]="url" alt="" loading="lazy" />
        } @else {
          <div class="cover-fallback" aria-hidden="true"><fs-icon name="restaurant" /></div>
        }
        @if (!card.openNow) {
          <span class="state">{{
            card.temporarilyClosed ? ui.t('closedForNow') : ui.t('closedNow')
          }}</span>
        }
      </div>
      <div class="body">
        @if (logo(); as url) {
          <img class="logo" [src]="url" alt="" width="48" height="48" loading="lazy" />
        } @else {
          <span class="logo logo-fallback" aria-hidden="true">{{ card.brandName.charAt(0) }}</span>
        }
        <div class="text">
          <h3>{{ card.brandName }}</h3>
          <p class="cuisines">{{ cuisines() }}</p>
          <p class="meta">
            @if (card.branchName) {
              <span>{{ card.branchName }}</span>
              <span aria-hidden="true">·</span>
            }
            <span>{{ ui.distance(card.distanceMeters) }}</span>
            <span aria-hidden="true">·</span>
            <span><fs-icon name="schedule" class="tiny" /> {{ ui.minutes(card.prepMinutes) }}</span>
          </p>
        </div>
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
      block-size: 100%;
      border-radius: 20px;
      overflow: hidden;
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant);
      color: inherit;
      text-decoration: none;
      transition:
        box-shadow 150ms ease,
        transform 150ms ease;
    }
    .card:hover {
      box-shadow: var(--mat-sys-level2);
      transform: translateY(-2px);
    }
    .card:focus-visible {
      outline: 3px solid var(--mat-sys-primary);
      outline-offset: 2px;
    }
    .cover {
      position: relative;
      aspect-ratio: 16 / 7;
      background: var(--mat-sys-surface-container-high);
    }
    .cover img,
    .cover-fallback {
      inline-size: 100%;
      block-size: 100%;
      object-fit: cover;
      display: block;
    }
    .cover-fallback {
      display: grid;
      place-items: center;
      color: var(--mat-sys-on-primary-container);
      background: linear-gradient(
        135deg,
        var(--mat-sys-primary-container),
        var(--mat-sys-tertiary-container)
      );
    }
    .closed .cover img,
    .closed .cover-fallback {
      filter: grayscale(0.7);
      opacity: 0.75;
    }
    .state {
      position: absolute;
      inset-block-start: 12px;
      inset-inline-start: 12px;
      padding: 4px 12px;
      border-radius: 999px;
      font: var(--mat-sys-label-large);
      background: var(--mat-sys-inverse-surface);
      color: var(--mat-sys-inverse-on-surface);
    }
    .body {
      display: flex;
      gap: 12px;
      padding: 12px 16px 16px;
    }
    .logo {
      position: relative;
      z-index: 1;
      inline-size: 48px;
      block-size: 48px;
      border-radius: 14px;
      object-fit: cover;
      flex: none;
      margin-block-start: -28px;
      border: 3px solid var(--mat-sys-surface-container-low);
      background: var(--mat-sys-surface);
    }
    .logo-fallback {
      display: grid;
      place-items: center;
      font: var(--mat-sys-title-large);
      font-weight: 600;
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
    }
    .text {
      min-width: 0;
    }
    h3 {
      font: var(--mat-sys-title-medium);
      font-weight: 600;
      margin: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    p {
      margin: 2px 0 0;
      font: var(--mat-sys-body-medium);
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
    .meta span {
      display: inline-flex;
      align-items: center;
      gap: 2px;
    }
    .tiny {
      --fs-icon-size: 16px;
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
  protected readonly cuisines = computed(() => {
    const description = this.locale.pick(this.store().description);
    const cuisines = this.store().cuisines.map(cuisineLabel).join(' · ');
    return cuisines || description;
  });
}
