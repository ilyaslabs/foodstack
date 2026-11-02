import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import { MenuItem, SettingsClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';
import { of } from 'rxjs';

import { CartStore, OtherStoreInCart } from '../cart/cart-store';
import { ItemDialog, ItemDialogData } from './item-dialog';

const ITEM: MenuItem = {
  id: 'i1',
  name: { en: 'Chicken karahi' },
  description: { en: 'Charcoal karahi' },
  priceMinor: 85000,
  imageUrls: {},
  dietaryTags: [],
  soldOut: false,
  modifierGroups: [
    {
      id: 'size',
      name: { en: 'Size' },
      minSelect: 1,
      maxSelect: 1,
      options: [
        { id: 'half', name: { en: 'Half' }, priceDeltaMinor: 0, soldOut: false },
        { id: 'full', name: { en: 'Full' }, priceDeltaMinor: 40000, soldOut: false },
      ],
    },
    {
      id: 'extras',
      name: { en: 'Extras' },
      minSelect: 0,
      maxSelect: 2,
      options: [
        { id: 'naan', name: { en: 'Naan' }, priceDeltaMinor: 5000, soldOut: false },
        { id: 'raita', name: { en: 'Raita' }, priceDeltaMinor: 3000, soldOut: false },
        { id: 'salad', name: { en: 'Salad' }, priceDeltaMinor: 2000, soldOut: true },
      ],
    },
  ],
};

describe('ItemDialog', () => {
  async function render(add: (line: unknown) => Promise<void>) {
    localStorage.clear();
    const close = vi.fn();
    TestBed.configureTestingModule({
      imports: [ItemDialog],
      providers: [
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            storeId: 's1',
            item: ITEM,
            currency: 'PKR',
            imageUrl: null,
            canOrder: true,
          } satisfies ItemDialogData,
        },
        { provide: MatDialogRef, useValue: { close } },
        {
          provide: CartStore,
          useValue: { add: vi.fn(add), busy: signal(false), cart: signal(null) },
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
    const fixture = TestBed.createComponent(ItemDialog);
    fixture.detectChanges();
    const cart = TestBed.inject(CartStore) as unknown as { add: ReturnType<typeof vi.fn> };
    return { fixture, element: fixture.nativeElement as HTMLElement, close, cart };
  }

  function addButton(element: HTMLElement): HTMLButtonElement {
    return element.querySelector('.add') as HTMLButtonElement;
  }

  it('starts on the first size, prices choices and quantity, and adds them', async () => {
    // given
    const { fixture, element, close, cart } = await render(async () => undefined);
    expect(addButton(element).textContent).toContain('850');

    // when: full size, naan, two of them
    (element.querySelectorAll('mat-radio-button input')[1] as HTMLInputElement).click();
    (element.querySelector('mat-checkbox input') as HTMLInputElement).click();
    (element.querySelector('[aria-label="One more"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // then: 2 x (850 + 400 + 50)
    expect(addButton(element).textContent).toContain('2,600');
    addButton(element).click();
    await fixture.whenStable();
    expect(cart.add).toHaveBeenCalledWith(
      expect.objectContaining({
        storeId: 's1',
        itemId: 'i1',
        quantity: 2,
        optionIds: ['full', 'naan'],
        replaceOtherStore: false,
      }),
    );
    expect(close).toHaveBeenCalledWith(true);
  });

  it('asks before replacing a cart from another restaurant', async () => {
    // given
    let calls = 0;
    const { fixture, element, close, cart } = await render(async () => {
      if (calls++ === 0) throw new OtherStoreInCart('Burger Lab');
    });

    // when
    addButton(element).click();
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(element.textContent).toContain('Your order has dishes from Burger Lab');
    const startNew = Array.from(element.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Start new order'),
    )!;
    startNew.click();
    await fixture.whenStable();
    expect(cart.add).toHaveBeenLastCalledWith(expect.objectContaining({ replaceOtherStore: true }));
    expect(close).toHaveBeenCalledWith(true);
  });
});
