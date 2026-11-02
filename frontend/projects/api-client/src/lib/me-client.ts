import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AddressInput, Me, SavedAddress } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

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
}
