import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import {
  DeliveryJob,
  DeliveryProof,
  DeliverySettings,
  DeliveryStatus,
  Earnings,
  JobChange,
  LiveRider,
  RiderDocumentInput,
  RiderPapers,
  RiderPayoutInput,
  LocationPoint,
  MapBounds,
  PushConfig,
  Rider,
  RiderInput,
  RiderKind,
  RiderLiveMessage,
  RiderStatus,
  Tracking,
} from './delivery-models';
import { liveStream } from './live-stream';
import { Versioned } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

type Token = () => Promise<string | null>;

function boundsParams(view: MapBounds): Record<string, number> {
  return { south: view.south, west: view.west, north: view.north, east: view.east };
}

function boundsQuery(view: MapBounds): string {
  return new HttpParams({ fromObject: boundsParams(view) }).toString();
}

function timeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return 'UTC';
  }
}

/** Riders: the rider's own shift and earnings, the operator's fleet and a store's own riders. */
@Injectable({ providedIn: 'root' })
export class RiderClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path: string): string {
    return `${this.config.apiUrl}/api/v1${path}`;
  }

  me(): Observable<Rider> {
    return this.http.get<Rider>(this.url('/rider/me'));
  }

  setOnline(online: boolean, at: LocationPoint | null): Observable<Rider> {
    return this.http.post<Rider>(this.url('/rider/me/online'), {
      online,
      lat: at?.lat ?? null,
      lng: at?.lng ?? null,
      accuracyMeters: at?.accuracyMeters ?? null,
    });
  }

  sendLocations(points: LocationPoint[]): Observable<void> {
    return this.http.post<void>(this.url('/rider/me/locations'), { points });
  }

  myEarnings(days = 7): Observable<Earnings> {
    return this.http.get<Earnings>(this.url('/rider/me/earnings'), {
      params: { days, tz: timeZone() },
    });
  }

  list(filter: { kind?: RiderKind; status?: RiderStatus; q?: string } = {}): Observable<Rider[]> {
    let params = new HttpParams();
    if (filter.kind) params = params.set('kind', filter.kind);
    if (filter.status) params = params.set('status', filter.status);
    if (filter.q) params = params.set('q', filter.q);
    return this.http.get<Rider[]>(this.url('/riders'), { params });
  }

  add(input: RiderInput): Observable<Rider> {
    return this.http.post<Rider>(this.url('/riders'), input);
  }

  get(riderId: string): Observable<Rider> {
    return this.http.get<Rider>(this.url(`/riders/${riderId}`));
  }

  update(riderId: string, input: RiderInput): Observable<Rider> {
    return this.http.put<Rider>(this.url(`/riders/${riderId}`), input);
  }

  setStatus(riderId: string, status: RiderStatus): Observable<Rider> {
    return this.http.post<Rider>(this.url(`/riders/${riderId}/status`), { status });
  }

  earningsOf(riderId: string, days = 7): Observable<Earnings> {
    return this.http.get<Earnings>(this.url(`/riders/${riderId}/earnings`), {
      params: { days, tz: timeZone() },
    });
  }

  storeRiders(storeId: string): Observable<Rider[]> {
    return this.http.get<Rider[]>(this.url(`/riders/stores/${storeId}`));
  }

  addStoreRider(storeId: string, input: RiderInput): Observable<Rider> {
    return this.http.post<Rider>(this.url(`/riders/stores/${storeId}`), input);
  }

  removeStoreRider(storeId: string, riderId: string): Observable<void> {
    return this.http.delete<void>(this.url(`/riders/stores/${storeId}/${riderId}`));
  }

  /** An operator rider's ID documents and payout details. */
  papers(riderId: string): Observable<RiderPapers> {
    return this.http.get<RiderPapers>(this.url(`/riders/${riderId}/papers`));
  }

  /** Operator riders whose papers are complete. */
  completePapers(): Observable<string[]> {
    return this.http.get<string[]>(this.url('/riders/papers/complete'));
  }

  addDocument(riderId: string, input: RiderDocumentInput): Observable<RiderPapers> {
    return this.http.post<RiderPapers>(this.url(`/riders/${riderId}/papers/documents`), input);
  }

  verifyDocument(riderId: string, documentId: string): Observable<RiderPapers> {
    return this.http.post<RiderPapers>(
      this.url(`/riders/${riderId}/papers/documents/${documentId}/verify`),
      null,
    );
  }

  removeDocument(riderId: string, documentId: string): Observable<RiderPapers> {
    return this.http.delete<RiderPapers>(
      this.url(`/riders/${riderId}/papers/documents/${documentId}`),
    );
  }

  setPayout(riderId: string, input: RiderPayoutInput): Observable<RiderPapers> {
    return this.http.put<RiderPapers>(this.url(`/riders/${riderId}/papers/payout`), input);
  }

  /** Online riders, for the live map; with a view, only those inside it. */
  live(view?: MapBounds): Observable<LiveRider[]> {
    return this.http.get<LiveRider[]>(this.url('/riders/live'), {
      params: view ? boundsParams(view) : {},
    });
  }

  /** Riders moving inside a map view; the server refuses a view wider than 64 cells. */
  watchLive(view: MapBounds, token: Token): Observable<RiderLiveMessage> {
    return liveStream<RiderLiveMessage>(
      this.url(`/riders/live/stream?${boundsQuery(view)}`),
      token,
    );
  }

  /** Where the rider of an order is and when they should arrive. */
  tracking(orderId: string): Observable<Tracking> {
    return this.http.get<Tracking>(this.url(`/riders/tracking/${orderId}`));
  }

  watchTracking(orderId: string, token: Token): Observable<RiderLiveMessage> {
    return liveStream<RiderLiveMessage>(this.url(`/riders/tracking/${orderId}/stream`), token);
  }
}

