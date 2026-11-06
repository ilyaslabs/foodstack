import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import {
  AddressSuggestion,
  Brand,
  BranchPrices,
  BrandUpdate,
  BranchUpdate,
  City,
  CityInput,
  Closure,
  Commission,
  DeliveryQuote,
  EditorItem,
  EditorMenu,
  EditorSection,
  Menu,
  MenuItemInput,
  NewBranch,
  Onboarding,
  OperatorPricing,
  PriceChange,
  StoreDetails,
  StoreOwner,
  StorePage,
  StoreSearch,
  StoreSearchPage,
  StoreStatus,
  StoreSummary,
  TimeRange,
  Translated,
  Weekday,
  Zone,
} from './catalogue-models';
import { Versioned } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

function versioned<T>(response: HttpResponse<T>): Versioned<T> {
  return { value: response.body as T, etag: response.headers.get('ETag') ?? '' };
}

/** Branches: onboarding and back-office edits, plus what customers read. */
@Injectable({ providedIn: 'root' })
export class StoreClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/stores${path}`;
  }

  list(query?: string): Observable<StoreSummary[]> {
    const params = query ? new HttpParams().set('q', query) : undefined;
    return this.http
      .get<{ items: StoreSummary[] }>(this.url(), { params })
      .pipe(map((response) => response.items));
  }

  onboard(onboarding: Onboarding): Observable<Versioned<StoreDetails>> {
    return this.http
      .post<StoreDetails>(this.url(), onboarding, { observe: 'response' })
      .pipe(map(versioned));
  }

  details(storeId: string): Observable<Versioned<StoreDetails>> {
    return this.http
      .get<StoreDetails>(this.url(`/${storeId}/details`), { observe: 'response' })
      .pipe(map(versioned));
  }

  updateBranch(
    storeId: string,
    update: BranchUpdate,
    etag: string,
  ): Observable<Versioned<StoreDetails>> {
    return this.put(`/${storeId}/branch`, update, etag);
  }

  updateAdmin(
    storeId: string,
    update: {
      legalName: string;
      taxId: string | null;
      operatorDeliveryAllowed: boolean;
      cityId?: string | null;
    },
    etag: string,
  ): Observable<Versioned<StoreDetails>> {
    return this.put(`/${storeId}/admin`, update, etag);
  }

  /**
   * The branch's own commission from a date (operator only); `commission` null goes back to the
   * brand's. `effectiveFrom` is epoch seconds, null for now.
   */
  updateCommission(
    storeId: string,
    commission: Commission | null,
    effectiveFrom: number | null,
  ): Observable<Versioned<StoreDetails>> {
    return this.http
      .put<StoreDetails>(
        this.url(`/${storeId}/commission`),
        { commission, effectiveFrom },
        { observe: 'response' },
      )
      .pipe(map(versioned));
  }

  /** Changes the branch's web address (operator only); a taken slug answers 409 SLUG_TAKEN. */
  updateSlug(storeId: string, slug: string, etag: string): Observable<Versioned<StoreDetails>> {
    return this.put(`/${storeId}/slug`, { slug }, etag);
  }

  updateHours(
    storeId: string,
    openingHours: Record<Weekday, TimeRange[]>,
    closures: Closure[],
    etag: string,
  ): Observable<Versioned<StoreDetails>> {
    return this.put(`/${storeId}/hours`, { openingHours, closures }, etag);
  }

  setAvailability(
    storeId: string,
    temporarilyClosed: boolean,
    reason: string | null,
  ): Observable<Versioned<StoreDetails>> {
    return this.http
      .put<StoreDetails>(
        this.url(`/${storeId}/availability`),
        { temporarilyClosed, reason },
        {
          observe: 'response',
        },
      )
      .pipe(map(versioned));
  }

  setStatus(storeId: string, status: StoreStatus): Observable<Versioned<StoreDetails>> {
    return this.http
      .put<StoreDetails>(this.url(`/${storeId}/status`), { status }, { observe: 'response' })
      .pipe(map(versioned));
  }

  readiness(storeId: string): Observable<string[]> {
    return this.http
      .get<{ missing: string[] }>(this.url(`/${storeId}/readiness`))
      .pipe(map((response) => response.missing));
  }

  owners(storeId: string): Observable<StoreOwner[]> {
    return this.http.get<StoreOwner[]>(this.url(`/${storeId}/owners`));
  }

  addOwner(
    storeId: string,
    owner: { email: string; displayName: string; initialPassword: string },
  ): Observable<StoreOwner> {
    return this.http.post<StoreOwner>(this.url(`/${storeId}/owners`), owner);
  }

  removeOwner(storeId: string, userId: string): Observable<void> {
    return this.http.delete<void>(this.url(`/${storeId}/owners/${userId}`));
  }

  setSoldOut(
    storeId: string,
    itemId: string,
    soldOut: boolean,
    optionId?: string,
  ): Observable<void> {
    return this.http.put<void>(this.url(`/${storeId}/menu/items/${itemId}/sold-out`), {
      soldOut,
      optionId: optionId ?? null,
    });
  }

  /** The branch's own prices, and its price changes waiting for approval. */
  branchPrices(storeId: string): Observable<BranchPrices> {
    return this.http.get<BranchPrices>(this.url(`/${storeId}/menu/prices`));
  }

  /**
   * Sets the branch's own price for an item, or an option's extra price; null goes back to the
   * brand price. PENDING means it waits for the operator's approval.
   */
  setBranchPrice(
    storeId: string,
    itemId: string,
    optionId: string | null,
    priceMinor: number | null,
  ): Observable<'APPLIED' | 'PENDING'> {
    return this.http
      .put<{ outcome: 'APPLIED' | 'PENDING' }>(this.url(`/${storeId}/menu/items/${itemId}/price`), {
        optionId,
        priceMinor,
      })
      .pipe(map((response) => response.outcome));
  }

  /** A live branch for customers, by its slug (spice-route-gulberg) or id. */
  page(slugOrId: string): Observable<StorePage> {
    return this.http.get<StorePage>(this.url(`/${encodeURIComponent(slugOrId)}`));
  }

  menu(storeId: string): Observable<Menu> {
    return this.http.get<Menu>(this.url(`/${storeId}/menu`));
  }

  quote(storeId: string, lat: number, lng: number): Observable<DeliveryQuote> {
    const params = new HttpParams().set('lat', lat).set('lng', lng);
    return this.http.get<DeliveryQuote>(this.url(`/${storeId}/delivery-quote`), { params });
  }

  private put<T>(path: string, body: unknown, etag: string): Observable<Versioned<T>> {
    return this.http
      .put<T>(this.url(path), body, { observe: 'response', headers: { 'If-Match': etag } })
      .pipe(map(versioned));
  }
}

/** Brands and their menu (one menu per brand, shown per branch). */
@Injectable({ providedIn: 'root' })
export class BrandClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/brands${path}`;
  }

  get(brandId: string): Observable<Versioned<Brand>> {
    return this.http
      .get<Brand>(this.url(`/${brandId}`), { observe: 'response' })
      .pipe(map(versioned));
  }

  update(brandId: string, update: BrandUpdate, etag: string): Observable<Versioned<Brand>> {
    return this.http
      .put<Brand>(this.url(`/${brandId}`), update, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(map(versioned));
  }

  /** A commission from a date (operator only); `effectiveFrom` is epoch seconds, null for now. */
  updateCommission(
    brandId: string,
    commission: Commission,
    effectiveFrom: number | null = null,
  ): Observable<Brand> {
    return this.http.put<Brand>(this.url(`/${brandId}/commission`), { commission, effectiveFrom });
  }

  /** Every branch of the brand (operator staff and the brand's owners). */
  branches(brandId: string): Observable<StoreSummary[]> {
    return this.http
      .get<{ items: StoreSummary[] }>(this.url(`/${brandId}/branches`))
      .pipe(map((response) => response.items));
  }

  /** Adds a branch in draft; it shares the brand's menu and goes live once ready. */
  addBranch(brandId: string, branch: NewBranch): Observable<Versioned<StoreDetails>> {
    return this.http
      .post<StoreDetails>(this.url(`/${brandId}/branches`), branch, { observe: 'response' })
      .pipe(map(versioned));
  }

  owners(brandId: string): Observable<StoreOwner[]> {
    return this.http.get<StoreOwner[]>(this.url(`/${brandId}/owners`));
  }

  /** Brand owners are given by operator staff. */
  addOwner(
    brandId: string,
    owner: { email: string; displayName: string; initialPassword: string },
  ): Observable<StoreOwner> {
    return this.http.post<StoreOwner>(this.url(`/${brandId}/owners`), owner);
  }

  removeOwner(brandId: string, userId: string): Observable<void> {
    return this.http.delete<void>(this.url(`/${brandId}/owners/${userId}`));
  }

  /** Whether branch managers may run promotions for their own branch (PRD BB-11). */
  setBranchPromotions(brandId: string, allowed: boolean): Observable<Brand> {
    return this.http.put<Brand>(this.url(`/${brandId}/branch-promotions`), { allowed });
  }

  /** The brand menu for editing; with `storeId`, only what that branch sells (branch managers may read it). */
  menu(brandId: string, storeId?: string): Observable<EditorMenu> {
    const params = storeId ? new HttpParams().set('storeId', storeId) : undefined;
    return this.http.get<EditorMenu>(this.url(`/${brandId}/menu`), { params });
  }

  /** Price changes of the brand and its branches waiting for approval. */
  priceChanges(brandId: string): Observable<PriceChange[]> {
    return this.http.get<PriceChange[]>(this.url(`/${brandId}/menu/price-changes`));
  }

  createSection(brandId: string, name: Translated): Observable<EditorSection> {
    return this.http.post<EditorSection>(this.url(`/${brandId}/menu/sections`), { name });
  }

  updateSection(
    brandId: string,
    sectionId: string,
    name: Translated,
    sortOrder: number,
    version: number,
  ): Observable<EditorSection> {
    return this.http.put<EditorSection>(this.url(`/${brandId}/menu/sections/${sectionId}`), {
      name,
      sortOrder,
      version,
    });
  }

  deleteSection(brandId: string, sectionId: string): Observable<void> {
    return this.http.delete<void>(this.url(`/${brandId}/menu/sections/${sectionId}`));
  }

  createItem(brandId: string, item: MenuItemInput): Observable<EditorItem> {
    return this.http.post<EditorItem>(this.url(`/${brandId}/menu/items`), item);
  }

  updateItem(brandId: string, itemId: string, item: MenuItemInput): Observable<EditorItem> {
    return this.http.put<EditorItem>(this.url(`/${brandId}/menu/items/${itemId}`), item);
  }

  deleteItem(brandId: string, itemId: string): Observable<void> {
    return this.http.delete<void>(this.url(`/${brandId}/menu/items/${itemId}`));
  }
}

