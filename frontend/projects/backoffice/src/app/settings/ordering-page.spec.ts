import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { OrderingSettings, OrderingSettingsClient, PaymentClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { OrderingPage } from './ordering-page';

const SETTINGS: OrderingSettings = {
  cashEnabled: true,
  cardEnabled: true,
  serviceFeeMinor: 5000,
  serviceFeeBasisPoints: 250,
  taxBasisPoints: 1600,
  tipsEnabled: true,
  acceptTimeoutSeconds: 300,
};

describe('OrderingPage', () => {
  async function render() {
    const update = vi.fn(() => of({ value: SETTINGS, etag: '"ordering-3"' }));
    TestBed.configureTestingModule({
      imports: [OrderingPage],
      providers: [
        {
          provide: OrderingSettingsClient,
          useValue: { get: () => of({ value: SETTINGS, etag: '"ordering-2"' }), update },
        },
        {
          provide: PaymentClient,
          useValue: { options: () => of({ cash: true, card: false, gateway: null }) },
        },
        { provide: LocaleStore, useValue: { currency: signal('PKR') } },
      ],
    });
    const fixture = TestBed.createComponent(OrderingPage);
    fixture.detectChanges();
    // ngOnInit awaits plain promises, which whenStable does not track
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, update, element: fixture.nativeElement as HTMLElement };
  }

  it('shows amounts in major units and percents, and warns when card has no gateway', async () => {
    // when
    const { element } = await render();

    // then
    const inputs = element.querySelectorAll('input[matinput]') as NodeListOf<HTMLInputElement>;
    expect(inputs[0].value).toBe('50.00');
    expect(inputs[1].value).toBe('2.5');
    expect(inputs[2].value).toBe('16');
    expect(element.textContent).toContain('No card gateway is set up');
  });

  it('saves minor units, basis points and seconds with the ETag', async () => {
    // given
    const { fixture, element, update } = await render();
    const tax = element.querySelectorAll('input[matinput]')[2] as HTMLInputElement;
    tax.value = '17.5';
    tax.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // when
    (element.querySelector('button[type=submit]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // then
    expect(update).toHaveBeenCalledWith(
      { ...SETTINGS, taxBasisPoints: 1750, priceApprovalRequired: false },
      '"ordering-2"',
    );
  });
});