/** Delivery jobs: the rider's offers and steps, a store's own riders and support's board. */
@Injectable({ providedIn: 'root' })
export class DispatchClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private url(path: string): string {
    return `${this.config.apiUrl}/api/v1${path}`;
  }

  myJobs(): Observable<DeliveryJob[]> {
    return this.http.get<DeliveryJob[]>(this.url('/dispatch/rider/jobs'));
  }

  accept(orderId: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/dispatch/rider/offers/${orderId}/accept`), {});
  }

  decline(orderId: string): Observable<void> {
    return this.http.post<void>(this.url(`/dispatch/rider/offers/${orderId}/decline`), {});
  }

  arrived(orderId: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/deliveries/${orderId}/arrived`), {});
  }

  pickedUp(orderId: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/deliveries/${orderId}/picked-up`), {});
  }

  delivered(orderId: string, proof: DeliveryProof): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/deliveries/${orderId}/delivered`), proof);
  }

  unassign(orderId: string, reason: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/deliveries/${orderId}/unassign`), { reason });
  }

  get(orderId: string): Observable<DeliveryJob> {
    return this.http.get<DeliveryJob>(this.url(`/deliveries/${orderId}`));
  }

  assignStoreRider(orderId: string, riderId: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/deliveries/${orderId}/store-rider`), { riderId });
  }

  open(status?: DeliveryStatus): Observable<DeliveryJob[]> {
    return this.http.get<DeliveryJob[]>(this.url('/dispatch/jobs'), {
      params: status ? { status } : {},
    });
  }

  reassign(orderId: string, riderId: string | null, reason: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/dispatch/jobs/${orderId}/reassign`), {
      riderId,
      reason,
    });
  }

  handBack(orderId: string, reason: string): Observable<DeliveryJob> {
    return this.http.post<DeliveryJob>(this.url(`/dispatch/jobs/${orderId}/hand-back`), { reason });
  }

  /** The signed-in rider's offers and jobs as they change. */
  watchMine(token: Token): Observable<JobChange> {
    return liveStream<JobChange>(this.url('/dispatch/rider/stream'), token);
  }

  /** Jobs picked up inside a map view as they change, for the dispatch board. */
  watchAll(view: MapBounds, token: Token): Observable<JobChange> {
    return liveStream<JobChange>(this.url(`/dispatch/live/stream?${boundsQuery(view)}`), token);
  }
}

/** Rider pay and dispatch rules (operator staff only). */
@Injectable({ providedIn: 'root' })
export class DeliverySettingsClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private versioned(response: HttpResponse<DeliverySettings>): Versioned<DeliverySettings> {
    return { value: response.body as DeliverySettings, etag: response.headers.get('ETag') ?? '' };
  }

  get(): Observable<Versioned<DeliverySettings>> {
    return this.http
      .get<DeliverySettings>(`${this.config.apiUrl}/api/v1/settings/delivery`, {
        observe: 'response',
        headers: { 'Cache-Control': 'no-cache' },
      })
      .pipe(map((r) => this.versioned(r)));
  }

  update(settings: DeliverySettings, etag: string): Observable<Versioned<DeliverySettings>> {
    return this.http
      .put<DeliverySettings>(`${this.config.apiUrl}/api/v1/settings/delivery`, settings, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(map((r) => this.versioned(r)));
  }
}

/** Phones and browsers that receive push messages (FCM). */
@Injectable({ providedIn: 'root' })
export class DeviceClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  pushConfig(): Observable<PushConfig> {
    return this.http.get<PushConfig>(`${this.config.apiUrl}/api/v1/devices/push-config`);
  }

  register(token: string, app: 'web' | 'backoffice'): Observable<void> {
    return this.http.post<void>(`${this.config.apiUrl}/api/v1/devices`, {
      token,
      platform: 'WEB',
      app,
    });
  }

  remove(token: string): Observable<void> {
    return this.http.post<void>(`${this.config.apiUrl}/api/v1/devices/remove`, { token });
  }
}
