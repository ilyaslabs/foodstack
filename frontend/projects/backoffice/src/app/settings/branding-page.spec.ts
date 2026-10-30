import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { MediaClient, SettingsClient } from '@foodstack/api-client';
import { BrandingStore, DEFAULT_BRANDING } from '@foodstack/ui';

import { BrandingPage } from './branding-page';

describe('BrandingPage', () => {
  const store = {
    branding: () => DEFAULT_BRANDING,
    etag: () => '"branding-1"',
    set: vi.fn(),
  };

  function render(settings: Partial<SettingsClient>, media: Partial<MediaClient> = {}) {
    TestBed.configureTestingModule({
      imports: [BrandingPage],
      providers: [
        { provide: BrandingStore, useValue: store },
        { provide: SettingsClient, useValue: settings },
        { provide: MediaClient, useValue: media },
      ],
    });
    const fixture = TestBed.createComponent(BrandingPage);
    fixture.detectChanges();
    return fixture;
  }

  it('uploads the logo, then saves the branding with the version it was read at', async () => {
    // given
    const updateBranding = vi
      .fn()
      .mockReturnValue(
        of({ value: { ...DEFAULT_BRANDING, name: 'Spice Route' }, etag: '"branding-2"' }),
      );
    const upload = vi
      .fn()
      .mockReturnValue(
        of({
          id: 'media-1',
          purpose: 'BRANDING',
          status: 'READY',
          variants: { card: { url: 'https://cdn/card.webp' } },
        }),
      );
    const fixture = render({ updateBranding }, { upload });
    const input: HTMLInputElement = fixture.nativeElement.querySelector(
      '[data-testid="logo-input"]',
    );
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'logo.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [file] });

    // when
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector(
      'input[formcontrolname="name"]',
    );
    nameInput.value = 'Spice Route';
    nameInput.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    // then
    expect(upload).toHaveBeenCalledWith(file, 'BRANDING');
    expect(updateBranding).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Spice Route', logoMediaId: 'media-1' }),
      '"branding-1"',
    );
    expect(store.set).toHaveBeenCalled();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Branding saved');
  });

  it('explains a conflicting change by someone else', async () => {
    // given
    const conflict = new HttpErrorResponse({
      status: 409,
      error: { code: 'SETTINGS_VERSION_CONFLICT' },
    });
    const fixture = render({ updateBranding: () => throwError(() => conflict) });
    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector(
      'input[formcontrolname="name"]',
    );
    nameInput.value = 'Another name';
    nameInput.dispatchEvent(new Event('input'));

    // when
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(fixture.nativeElement.textContent).toContain('Someone else changed the branding');
  });
});
