import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { Branding, BrandingUpdate, LocaleSettings, Versioned } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

/** Makes the browser fetch a fresh copy instead of answering from its HTTP cache. */
const FRESH = { 'Cache-Control': 'no-cache' };

@Injectable({ providedIn: 'root' })
export class SettingsClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  /**
   * The branding and its version. The public response may be cached for a minute; editors pass
   * `fresh` so they never start from a stale copy, whose old version would make the save fail.
   */
  getBranding(fresh = false): Observable<Versioned<Branding>> {
    return this.http
      .get<Branding>(`${this.config.apiUrl}/api/v1/settings/branding`, {
        observe: 'response',
        headers: fresh ? FRESH : {},
      })
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

  /** Languages and region with their version; `fresh` skips the browser cache, as for {@link getBranding}. */
  getLocale(fresh = false): Observable<Versioned<LocaleSettings>> {
    return this.http
      .get<LocaleSettings>(`${this.config.apiUrl}/api/v1/settings/locale`, {
        observe: 'response',
        headers: fresh ? FRESH : {},
      })
      .pipe(
        map((response) => ({
          value: response.body as LocaleSettings,
          etag: response.headers.get('ETag') ?? '',
        })),
      );
  }

  updateLocale(
    update: Pick<LocaleSettings, 'defaultLanguage' | 'languages' | 'currency' | 'timezone'>,
    etag: string,
  ): Observable<Versioned<LocaleSettings>> {
    return this.http
      .put<LocaleSettings>(`${this.config.apiUrl}/api/v1/settings/locale`, update, {
        observe: 'response',
        headers: { 'If-Match': etag },
      })
      .pipe(
        map((response) => ({
          value: response.body as LocaleSettings,
          etag: response.headers.get('ETag') ?? '',
        })),
      );
  }
}
