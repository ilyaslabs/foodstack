import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatRippleModule } from '@angular/material/core';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';

import { AuthService } from '@foodstack/auth';
import { BrandHeader, Icon, IconName, PushRegistration } from '@foodstack/ui';

import { Session } from '../shared/session';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly icon: IconName;
  readonly exact: boolean;
  /** Shown only with this permission (or any of these); none means everyone. */
  readonly permission?: string | readonly string[];
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
    MatRippleModule,
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
  private readonly push = inject(PushRegistration);

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
      path: '/orders/board',
      label: 'Live orders',
      icon: 'notifications',
      exact: true,
      permission: ['store:portal', 'order:manage'],
    },
    {
      path: '/orders',
      label: 'Orders',
      icon: 'receipt',
      exact: true,
      permission: ['store:portal', 'order:manage'],
    },
    {
      path: '/rider',
      label: 'Deliveries',
      icon: 'rider',
      exact: true,
      permission: 'rider:portal',
    },
    {
      path: '/rider/earnings',
      label: 'Earnings',
      icon: 'payments',
      exact: true,
      permission: 'rider:portal',
    },
    {
      path: '/dispatch',
      label: 'Dispatch',
      icon: 'map',
      exact: true,
      permission: 'dispatch:manage',
    },
    {
      path: '/riders',
      label: 'Riders',
      icon: 'person',
      exact: false,
      permission: 'rider:manage',
    },
    {
      path: '/stores',
      label: 'Stores',
      icon: 'storefront',
      exact: false,
      permission: 'store:manage',
    },
    {
      path: '/price-approvals',
      label: 'Price approvals',
      icon: 'check_circle',
      exact: true,
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
      path: '/cities',
      label: 'Cities',
      icon: 'location_city',
      exact: false,
      permission: 'geo:manage',
    },
    {
      path: '/promotions',
      label: 'Coupons',
      icon: 'local_offer',
      exact: false,
      permission: ['promotion:manage', 'store:portal'],
    },
    {
      path: '/finance',
      label: 'Finance',
      icon: 'account_balance',
      exact: false,
      permission: ['ledger:read', 'ledger:store'],
    },
    {
      path: '/reviews',
      label: 'Reviews',
      icon: 'rate_review',
      exact: false,
      permission: 'review:moderate',
    },
    {
      path: '/users',
      label: 'Users',
      icon: 'group',
      exact: false,
      permission: 'identity:users:read',
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
    {
      path: '/settings/sign-in',
      label: 'Sign-in options',
      icon: 'key',
      exact: false,
      permission: 'settings:manage',
    },
    {
      path: '/settings/ordering',
      label: 'Ordering',
      icon: 'shopping_bag',
      exact: false,
      permission: 'settings:manage',
    },
    {
      path: '/settings/delivery',
      label: 'Rider pay',
      icon: 'delivery',
      exact: false,
      permission: 'settings:manage',
    },
    {
      path: '/settings/messages',
      label: 'Messages',
      icon: 'edit',
      exact: false,
      permission: 'settings:manage',
    },
    {
      path: '/settings/emails',
      label: 'Email log',
      icon: 'mail',
      exact: false,
      permission: ['settings:manage', 'order:manage'],
    },
    {
      path: '/audit',
      label: 'Audit log',
      icon: 'history',
      exact: false,
      permission: 'identity:audit:read',
    },
  ];

  /** Operators see everything they may manage; store owners see their own store. */
  protected readonly nav = computed<NavItem[]>(() => {
    const permissions = this.session.permissions();
    const items = this.allNav.filter(
      (item) =>
        !item.permission ||
        (typeof item.permission === 'string' ? [item.permission] : item.permission).some((p) =>
          permissions.has(p),
        ),
    );
    const payouts = items.findIndex((item) => item.path === '/finance');
    if (payouts >= 0 && !permissions.has('ledger:read')) {
      items[payouts] = { ...items[payouts]!, label: 'Payouts' };
    }
    if (!permissions.has('store:manage') && permissions.has('store:portal')) {
      const storeIds = this.me()?.storeIds ?? [];
      // a brand owner runs every branch of the brand; a branch manager usually one branch
      const brandOwner = (this.me()?.brandIds ?? []).length > 0;
      const single = !brandOwner && storeIds.length === 1;
      items.splice(1, 0, {
        path: single ? `/stores/${storeIds[0]}` : '/stores',
        label: brandOwner ? 'Branches' : single ? 'My store' : 'My stores',
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

  protected async signOut(): Promise<void> {
    // this browser stops getting the person's push messages
    await this.push.disable();
    await this.auth.signOut();
  }
}
