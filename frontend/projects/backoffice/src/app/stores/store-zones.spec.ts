import { Component, input, output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { GeoClient, LatLng, Zone } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { StoreContext } from './store-context';
import { StoreZones } from './store-zones';
import { ZoneMap } from './zones/zone-map';

/** Stands in for the MapLibre map, which needs WebGL. */
@Component({ selector: 'app-zone-map', template: '' })
class FakeZoneMap {
  readonly branch = input<LatLng | null>(null);
  readonly zones = input<{ polygon: LatLng[] }[]>([]);
  readonly selected = input(-1);
  readonly changed = output<LatLng[]>();
}

const BRANCH = { lat: 31.512, lng: 74.344 };
const SQUARE: LatLng[] = [
  { lat: 31.5, lng: 74.33 },
  { lat: 31.5, lng: 74.36 },
  { lat: 31.53, lng: 74.36 },
  { lat: 31.53, lng: 74.33 },
];
const ZONE: Zone = {
  name: 'Gulberg',
  center: null,
  radiusMeters: null,
  polygon: SQUARE,
  chargeMinor: 15000,
  minOrderMinor: 50000,
  freeDeliveryAboveMinor: null,
};

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('StoreZones', () => {
  async function render(zones: Zone[] = [ZONE]) {
    const replaceZones = vi.fn((_: string, saved: Zone[]) => of(saved));
    const open = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [StoreZones],
      providers: [
        { provide: GeoClient, useValue: { zones: () => of(zones), replaceZones } },
        { provide: MatSnackBar, useValue: { open } },
        {
          provide: StoreContext,
          useValue: {
            store: signal({ id: 's1', deliveryMode: 'SELF', location: BRANCH }),
            refreshReadiness: vi.fn(() => Promise.resolve()),
          },
        },
        { provide: LocaleStore, useValue: { currency: () => 'PKR' } },
      ],
    });
    TestBed.overrideComponent(StoreZones, {
      remove: { imports: [ZoneMap] },
      add: { imports: [FakeZoneMap] },
    });
    const fixture = TestBed.createComponent(StoreZones);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    const map = () =>
      fixture.debugElement.query((el) => el.componentInstance instanceof FakeZoneMap)
        .componentInstance as FakeZoneMap;
    return { fixture, replaceZones, open, map, element: fixture.nativeElement as HTMLElement };
  }

  function click(element: HTMLElement, label: string): void {
    const button = [...element.querySelectorAll('button')].find((b) =>
      b.textContent?.includes(label),
    );
    button!.click();
  }

  it('shows saved zones on the map with the first one ready to edit', async () => {
    // when
    const { element, map } = await render();

    // then
    expect(map().zones()[0].polygon).toEqual(SQUARE);
    expect(map().selected()).toBe(0);
    expect(map().branch()).toEqual(BRANCH);
    expect(element.textContent).toContain('Editing Gulberg');
    expect(element.textContent).toContain('4 corners');
  });

  it('saves the drawn outline as a polygon with its charges in minor units', async () => {
    // given
    const { fixture, element, map, replaceZones, open } = await render();
    const reshaped = [...SQUARE, { lat: 31.515, lng: 74.32 }];

    // when
    map().changed.emit(reshaped);
    fixture.detectChanges();
    click(element, 'Save zones');
    await settle();

    // then
    expect(replaceZones).toHaveBeenCalledWith('s1', [
      {
        name: 'Gulberg',
        center: null,
        radiusMeters: null,
        polygon: reshaped,
        chargeMinor: 15000,
        minOrderMinor: 50000,
        freeDeliveryAboveMinor: null,
      },
    ]);
    expect(open).toHaveBeenCalledWith('Delivery zones saved.', undefined, { duration: 4000 });
  });

  it('refuses to save an outline that crosses itself', async () => {
    // given
    const { fixture, element, map, replaceZones } = await render();

    // when
    map().changed.emit([SQUARE[0], SQUARE[2], SQUARE[1], SQUARE[3]]);
    fixture.detectChanges();
    click(element, 'Save zones');
    await settle();
    fixture.detectChanges();

    // then
    expect(replaceZones).not.toHaveBeenCalled();
    expect(element.textContent).toContain('Two edges of this zone cross');
  });

  it('starts a new zone as a circle around the branch and warns when the branch is outside', async () => {
    // given
    const far = SQUARE.map((p) => ({ lat: p.lat + 1, lng: p.lng }));
    const { fixture, element, map, replaceZones, open } = await render([{ ...ZONE, polygon: far }]);

    // when
    click(element, 'Add zone');
    fixture.detectChanges();

    // then
    expect(map().selected()).toBe(1);
    expect(map().zones()[1].polygon).toHaveLength(16);
    expect(element.textContent).toContain('Editing Zone 2');

    // when
    click(element, 'Clear shape');
    fixture.detectChanges();
    expect(element.textContent).toContain('Click the map to draw this zone');
    click(element, 'Start from a circle');
    fixture.detectChanges();
    click(element, 'Save zones');
    await settle();

    // then
    expect(replaceZones).toHaveBeenCalled();
    expect(open).toHaveBeenCalledWith('Delivery zones saved.', undefined, { duration: 4000 });
    expect(replaceZones.mock.calls[0][1][1].polygon).toHaveLength(16);
  });

  it('warns when no zone covers the branch itself', async () => {
    // given
    const far = SQUARE.map((p) => ({ lat: p.lat + 1, lng: p.lng }));
    const { element, open } = await render([{ ...ZONE, polygon: far }]);

    // when
    click(element, 'Save zones');
    await settle();

    // then
    expect(open).toHaveBeenCalledWith(
      'Saved. Note: the branch itself is outside every zone.',
      undefined,
      {
        duration: 4000,
      },
    );
  });
});
