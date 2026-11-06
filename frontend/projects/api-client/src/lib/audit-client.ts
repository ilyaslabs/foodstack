import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';

export type AuditKind = 'WRITE' | 'SIGN_IN' | 'SIGN_IN_FAILED';

/** One line of the audit log (PRD AD-3, ID-8). Bodies are JSON text with secrets masked. */
export interface AuditLine {
  id: string;
  kind: AuditKind;
  /** UTC epoch seconds. */
  at: number;
  actorId: string | null;
  actorName: string | null;
  roles: string[];
  /** The API area written, such as `stores`; `auth` for sign-ins. */
  area: string;
  /** The endpoint, such as `PUT /api/v1/stores/{storeId}`, or how the user signed in. */
  action: string;
  resource: string | null;
  status: number;
  request: string | null;
  /** The state before the write: the previous write's result for the same path. */
  before: string | null;
  /** The state the write answered with. */
  after: string | null;
  ip: string | null;
  traceId: string | null;
}

export interface AuditPage {
  items: AuditLine[];
  /** Pass as `before` for the next page; null on the last page. */
  nextBefore: string | null;
}

export interface AuditQuery {
  actorId?: string;
  kind?: AuditKind;
  /** Part of the endpoint, path or user name. */
  q?: string;
  before?: string;
  limit?: number;
}

/** The audit log, for operator staff with `identity:audit:read`. */
@Injectable({ providedIn: 'root' })
export class AuditClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  search(query: AuditQuery): Observable<AuditPage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<AuditPage>(`${this.config.apiUrl}/api/v1/audit`, { params });
  }
}
