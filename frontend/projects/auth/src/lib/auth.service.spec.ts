import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { AuthService } from './auth.service';
import { BrowserNavigation } from './browser-navigation';

/** Lets awaited calls inside the service move on. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('AuthService', () => {
  let assigned: string[];

  function setUp() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: RUNTIME_CONFIG, useValue: { apiUrl: 'https://food.example' } },
        { provide: BrowserNavigation, useValue: { assign: (url: string) => assigned.push(url) } },
      ],
    });
    return TestBed.inject(AuthService);
  }

  beforeEach(() => {
    assigned = [];
    localStorage.clear();
  });

  it('signs in with a password as a web client, keeping the token in memory only', async () => {
    // given
    const auth = setUp();

    // when
    const done = auth.loginWithPassword('a@example.com', 'correct horse');
    const request = TestBed.inject(HttpTestingController).expectOne(
      'https://food.example/api/v1/auth/login',
    );
    request.flush({
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expiresIn: 600,
      refreshExpiresAt: 1,
    });
    await done;

    // then
    expect(request.request.body).toEqual({
      client: 'web',
      email: 'a@example.com',
      password: 'correct horse',
    });
    expect(request.request.withCredentials).toBe(true);
    expect(auth.signedIn()).toBe(true);
    expect(auth.accessToken()).toBe('token-1');
    expect(JSON.stringify(localStorage)).not.toContain('token-1');
    expect(JSON.stringify(sessionStorage)).not.toContain('token-1');
  });

  it('after a reload, gets a new access token from the refresh cookie', async () => {
    // given: this browser signed in before
    localStorage.setItem('foodstack.auth.signedIn', '1');

    // when
    const auth = setUp();
    expect(auth.hasSession()).toBe(true);
    const request = TestBed.inject(HttpTestingController).expectOne(
      'https://food.example/api/v1/auth/refresh',
    );
    request.flush({
      accessToken: 'token-2',
      tokenType: 'Bearer',
      expiresIn: 600,
      refreshExpiresAt: 1,
    });
    await settle();

    // then
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.body).toEqual({});
    expect(auth.accessToken()).toBe('token-2');
  });

  it('retries when another tab refreshed at the same moment', async () => {
    // given
    const auth = setUp();
    const http = TestBed.inject(HttpTestingController);

    // when
    const renewed = auth.renew();
    await settle();
    http
      .expectOne('https://food.example/api/v1/auth/refresh')
      .flush({ code: 'REFRESH_RACE' }, { status: 409, statusText: 'Conflict' });
    await new Promise((resolve) => setTimeout(resolve, 350));
    http
      .expectOne('https://food.example/api/v1/auth/refresh')
      .flush({ accessToken: 'token-3', tokenType: 'Bearer', expiresIn: 600, refreshExpiresAt: 1 });

    // then
    expect(await renewed).toBe(true);
    expect(auth.accessToken()).toBe('token-3');
  });

  it('forgets the sign-in when the refresh token is refused', async () => {
    // given
    localStorage.setItem('foodstack.auth.signedIn', '1');
    const auth = setUp();

    // when
    TestBed.inject(HttpTestingController)
      .expectOne('https://food.example/api/v1/auth/refresh')
      .flush({ code: 'REFRESH_REUSED' }, { status: 401, statusText: 'Unauthorized' });
    await settle();

    // then
    expect(auth.hasSession()).toBe(false);
    expect(localStorage.getItem('foodstack.auth.signedIn')).toBeNull();
  });

  it('opens the sign-in page with the way back', async () => {
    // given
    const auth = setUp();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    // when
    await auth.signIn('/checkout', { signUp: true });

    // then
    expect(navigate).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { returnTo: '/checkout', mode: 'signup' },
    });
  });

  it('signs out on the server, then reloads the app signed out', async () => {
    // given
    const auth = setUp();
    const http = TestBed.inject(HttpTestingController);
    const login = auth.loginWithCode('a@example.com', '123456');
    http
      .expectOne('https://food.example/api/v1/auth/login')
      .flush({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, refreshExpiresAt: 1 });
    await login;

    // when
    const done = auth.signOut();
    const logout = http.expectOne('https://food.example/api/v1/auth/logout');
    logout.flush(null, { status: 204, statusText: 'No Content' });
    await done;

    // then
    expect(logout.request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(auth.hasSession()).toBe(false);
    expect(assigned).toHaveLength(1);
  });
});
