import { Routes } from '@angular/router';

import { authGuard } from '@foodstack/auth';

import { riderHome } from './rider/rider-home';
import { Shell } from './shell/shell';

export const routes: Routes = [
  {
    path: 'sign-in',
    loadComponent: () => import('./sign-in/sign-in-page').then((m) => m.SignInPage),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        canActivate: [riderHome],
        loadComponent: () => import('./dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'rider',
        loadComponent: () => import('./rider/rider-page').then((m) => m.RiderPage),
      },
      {
        path: 'rider/earnings',
        loadComponent: () => import('./rider/rider-earnings-page').then((m) => m.RiderEarningsPage),
      },
      {
        path: 'riders',
        loadComponent: () => import('./riders/riders-page').then((m) => m.RidersPage),
      },
      {
        path: 'riders/:id',
        loadComponent: () => import('./riders/rider-detail-page').then((m) => m.RiderDetailPage),
      },
      {
        path: 'dispatch',
        loadComponent: () => import('./riders/dispatch-page').then((m) => m.DispatchPage),
      },
      {
        path: 'settings/delivery',
        loadComponent: () =>
          import('./settings/delivery-settings-page').then((m) => m.DeliverySettingsPage),
      },
      {
        path: 'stores',
        loadComponent: () => import('./stores/stores-page').then((m) => m.StoresPage),
      },
      {
        path: 'stores/new',
        loadComponent: () => import('./stores/onboard-page').then((m) => m.OnboardPage),
      },
      {
        path: 'price-approvals',
        loadComponent: () =>
          import('./stores/price-approvals-page').then((m) => m.PriceApprovalsPage),
      },
      {
        path: 'stores/:id',
        loadComponent: () => import('./stores/store-page').then((m) => m.StorePage),
      },
      {
        path: 'orders/board',
        loadComponent: () => import('./orders/order-board').then((m) => m.OrderBoard),
      },
      {
        path: 'orders',
        loadComponent: () => import('./orders/orders-page').then((m) => m.OrdersPage),
      },
      {
        path: 'promotions',
        loadComponent: () => import('./promotions/promotions-page').then((m) => m.PromotionsPage),
      },
      {
        path: 'settings/ordering',
        loadComponent: () => import('./settings/ordering-page').then((m) => m.OrderingPage),
      },
      {
        path: 'audit',
        loadComponent: () => import('./settings/audit-page').then((m) => m.AuditPage),
      },
      {
        path: 'settings/emails',
        loadComponent: () => import('./settings/emails-page').then((m) => m.EmailsPage),
      },
      {
        path: 'delivery-pricing',
        loadComponent: () =>
          import('./delivery/delivery-pricing-page').then((m) => m.DeliveryPricingPage),
      },
      {
        path: 'cities',
        loadComponent: () => import('./delivery/cities-page').then((m) => m.CitiesPage),
      },
      {
        path: 'users',
        loadComponent: () => import('./users/users-page').then((m) => m.UsersPage),
      },
      {
        path: 'settings/locale',
        loadComponent: () => import('./settings/locale-page').then((m) => m.LocalePage),
      },
      {
        path: 'settings/branding',
        loadComponent: () => import('./settings/branding-page').then((m) => m.BrandingPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
