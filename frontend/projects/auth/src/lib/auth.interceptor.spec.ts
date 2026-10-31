import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: RUNTIME_CONFIG, useValue: { apiUrl: '', issuer: '', clientId: 'x' } },
        { provide: AuthService, useValue: { accessToken: () => 'token-1' } },
      ],
    });
  });

  it('adds the token to API calls', () => {
    TestBed.inject(HttpClient).get('/api/v1/me').subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('never sends the token to object storage', () => {
    TestBed.inject(HttpClient)
      .put('https://storage.example/bucket/key?X-Amz-Signature=s', 'bytes')
      .subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(() => true);
    expect(request.request.headers.has('Authorization')).toBe(false);
  });
});
