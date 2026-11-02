import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';

import { GeoClient, SavedAddress, SettingsClient } from '@foodstack/api-client';

import { AddressBook } from '../addresses/address-book';
import { LocationStore } from '../location-store';
import { LocationDialog } from './location-dialog';

describe('LocationDialog', () => {
  const home: SavedAddress = {
    id: 'a1',
    label: 'HOME',
    name: null,
    lat: 31.52,
    lng: 74.35,
    addressLine: '12 Main Boulevard',
    instructions: 'Blue gate',
    preferred: true,
    updatedAt: 1,
    version: 0,
  };

  function setUp(addresses: SavedAddress[] | null) {
    const close = vi.fn();
    TestBed.configureTestingModule({
      imports: [LocationDialog],
      providers: [
        { provide: MatDialogRef, useValue: { close } },
        { provide: SettingsClient, useValue: {} },
        { provide: GeoClient, useValue: { searchAddresses: () => of([]), reverse: () => of([]) } },
        {
          provide: AddressBook,
          useValue: { addresses: signal(addresses), load: vi.fn(async () => addresses ?? []) },
        },
      ],
    });
    return { close };
  }

  it('asks where to deliver with the address picker', async () => {
    // given
    setUp(null);

    // when
    const fixture = TestBed.createComponent(LocationDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    // then
    expect(element.querySelector('h2')?.textContent).toContain('Where should we deliver?');
    expect(element.querySelector('app-location-picker')).not.toBeNull();
    expect(element.querySelector('.saved')).toBeNull();
  });

  it('offers saved addresses and uses the one picked, with its address line and notes', async () => {
    // given
    const { close } = setUp([home]);
    const fixture = TestBed.createComponent(LocationDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    // when
    (element.querySelector('.address') as HTMLButtonElement).click();

    // then
    expect(element.querySelector('.saved h3')?.textContent).toContain('Saved addresses');
    const location = TestBed.inject(LocationStore).location();
    expect(location).toEqual(
      expect.objectContaining({
        lat: 31.52,
        addressId: 'a1',
        addressLine: '12 Main Boulevard',
        instructions: 'Blue gate',
      }),
    );
    expect(location?.label).toBe('Home · 12 Main Boulevard');
    expect(close).toHaveBeenCalledWith(true);
  });
});
