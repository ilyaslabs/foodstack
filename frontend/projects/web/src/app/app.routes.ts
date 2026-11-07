import { Routes } from '@angular/router';

import { authGuard } from '@foodstack/auth';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  {
    path: 'stores/:slug',
    loadComponent: () => import('./stores/store-page').then((m) => m.StorePage),
  },
  {
    path: 'checkout',
    canActivate: [authGuard],
    loadComponent: () => import('./checkout/checkout-page').then((m) => m.CheckoutPage),
  },
  {
    path: 'orders',
    canActivate: [authGuard],
    loadComponent: () => import('./orders/orders-page').then((m) => m.OrdersPage),
  },
  {
    path: 'orders/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./orders/order-page').then((m) => m.OrderPage),
  },
  {
    path: 'pay/:orderId',
    canActivate: [authGuard],
    loadComponent: () => import('./pay/pay-page').then((m) => m.PayPage),
  },
  {
    path: 'account',
    canActivate: [authGuard],
    loadComponent: () => import('./account/account-page').then((m) => m.AccountPage),
  },
  {
    path: 'sign-in',
    loadComponent: () => import('./sign-in/sign-in-page').then((m) => m.SignInPage),
  },
  { path: '**', redirectTo: '' },
];
