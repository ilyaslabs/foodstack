import { Routes } from '@angular/router';

import { AuthCallback } from '@foodstack/auth';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  { path: 'auth/callback', component: AuthCallback },
  { path: '**', redirectTo: '' },
];
