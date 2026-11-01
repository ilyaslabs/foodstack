import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import {
  AddressSuggestion,
  Brand,
  BrandUpdate,
  BranchUpdate,
  Closure,
  Commission,
  DeliveryQuote,
  EditorItem,
  EditorMenu,
  EditorSection,
  Menu,
  MenuItemInput,
  Onboarding,
  OperatorPricing,
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
    update: { legalName: string; taxId: string | null; operatorDeliveryAllowed: boolean },
    etag: string,
  ): Observable<Versioned<StoreDetails>> {
    return this.put(`/${storeId}/admin`, update, etag);
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

  updateCommission(brandId: string, commission: Commission): Observable<Brand> {
    return this.http.put<Brand>(this.url(`/${brandId}/commission`), commission);
  }

  menu(brandId: string): Observable<EditorMenu> {
    return this.http.get<EditorMenu>(this.url(`/${brandId}/menu`));
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
    return this.http.get<StoreSearchPage>(`${this.config.apiUrl}/api/v1/search/stores`, { params });
  }
}
