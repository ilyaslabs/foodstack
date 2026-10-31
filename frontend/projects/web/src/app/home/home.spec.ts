import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { AuthService } from '@foodstack/auth';
import { BrandingStore, DEFAULT_BRANDING } from '@foodstack/ui';

import { Home } from './home';

describe('Home', () => {
  function render(signedIn: boolean) {
    const signIn = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [
        {
          provide: BrandingStore,
          useValue: {
            branding: signal({
              ...DEFAULT_BRANDING,
              name: 'Spice Route',
              tagline: 'Dinner nearby',
            }),
          },
        },
        { provide: AuthService, useValue: { signedIn: signal(signedIn), signIn } },
      ],
    });
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    return { fixture, signIn };
  }

  it('shows the operator brand and a sign-in button', () => {
    const { fixture, signIn } = render(false);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Spice Route');
    expect(fixture.nativeElement.textContent).toContain('Dinner nearby');
    fixture.nativeElement.querySelector('button').click();
    expect(signIn).toHaveBeenCalledWith('/');
  });

  it('greets a signed-in customer', () => {
    const { fixture } = render(true);
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
