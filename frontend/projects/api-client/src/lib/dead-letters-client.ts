import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';

export type DeadLetterStatus = 'OPEN' | 'REPLAYED' | 'DISMISSED';

/** An event a module could not handle after its retries (TDD 7.1). */
export interface DeadLetter {
  id: string;
  /** The module that published the event, such as `order`. */
  module: string;
  /** The module that failed on it, and the one a replay goes to. */
  consumer: string;
  eventType: string | null;
  eventId: string | null;
  error: string;
  /** ISO-8601. */
  failedAt: string;
  status: DeadLetterStatus;
  handledBy: string | null;
  handledAt: string | null;
}

/** Failed events, for operators with `settings:manage`. */
@Injectable({ providedIn: 'root' })
export class DeadLettersClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  list(status?: DeadLetterStatus): Observable<DeadLetter[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http.get<DeadLetter[]>(this.url(''), { params });
  }

  /** The event as it was published. */
  event(id: string): Observable<string | null> {
    return this.http
      .get<{ value: string | null }>(this.url(`/${encodeURIComponent(id)}/event`))
      .pipe(map((response) => response.value));
  }

  /** Sends the event again to the module that failed on it. */
  replay(id: string): Observable<DeadLetter> {
    return this.http.post<DeadLetter>(this.url(`/${encodeURIComponent(id)}/replay`), null);
  }

  dismiss(id: string): Observable<DeadLetter> {
    return this.http.post<DeadLetter>(this.url(`/${encodeURIComponent(id)}/dismiss`), null);
  }

  private url(path: string): string {
    return `${this.config.apiUrl}/api/v1/settings/dead-letters${path}`;
  }
}