/** Price changes waiting for approval (operator staff, PRD ST-6). */
@Injectable({ providedIn: 'root' })
export class PriceChangeClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/price-changes${path}`;
  }

  pending(): Observable<PriceChange[]> {
    return this.http.get<PriceChange[]>(this.url());
  }

  approve(id: string): Observable<PriceChange> {
    return this.http.post<PriceChange>(this.url(`/${encodeURIComponent(id)}/approve`), null);
  }

  reject(id: string): Observable<PriceChange> {
    return this.http.post<PriceChange>(this.url(`/${encodeURIComponent(id)}/reject`), null);
  }
}

/** The operator's cities (PRD AD-4), each with optional own delivery pricing. */
@Injectable({ providedIn: 'root' })
export class CityClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/geo/cities${path}`;
  }

  list(): Observable<City[]> {
    return this.http.get<City[]>(this.url());
  }

  create(city: CityInput): Observable<Versioned<City>> {
    return this.http.post<City>(this.url(), city, { observe: 'response' }).pipe(map(versioned));
  }

  /** Saves changes to the version read; another change in between answers 412. */
  update(cityId: string, city: CityInput, version: number): Observable<City> {
    return this.http.put<City>(this.url(`/${cityId}`), city, {
      headers: { 'If-Match': `"city-${version}"` },
    });
  }
}

