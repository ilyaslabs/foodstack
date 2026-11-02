import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Me } from './models';
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
}
