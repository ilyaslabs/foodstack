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
  minOrderMinor: 0,
  freeDeliveryAboveMinor: null,
  peakMultiplierPercent: 150,
  peakWindows: [{ days: ['FRIDAY'], start: '23:00', end: '02:00' }],
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
          useValue: {
            currency: signal('PKR'),
            locale: signal({ timezone: 'Asia/Karachi' }),
            money: (minor: number) => `Rs ${minor / 100}`,
          },
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
      {
        enabled: true,
        baseChargeMinor: 12000,
        perKmMinor: 2500,
        maxDistanceMeters: 8000,
        minOrderMinor: 0,
        freeDeliveryAboveMinor: null,
        peakMultiplierPercent: 150,
        peakWindows: [{ days: ['FRIDAY'], start: '23:00', end: '02:00' }],
      },
      '"pricing-2"',
    );
  });

  it('shows peak hours in words and saves the minimum order and free threshold', async () => {
    // given
    const { fixture, element, updateOperatorPricing } = await render();
    expect(element.querySelector('.window-note')?.textContent).toContain(
      'Fri, 23:00 to 02:00, running past midnight',
    );
    expect(element.textContent).toContain("each branch's local time");
    const minOrder = element.querySelector('input[name=minOrder]') as HTMLInputElement;
    const freeAbove = element.querySelector('input[name=freeAbove]') as HTMLInputElement;
    minOrder.value = '500';
    minOrder.dispatchEvent(new Event('input'));
    freeAbove.value = '2000';
    freeAbove.dispatchEvent(new Event('input'));
    (element.querySelector('button[aria-label^="Remove peak"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // when
    (element.querySelector('button[type=submit]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // then
    expect(updateOperatorPricing).toHaveBeenCalledWith(
      expect.objectContaining({
        minOrderMinor: 50000,
        freeDeliveryAboveMinor: 200000,
        peakWindows: [],
      }),
      '"pricing-2"',
    );
  });
});
