import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { RUNTIME_CONFIG } from '@foodstack/api-client';

import { AuthService } from './auth.service';

/** Adds the access token to API requests only, never to object storage or other hosts. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const config = inject(RUNTIME_CONFIG);
  const token = inject(AuthService).accessToken();
  const isApi = request.url.startsWith(`${config.apiUrl}/api/`);
  if (!token || !isApi) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
