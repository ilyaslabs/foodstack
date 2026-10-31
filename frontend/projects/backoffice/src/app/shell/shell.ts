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

import { AuthService } from '@foodstack/auth';
import { BrandHeader, Icon, IconName } from '@foodstack/ui';

import { Session } from '../shared/session';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon: IconName;
  readonly exact: boolean;
  /** Shown only with this permission; none means everyone. */
  readonly permission?: string;
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

  private readonly session = inject(Session);
  protected readonly me = this.session.me;

  /** Below 960 px the navigation becomes a drawer opened from the menu button. */
  protected readonly compact = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  private readonly allNav: readonly NavItem[] = [
    { path: '/', label: 'Overview', icon: 'dashboard', exact: true },
    {
      path: '/stores',
      label: 'Stores',
      icon: 'storefront',
      exact: false,
      permission: 'store:manage',
    },
    {
      path: '/delivery-pricing',
      label: 'Delivery pricing',
      icon: 'delivery',
      exact: false,
      permission: 'geo:manage',
    },
    {
      path: '/settings/locale',
      label: 'Languages',
      icon: 'language',
      exact: false,
      permission: 'settings:manage',
    },
    {
      path: '/settings/branding',
      label: 'Branding',
      icon: 'palette',
      exact: false,
      permission: 'settings:manage',
    },
  ];

  /** Operators see everything they may manage; store owners see their own store. */
  protected readonly nav = computed<NavItem[]>(() => {
    const permissions = this.session.permissions();
    const items = this.allNav.filter(
      (item) => !item.permission || permissions.has(item.permission),
    );
    if (!permissions.has('store:manage') && permissions.has('store:portal')) {
      const storeIds = this.me()?.storeIds ?? [];
      items.splice(1, 0, {
        path: storeIds.length === 1 ? `/stores/${storeIds[0]}` : '/stores',
        label: storeIds.length === 1 ? 'My store' : 'My stores',
        icon: 'storefront',
        exact: false,
      });
    }
    return items;
  });

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
