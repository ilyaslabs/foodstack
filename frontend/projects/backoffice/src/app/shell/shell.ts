import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';

import { MeClient } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { BrandHeader, Icon, IconName } from '@foodstack/ui';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon: IconName;
  readonly exact: boolean;
}

/** The signed-in frame: brand header, account menu, navigation and the current page. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatDividerModule,
    MatListModule,
    MatMenuModule,
    MatSidenavModule,
    BrandHeader,
    Icon,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly auth = inject(AuthService);

  protected readonly me = toSignal(inject(MeClient).me());

  /** Below 960 px the navigation becomes a drawer opened from the menu button. */
  protected readonly compact = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  protected readonly nav: readonly NavItem[] = [
    { path: '/', label: 'Overview', icon: 'dashboard', exact: true },
    { path: '/settings/branding', label: 'Branding', icon: 'palette', exact: false },
  ];

  protected readonly initials = computed(() => {
    const name = this.me()?.displayName ?? '';
    const letters = name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]!.toUpperCase())
      .join('');
    return letters || '?';
  });

  protected readonly role = computed(() =>
    (this.me()?.roles ?? [])
      .map((role) => role.charAt(0) + role.slice(1).toLowerCase().replace(/_/g, ' '))
      .join(', '),
  );

  protected signOut(): void {
    this.auth.signOut();
  }
}
