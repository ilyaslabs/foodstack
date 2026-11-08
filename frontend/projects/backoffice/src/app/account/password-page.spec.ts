import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { MeClient } from '@foodstack/api-client';

import { PasswordPage } from './password-page';

describe('PasswordPage', () => {
  async function render(change: () => unknown) {
    const changePassword = vi.fn(change);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PasswordPage],
      providers: [{ provide: MeClient, useValue: { changePassword } }],
    });
    const fixture = TestBed.createComponent(PasswordPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const fill = (current: string, next: string, again: string) => {
      for (const [name, value] of [
        ['current', current],
        ['next', next],
        ['again', again],
      ]) {
        const input = element.querySelector(`input[name=${name}]`) as HTMLInputElement;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      }
    };
    const submit = async () => {
      element.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
      fixture.detectChanges();
    };
    return { element, fill, submit, changePassword };
  }

  it('changes the password and says so', async () => {
    // given
    const { element, fill, submit, changePassword } = await render(() => of(undefined));
    fill('old-password-1', 'new-password-22', 'new-password-22');

    // when
    await submit();

    // then
    expect(changePassword).toHaveBeenCalledWith('old-password-1', 'new-password-22');
    expect(element.querySelector('[role=status]')?.textContent).toContain(
      'Your password is changed',
    );
  });

  it('refuses two different new passwords without calling the server', async () => {
    // given
    const { element, fill, submit, changePassword } = await render(() => of(undefined));
    fill('old-password-1', 'new-password-22', 'new-password-23');

    // when
    await submit();

    // then
    expect(changePassword).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')?.textContent).toContain('not the same');
  });

  it('says when the current password is wrong', async () => {
    // given
    const wrong = new HttpErrorResponse({ status: 400, error: { code: 'PASSWORD_WRONG' } });
    const { element, fill, submit } = await render(() => throwError(() => wrong));
    fill('nope-nope-nope', 'new-password-22', 'new-password-22');

    // when
    await submit();

    // then
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'current password is not correct',
    );
  });
});
