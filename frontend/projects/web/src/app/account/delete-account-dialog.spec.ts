import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { MeClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { DeleteAccountDialog } from './delete-account-dialog';

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('DeleteAccountDialog', () => {
  function render(requestDeletion: () => unknown) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DeleteAccountDialog],
      providers: [
        { provide: MeClient, useValue: { requestDeletion } },
        { provide: MAT_DIALOG_DATA, useValue: { graceDays: 14 } },
        { provide: MatDialogRef, useValue: { close: vi.fn(), disableClose: false } },
        { provide: LocaleStore, useValue: { language: () => 'en', direction: () => 'ltr' } },
      ],
    });
    const fixture = TestBed.createComponent(DeleteAccountDialog);
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('explains the grace period, then shows the deletion date', async () => {
    // given
    const request = vi.fn(() => of({ deleteAfter: 1_795_255_200, graceDays: 14 }));
    const { fixture, element } = render(request);
    expect(element.textContent).toContain('In 14 days');

    // when
    (element.querySelector('button.danger') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    // then
    expect(request).toHaveBeenCalled();
    expect(element.textContent).toContain('Your account will be deleted on');
    expect(element.textContent).toContain('2026');
  });

  it('tells back-office accounts to ask the operator', async () => {
    // given
    const { fixture, element } = render(() =>
      throwError(
        () => new HttpErrorResponse({ status: 409, error: { code: 'ACCOUNT_HAS_STAFF_ROLES' } }),
      ),
    );

    // when
    (element.querySelector('button.danger') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    // then
    expect(element.querySelector('[role=alert]')?.textContent).toContain('Ask the operator');
  });
});
