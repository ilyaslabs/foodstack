import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { shareReplay } from 'rxjs';

import { MeClient } from '@foodstack/api-client';

/** The signed-in user, read once per page load, with the permission checks the screens need. */
@Injectable({ providedIn: 'root' })
export class Session {
  readonly me$ = inject(MeClient).me().pipe(shareReplay(1));
  readonly me = toSignal(this.me$);

  readonly permissions = computed(() => new Set(this.me()?.permissions ?? []));
  /** Operator staff who onboard and manage every store. */
  readonly operator = computed(() => this.permissions().has('store:manage'));
  /** Store owners and staff, limited to their own branches. */
  readonly storePortal = computed(() => this.permissions().has('store:portal'));

  can(permission: string): boolean {
    return this.permissions().has(permission);
  }
}
