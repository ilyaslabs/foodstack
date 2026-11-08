import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { UserAccount, UsersClient } from '@foodstack/api-client';

import { NewUserDialog } from './new-user-dialog';

const MADE: UserAccount = {
  id: 'u9',
  email: 'farah@example.com',
  displayName: 'Farah Khan',
  roles: ['FINANCE'],
  permissions: [],
  status: 'ACTIVE',
  createdAt: 1_762_000_000,
};

describe('NewUserDialog', () => {
  async function render(create: () => unknown) {
    const close = vi.fn();
    const createSpy = vi.fn(create);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [NewUserDialog],
      providers: [
        { provide: UsersClient, useValue: { create: createSpy } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    const fixture = TestBed.createComponent(NewUserDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const type = (name: string, value: string) => {
      const input = element.querySelector(`input[name=${name}]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    const submit = async () => {
      element.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
      fixture.detectChanges();
    };
    return { fixture, element, type, submit, close, create: createSpy };
  }

  it('makes a staff account with the chosen role and closes with it', async () => {
    // given
    const { fixture, element, type, submit, close, create } = await render(() => of(MADE));
    type('name', ' Farah Khan ');
    type('email', 'farah@example.com');
    type('password', 'first-pass-123');
    const finance = Array.from(element.querySelectorAll('mat-radio-button')).find((r) =>
      r.textContent?.includes('Finance'),
    );
    (finance!.querySelector('input') as HTMLInputElement).click();
    fixture.detectChanges();

    // when
    await submit();

    // then
    expect(create).toHaveBeenCalledWith({
      email: 'farah@example.com',
      password: 'first-pass-123',
      displayName: 'Farah Khan',
      roles: ['FINANCE'],
    });
    expect(close).toHaveBeenCalledWith(MADE);
  });

  it('refuses a short first password without calling the server', async () => {
    // given
    const { element, type, submit, create } = await render(() => of(MADE));
    type('name', 'Sam');
    type('email', 'sam@example.com');
    type('password', 'short');

    // when
    await submit();

    // then
    expect(create).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain('at least 10 characters');
  });

  it('says when the email already has an account', async () => {
    // given
    const taken = new HttpErrorResponse({ status: 409, error: { code: 'EMAIL_TAKEN' } });
    const { element, type, submit, close } = await render(() => throwError(() => taken));
    type('name', 'Sam');
    type('email', 'sam@example.com');
    type('password', 'first-pass-123');

    // when
    await submit();

    // then
    expect(close).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain('already exists');
  });
});
