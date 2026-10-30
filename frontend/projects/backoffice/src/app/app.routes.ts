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
        path: 'settings/branding',
        loadComponent: () => import('./settings/branding-page').then((m) => m.BrandingPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
