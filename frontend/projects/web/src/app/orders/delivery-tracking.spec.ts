import { Component, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';

import { Order, RiderClient, Tracking } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { FsMap, LocaleStore, MapMarker } from '@foodstack/ui';

import { DeliveryTracking } from './delivery-tracking';

/** Stands in for the MapLibre map, which needs WebGL. */
@Component({ selector: 'fs-map', template: '' })
class FakeMap {
  readonly markers = input<MapMarker[]>([]);
  readonly route = input<{ lat: number; lng: number }[]>([]);
  readonly fit = input(false);
  readonly label = input('');
}

function order(overrides: Partial<Order>): Order {
  return {
    id: 'o1',
    status: 'ACCEPTED',
    fulfilment: 'DELIVERY',
    deliveryMode: 'OPERATOR',
    store: { storeId: 's1', name: 'Spice Route' },
    rider: { riderId: 'r1', name: 'Bilal', vehicle: 'MOTORBIKE', assignedAt: 0 },
    deliveryCode: '3173',
    handedBackAt: null,
    ...overrides,
  } as Order;
}

function tracking(overrides: Partial<Tracking>): Tracking {
  return {
    orderId: 'o1',
    riderName: 'Bilal',
    vehicle: 'MOTORBIKE',
    pickedUp: false,
    lat: 31.514,
    lng: 74.348,
    at: 0,
    pickupLat: 31.513,
    pickupLng: 74.346,
    dropoffLat: 31.52,
    dropoffLng: 74.358,
    etaSeconds: 900,
    remainingMeters: 2400,
    ...overrides,
  };
}

describe('DeliveryTracking', () => {
  async function render(o: Order, t: Tracking) {
    TestBed.configureTestingModule({
      imports: [DeliveryTracking],
      providers: [
        { provide: RiderClient, useValue: { tracking: () => of(t), watchTracking: () => NEVER } },
        { provide: AuthService, useValue: { freshToken: async () => 'token' } },
        { provide: LocaleStore, useValue: { language: signal('en') } },
      ],
    });
    TestBed.overrideComponent(DeliveryTracking, {
      remove: { imports: [FsMap] },
      add: { imports: [FakeMap] },
    });
    const fixture = TestBed.createComponent(DeliveryTracking);
    fixture.componentRef.setInput('order', o);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('before pickup shows the rider heading to the restaurant, the code, and no arrival time', async () => {
    // when
    const element = await render(order({}), tracking({}));

    // then
    expect(element.textContent).toContain('Bilal');
    expect(element.textContent).toContain('Spice Route');
    expect(element.querySelector('.eta')).toBeNull();
    expect(element.querySelector('.digits')?.textContent).toBe('3173');
    expect(element.querySelector('fs-map')).not.toBeNull();
  });

  it('after pickup shows the arrival time and the distance left', async () => {
    // when
    const element = await render(
      order({ status: 'PICKED_UP' }),
      tracking({ pickedUp: true, etaSeconds: 600, remainingMeters: 1500 }),
    );

    // then
    expect(element.querySelector('.eta')?.textContent).toContain('10');
    expect(element.textContent).toContain('1.5');
  });

  it('says the store delivers itself when there is no operator rider', async () => {
    // when
    const element = await render(
      order({ rider: null, deliveryMode: 'SELF', deliveryCode: null }),
      tracking({}),
    );

    // then
    expect(element.textContent).toContain('Spice Route');
    expect(element.querySelector('fs-map')).toBeNull();
    expect(element.querySelector('.digits')).toBeNull();
  });
});
