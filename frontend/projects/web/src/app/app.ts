import { Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap } from 'rxjs';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';

import { Me, MeClient } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { BrandHeader, Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from './i18n';
import { NotificationBell } from './notifications/notification-bell';

@Component({
  imports: [
    RouterOutlet,
    RouterLink,
    MatButtonModule,
    MatMenuModule,
    BrandHeader,
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
