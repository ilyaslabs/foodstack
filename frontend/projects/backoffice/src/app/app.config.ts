import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { authInterceptor } from '@foodstack/auth';
import { BrandingStore, LocaleStore } from '@foodstack/ui';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    provideAppInitializer(() => inject(BrandingStore).load(true)),
    // The back office stays English and left to right; the languages are for editing content.
    provideAppInitializer(() => inject(LocaleStore).load(false, true)),
  ],
};