/** Delivery zones, operator delivery pricing and address search. */
@Injectable({ providedIn: 'root' })
export class GeoClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  zones(storeId: string): Observable<Zone[]> {
    return this.http
      .get<{ zones: Zone[] }>(`${this.config.apiUrl}/api/v1/zones/stores/${storeId}`)
      .pipe(map((response) => response.zones));
  }

  replaceZones(storeId: string, zones: Zone[]): Observable<Zone[]> {
    return this.http
      .put<{ zones: Zone[] }>(`${this.config.apiUrl}/api/v1/zones/stores/${storeId}`, { zones })
      .pipe(map((response) => response.zones));
  }

  operatorPricing(): Observable<Versioned<OperatorPricing>> {
    return this.http
      .get<OperatorPricing>(`${this.config.apiUrl}/api/v1/geo/operator-pricing`, {
        observe: 'response',
      })
      .pipe(map(versioned));
  }

  updateOperatorPricing(
    pricing: Omit<OperatorPricing, 'version' | 'updatedAt'>,
    etag: string,
  ): Observable<Versioned<OperatorPricing>> {
    return this.http
      .put<OperatorPricing>(`${this.config.apiUrl}/api/v1/geo/operator-pricing`, pricing, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(map(versioned));
  }

  searchAddresses(query: string): Observable<AddressSuggestion[]> {
    return this.http.get<AddressSuggestion[]>(`${this.config.apiUrl}/api/v1/geo/addresses`, {
      params: new HttpParams().set('q', query),
    });
  }

  reverse(lat: number, lng: number): Observable<AddressSuggestion[]> {
    return this.http.get<AddressSuggestion[]>(
      `${this.config.apiUrl}/api/v1/geo/addresses/reverse`,
      {
        params: new HttpParams().set('lat', lat).set('lng', lng),
      },
    );
  }
}

/** Customer store search. */
@Injectable({ providedIn: 'root' })
export class SearchClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  stores(search: StoreSearch): Observable<StoreSearchPage> {
    let params = new HttpParams().set('lat', search.lat).set('lng', search.lng);
    if (search.q) params = params.set('q', search.q);
    if (search.cuisine) params = params.set('cuisine', search.cuisine);
    if (search.fulfilment) params = params.set('fulfilment', search.fulfilment);
    if (search.page) params = params.set('page', search.page);
    if (search.size) params = params.set('size', search.size);
    if (search.radius) params = params.set('radius', search.radius);
    return this.http.get<StoreSearchPage>(`${this.config.apiUrl}/api/v1/search/stores`, { params });
  }
}
