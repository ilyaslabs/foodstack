import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';

import { GeoClient, SettingsClient } from '@foodstack/api-client';

import { LocationDialog } from './location-dialog';

describe('LocationDialog', () => {
  it('asks where to deliver with the address picker', async () => {
    // given
    TestBed.configureTestingModule({
      imports: [LocationDialog],
      providers: [
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: SettingsClient, useValue: {} },
        { provide: GeoClient, useValue: { searchAddresses: () => of([]), reverse: () => of([]) } },
      ],
    });

    // when
    const fixture = TestBed.createComponent(LocationDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    // then
    expect(element.querySelector('h2')?.textContent).toContain('Where should we deliver?');
    expect(element.querySelector('app-location-picker')).not.toBeNull();
  });
});
