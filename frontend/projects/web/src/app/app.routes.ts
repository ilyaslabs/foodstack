import { Routes } from '@angular/router';

import { AuthCallback } from '@foodstack/auth';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  {
    path: 'stores/:slug',
    loadComponent: () => import('./stores/store-page').then((m) => m.StorePage),
  },
  { path: 'auth/callback', component: AuthCallback },
  { path: '**', redirectTo: '' },
];
