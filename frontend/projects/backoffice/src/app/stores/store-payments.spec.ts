import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { CardCollection, MerchantAccount, PaymentClient, StoreClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { Session } from '../shared/session';
import { StoreContext } from './store-context';
import { StorePayments } from './store-payments';

const BRAND_ACCOUNT: MerchantAccount = {
  id: 'ma-1',
  scope: 'BRAND',
  ownerId: 'b1',
  gateway: 'sandbox',
  keyHint: '…1234',
  status: 'ACTIVE',
  lastError: null,
  verifiedAt: 1_762_000_000,
};

function collection(change: Partial<CardCollection> = {}): CardCollection {
  return {
    storeId: 's1',
    brandId: 'b1',
    enabled: true,
    gateway: 'sandbox',
    mode: 'DIRECT',
    suspended: false,
    storeAccount: null,
    brandAccount: BRAND_ACCOUNT,
    collectedBy: 'STORE',
    accountId: 'ma-1',
    storeWebhookUrl: null,
    brandWebhookUrl: null,
    canConnectStore: false,
    canConnectBrand: false,
    ...change,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('StorePayments', () => {
  async function render(card: CardCollection, permissions: string[]) {
    const setPaymentCollection = vi.fn(() =>
      of({ value: { id: 's1', brand: { id: 'b1' } }, etag: '"store-2"' }),
    );
    const open = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [StorePayments],
      providers: [
        { provide: PaymentClient, useValue: { collection: () => of(card) } },
        { provide: StoreClient, useValue: { setPaymentCollection } },
        { provide: MatSnackBar, useValue: { open } },
        { provide: Session, useValue: { can: (p: string) => permissions.includes(p) } },
        {
          provide: StoreContext,
          useValue: {
            store: signal({
              id: 's1',
              brand: { id: 'b1' },
              paymentCollection: {
                mode: 'DIRECT',
                creditLimitMinor: null,
                reason: 'Own Stripe',
                changedBy: 'u1',
                changedAt: 1_762_000_000,
              },
            }),
            set: vi.fn(),
          },
        },
        { provide: LocaleStore, useValue: { currency: () => 'PKR' } },
      ],
    });
    const fixture = TestBed.createComponent(StorePayments);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement, setPaymentCollection };
  }

  it("says card payments go to the brand's account", async () => {
    // when
    const { element } = await render(collection(), ['payment:account']);

    // then
    expect(element.querySelector('.status')?.textContent).toContain(
      "Card payments go to the brand's account",
    );
    expect(element.textContent).not.toContain('Who collects');
  });

  it('shows a suspended branch as collected by the operator', async () => {
    // when
    const { element } = await render(
      collection({ suspended: true, collectedBy: 'OPERATOR', accountId: null }),
      ['store:payment-collection:manage'],
    );

    // then
    expect(element.querySelector('.status')?.getAttribute('data-state')).toBe('suspended');
    expect(element.textContent).toContain('Suspended: over the credit limit');
  });

  it('saves the credit limit in minor units with the reason', async () => {
    // given
    const { fixture, element, setPaymentCollection } = await render(collection(), [
      'store:payment-collection:manage',
    ]);
    const limit = element.querySelector('input[name=creditLimit]') as HTMLInputElement;
    const reason = element.querySelector('input[name=reason]') as HTMLInputElement;
    limit.value = '25000';
    limit.dispatchEvent(new Event('input'));
    reason.value = 'Raised after review';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // when
    (element.querySelector('form button[type=submit]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // then
    expect(setPaymentCollection).toHaveBeenCalledWith(
      's1',
      'DIRECT',
      2_500_000,
      'Raised after review',
    );
  });
});
