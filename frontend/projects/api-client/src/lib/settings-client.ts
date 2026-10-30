import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { Branding, BrandingUpdate, Versioned } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

@Injectable({ providedIn: 'root' })
export class SettingsClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  getBranding(): Observable<Versioned<Branding>> {
    return this.http
      .get<Branding>(`${this.config.apiUrl}/api/v1/settings/branding`, { observe: 'response' })
      .pipe(
        map((response) => ({
          value: response.body as Branding,
          etag: response.headers.get('ETag') ?? '',
        })),
      );
  }

  updateBranding(update: BrandingUpdate, etag: string): Observable<Versioned<Branding>> {
    return this.http
      .put<Branding>(`${this.config.apiUrl}/api/v1/settings/branding`, update, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(
        map((response) => ({
          value: response.body as Branding,
          etag: response.headers.get('ETag') ?? '',
        })),
      );
  }
}
