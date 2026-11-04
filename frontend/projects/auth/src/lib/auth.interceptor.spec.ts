import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

/** Lets the interceptor's awaited renewal finish. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('authInterceptor', () => {
  let token: string;
  let auth: {
    accessToken: () => string;
    hasSession: () => boolean;
    expiresSoon: () => boolean;
    renew: ReturnType<typeof vi.fn>;
    signIn: ReturnType<typeof vi.fn>;
    currentPath: () => string;
  };

  function setUp(renewed = true, expiresSoon = false) {
    token = 'token-1';
    auth = {
      accessToken: () => token,
      hasSession: () => true,
      expiresSoon: () => expiresSoon,
      renew: vi.fn(async () => {
        if (renewed) token = 'token-2';
        return renewed;
      }),
      signIn: vi.fn(),
      currentPath: () => '/stores/s1',
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: RUNTIME_CONFIG, useValue: { apiUrl: '' } },
        { provide: AuthService, useValue: auth },
      ],
    });
  }

  it('adds the token to API calls', () => {
    setUp();
    TestBed.inject(HttpClient).get('/api/v1/me').subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('never sends the token to object storage', () => {
    setUp();
    TestBed.inject(HttpClient)
      .put('https://storage.example/bucket/key?X-Amz-Signature=s', 'bytes')
      .subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(() => true);
    expect(request.request.headers.has('Authorization')).toBe(false);
  });

  it('renews a token that is about to run out before sending', async () => {
    // given
    setUp(true, true);

    // when
    TestBed.inject(HttpClient).post('/api/v1/brands/b1/menu/items', {}).subscribe();
    await settle();

    // then
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/brands/b1/menu/items');
    expect(auth.renew).toHaveBeenCalledTimes(1);
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-2');
  });

  it('renews once and retries when the server answers 401', async () => {
    // given
    setUp();
    const http = TestBed.inject(HttpTestingController);
    let body: unknown;
    TestBed.inject(HttpClient)
      .post('/api/v1/brands/b1/menu/items', { name: 'Soup' })
      .subscribe((response) => (body = response));

    // when
    http
      .expectOne('/api/v1/brands/b1/menu/items')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();
    const retry = http.expectOne('/api/v1/brands/b1/menu/items');
    retry.flush({ id: 'i1' });

    // then
    expect(retry.request.headers.get('Authorization')).toBe('Bearer token-2');
    expect(retry.request.body).toEqual({ name: 'Soup' });
    expect(body).toEqual({ id: 'i1' });
    expect(auth.signIn).not.toHaveBeenCalled();
  });

  it('sends the user to sign in, back to the same page, when renewal fails', async () => {
    // given
    setUp(false);
    let status = 0;
    TestBed.inject(HttpClient)
      .get('/api/v1/me')
      .subscribe({ error: (error) => (status = error.status) });

    // when
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();

    // then
    expect(status).toBe(401);
    expect(auth.signIn).toHaveBeenCalledWith('/stores/s1');
  });
});
