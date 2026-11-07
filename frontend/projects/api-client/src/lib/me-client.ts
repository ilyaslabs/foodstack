import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map } from 'rxjs';

import { AddressInput, Me, SavedAddress } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

/** Everything FoodStack keeps about the signed-in customer, as one file (PRD ID-7). */
export interface DataExport {
  exportedAt: string;
  account: unknown;
  addresses: unknown[];
  orders: unknown[];
  reviews: unknown;
  notifications: unknown;
}

/** A scheduled account deletion: when it runs, epoch seconds, and the grace period. */
export interface AccountDeletion {
  deleteAfter: number | null;
  graceDays: number;
}

@Injectable({ providedIn: 'root' })
export class MeClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  me(): Observable<Me> {
    return this.http.get<Me>(`${this.config.apiUrl}/api/v1/me`);
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.put<void>(`${this.config.apiUrl}/api/v1/me/password`, {
      currentPassword,
      newPassword,
    });
  }

  revokeTokens(): Observable<void> {
    return this.http.post<void>(`${this.config.apiUrl}/api/v1/me/revoke-tokens`, null);
  }

  /** The signed-in customer's saved addresses, preferred first. */
  addresses(): Observable<SavedAddress[]> {
    return this.http.get<SavedAddress[]>(`${this.config.apiUrl}/api/v1/me/addresses`);
  }

  addAddress(address: AddressInput): Observable<SavedAddress> {
    return this.http.post<SavedAddress>(`${this.config.apiUrl}/api/v1/me/addresses`, address);
  }

  updateAddress(id: string, address: AddressInput): Observable<SavedAddress> {
    return this.http.put<SavedAddress>(
      `${this.config.apiUrl}/api/v1/me/addresses/${encodeURIComponent(id)}`,
      address,
    );
  }

  removeAddress(id: string): Observable<void> {
    return this.http.delete<void>(
      `${this.config.apiUrl}/api/v1/me/addresses/${encodeURIComponent(id)}`,
    );
  }

  /**
   * The customer's data export. Each module serves its own part (account and addresses, orders,
   * reviews, notifications); this joins them into one document and fails if any part fails, so a
   * download is never silently incomplete.
   */
  exportData(): Observable<DataExport> {
    const api = this.config.apiUrl;
    return forkJoin({
      me: this.http.get<{ account: unknown; addresses: unknown[] }>(`${api}/api/v1/me/export`),
      orders: this.http.get<unknown[]>(`${api}/api/v1/orders/mine/export`),
      reviews: this.http.get<unknown>(`${api}/api/v1/reviews/mine/export`),
      notifications: this.http.get<unknown>(`${api}/api/v1/notifications/mine/export`),
    }).pipe(
      map(({ me, orders, reviews, notifications }) => ({
        exportedAt: new Date().toISOString(),
        account: me.account,
        addresses: me.addresses,
        orders,
        reviews,
        notifications,
      })),
    );
  }

  /** How many days after a request the account is deleted. */
  deletionPolicy(): Observable<AccountDeletion> {
    return this.http.get<AccountDeletion>(`${this.config.apiUrl}/api/v1/me/deletion`);
  }

  /** Asks to delete the account; the customer is signed out everywhere and can cancel by signing in. */
  requestDeletion(): Observable<AccountDeletion> {
    return this.http.post<AccountDeletion>(`${this.config.apiUrl}/api/v1/me/deletion`, null);
  }
}
