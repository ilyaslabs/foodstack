import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/common';
import { of, throwError } from 'rxjs';

import { Branding, SettingsClient } from '@foodstack/api-client';

import { BrandingStore, DEFAULT_BRANDING } from './branding-store';

const BRANDING: Branding = {
  ...DEFAULT_BRANDING,
  name: 'Spice Route',
  logo: {
    mediaId: 'm1',
    urls: { thumb: 'https://cdn.example/thumb.webp', card: 'https://cdn.example/card.webp' },
  },
  version: 3,
};

describe('BrandingStore', () => {
  function setUp(settings: Partial<SettingsClient>) {
    TestBed.configureTestingModule({
      providers: [{ provide: SettingsClient, useValue: settings }],
    });
    return { store: TestBed.inject(BrandingStore), document: TestBed.inject(DOCUMENT) };
  }

  it('applies the loaded branding as design tokens and the page title', async () => {
    // given
    const { store, document } = setUp({
      getBranding: () => of({ value: BRANDING, etag: '"branding-3"' }),
    });

    // when
    await store.load();

    // then
    expect(store.branding().name).toBe('Spice Route');
    expect(store.etag()).toBe('"branding-3"');
    expect(store.logoUrl()).toBe('https://cdn.example/thumb.webp');
    expect(document.getElementById('fs-branding')?.textContent).toContain('--mat-sys-primary');
    expect(document.title).toBe('Spice Route');
  });

  it('falls back to the default brand when the API cannot be reached', async () => {
    // given
    const { store, document } = setUp({
      getBranding: () => throwError(() => new Error('offline')),
    });

    // when
    await store.load();

    // then
    expect(store.branding()).toEqual(DEFAULT_BRANDING);
    expect(document.getElementById('fs-branding')).not.toBeNull();
  });
});
