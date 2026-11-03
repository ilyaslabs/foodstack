import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of } from 'rxjs';

import {
  DispatchClient,
  Order,
  OrderChange,
  OrderClient,
  RiderClient,
  StoreClient,
} from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { LocaleStore } from '@foodstack/ui';

import { OrderBoard } from './order-board';

function order(overrides: Partial<Order>): Order {
  const now = Math.floor(Date.now() / 1000);
  return {
    id: 'o1',
    number: '7F3A-91C2',
    status: 'CONFIRMED',
    fulfilment: 'PICKUP',
    deliveryMode: null,
    paymentMethod: 'CASH',
    currency: 'PKR',
    customerId: 'c1',
    customerName: 'Sara',
    contactPhone: null,
    store: {
      storeId: 's1',
      name: 'Karahi Corner',
      slug: null,
      addressLine: null,
      city: null,
      lat: 0,
      lng: 0,
      logoUrls: {},
    },
    destination: null,
    rider: null,
    deliveryCode: null,
    handedBackAt: null,
    lines: [
      {
        itemId: 'i1',
        name: { en: 'Chicken karahi' },
        quantity: 2,
        unitPriceMinor: 125000,
        options: [{ groupName: { en: 'Size' }, name: { en: 'Full' }, priceDeltaMinor: 40000 }],
        lineTotalMinor: 250000,
        note: 'Less spicy',
      },
    ],
    totals: {
      subtotalMinor: 250000,
      itemsDiscountMinor: 0,
      deliveryChargeMinor: 0,
      deliveryDiscountMinor: 0,
      serviceFeeMinor: 5000,
      taxMinor: 0,
      tipMinor: 0,
      totalMinor: 255000,
    },
    coupon: null,
    prepMinutes: 20,
    acceptBy: now + 125,
    readyBy: null,
    estimatedAt: null,
    paymentUrl: null,
    paidMinor: 0,
    refundRequestedMinor: 0,
    refundedMinor: 0,
    timeline: [],
    closeReason: null,
    placedAt: now - 60,
    revision: 1,
    actions: ['ACCEPT', 'REJECT', 'CANCEL'],
    ...overrides,
  };
}

describe('OrderBoard', () => {
  async function render(
    board: () => Order[],
    act = vi.fn(),
    accepted: { minutes: number; storeRiderId: string | null } = {
      minutes: 30,
      storeRiderId: null,
    },
  ) {
    localStorage.clear();
    const changes = new Subject<OrderChange>();
    const boardCall = vi.fn(() => of(board()));
    TestBed.configureTestingModule({
      imports: [OrderBoard],
      providers: [
        {
          provide: OrderClient,
          useValue: {
            board: boardCall,
            watchStore: () => changes.asObservable(),
            act,
            cancel: vi.fn(),
          },
        },
        {
          provide: StoreClient,
          useValue: {
            list: () =>
              of([
                {
                  id: 's1',
                  brandId: 'b1',
                  brandName: 'Karahi Corner',
                  branchName: 'Model Town',
                  city: 'Lahore',
                  status: 'LIVE',
                  temporarilyClosed: false,
                  deliveryMode: 'SELF',
                  createdAt: null,
                  logoUrls: {},
                },
              ]),
          },
        },
        {
          provide: RiderClient,
          useValue: {
            storeRiders: () =>
              of([{ id: 'r1', name: 'Usman', vehicle: 'MOTORBIKE', online: true }]),
          },
        },
        { provide: DispatchClient, useValue: { assignStoreRider: vi.fn() } },
        { provide: AuthService, useValue: { freshToken: async () => 'token' } },
        {
          provide: LocaleStore,
          useValue: {
            pick: (t: Record<string, string>) => t['en'],
            money: (minor: number) => `Rs ${minor / 100}`,
          },
        },
      ],
    });
    // the component imports MatDialogModule, so its own MatDialog provider must be replaced
    TestBed.overrideProvider(MatDialog, {
      useValue: { open: () => ({ afterClosed: () => of(accepted) }) },
    });
    const fixture = TestBed.createComponent(OrderBoard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement, changes, boardCall, act };
  }

  it('shows a new order with its countdown, dishes and notes in the New column', async () => {
    // when
    const { element } = await render(() => [order({})]);

    // then
    const fresh = element.querySelector('[data-column="new"]')!;
    expect(fresh.textContent).toContain('#7F3A-91C2');
    expect(fresh.textContent).toContain('Chicken karahi');
    expect(fresh.textContent).toContain('Full');
    expect(fresh.textContent).toContain('Less spicy');
    expect(fresh.textContent).toMatch(/2:0\d/);
    expect(fresh.textContent).toContain('Cash Rs 2550');
    expect(element.querySelector('[role=alert]')?.textContent).toContain('1 new order is waiting');
  });

  it('accepts with the chosen minutes and moves the order to the kitchen', async () => {
    // given
    const accepted = order({
      status: 'ACCEPTED',
      actions: ['PREPARING', 'READY'],
      readyBy: Math.floor(Date.now() / 1000) + 1800,
    });
    const act = vi.fn(() => of(accepted));
    const { fixture, element } = await render(() => [order({})], act);
    vi.spyOn(window, 'print').mockImplementation(() => undefined);

    // when
    const accept = Array.from(element.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Accept',
    )!;
    accept.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(act).toHaveBeenCalledWith('o1', 'ACCEPT', { prepMinutes: 30, storeRiderId: null });
    expect(element.querySelector('[data-column="new"]')?.textContent).toContain('Nothing here');
    expect(element.querySelector('[data-column="kitchen"]')?.textContent).toContain('#7F3A-91C2');
  });

  it('sends the store rider chosen when accepting a delivery the branch makes itself', async () => {
    // given
    const act = vi.fn(() => of(order({ status: 'ACCEPTED', actions: ['PREPARING', 'READY'] })));
    const delivery = order({ fulfilment: 'DELIVERY', deliveryMode: 'SELF' });
    const { fixture, element } = await render(() => [delivery], act, {
      minutes: 20,
      storeRiderId: 'r1',
    });
    vi.spyOn(window, 'print').mockImplementation(() => undefined);

    // when
    Array.from(element.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Accept')!
      .click();
    await fixture.whenStable();

    // then
    expect(act).toHaveBeenCalledWith('o1', 'ACCEPT', { prepMinutes: 20, storeRiderId: 'r1' });
  });

  it('reloads the board when the live stream says something changed', async () => {
    // given
    let orders = [order({})];
    const { fixture, element, changes, boardCall } = await render(() => orders);
    orders = [order({}), order({ id: 'o2', number: 'AAAA-0002' })];

    // when
    changes.next({ orderId: 'o2', status: 'CONFIRMED' });
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(boardCall).toHaveBeenCalledTimes(2);
    expect(element.textContent).toContain('#AAAA-0002');
    expect(element.textContent).toContain('Live');
  });
});
