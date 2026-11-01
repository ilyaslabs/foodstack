import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Role } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

export type UserStatus = 'ACTIVE' | 'BLOCKED';

/** An account as the operator's Users page shows it. */
export interface UserAccount {
  id: string;
  email: string;
  displayName: string;
  roles: Role[];
  permissions: string[];
  status: UserStatus;
  /** UTC epoch seconds. */
  createdAt: number;
  brandIds?: string[];
  storeIds?: string[];
}

export interface UserPage {
  items: UserAccount[];
  /** Pass as `after` for the next page; null on the last page. */
  nextCursor: string | null;
}

export interface UserQuery {
  /** A full email, or part of a name. */
  q?: string;
  role?: Role;
  status?: UserStatus;
  after?: string;
  limit?: number;
}

/** Every account on the platform, for staff with `identity:users:read` (PRD ID-4). */
@Injectable({ providedIn: 'root' })
export class UsersClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  search(query: UserQuery): Observable<UserPage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<UserPage>(`${this.config.apiUrl}/api/v1/users`, { params });
  }

  /** Blocks sign-in and ends every session of the account at once. */
  block(id: string, reason: string): Observable<UserAccount> {
    return this.http.post<UserAccount>(
      `${this.config.apiUrl}/api/v1/users/${encodeURIComponent(id)}/block`,
      { reason },
    );
  }

  unblock(id: string): Observable<UserAccount> {
    return this.http.post<UserAccount>(
      `${this.config.apiUrl}/api/v1/users/${encodeURIComponent(id)}/unblock`,
      null,
    );
  }
}
