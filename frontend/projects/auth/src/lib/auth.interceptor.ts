import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, of, switchMap, throwError, catchError } from 'rxjs';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { AuthService } from './auth.service';

/**
 * Adds the access token to API requests only, never to object storage or other hosts. A token that
 * is about to run out is renewed first, and a 401 renews once and retries, so a save made after a
 * long time on one form still goes through. When renewal fails the user signs in again and comes
 * back to the same page.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const config = inject(RUNTIME_CONFIG);
  const auth = inject(AuthService);
  const isApi = request.url.startsWith(`${config.apiUrl}/api/`);
  if (!isApi || !auth.hasSession()) {
    return next(request);
  }
  const send = () => next(withToken(request, auth.accessToken()));
  const ready = auth.expiresSoon() ? from(auth.renew()) : of(true);
  return ready.pipe(
    switchMap(() => send()),
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return from(auth.renew()).pipe(
        switchMap((renewed) => {
          if (renewed) return send();
          void auth.signIn(auth.currentPath());
          return throwError(() => error);
        }),
      );
    }),
  );
};

function withToken<T>(request: HttpRequest<T>, token: string | null): HttpRequest<T> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
