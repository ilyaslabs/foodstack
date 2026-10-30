import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';

import { RUNTIME_CONFIG, normalizeConfig } from '@foodstack/api-client';

import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';

/**
 * During server rendering the API is reached on the internal network
 * (FOODSTACK_INTERNAL_API_URL), not through the public URL.
 */
const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    {
      provide: RUNTIME_CONFIG,
      useFactory: () =>
        normalizeConfig({
          apiUrl: process.env['FOODSTACK_INTERNAL_API_URL'] ?? 'http://localhost:8080',
          issuer: process.env['FOODSTACK_ISSUER'] ?? '',
          clientId: 'foodstack-web',
        }),
    },
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
