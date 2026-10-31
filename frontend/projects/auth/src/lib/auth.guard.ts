import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';

import { AuthService } from './auth.service';

/** Lets signed-in users through and sends everyone else to sign in, coming back here afterwards. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.signedIn()) {
    return true;
  }
  void auth.signIn(state.url);
  return false;
};
