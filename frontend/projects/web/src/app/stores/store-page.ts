import {
  Component,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Meta, Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import {
  DeliveryQuote,
  Menu,
  MenuItem,
  StoreClient,
  StorePage as Page,
  WEEKDAYS,
  Weekday,
} from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { BrandingStore, Icon, LocaleStore } from '@foodstack/ui';

import { CartPanel } from '../cart/cart-panel';

import { Ui } from '../i18n';
import { LocationStore } from '../location-store';
import { cuisineLabel } from './cuisine';
import { foodColour } from './food-colour';
import { ItemDialog, ItemDialogData } from './item-dialog';

const DIETARY: Record<string, Record<string, string>> = {
  en: {
    vegetarian: 'Vegetarian',
    vegan: 'Vegan',
    halal: 'Halal',
    'gluten-free': 'Gluten free',
    'dairy-free': 'Dairy free',
    spicy: 'Spicy',
    'contains-nuts': 'Contains nuts',
  },
  ar: {
    vegetarian: 'نباتي',
    vegan: 'نباتي صرف',
    halal: 'حلال',
    'gluten-free': 'خالٍ من الغلوتين',
    'dairy-free': 'خالٍ من الألبان',
    spicy: 'حار',
    'contains-nuts': 'يحتوي على مكسرات',
  },
  ur: {
    vegetarian: 'سبزی',
    vegan: 'ویگن',
    halal: 'حلال',
    'gluten-free': 'گلوٹین فری',
    'dairy-free': 'ڈیری فری',
    spicy: 'مصالحے دار',
    'contains-nuts': 'میوے شامل',
  },
};

/** A store's page: who they are, whether they are open, the delivery fee to you, and the menu. */
@Component({
  selector: 'app-store-page',
  imports: [RouterLink, MatButtonModule, MatProgressBarModule, Icon, CartPanel],
  templateUrl: './store-page.html',
  styleUrl: './store-page.scss',
})
export class StorePage {
  /** The branch's web address from the URL (spice-route-gulberg); an id also works. */
  readonly slug = input.required<string>();

  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  protected readonly locations = inject(LocationStore);
  private readonly stores = inject(StoreClient);
  private readonly branding = inject(BrandingStore);
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly document = inject(DOCUMENT);
  private readonly dialog = inject(MatDialog);
  private readonly auth = inject(AuthService);

  protected readonly store = signal<Page | null>(null);
  protected readonly menu = signal<Menu | null>(null);
  protected readonly missing = signal(false);
  protected readonly quote = signal<DeliveryQuote | null>(null);
  protected readonly browser = signal(false);
  protected readonly cuisineLabel = cuisineLabel;

  protected readonly fallbackColour = computed(() => foodColour(this.store()?.brandName ?? ''));

  protected readonly cover = computed(() => {
    const urls = this.store()?.coverUrls ?? {};
    return urls['hero'] ?? urls['card'] ?? Object.values(urls)[0] ?? null;
  });
  protected readonly logo = computed(() => {
    const urls = this.store()?.logoUrls ?? {};
    return urls['card'] ?? urls['thumb'] ?? Object.values(urls)[0] ?? null;
  });
  protected readonly sections = computed(() =>
    (this.menu()?.sections ?? []).filter((section) => section.items.length),
  );

  /** The week in the store's own time zone, today first, with weekday names in the customer's language. */
  protected readonly week = computed(() => {
    const store = this.store();
    if (!store) return [];
    const language = this.locale.language();
    const today = todayIn(store.timezone);
    const start = WEEKDAYS.indexOf(today);
    return WEEKDAYS.map((_, i) => WEEKDAYS[(start + i) % 7]).map((day, i) => ({
      day,
      name: i === 0 ? this.ui.t('today') : weekdayName(day, language),
      hours: (store.openingHours[day] ?? [])
        .map((range) => `${range.open}–${range.close}`)
        .join(', '),
    }));
  });

  constructor() {
    effect(() => {
      const slug = this.slug();
      untracked(() => void this.load(slug));
    });
    afterNextRender(() => {
      this.locations.restore();
      this.browser.set(true);
    });
    effect(() => {
      const store = this.store();
      const location = this.locations.location();
      if (!this.browser() || !store || !location) {
        this.quote.set(null);
        return;
      }
      untracked(() => void this.loadQuote(store.id, location.lat, location.lng));
    });
    effect(() => {
      const store = this.store();
      if (!store) return;
      const description = this.locale.pick(store.description);
      this.title.setTitle(`${store.displayName} · ${this.branding.branding().name}`);
      if (description) this.meta.updateTag({ name: 'description', content: description });
      if (store.slug) this.canonical(`/stores/${store.slug}`);
    });
  }

  private async load(slug: string): Promise<void> {
    this.store.set(null);
    this.menu.set(null);
    this.missing.set(false);
    try {
      const store = await firstValueFrom(this.stores.page(slug));
      const menu = await firstValueFrom(this.stores.menu(store.id));
      this.store.set(store);
      this.menu.set(menu);
    } catch {
      this.missing.set(true);
    }
  }

  /** One canonical address per store page, so search engines index the slug URL. */
  private canonical(path: string): void {
    const href = new URL(path, this.document.location?.href ?? 'http://localhost').href;
    let link = this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.document.head.appendChild(link);
    }
    link.setAttribute('href', href);
  }

  private async loadQuote(storeId: string, lat: number, lng: number): Promise<void> {
    try {
      this.quote.set(await firstValueFrom(this.stores.quote(storeId, lat, lng)));
    } catch {
      this.quote.set(null);
    }
  }

  protected money(minor: number): string {
    return this.locale.money(minor, this.menu()?.currency);
  }

  protected image(item: MenuItem): string | null {
    return (
      item.imageUrls['card'] ?? item.imageUrls['thumb'] ?? Object.values(item.imageUrls)[0] ?? null
    );
  }

  protected dietary(tag: string): string {
    return DIETARY[this.locale.language()]?.[tag] ?? DIETARY['en'][tag] ?? tag;
  }

  /** Lowest extra price a required choice adds, so "from" prices are honest. */
  protected fromPrice(item: MenuItem): number | null {
    let extra = 0;
    for (const group of item.modifierGroups) {
      if (group.minSelect < 1) continue;
      const deltas = group.options.map((option) => option.priceDeltaMinor).sort((a, b) => a - b);
      extra += deltas.slice(0, group.minSelect).reduce((sum, delta) => sum + delta, 0);
    }
    return item.modifierGroups.some((group) =>
      group.options.some((option) => option.priceDeltaMinor > 0),
    )
      ? item.priceMinor + extra
      : null;
  }

  /** Opens the dish to choose and add it; signing in comes first, and brings the customer back here. */
  protected open(item: MenuItem): void {
    const store = this.store();
    if (!store || item.soldOut) return;
    if (!this.auth.signedIn() && !this.auth.hasSession()) {
      void this.auth.signIn(this.auth.currentPath());
      return;
    }
    this.dialog.open<ItemDialog, ItemDialogData, boolean>(ItemDialog, {
      data: {
        storeId: store.id,
        item,
        currency: this.menu()?.currency ?? '',
        imageUrl: this.image(item),
        canOrder: store.openNow,
      },
      width: '520px',
      maxWidth: 'calc(100vw - 32px)',
      autoFocus: 'first-tabbable',
    });
  }

  protected jump(sectionId: string): void {
    this.document
      .getElementById(`section-${sectionId}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

function todayIn(timezone: string): Weekday {
  try {
    const name = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: timezone }).format(
      new Date(),
    );
    return name.toUpperCase() as Weekday;
  } catch {
    return WEEKDAYS[(new Date().getDay() + 6) % 7];
  }
}

function weekdayName(day: Weekday, language: string): string {
  // 2024-01-01 was a Monday.
  const date = new Date(Date.UTC(2024, 0, 1 + WEEKDAYS.indexOf(day), 12));
  try {
    return new Intl.DateTimeFormat(language, { weekday: 'long', timeZone: 'UTC' }).format(date);
  } catch {
    return day.charAt(0) + day.slice(1).toLowerCase();
  }
}
