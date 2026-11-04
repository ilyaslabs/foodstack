import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import {
  GeoClient,
  SearchClient,
  SettingsClient,
  StoreCard,
  StoreSearchPage,
} from '@foodstack/api-client';
import { BrandingStore, DEFAULT_BRANDING } from '@foodstack/ui';

import { LocationStore } from '../location-store';
import { Home } from './home';

const CARD: StoreCard = {
  storeId: 's1',
  slug: 'spice-route-gulberg',
  brandId: 'b1',
  brandName: 'Spice Route',
  branchName: 'Gulberg',
  branchCount: 1,
  description: { en: 'Grills and karahi' },
  cuisines: ['pakistani', 'bbq'],
  logoUrls: {},
  coverUrls: {},
  area: 'Gulberg III',
  city: 'Lahore',
  distanceMeters: 1800,
  deliveryMode: 'SELF',
  pickupEnabled: true,
  openNow: true,
  temporarilyClosed: false,
  prepMinutes: 25,
  dietaryTags: [],
};

function page(stores: StoreCard[]): StoreSearchPage {
  return {
    stores,
    cuisines: [
      { cuisine: 'pakistani', count: 1 },
      { cuisine: 'bbq', count: 1 },
    ],
    total: stores.length,
    page: 0,
    size: 24,
    hasMore: false,
    radiusMeters: 8000,
    widerRadiusMeters: 15000,
  };
}

describe('Home', () => {
  async function render(options: { saved: boolean; search?: Partial<SearchClient> }) {
    localStorage.clear();
    if (options.saved)
      localStorage.setItem(
        'fs-location',
        JSON.stringify({ lat: 31.51, lng: 74.34, label: 'Gulberg III, Lahore' }),
      );
    const stores = vi.fn(() => of(page([CARD])));
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [
        provideRouter([]),
        {
          provide: BrandingStore,
          useValue: {
            branding: signal({
              ...DEFAULT_BRANDING,
              name: 'Spice Route',
              tagline: 'Dinner nearby',
            }),
          },
        },
        { provide: SettingsClient, useValue: {} },
        { provide: SearchClient, useValue: options.search ?? { stores } },
        { provide: GeoClient, useValue: { searchAddresses: () => of([]), reverse: () => of([]) } },
      ],
    });
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, stores, element: fixture.nativeElement as HTMLElement };
  }

  it('asks for a location before showing stores', async () => {
    // when
    const { element, stores } = await render({ saved: false });

    // then
    expect(element.querySelector('h1')?.textContent).toContain('Dinner nearby');
    expect(element.querySelector('app-location-picker')).not.toBeNull();
    expect(element.textContent).toContain('Where should we deliver?');
    expect(stores).not.toHaveBeenCalled();
  });

  it('searches around the saved location and shows the stores found', async () => {
    // when
    const { element, stores } = await render({ saved: true });

    // then
    expect(stores).toHaveBeenCalledWith(
      expect.objectContaining({ lat: 31.51, lng: 74.34, fulfilment: 'DELIVERY', page: 0 }),
    );
    expect(element.querySelectorAll('app-store-card').length).toBe(1);
    expect(element.textContent).toContain('Pakistani · BBQ');
    expect(element.textContent).toContain('1 store');
    expect(element.querySelector('app-store-card a')?.getAttribute('href')).toBe(
      '/stores/spice-route-gulberg',
    );
  });

  it('offers restaurants further away, one step at a time', async () => {
    // given
    const { fixture, element, stores } = await render({ saved: true });
    expect(element.textContent).toContain('within 8 km');

    // when
    const further = [...element.querySelectorAll<HTMLButtonElement>('.further button')][0];
    expect(further.textContent).toContain('Show restaurants further away (up to 15 km)');
    further.click();
    await fixture.whenStable();

    // then
    expect(stores).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, radius: 15000 }));
  });

  it('switches to pickup', async () => {
    // given
    const { fixture, stores, element } = await render({ saved: true });

    // when
    (element.querySelectorAll('.mode button')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();

    // then
    expect(stores).toHaveBeenLastCalledWith(expect.objectContaining({ fulfilment: 'PICKUP' }));
  });

  it('says so when search is down', async () => {
    // when
    const { element } = await render({
      saved: true,
      search: { stores: () => throwError(() => new Error('503')) },
    });

    // then
    expect(element.textContent).toContain('Search is not available right now');
  });
});
