import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { LocaleStore } from '@foodstack/ui';

import { Ui } from './i18n';

describe('Ui', () => {
  function setUp(language: string) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: LocaleStore, useValue: { language: signal(language) } }],
    });
    return TestBed.inject(Ui);
  }

  it('fills placeholders in the customer language', () => {
    expect(setUp('en').t('storesFound', { n: 12 })).toBe('12 stores');
    expect(setUp('ar').t('deliveryFee', { fee: 'X' })).toBe('التوصيل X');
  });

  it('uses English for a language without a dictionary', () => {
    expect(setUp('fr').t('signIn')).toBe('Sign in');
  });

  it('writes numbers in the customer language', () => {
    expect(setUp('en').distance(1800)).toBe('1.8 km');
    // CLDR picks the digits for Arabic (Latin in recent data, Arabic-Indic in older)
    expect(setUp('ar').minutes(25)).toMatch(/^(25|٢٥) دقيقة$/);
  });
});
