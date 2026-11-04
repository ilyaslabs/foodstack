import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Lets signed-in users through. An expired token is renewed from the refresh cookie first; only
 * users who are not signed in go to the sign-in page, coming back here afterwards.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  if (auth.signedIn() || (auth.hasSession() && (await auth.renew()))) {
    return true;
  }
  void auth.signIn(state.url);
  return false;
};
