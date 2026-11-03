import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { Session } from '../shared/session';

/** People who only ride start on their deliveries instead of the overview. */
export const riderHome: CanActivateFn = async () => {
  const router = inject(Router);
  try {
    const me = await firstValueFrom(inject(Session).me$);
    const permissions = new Set(me.permissions ?? []);
    const onlyRides =
      permissions.has('rider:portal') &&
      !permissions.has('store:portal') &&
      !permissions.has('store:manage') &&
      !permissions.has('order:manage');
    return onlyRides ? router.parseUrl('/rider') : true;
  } catch {
    return true;
  }
};
