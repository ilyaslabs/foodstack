import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';

import { BrandClient, GeoClient, SearchClient, StoreClient } from './catalogue-clients';
import { RUNTIME_CONFIG } from './runtime-config';

describe('catalogue clients', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: RUNTIME_CONFIG,
          useValue: { apiUrl: 'https://api.example' },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('searches stores with only the filters that are set', () => {
    // when
    TestBed.inject(SearchClient)
      .stores({ lat: 31.5, lng: 74.3, q: 'برياني', fulfilment: 'PICKUP', cuisine: null })
      .subscribe();

    // then
    const request = http.expectOne((r) => r.url === 'https://api.example/api/v1/search/stores');
    expect(request.request.params.get('lat')).toBe('31.5');
    expect(request.request.params.get('q')).toBe('برياني');
    expect(request.request.params.get('fulfilment')).toBe('PICKUP');
    expect(request.request.params.has('cuisine')).toBe(false);
    request.flush({ stores: [], cuisines: [], total: 0, page: 0, size: 20 });
  });

  it('guards branch edits with If-Match and returns the new ETag', async () => {
    // when
    const result = firstValueFrom(
      TestBed.inject(StoreClient).updateHours('s1', {} as never, [], '"store-3"'),
    );

    // then
    const request = http.expectOne('https://api.example/api/v1/stores/s1/hours');
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"store-3"');
    request.flush({ id: 's1' }, { headers: { ETag: '"store-4"' } });
    expect((await result).etag).toBe('"store-4"');
  });

  it('marks one option of a dish sold out at one branch', () => {
    // when
    TestBed.inject(StoreClient).setSoldOut('s1', 'i1', true, 'o2').subscribe();

    // then
    const request = http.expectOne('https://api.example/api/v1/stores/s1/menu/items/i1/sold-out');
    expect(request.request.body).toEqual({ soldOut: true, optionId: 'o2' });
    request.flush(null);
  });

  it('sends the section version so a stale edit is refused', () => {
    // when
    TestBed.inject(BrandClient).updateSection('b1', 'sec1', { en: 'Starters' }, 2, 5).subscribe();

    // then
    const request = http.expectOne('https://api.example/api/v1/brands/b1/menu/sections/sec1');
    expect(request.request.body).toEqual({ name: { en: 'Starters' }, sortOrder: 2, version: 5 });
    request.flush({});
  });

  it('asks for a delivery quote at a point', () => {
    // when
    TestBed.inject(StoreClient).quote('s1', 31.51, 74.34).subscribe();

    // then
    const request = http.expectOne(
      (r) => r.url === 'https://api.example/api/v1/stores/s1/delivery-quote',
    );
    expect(request.request.params.get('lat')).toBe('31.51');
    expect(request.request.params.get('lng')).toBe('74.34');
    request.flush({ deliverable: true });
  });

  it('searches addresses through the geo module', () => {
    // when
    TestBed.inject(GeoClient).searchAddresses('Gulberg').subscribe();

    // then
    const request = http.expectOne((r) => r.url === 'https://api.example/api/v1/geo/addresses');
    expect(request.request.params.get('q')).toBe('Gulberg');
    request.flush([]);
  });
});
