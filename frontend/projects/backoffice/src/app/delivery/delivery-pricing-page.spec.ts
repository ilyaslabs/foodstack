import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { GeoClient, OperatorPricing } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { DeliveryPricingPage } from './delivery-pricing-page';

const PRICING: OperatorPricing = {
  enabled: true,
  baseChargeMinor: 10000,
  perKmMinor: 2500,
  maxDistanceMeters: 8000,
  version: 2,
  updatedAt: null,
};

describe('DeliveryPricingPage', () => {
  async function render() {
    const updateOperatorPricing = vi.fn(() =>
      of({ value: { ...PRICING, version: 3 }, etag: '"pricing-3"' }),
    );
    TestBed.configureTestingModule({
      imports: [DeliveryPricingPage],
      providers: [
        {
          provide: GeoClient,
          useValue: {
            operatorPricing: () => of({ value: PRICING, etag: '"pricing-2"' }),
            updateOperatorPricing,
          },
        },
        {
          provide: LocaleStore,
          useValue: { currency: signal('PKR'), money: (minor: number) => `Rs ${minor / 100}` },
        },
      ],
    });
    const fixture = TestBed.createComponent(DeliveryPricingPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, updateOperatorPricing, element: fixture.nativeElement as HTMLElement };
  }

  it('shows the current rate in major units with example charges', async () => {
    // when
    const { element } = await render();

    // then
    const inputs = element.querySelectorAll('input[matinput]') as NodeListOf<HTMLInputElement>;
    expect(inputs[0].value).toBe('100.00');
    expect(inputs[1].value).toBe('25.00');
    expect(element.textContent).toContain('Rs 175');
  });

  it('saves the rate in minor units with the ETag', async () => {
    // given
    const { fixture, element, updateOperatorPricing } = await render();
    const base = element.querySelectorAll('input[matinput]')[0] as HTMLInputElement;
    base.value = '120';
    base.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // when
    (element.querySelector('button[type=submit]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // then
    expect(updateOperatorPricing).toHaveBeenCalledWith(
      { enabled: true, baseChargeMinor: 12000, perKmMinor: 2500, maxDistanceMeters: 8000 },
      '"pricing-2"',
    );
  });
});
