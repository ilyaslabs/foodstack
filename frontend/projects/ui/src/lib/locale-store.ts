import { DOCUMENT } from '@angular/common';
import { Injectable, REQUEST, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocaleSettings, SettingsClient } from '@foodstack/api-client';

/** Shown until the API answers. */
export const DEFAULT_LOCALE: LocaleSettings = {
  defaultLanguage: 'en',
  languages: ['en'],
  rtlLanguages: [],
  currency: 'USD',
  timezone: 'UTC',
  version: 0,
};

/** Names of languages in their own script, for language switches. */
export const LANGUAGE_NAMES: Record<string, string> = {
  en: 'English',
  ar: 'العربية',
  ur: 'اردو',
  fa: 'فارسی',
  fr: 'Français',
  de: 'Deutsch',
  es: 'Español',
  tr: 'Türkçe',
  hi: 'हिन्दी',
  bn: 'বাংলা',
  id: 'Bahasa Indonesia',
  ms: 'Bahasa Melayu',
  pt: 'Português',
  ru: 'Русский',
  zh: '中文',
};

const STORAGE_KEY = 'fs-language';

/**
 * The deployment's languages and currency (settings module), and the language the person picked.
 * Applies `lang` and `dir` on the document so right-to-left languages flip the whole layout.
 */
@Injectable({ providedIn: 'root' })
export class LocaleStore {
  private readonly settings = inject(SettingsClient);
  private readonly document = inject(DOCUMENT);
  private readonly request = inject(REQUEST, { optional: true });

  private readonly state = signal<LocaleSettings>(DEFAULT_LOCALE);
  private readonly picked = signal<string | null>(null);

  readonly locale = this.state.asReadonly();
  readonly languages = computed(() => this.state().languages);
  readonly currency = computed(() => this.state().currency);
  readonly language = computed(() => {
    const picked = this.picked();
    return picked && this.state().languages.includes(picked)
      ? picked
      : this.state().defaultLanguage;
  });
  readonly rtl = computed(() => this.state().rtlLanguages.includes(this.language()));

  private applyToDocument = true;

  /**
   * @param applyToDocument set `lang`/`dir` on the page; the back office keeps its English,
   *     left-to-right frame and only uses the languages for editing content
   * @param fresh bypass the browser cache, so editors see languages saved a moment ago
   */
  async load(applyToDocument = true, fresh = false): Promise<void> {
    this.applyToDocument = applyToDocument;
    try {
      const { value } = await firstValueFrom(this.settings.getLocale(fresh));
      this.state.set(value);
    } catch {
      this.state.set(DEFAULT_LOCALE);
    }
    this.picked.set(this.readPicked());
    this.apply();
  }

  /** The cookie lets server rendering answer in the picked language; local storage is the fallback. */
  private readPicked(): string | null {
    const cookies = this.request?.headers.get('cookie') ?? this.cookieString();
    const match = /(?:^|;\s*)fs-language=([A-Za-z-]{2,12})/.exec(cookies);
    if (match) return match[1];
    try {
      return this.document.defaultView?.localStorage?.getItem(STORAGE_KEY) ?? null;
    } catch {
      return null;
    }
  }

  private cookieString(): string {
    try {
      return this.document.cookie ?? '';
    } catch {
      return '';
    }
  }

  use(language: string): void {
    this.picked.set(language);
    try {
      this.document.defaultView?.localStorage?.setItem(STORAGE_KEY, language);
      this.document.cookie = `${STORAGE_KEY}=${encodeURIComponent(language)}; path=/; max-age=31536000; SameSite=Lax`;
    } catch {
      // storage blocked: the choice lasts for this page only
    }
    this.apply();
  }

  /** Text in the current language, else the default language, else any language. */
  pick(text: Record<string, string> | null | undefined): string {
    if (!text) return '';
    return (
      text[this.language()] || text[this.state().defaultLanguage] || Object.values(text)[0] || ''
    );
  }

  /** An amount in minor units, in the deployment currency and the current language's format. */
  money(minor: number, currency = this.currency()): string {
    return formatMoney(minor, currency, this.language());
  }

  /** Whether a language is written right to left, for inputs holding that language. */
  isRtl(language: string): boolean {
    return this.state().rtlLanguages.includes(language);
  }

  /** A language's own name, such as "اردو" for ur. */
  languageName(language: string): string {
    return LANGUAGE_NAMES[language] ?? language;
  }

  /** Replaces the settings after an edit in the back office. */
  set(locale: LocaleSettings): void {
    this.state.set(locale);
    this.apply();
  }

  private apply(): void {
    if (!this.applyToDocument) return;
    const root = this.document.documentElement;
    root.setAttribute('lang', this.language());
    root.setAttribute('dir', this.rtl() ? 'rtl' : 'ltr');
  }
}

/**
 * ISO 4217 minor-unit exponents that differ from 2. The API stores amounts in these minor units
 * (java.util.Currency), which is not always what CLDR shows: PKR has paisa (2) though prices are
 * usually displayed without them.
 */
const ISO_DIGITS: Record<string, number> = {
  BIF: 0,
  CLP: 0,
  DJF: 0,
  GNF: 0,
  ISK: 0,
  JPY: 0,
  KMF: 0,
  KRW: 0,
  PYG: 0,
  RWF: 0,
  UGX: 0,
  VND: 0,
  VUV: 0,
  XAF: 0,
  XOF: 0,
  XPF: 0,
  BHD: 3,
  IQD: 3,
  JOD: 3,
  KWD: 3,
  LYD: 3,
  OMR: 3,
  TND: 3,
};

/** Formats minor units (paisa, cents) for display in the given language. */
export function formatMoney(minor: number, currency: string, language = 'en'): string {
  const amount = minor / 10 ** currencyDigits(currency);
  try {
    return new Intl.NumberFormat(language, { style: 'currency', currency }).format(amount);
  } catch {
    return `${amount.toFixed(currencyDigits(currency))} ${currency}`;
  }
}

/** Decimals of a currency's minor unit, for turning a typed price into minor units. */
export function currencyDigits(currency: string): number {
  return ISO_DIGITS[currency?.toUpperCase()] ?? 2;
}
