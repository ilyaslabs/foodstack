import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/common';
import { of, throwError } from 'rxjs';

import { LocaleSettings, SettingsClient } from '@foodstack/api-client';

import { LocaleStore, currencyDigits, formatMoney } from './locale-store';

const LOCALE: LocaleSettings = {
  defaultLanguage: 'en',
  languages: ['en', 'ar', 'ur'],
  rtlLanguages: ['ar', 'ur'],
  currency: 'PKR',
  timezone: 'Asia/Karachi',
  version: 2,
};

describe('LocaleStore', () => {
  function setUp(settings: Partial<SettingsClient>) {
    TestBed.configureTestingModule({
      providers: [{ provide: SettingsClient, useValue: settings }],
    });
    const document = TestBed.inject(DOCUMENT);
    document.cookie = 'fs-language=; max-age=0; path=/';
    localStorage.clear();
    return { store: TestBed.inject(LocaleStore), document };
  }

  afterEach(() => {
    document.documentElement.setAttribute('dir', 'ltr');
    document.documentElement.setAttribute('lang', 'en');
  });

  it('switches the whole page to right to left for Urdu and remembers the pick', async () => {
    // given
    const { store, document } = setUp({
      getLocale: () => of({ value: LOCALE, etag: '"locale-2"' }),
    });
    await store.load();

    // when
    store.use('ur');

    // then
    expect(store.language()).toBe('ur');
    expect(store.rtl()).toBe(true);
    expect(document.documentElement.getAttribute('dir')).toBe('rtl');
    expect(document.documentElement.getAttribute('lang')).toBe('ur');
    expect(document.cookie).toContain('fs-language=ur');
    expect(localStorage.getItem('fs-language')).toBe('ur');
  });

  it('picks the customer language, then the default, then any text', async () => {
    // given
    const { store } = setUp({ getLocale: () => of({ value: LOCALE, etag: '"locale-2"' }) });
    await store.load();
    store.use('ar');

    // then
    expect(store.pick({ en: 'Biryani', ar: 'برياني' })).toBe('برياني');
    expect(store.pick({ en: 'Naan' })).toBe('Naan');
    expect(store.pick({ ur: 'دال' })).toBe('دال');
    expect(store.pick(null)).toBe('');
  });

  it('ignores a saved language the operator no longer offers', async () => {
    // given
    const { store } = setUp({ getLocale: () => of({ value: LOCALE, etag: '"locale-2"' }) });
    localStorage.setItem('fs-language', 'fr');

    // when
    await store.load();

    // then
    expect(store.language()).toBe('en');
  });

  it('keeps the back-office frame left to right when asked not to touch the document', async () => {
    // given
    const { store, document } = setUp({
      getLocale: () => of({ value: LOCALE, etag: '"locale-2"' }),
    });
    await store.load(false);

    // when
    store.use('ar');

    // then
    expect(document.documentElement.getAttribute('dir')).toBe('ltr');
    expect(store.isRtl('ur')).toBe(true);
  });

  it('falls back to English and US dollars when settings cannot be read', async () => {
    // given
    const { store } = setUp({ getLocale: () => throwError(() => new Error('offline')) });

    // when
    await store.load();

    // then
    expect(store.languages()).toEqual(['en']);
    expect(store.currency()).toBe('USD');
  });
});

describe('formatMoney', () => {
  it('reads amounts in ISO minor units, whatever the display decimals', () => {
    // PKR is stored in paisa (2 digits) though usually shown without them
    expect(formatMoney(65000, 'PKR', 'en')).toMatch(/650(\.00)?$/);
    expect(formatMoney(1250, 'KWD', 'en')).toContain('1.250');
    expect(formatMoney(500, 'JPY', 'en')).toContain('500');
    expect(currencyDigits('pkr')).toBe(2);
  });
});
