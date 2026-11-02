import { Component, afterNextRender, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap } from 'rxjs';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';

import { Me, MeClient } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { BrandingStore, Icon, LocaleStore } from '@foodstack/ui';

import { CartStore } from './cart/cart-store';
import { Ui } from './i18n';
import { LocationDialog } from './location/location-dialog';
import { LocationStore } from './location-store';
import { NotificationBell } from './notifications/notification-bell';

@Component({
  imports: [
    RouterOutlet,
    RouterLink,
    MatButtonModule,
    MatDialogModule,
    MatMenuModule,
    Icon,
    NotificationBell,
  ],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly locale = inject(LocaleStore);
  protected readonly ui = inject(Ui);
  protected readonly branding = inject(BrandingStore);
  protected readonly cart = inject(CartStore);
  protected readonly locations = inject(LocationStore);

  constructor() {
    // The header shows the saved address on every page, not only on those that use it.
    afterNextRender(() => this.locations.restore());
  }
  protected readonly year = new Date().getFullYear();

  private readonly dialog = inject(MatDialog);
  private readonly meClient = inject(MeClient);

  /** Offered only when the operator enabled more than one language. */
  protected readonly languages = computed(() => this.locale.languages());

  /** The signed-in customer, for the account menu. */
  protected readonly me = toSignal(
    toObservable(computed(() => this.auth.signedIn())).pipe(
      switchMap((signedIn) =>
        signedIn ? this.meClient.me().pipe(catchError(() => of(null))) : of(null),
      ),
    ),
    { initialValue: null as Me | null },
  );

  protected readonly initials = computed(() => {
    const letters = (this.me()?.displayName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join('');
    return letters || '•';
  });

  protected readonly brandInitial = computed(() =>
    (this.branding.branding().name || '•').charAt(0).toUpperCase(),
  );

  protected readonly firstName = computed(() => (this.me()?.displayName ?? '').split(/\s+/)[0]);

  /** What the header shows as the delivery address. */
  protected readonly locationLabel = computed(() => {
    const location = this.locations.location();
    if (!location) return this.ui.t('chooseLocation');
    return location.label || this.ui.t('currentLocation');
  });

  protected pickLocation(): void {
    this.dialog.open(LocationDialog, {
      width: '560px',
      maxWidth: 'calc(100vw - 32px)',
      autoFocus: 'input',
      panelClass: 'fs-location-dialog',
    });
  }

  protected signIn(): void {
    void this.auth.signIn(this.here());
  }

  protected signUp(): void {
    void this.auth.signIn(this.here(), { signUp: true });
  }

  /** Back to the same page after signing in. */
  private here(): string {
    return typeof location === 'undefined' ? '/' : location.pathname + location.search;
  }

  protected signOut(): void {
    this.auth.signOut();
  }
}
