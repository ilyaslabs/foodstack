import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { liveStream } from './live-stream';
import { Versioned } from './models';
import {
  AddCartLine,
  CardCollection,
  Cart,
  Checkout,
  CheckoutRequest,
  EmailDelivery,
  InboxItem,
  MerchantAccount,
  NotificationPreferences,
  Order,
  OrderAction,
  OrderChange,
  OrderPage,
  OrderStatus,
  OrderingSettings,
  PaymentOptions,
  PlaceOrder,
  PlacedOrder,
  Promotion,
  PromotionInput,
} from './ordering-models';
import { RUNTIME_CONFIG } from './runtime-config';

function versioned<T>(response: HttpResponse<T>): Versioned<T> {
  return { value: response.body as T, etag: response.headers.get('ETag') ?? '' };
}

/** The signed-in customer's cart and the checkout quote (PRD 4.4). */
@Injectable({ providedIn: 'root' })
export class CartClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/carts/me${path}`;
  }

  get(): Observable<Cart> {
    return this.http.get<Cart>(this.url());
  }

  add(line: AddCartLine): Observable<Cart> {
    return this.http.post<Cart>(this.url('/lines'), line);
  }

  setQuantity(lineId: string, quantity: number): Observable<Cart> {
    return this.http.patch<Cart>(this.url(`/lines/${lineId}`), { quantity });
  }

  remove(lineId: string): Observable<Cart> {
    return this.http.delete<Cart>(this.url(`/lines/${lineId}`));
  }

  clear(): Observable<void> {
    return this.http.delete<void>(this.url());
  }

  checkout(request: CheckoutRequest): Observable<Checkout> {
    return this.http.post<Checkout>(this.url('/checkout'), request);
  }
}

/** Orders for customers, store staff and support (PRD 4.5, 4.6). */
@Injectable({ providedIn: 'root' })
export class OrderClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/orders${path}`;
  }

  /** The same key for a retry of the same checkout makes a double tap place one order. */
  place(order: PlaceOrder, idempotencyKey: string): Observable<PlacedOrder> {
    return this.http.post<PlacedOrder>(this.url(), order, {
      headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
    });
  }

  get(orderId: string): Observable<Order> {
    return this.http.get<Order>(this.url(`/${orderId}`));
  }

  mine(before?: string | null, limit = 20): Observable<OrderPage> {
    let params = new HttpParams().set('limit', limit);
    if (before) params = params.set('before', before);
    return this.http.get<OrderPage>(this.url('/mine'), { params });
  }

  all(
    filter: { status?: OrderStatus | null; storeId?: string | null },
    before?: string | null,
    limit = 20,
  ): Observable<OrderPage> {
    let params = new HttpParams().set('limit', limit);
    if (filter.status) params = params.set('status', filter.status);
    if (filter.storeId) params = params.set('storeId', filter.storeId);
    if (before) params = params.set('before', before);
    return this.http.get<OrderPage>(this.url(), { params });
  }

  board(storeId: string): Observable<Order[]> {
    return this.http.get<Order[]>(this.url(`/stores/${storeId}/board`));
  }

  storeHistory(storeId: string, before?: string | null, limit = 20): Observable<OrderPage> {
    let params = new HttpParams().set('limit', limit);
    if (before) params = params.set('before', before);
    return this.http.get<OrderPage>(this.url(`/stores/${storeId}`), { params });
  }

  cancel(orderId: string, reason?: string | null): Observable<Order> {
    return this.http.post<Order>(this.url(`/${orderId}/cancel`), { reason: reason ?? null });
  }

  act(
    orderId: string,
    action: OrderAction,
    body: {
      prepMinutes?: number | null;
      reason?: string | null;
      storeRiderId?: string | null;
    } = {},
  ): Observable<Order> {
    const path = action.toLowerCase().replace('_', '-');
    return this.http.post<Order>(this.url(`/${orderId}/actions/${path}`), body);
  }

  /**
   * Gives money back to the card. `fundedBy` says who bears it once the order is done: the store
   * (the default) or the operator.
   */
  refund(
    orderId: string,
    amountMinor: number,
    reason: string,
    fundedBy: 'STORE' | 'OPERATOR' = 'STORE',
  ): Observable<Order> {
    return this.http.post<Order>(this.url(`/${orderId}/refund`), { amountMinor, reason, fundedBy });
  }

  /** Live changes of one order. */
  watch(orderId: string, token: () => Promise<string | null>): Observable<OrderChange> {
    return liveStream<OrderChange>(this.url(`/${orderId}/stream`), token);
  }

  /** Live changes of every order of a branch, for its board. */
  watchStore(storeId: string, token: () => Promise<string | null>): Observable<OrderChange> {
    return liveStream<OrderChange>(this.url(`/stores/${storeId}/stream`), token);
  }
}

