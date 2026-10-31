import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';
import { SettingsClient } from './settings-client';

describe('SettingsClient', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: RUNTIME_CONFIG,
          useValue: { apiUrl: 'https://api.example', issuer: '', clientId: 'x' },
        },
      ],
    });
  });

  it('returns the branding with its ETag', async () => {
    const result = firstValueFrom(TestBed.inject(SettingsClient).getBranding());
    TestBed.inject(HttpTestingController)
      .expectOne('https://api.example/api/v1/settings/branding')
      .flush({ name: 'Spice Route' }, { headers: { ETag: '"branding-4"' } });
    expect(await result).toEqual({ value: { name: 'Spice Route' }, etag: '"branding-4"' });
  });

  it('sends updates guarded by If-Match', () => {
    TestBed.inject(SettingsClient)
      .updateBranding(
        {
          name: 'n',
          tagline: null,
          primaryColor: '#000000',
          secondaryColor: '#ffffff',
          fontFamily: 'Roboto',
          logoMediaId: null,
        },
        '"branding-4"',
      )
      .subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      'https://api.example/api/v1/settings/branding',
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"branding-4"');
  });
});
