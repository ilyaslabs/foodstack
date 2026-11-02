import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import {
  Cart,
  CartClient,
  Checkout,
  OrderClient,
  RUNTIME_CONFIG,
  SettingsClient,
} from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { CartStore } from '../cart/cart-store';
import { LocationStore } from '../location-store';
import { CheckoutPage, setPaymentRedirect } from './checkout-page';

const STORE = {
  storeId: 's1',
  slug: 'karahi-corner',
  brandName: 'Karahi Corner',
  branchName: 'Model Town',
  logoUrls: {},
  location: { lat: 31.48, lng: 74.32 },
  addressLine: '5 Link Road',
  city: 'Lahore',
  deliveryMode: 'SELF' as const,
  pickupEnabled: true,
  live: true,
  openNow: true,
  prepMinutes: 20,
  timezone: 'Asia/Karachi',
};

const LINE = {
  lineId: 'l1',
  note: null,
  priced: {
    itemId: 'i1',
    name: { en: 'Chicken karahi' },
    quantity: 2,
    unitPriceMinor: 125000,
    options: [],
    lineTotalMinor: 250000,
  },
};

const CART: Cart = {
  version: 3,
  store: STORE,
  currency: 'PKR',
  lines: [LINE],
  subtotalMinor: 250000,
  itemCount: 2,
  problems: [],
};

function quote(total: number): Checkout {
  return {
    cartVersion: 3,
    store: STORE,
    currency: 'PKR',
    fulfilment: 'PICKUP',
    lines: [LINE],
    subtotalMinor: 250000,
    delivery: null,
    coupon: null,
    itemsDiscountMinor: 0,
    deliveryDiscountMinor: 0,
    deliveryChargeMinor: 0,
    serviceFeeMinor: total - 250000,
    taxMinor: 0,
    tipMinor: 0,
    totalMinor: total,
    etaMinutes: 20,
    paymentMethods: ['CARD', 'CASH'],
    tipsEnabled: true,
    problems: [],
  };
}

describe('CheckoutPage', () => {
  async function render(checkout: () => Checkout, place: ReturnType<typeof vi.fn>) {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [CheckoutPage],
      providers: [
        provideRouter([]),
        { provide: RUNTIME_CONFIG, useValue: { apiUrl: '' } },
        { provide: CartClient, useValue: { checkout: vi.fn(() => of(checkout())) } },
        { provide: OrderClient, useValue: { place } },
        {
          provide: CartStore,
          useValue: {
            cart: signal(CART),
            empty: signal(false),
            itemCount: signal(2),
            busy: signal(false),
            load: vi.fn(async () => undefined),
          },
        },
        {
          provide: SettingsClient,
          useValue: {
            getLocale: () =>
              of({
                value: {
                  defaultLanguage: 'en',
                  languages: ['en'],
                  rtlLanguages: [],
                  currency: 'PKR',
                  timezone: 'Asia/Karachi',
                  version: 1,
                },
                etag: '"locale-1"',
              }),
          },
        },
      ],
    });
    await TestBed.inject(LocaleStore).load();
    TestBed.inject(LocationStore).clear();
    const fixture = TestBed.createComponent(CheckoutPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      setFulfilment(value: 'PICKUP'): void;
      phone: string;
      place(): Promise<void>;
    };
    page.setFulfilment('PICKUP');
    page.phone = '0300 1234567';
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, page, element: fixture.nativeElement as HTMLElement };
  }

  it('places a card order for the total it shows, and goes to the payment page', async () => {
    // given
    const place = vi.fn(() =>
      of({
        orderId: 'o1',
        number: '0000-0001',
        status: 'PAYMENT_PENDING',
        paymentUrl: 'https://pay.example/o1',
      }),
    );
    const redirect = vi.fn();
    setPaymentRedirect(redirect);
    const { page, element } = await render(() => quote(255000), place);
    expect(element.querySelector('.place')?.textContent).toContain('2,550');

    // when
    await page.place();

    // then
    expect(place).toHaveBeenCalledWith(
      expect.objectContaining({
        fulfilment: 'PICKUP',
        paymentMethod: 'CARD',
        expectedTotalMinor: 255000,
        contactPhone: '0300 1234567',
      }),
      expect.any(String),
    );
    expect(redirect).toHaveBeenCalledWith('https://pay.example/o1');
  });

  it('retries with the same key, and shows the new total when prices changed', async () => {
    // given
    let total = 255000;
    const place = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })))
      .mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'PRICE_CHANGED' } })),
      )
      .mockReturnValue(
        of({ orderId: 'o2', number: '0000-0002', status: 'CONFIRMED', paymentUrl: null }),
      );
    const { fixture, page, element } = await render(() => quote(total), place);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    // when: the network drops, then the retry finds new prices
    await page.place();
    await page.place();
    total = 260000;
    await fixture.whenStable();
    fixture.detectChanges();

    // then: one key for the same order, and the message about prices
    expect(place.mock.calls[0][1]).toBe(place.mock.calls[1][1]);
    expect(element.textContent).toContain('Prices changed');

    // when: placed again with the new total
    await (fixture.componentInstance as unknown as { refresh(): Promise<void> }).refresh();
    fixture.detectChanges();
    await page.place();

    // then: a new key, since the order (its total) is different now
    expect(place.mock.calls[2][0]).toEqual(expect.objectContaining({ expectedTotalMinor: 260000 }));
    expect(place.mock.calls[2][1]).not.toBe(place.mock.calls[0][1]);
    expect(navigate).toHaveBeenCalledWith('/orders/o2');
  });
});
