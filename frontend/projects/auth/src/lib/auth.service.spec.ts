import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { AuthService } from './auth.service';
import { BrowserNavigation } from './browser-navigation';

describe('AuthService', () => {
  let assigned: string[];

  beforeEach(() => {
    assigned = [];
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: RUNTIME_CONFIG,
          useValue: {
            apiUrl: '',
            issuer: 'https://food.example',
            clientId: 'foodstack-backoffice',
          },
        },
        { provide: BrowserNavigation, useValue: { assign: (url: string) => assigned.push(url) } },
      ],
    });
  });

  it('sends the browser to the authorize endpoint with a PKCE challenge', async () => {
    // when
    await TestBed.inject(AuthService).signIn('/settings/branding');

    // then
    const url = new URL(assigned[0]);
    expect(url.origin + url.pathname).toBe('https://food.example/oauth2/authorize');
    expect(url.searchParams.get('client_id')).toBe('foodstack-backoffice');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('redirect_uri')).toMatch(/auth\/callback$/);
    expect(url.searchParams.get('code_challenge')).toBeTruthy();
  });

  it('exchanges the code for a token and returns to the page the user wanted', async () => {
    // given
    const auth = TestBed.inject(AuthService);
    await auth.signIn('/settings/branding');
    const state = new URL(assigned[0]).searchParams.get('state')!;

    // when
    const done = auth.completeSignIn(new URLSearchParams({ code: 'the-code', state }));
    const request = TestBed.inject(HttpTestingController).expectOne(
      'https://food.example/oauth2/token',
    );
    request.flush({ access_token: 'token-1', expires_in: 600 });

    // then
    expect(await done).toBe('/settings/branding');
    expect(request.request.body).toContain('grant_type=authorization_code');
    expect(request.request.body).toContain('code_verifier=');
    expect(auth.signedIn()).toBe(true);
    expect(auth.accessToken()).toBe('token-1');
  });

  it('refuses a callback whose state does not match', async () => {
    // given
    const auth = TestBed.inject(AuthService);
    await auth.signIn('/');

    // then
    await expect(
      auth.completeSignIn(new URLSearchParams({ code: 'c', state: 'forged' })),
    ).rejects.toThrow();
    expect(auth.signedIn()).toBe(false);
  });

  async function signedIn(auth: AuthService): Promise<void> {
    await auth.signIn('/');
    const state = new URL(assigned.pop()!).searchParams.get('state')!;
    const done = auth.completeSignIn(new URLSearchParams({ code: 'first', state }));
    TestBed.inject(HttpTestingController)
      .expectOne('https://food.example/oauth2/token')
      .flush({ access_token: 'token-1', id_token: 'id-1', expires_in: 600 });
    await done;
  }

  it('renews in the background through the identity session, keeping the page', async () => {
    // given
    const auth = TestBed.inject(AuthService);
    await signedIn(auth);
    const fetch = vi.spyOn(window, 'fetch').mockImplementation(async (input) => {
      const state = new URL(String(input)).searchParams.get('state');
      return { url: `${auth.redirectUri()}?code=again&state=${state}` } as Response;
    });

    // when
    const renewal = auth.renew();
    // PKCE hashing and the fetch resolve over several turns, so wait for the token request
    const request = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne('https://food.example/oauth2/token'),
    );
    request.flush({ access_token: 'token-2', expires_in: 600 });

    // then
    expect(await renewal).toBe(true);
    expect(request.request.body).toContain('code=again');
    expect(auth.accessToken()).toBe('token-2');
    expect(assigned).toEqual([]);
    fetch.mockRestore();
  });

  it('reports a failed renewal when the identity session has ended', async () => {
    // given
    const auth = TestBed.inject(AuthService);
    await signedIn(auth);
    const fetch = vi
      .spyOn(window, 'fetch')
      .mockResolvedValue({ url: 'https://food.example/login' } as Response);

    // then
    expect(await auth.renew()).toBe(false);
    expect(auth.accessToken()).toBe('token-1');
    fetch.mockRestore();
  });

  it('treats a token as expired once its time passes', async () => {
    // given
    const auth = TestBed.inject(AuthService);
    await signedIn(auth);
    vi.useFakeTimers({ toFake: ['Date'] });

    // when
    vi.setSystemTime(Date.now() + 601_000);

    // then
    expect(auth.signedIn()).toBe(false);
    expect(auth.accessToken()).toBeNull();
    expect(auth.hasSession()).toBe(true);
    expect(auth.expiresSoon()).toBe(true);
    vi.useRealTimers();
  });
});
