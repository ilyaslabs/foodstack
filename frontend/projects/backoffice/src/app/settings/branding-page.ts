import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import { MediaClient, Problem, SettingsClient } from '@foodstack/api-client';
import { BrandingStore } from '@foodstack/ui';

const HEX = /^#[0-9A-Fa-f]{6}$/;

export const FONTS = ['Roboto', 'Inter', 'Noto Sans', 'Poppins', 'Lato', 'Open Sans'];

/** Name, colours, font and logo of the platform (PRD: platform setup). */
@Component({
  selector: 'app-branding-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
  ],
  templateUrl: './branding-page.html',
  styleUrl: './branding-page.scss',
})
export class BrandingPage {
  private readonly store = inject(BrandingStore);
  private readonly settings = inject(SettingsClient);
  private readonly media = inject(MediaClient);

  protected readonly fonts = FONTS;
  protected readonly saving = signal(false);
  protected readonly uploading = signal(false);
  protected readonly message = signal<{ kind: 'ok' | 'error'; text: string } | null>(null);
  protected readonly logoPreview = signal<string | null>(
    this.store.branding().logo?.urls['card'] ?? null,
  );

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: [this.store.branding().name, [Validators.required, Validators.maxLength(60)]],
    tagline: [this.store.branding().tagline ?? '', [Validators.maxLength(120)]],
    primaryColor: [
      this.store.branding().primaryColor,
      [Validators.required, Validators.pattern(HEX)],
    ],
    secondaryColor: [
      this.store.branding().secondaryColor,
      [Validators.required, Validators.pattern(HEX)],
    ],
    fontFamily: [this.store.branding().fontFamily, [Validators.required]],
    logoMediaId: [this.store.branding().logo?.mediaId ?? ''],
  });

  protected async onLogoSelected(event: Event): Promise<void> {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.message.set(null);
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'BRANDING'));
      this.form.controls.logoMediaId.setValue(asset.id);
      this.form.markAsDirty();
      this.logoPreview.set(asset.variants['card']?.url ?? null);
    } catch (error) {
      this.message.set({ kind: 'error', text: describe(error, 'The logo could not be uploaded.') });
    } finally {
      this.uploading.set(false);
    }
  }

  protected async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.message.set(null);
    try {
      const saved = await firstValueFrom(
        this.settings.updateBranding(
          { ...value, tagline: value.tagline || null, logoMediaId: value.logoMediaId || null },
          this.store.etag(),
        ),
      );
      this.store.set(saved.value, saved.etag);
      this.form.markAsPristine();
      this.message.set({ kind: 'ok', text: 'Branding saved. Customers see it right away.' });
    } catch (error) {
      this.message.set({ kind: 'error', text: describe(error, 'Branding could not be saved.') });
    } finally {
      this.saving.set(false);
    }
  }
}

function describe(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as Partial<Problem> | null;
    if (problem?.code === 'SETTINGS_VERSION_CONFLICT') {
      return 'Someone else changed the branding meanwhile. Reload the page to see their changes.';
    }
    return problem?.detail ?? fallback;
  }
  return fallback;
}
