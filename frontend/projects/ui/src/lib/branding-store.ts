import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Branding, SettingsClient } from '@foodstack/api-client';

import { brandStylesheet } from './branding-theme';

const STYLE_ID = 'fs-branding';

/** Shown until the API answers, and when it cannot be reached. */
export const DEFAULT_BRANDING: Branding = {
  name: 'FoodStack',
  tagline: null,
  primaryColor: '#D84315',
  secondaryColor: '#00695C',
  fontFamily: 'Roboto',
  logo: null,
  version: 0,
  updatedAt: null,
};

/**
 * Loads the operator's branding on boot and applies it as Material 3 design tokens (TDD 14.2), so
 * components never hard-code colours. Runs on the server during SSR too, so the first paint is
 * already branded.
 */
@Injectable({ providedIn: 'root' })
export class BrandingStore {
  private readonly settings = inject(SettingsClient);
  private readonly document = inject(DOCUMENT);

  private readonly state = signal<{ branding: Branding; etag: string }>({
    branding: DEFAULT_BRANDING,
    etag: '',
  });

  readonly branding = computed(() => this.state().branding);
  readonly etag = computed(() => this.state().etag);
  readonly logoUrl = computed(() => this.branding().logo?.urls['thumb'] ?? null);

  async load(): Promise<void> {
    try {
      const { value, etag } = await firstValueFrom(this.settings.getBranding());
      this.set(value, etag);
    } catch {
      this.apply(DEFAULT_BRANDING);
    }
  }

  set(branding: Branding, etag: string): void {
    this.state.set({ branding, etag });
    this.apply(branding);
  }

  private apply(branding: Branding): void {
    let style = this.document.getElementById(STYLE_ID);
    if (!style) {
      style = this.document.createElement('style');
      style.id = STYLE_ID;
      this.document.head.appendChild(style);
    }
    style.textContent = brandStylesheet(
      branding.primaryColor,
      branding.secondaryColor,
      branding.fontFamily,
    );
    this.document.title = branding.name;
  }
}