@Injectable({ providedIn: 'root' })
export class PaymentClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  options(): Observable<PaymentOptions> {
    return this.http.get<PaymentOptions>(`${this.config.apiUrl}/api/v1/payments/options`);
  }

  /** Where a branch's card payments go, with its brand's and its own merchant accounts. */
  collection(storeId: string): Observable<CardCollection> {
    return this.http.get<CardCollection>(
      `${this.config.apiUrl}/api/v1/payments/merchant-accounts`,
      {
        params: { storeId },
      },
    );
  }

  /** Connects a brand's or branch's merchant account; the keys are tried by the gateway first. */
  connect(
    scope: 'brands' | 'stores',
    ownerId: string,
    secretKey: string,
    webhookSecret: string,
  ): Observable<MerchantAccount> {
    return this.http.put<MerchantAccount>(
      `${this.config.apiUrl}/api/v1/payments/merchant-accounts/${scope}/${encodeURIComponent(ownerId)}`,
      { secretKey, webhookSecret },
    );
  }

  /** Stops using the account; payments already taken keep it for refunds. */
  disconnect(scope: 'brands' | 'stores', ownerId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.config.apiUrl}/api/v1/payments/merchant-accounts/${scope}/${encodeURIComponent(ownerId)}`,
    );
  }
}

/** The bell, email choices and, for the operator, the email delivery log (PRD 4.9). */
@Injectable({ providedIn: 'root' })
export class NotificationClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/notifications${path}`;
  }

  list(before?: number | null, limit = 20): Observable<InboxItem[]> {
    let params = new HttpParams().set('limit', limit);
    if (before) params = params.set('before', before);
    return this.http.get<InboxItem[]>(this.url(), { params });
  }

  unread(): Observable<number> {
    return this.http.get<{ unread: number }>(this.url('/unread-count')).pipe(map((r) => r.unread));
  }

  markRead(id: string): Observable<void> {
    return this.http.post<void>(this.url(`/${id}/read`), null);
  }

  markAllRead(): Observable<void> {
    return this.http.post<void>(this.url('/read-all'), null);
  }

  preferences(): Observable<NotificationPreferences> {
    return this.http.get<NotificationPreferences>(this.url('/preferences'));
  }

  updatePreferences(update: Partial<NotificationPreferences>): Observable<NotificationPreferences> {
    return this.http.put<NotificationPreferences>(this.url('/preferences'), update);
  }

  deliveries(
    status?: string | null,
    before?: number | null,
    limit = 50,
  ): Observable<EmailDelivery[]> {
    let params = new HttpParams().set('limit', limit);
    if (status) params = params.set('status', status);
    if (before) params = params.set('before', before);
    return this.http.get<EmailDelivery[]>(this.url('/deliveries'), { params });
  }

  retry(id: string): Observable<void> {
    return this.http.post<void>(this.url(`/deliveries/${id}/retry`), null);
  }
}

/** Payment methods, fees, tax, tips and the accept timeout (PRD 4.5). */
@Injectable({ providedIn: 'root' })
export class OrderingSettingsClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  get(): Observable<Versioned<OrderingSettings>> {
    return this.http
      .get<OrderingSettings>(`${this.config.apiUrl}/api/v1/settings/ordering`, {
        observe: 'response',
        headers: { 'Cache-Control': 'no-cache' },
      })
      .pipe(map(versioned));
  }

  update(settings: OrderingSettings, etag: string): Observable<Versioned<OrderingSettings>> {
    return this.http
      .put<OrderingSettings>(`${this.config.apiUrl}/api/v1/settings/ordering`, settings, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(map(versioned));
  }
}

/** Coupons of the operator and of brands (PRD 4.8). */
@Injectable({ providedIn: 'root' })
export class PromotionClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path = ''): string {
    return `${this.config.apiUrl}/api/v1/promotions${path}`;
  }

  list(brandId?: string | null): Observable<Promotion[]> {
    const params = brandId ? new HttpParams().set('brandId', brandId) : undefined;
    return this.http.get<Promotion[]>(this.url(), { params });
  }

  get(id: string): Observable<Versioned<Promotion>> {
    return this.http
      .get<Promotion>(this.url(`/${id}`), { observe: 'response' })
      .pipe(map(versioned));
  }

  create(input: PromotionInput): Observable<Versioned<Promotion>> {
    return this.http
      .post<Promotion>(this.url(), input, { observe: 'response' })
      .pipe(map(versioned));
  }

  update(id: string, input: PromotionInput, etag: string): Observable<Versioned<Promotion>> {
    return this.http
      .put<Promotion>(this.url(`/${id}`), input, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(map(versioned));
  }

  setActive(id: string, active: boolean): Observable<Promotion> {
    return this.http.post<Promotion>(this.url(`/${id}/active`), { active });
  }

  /** Whether the brand lets its branch managers create promotions for their branch (PRD BB-11). */
  brandRules(brandId: string): Observable<{ branchPromotionsAllowed: boolean }> {
    return this.http.get<{ branchPromotionsAllowed: boolean }>(
      this.url(`/brands/${encodeURIComponent(brandId)}/rules`),
    );
  }
}
