import {
  Component,
  DestroyRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { Fulfilment, SearchClient, StoreCard as Card } from '@foodstack/api-client';
import { BrandingStore, Icon } from '@foodstack/ui';

import { Ui } from '../i18n';
import { LocationPicker } from '../location/location-picker';
import { LocationStore } from '../location-store';
import { StoreCard } from '../stores/store-card';
import { cuisineLabel } from '../stores/cuisine';

const PAGE_SIZE = 24;

/**
 * The storefront: pick a location, then browse the stores that serve it (open ones first),
 * narrowed by text in any enabled language, cuisine, and delivery or pickup.
 */
@Component({
  selector: 'app-home',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatChipsModule,
    MatProgressBarModule,
    Icon,
    LocationPicker,
    StoreCard,
  ],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly branding = inject(BrandingStore);
  protected readonly ui = inject(Ui);
  protected readonly locations = inject(LocationStore);
  private readonly search = inject(SearchClient);

  protected readonly ready = signal(false);
  protected readonly editingLocation = signal(false);
  protected readonly query = signal('');
  protected readonly cuisine = signal<string | null>(null);
  protected readonly fulfilment = signal<Fulfilment>('DELIVERY');

  protected readonly stores = signal<Card[]>([]);
  protected readonly cuisines = signal<{ cuisine: string; count: number }[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  private page = 0;
  private requestId = 0;

  protected readonly showPicker = computed(
    () => this.ready() && (!this.locations.location() || this.editingLocation()),
  );
  protected readonly cuisineLabel = cuisineLabel;
  protected readonly locationLabel = computed(
    () => this.locations.location()?.label || this.ui.t('currentLocation'),
  );
  protected readonly countLabel = computed(() =>
    this.total() === 1
      ? this.ui.t('storeFound')
      : this.ui.t('storesFound', { n: this.ui.number(this.total()) }),
  );

  private readonly typed = new Subject<string>();

  constructor() {
    // The location lives in this browser only, so results are fetched after hydration.
    afterNextRender(() => {
      this.locations.restore();
      this.ready.set(true);
    });
    this.typed
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((text) => this.query.set(text));
    effect(() => {
      const location = this.locations.location();
      const query = this.query();
      const cuisine = this.cuisine();
      const fulfilment = this.fulfilment();
      if (!this.ready() || !location) return;
      untracked(
        () =>
          void this.load(0, { lat: location.lat, lng: location.lng, query, cuisine, fulfilment }),
      );
    });
  }

  protected onType(event: Event): void {
    this.typed.next((event.target as HTMLInputElement).value.trim());
  }

  protected clearSearch(input: HTMLInputElement): void {
    input.value = '';
    this.typed.next('');
    this.query.set('');
    this.cuisine.set(null);
  }

  protected pickCuisine(cuisine: string | null): void {
    this.cuisine.set(cuisine);
  }

  protected more(): void {
    this.reload(this.page + 1);
  }

  protected retry(): void {
    this.reload(this.stores().length ? this.page + 1 : 0);
  }

  private reload(page: number): void {
    const location = this.locations.location();
    if (!location) return;
    void this.load(page, {
      lat: location.lat,
      lng: location.lng,
      query: this.query(),
      cuisine: this.cuisine(),
      fulfilment: this.fulfilment(),
    });
  }

  private async load(
    page: number,
    search: {
      lat: number;
      lng: number;
      query: string;
      cuisine: string | null;
      fulfilment: Fulfilment;
    },
  ): Promise<void> {
    const id = ++this.requestId;
    this.loading.set(true);
    this.failed.set(false);
    try {
      const result = await firstValueFrom(
        this.search.stores({
          lat: search.lat,
          lng: search.lng,
          q: search.query || null,
          cuisine: search.cuisine,
          fulfilment: search.fulfilment,
          page,
          size: PAGE_SIZE,
        }),
      );
      if (id !== this.requestId) return;
      this.page = page;
      this.stores.update((current) =>
        page === 0 ? result.stores : [...current, ...result.stores],
      );
      this.cuisines.set(result.cuisines);
      this.total.set(result.total);
    } catch {
      if (id !== this.requestId) return;
      if (page === 0) this.stores.set([]);
      this.failed.set(true);
    } finally {
      if (id === this.requestId) this.loading.set(false);
    }
  }
}
