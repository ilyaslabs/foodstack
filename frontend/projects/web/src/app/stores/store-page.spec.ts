import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import {
  LocaleSettings,
  Menu,
  SettingsClient,
  StoreClient,
  StorePage as Page,
} from '@foodstack/api-client';
import { BrandingStore, DEFAULT_BRANDING, LocaleStore } from '@foodstack/ui';

import { StorePage } from './store-page';

const LOCALE: LocaleSettings = {
  defaultLanguage: 'en',
  languages: ['en', 'ar', 'ur'],
  rtlLanguages: ['ar', 'ur'],
  currency: 'PKR',
  timezone: 'Asia/Karachi',
  version: 1,
};

const PAGE: Page = {
  id: 's1',
  brandId: 'b1',
  brandName: 'Spice Route',
  branchName: 'Gulberg',
  displayName: 'Spice Route Gulberg',
  description: { en: 'Grills and karahi', ur: 'گرِل اور کڑاہی' },
  cuisines: ['pakistani'],
  logoUrls: {},
  coverUrls: {},
  address: '42 Main Boulevard, Gulberg III, Lahore',
  location: { lat: 31.512, lng: 74.344 },
  openNow: true,
  temporarilyClosed: false,
  closedReason: null,
  openingHours: {
    MONDAY: [{ open: '12:00', close: '23:00' }],
    TUESDAY: [{ open: '12:00', close: '23:00' }],
    WEDNESDAY: [],
    THURSDAY: [{ open: '12:00', close: '23:00' }],
    FRIDAY: [{ open: '12:00', close: '23:00' }],
    SATURDAY: [{ open: '12:00', close: '23:00' }],
    SUNDAY: [{ open: '12:00', close: '23:00' }],
  },
  prepMinutes: 25,
  deliveryMode: 'SELF',
  pickupEnabled: true,
  timezone: 'Asia/Karachi',
};

const MENU: Menu = {
  storeId: 's1',
  brandId: 'b1',
  currency: 'PKR',
  sections: [
    {
      id: 'sec1',
      name: { en: 'Biryani', ar: 'برياني', ur: 'بریانی' },
      items: [
        {
          id: 'i1',
          name: { en: 'Chicken biryani', ar: 'برياني دجاج', ur: 'چکن بریانی' },
          description: { en: 'Slow-cooked rice' },
          priceMinor: 65000,
          imageUrls: {},
          dietaryTags: ['halal'],
          soldOut: false,
          modifierGroups: [],
        },
        {
          id: 'i2',
          name: { en: 'Mutton biryani', ur: 'مٹن بریانی' },
          description: {},
          priceMinor: 95000,
          imageUrls: {},
          dietaryTags: [],
          soldOut: true,
          modifierGroups: [],
        },
      ],
    },
    { id: 'empty', name: { en: 'Drinks' }, items: [] },
  ],
};

describe('StorePage', () => {
  async function render(stores: Partial<StoreClient>, language = 'en') {
    localStorage.clear();
    localStorage.setItem('fs-language', language);
    document.cookie = 'fs-language=; max-age=0; path=/';
    TestBed.configureTestingModule({
      imports: [StorePage],
      providers: [
        provideRouter([]),
        {
          provide: BrandingStore,
          useValue: { branding: signal({ ...DEFAULT_BRANDING, name: 'FoodStack' }) },
        },
        {
          provide: SettingsClient,
          useValue: { getLocale: () => of({ value: LOCALE, etag: '"locale-1"' }) },
        },
        { provide: StoreClient, useValue: stores },
      ],
    });
    await TestBed.inject(LocaleStore).load();
    const fixture = TestBed.createComponent(StorePage);
    fixture.componentRef.setInput('id', 's1');
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => {
    document.documentElement.setAttribute('dir', 'ltr');
    document.documentElement.setAttribute('lang', 'en');
  });

  it('shows the menu with prices, tags and sold-out dishes', async () => {
    // when
    const { element } = await render({
      page: () => of(PAGE),
      menu: () => of(MENU),
      quote: () => of() as never,
    });

    // then
    expect(element.querySelector('h1')?.textContent).toContain('Spice Route');
    expect(element.textContent).toContain('Chicken biryani');
    expect(element.textContent).toContain('650');
    expect(element.textContent).toContain('Halal');
    expect(element.querySelector('.sold-out')?.textContent).toContain('Sold out');
    expect(element.textContent).not.toContain('Drinks');
  });

  it('reads right to left in Urdu, with the default language where a dish has no Urdu', async () => {
    // when
    const { element } = await render(
      { page: () => of(PAGE), menu: () => of(MENU), quote: () => of() as never },
      'ur',
    );

    // then
    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(element.textContent).toContain('چکن بریانی');
    expect(element.textContent).toContain('گرِل اور کڑاہی');
    expect(element.textContent).toContain('ختم');
    expect(element.textContent).toContain('حلال');
  });

  it('says the store is not available when it cannot be found', async () => {
    // when
    const { element } = await render({
      page: () => throwError(() => new Error('404')),
      menu: () => of(MENU),
    });

    // then
    expect(element.textContent).toContain('This store is not available');
  });
});
