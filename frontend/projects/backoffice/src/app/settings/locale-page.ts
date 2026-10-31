import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { SettingsClient } from '@foodstack/api-client';
import { LANGUAGE_NAMES, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';

const CURRENCIES = [
  'PKR',
  'AED',
  'SAR',
  'QAR',
  'KWD',
  'BHD',
  'OMR',
  'EGP',
  'INR',
  'BDT',
  'TRY',
  'GBP',
  'EUR',
  'USD',
];

const ENGLISH_NAMES: Record<string, string> = {
  en: 'English',
  ar: 'Arabic',
  ur: 'Urdu',
  fa: 'Persian',
  fr: 'French',
  de: 'German',
  es: 'Spanish',
  tr: 'Turkish',
  hi: 'Hindi',
  bn: 'Bengali',
  id: 'Indonesian',
  ms: 'Malay',
  pt: 'Portuguese',
  ru: 'Russian',
  zh: 'Chinese',
};

function timezones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf(key: string): string[] }).supportedValuesOf(
      'timeZone',
    );
  } catch {
    return ['UTC'];
  }
}

/** Languages customers can pick, the default language, currency and time zone (PRD ST-5). */
@Component({
  selector: 'app-locale-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSnackBarModule,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Languages and region</h1>
      <p>
        Customers switch between these languages. Stores type menus in each one; missing text falls
        back to the default.
      </p>
    </header>

    @if (loaded()) {
      <mat-card appearance="outlined">
        <form (ngSubmit)="save()">
          <div class="card-section">
            <h2>Languages</h2>
            <mat-chip-listbox
              multiple
              aria-label="Languages"
              name="languages"
              [(ngModel)]="languages"
            >
              @for (code of allLanguages; track code) {
                <mat-chip-option [value]="code">
                  {{ englishNames[code] }}
                  @if (nativeNames[code] !== englishNames[code]) {
                    <span class="native" [attr.lang]="code">· {{ nativeNames[code] }}</span>
                  }
                </mat-chip-option>
              }
            </mat-chip-listbox>
            <div class="grid-2">
              <mat-form-field appearance="outline">
                <mat-label>Default language</mat-label>
                <mat-select name="defaultLanguage" [(ngModel)]="defaultLanguage" required>
                  @for (code of languages; track code) {
                    <mat-option [value]="code">{{ englishNames[code] ?? code }}</mat-option>
                  }
                </mat-select>
                <mat-hint>Required text, such as dish names, is typed in this language.</mat-hint>
              </mat-form-field>
            </div>
          </div>
          <div class="card-section">
            <h2>Region</h2>
            <div class="grid-2">
              <mat-form-field appearance="outline">
                <mat-label>Currency</mat-label>
                <mat-select name="currency" [(ngModel)]="currency" required>
                  @for (code of currencies; track code) {
                    <mat-option [value]="code">{{ code }}</mat-option>
                  }
                </mat-select>
                <mat-hint>Change it before stores add prices.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Time zone</mat-label>
                <mat-select name="timezone" [(ngModel)]="timezone" required>
                  @for (zone of zones; track zone) {
                    <mat-option [value]="zone">{{ zone }}</mat-option>
                  }
                </mat-select>
                <mat-hint>New branches start in this time zone.</mat-hint>
              </mat-form-field>
            </div>
          </div>
          @if (error()) {
            <div class="banner error" role="alert">{{ error() }}</div>
          }
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
          </div>
        </form>
      </mat-card>
    } @else if (loadError()) {
      <p>The settings could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 880px;
    }
    .native {
      margin-inline-start: 4px;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class LocalePage implements OnInit {
  private readonly settings = inject(SettingsClient);
  private readonly locale = inject(LocaleStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly allLanguages = Object.keys(LANGUAGE_NAMES);
  protected readonly nativeNames = LANGUAGE_NAMES;
  protected readonly englishNames = ENGLISH_NAMES;
  protected readonly currencies = CURRENCIES;
  protected readonly zones = timezones();

  protected readonly loaded = signal(false);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected languages: string[] = [];
  protected defaultLanguage = 'en';
  protected currency = 'USD';
  protected timezone = 'UTC';
  private etag = '';

  async ngOnInit(): Promise<void> {
    try {
      const { value, etag } = await firstValueFrom(this.settings.getLocale());
      this.etag = etag;
      this.languages = [...value.languages];
      this.defaultLanguage = value.defaultLanguage;
      this.currency = value.currency;
      this.timezone = value.timezone;
      for (const code of value.languages)
        if (!this.allLanguages.includes(code)) this.allLanguages.push(code);
      if (!this.currencies.includes(value.currency)) this.currencies.push(value.currency);
      if (!this.zones.includes(value.timezone)) this.zones.unshift(value.timezone);
      this.loaded.set(true);
    } catch {
      this.loadError.set(true);
    }
  }

  protected async save(): Promise<void> {
    if (this.languages.length === 0) {
      this.error.set('Pick at least one language.');
      return;
    }
    if (!this.languages.includes(this.defaultLanguage)) {
      this.error.set('The default language must be one of the picked languages.');
      return;
    }
    const languages = [
      this.defaultLanguage,
      ...this.languages.filter((code) => code !== this.defaultLanguage),
    ];
    this.error.set('');
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(
        this.settings.updateLocale(
          {
            defaultLanguage: this.defaultLanguage,
            languages,
            currency: this.currency,
            timezone: this.timezone,
          },
          this.etag,
        ),
      );
      this.etag = saved.etag;
      this.locale.set(saved.value);
      this.snackBar.open('Saved. Customers see the new languages right away.', undefined, {
        duration: 4000,
      });
    } catch (error) {
      this.error.set(describeError(error, 'The settings could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
