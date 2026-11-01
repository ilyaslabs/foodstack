import { Routes } from '@angular/router';

import { AuthCallback, authGuard } from '@foodstack/auth';

import { Shell } from './shell/shell';

export const routes: Routes = [
  { path: 'auth/callback', component: AuthCallback },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./dashboard/dashboard').then((m) => m.Dashboard) },
      {
        path: 'stores',
        loadComponent: () => import('./stores/stores-page').then((m) => m.StoresPage),
      },
      {
        path: 'stores/new',
        loadComponent: () => import('./stores/onboard-page').then((m) => m.OnboardPage),
      },
      {
        path: 'stores/:id',
        loadComponent: () => import('./stores/store-page').then((m) => m.StorePage),
      },
      {
        path: 'delivery-pricing',
        loadComponent: () =>
          import('./delivery/delivery-pricing-page').then((m) => m.DeliveryPricingPage),
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
