import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from './auth.service';
import { SignIn } from './sign-in';

describe('SignIn', () => {
  let auth: {
    loginWithPassword: ReturnType<typeof vi.fn>;
    requestCode: ReturnType<typeof vi.fn>;
    loginWithCode: ReturnType<typeof vi.fn>;
    completeTwoStep: ReturnType<typeof vi.fn>;
    startEnrollment: ReturnType<typeof vi.fn>;
    confirmEnrollment: ReturnType<typeof vi.fn>;
  };

  function render() {
    auth = {
      loginWithPassword: vi.fn().mockResolvedValue(undefined),
      requestCode: vi.fn().mockResolvedValue(undefined),
      loginWithCode: vi.fn().mockResolvedValue(undefined),
      completeTwoStep: vi.fn().mockResolvedValue(undefined),
      startEnrollment: vi.fn().mockResolvedValue({
        secret: 'JBSWY3DPEHPK3PXP',
        uri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP',
      }),
      confirmEnrollment: vi.fn().mockResolvedValue(['abcde-fghjk', 'mnpqr-stuvw']),
    };
    TestBed.configureTestingModule({
      imports: [SignIn],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(SignIn);
    fixture.detectChanges();
    return fixture;
  }

  function type(element: HTMLElement, selector: string, value: string) {
    const input = element.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('signs in with a password and goes back where the user was', async () => {
    // given
    const fixture = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const element = fixture.nativeElement as HTMLElement;

    // when
    type(element, 'input[type=email]', 'a@example.com');
    type(element, 'input[type=password]', 'correct horse');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();

    // then
    expect(auth.loginWithPassword).toHaveBeenCalledWith('a@example.com', 'correct horse');
    expect(navigate).toHaveBeenCalledWith('/', { replaceUrl: true });
  });

  it('shows a clear message when the password is wrong', async () => {
    // given
    const fixture = render();
    auth.loginWithPassword.mockRejectedValue(
      new HttpErrorResponse({ status: 401, error: { code: 'LOGIN_FAILED' } }),
    );
    const element = fixture.nativeElement as HTMLElement;

    // when
    type(element, 'input[type=email]', 'a@example.com');
    type(element, 'input[type=password]', 'wrong');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(element.querySelector('[role=alert]')?.textContent).toContain(
      'That email and password do not match.',
    );
  });

  it('emails a code, then signs in with it', async () => {
    // given
    const fixture = render();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const element = fixture.nativeElement as HTMLElement;
    const useCode = [...element.querySelectorAll<HTMLButtonElement>('nav button')].find((b) =>
      b.textContent?.includes('code'),
    )!;
    useCode.click();
    fixture.detectChanges();

    // when
    type(element, 'input[type=email]', 'a@example.com');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    type(element, 'input[autocomplete=one-time-code]', '123456');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();

    // then
    expect(auth.requestCode).toHaveBeenCalledWith('a@example.com', 'LOGIN');
    expect(auth.loginWithCode).toHaveBeenCalledWith('a@example.com', '123456');
  });

  async function signInWithPassword(fixture: ReturnType<typeof render>) {
    const element = fixture.nativeElement as HTMLElement;
    type(element, 'input[type=email]', 'admin@example.com');
    type(element, 'input[type=password]', 'correct horse');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('asks for the authenticator code when the account has two-step sign-in', async () => {
    // given
    const fixture = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    auth.loginWithPassword.mockResolvedValue({ next: 'TOTP', challenge: 'ch', expiresIn: 300 });
    const element = fixture.nativeElement as HTMLElement;

    // when
    await signInWithPassword(fixture);
    expect(navigate).not.toHaveBeenCalled();
    expect(element.querySelector('h1')?.textContent).toContain('Two-step sign-in');
    type(element, 'input[autocomplete=one-time-code]', '654321');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();

    // then
    expect(auth.completeTwoStep).toHaveBeenCalledWith('ch', '654321');
    expect(navigate).toHaveBeenCalledWith('/', { replaceUrl: true });
  });

  it('sets up the authenticator app when the role requires it, then shows the recovery codes', async () => {
    // given
    const fixture = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    auth.loginWithPassword.mockResolvedValue({
      next: 'TOTP_ENROLL',
      challenge: 'ch',
      expiresIn: 300,
    });
    const element = fixture.nativeElement as HTMLElement;

    // when
    await signInWithPassword(fixture);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('fs-qr-code svg path')?.getAttribute('d')).toMatch(/^M/);
    expect(element.textContent).toContain('JBSW Y3DP EHPK 3PXP');
    type(element, 'input[autocomplete=one-time-code]', '123456');
    element.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(auth.startEnrollment).toHaveBeenCalledWith('ch');
    expect(auth.confirmEnrollment).toHaveBeenCalledWith('ch', '123456');
    expect(element.querySelector('h1')?.textContent).toContain('Save your recovery codes');
    expect(element.querySelectorAll('.codes li')).toHaveLength(2);
    expect(navigate).not.toHaveBeenCalled();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.includes('continue'))!
      .click();
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/', { replaceUrl: true });
  });
});
