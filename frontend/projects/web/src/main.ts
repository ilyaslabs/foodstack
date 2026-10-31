import { bootstrapApplication } from '@angular/platform-browser';

import { RUNTIME_CONFIG, loadRuntimeConfig } from '@foodstack/api-client';

import { appConfig } from './app/app.config';
import { App } from './app/app';

loadRuntimeConfig('/config.json')
  .then((config) =>
    bootstrapApplication(App, {
      ...appConfig,
      providers: [...appConfig.providers, { provide: RUNTIME_CONFIG, useValue: config }],
    }),
  )
  .catch((err) => console.error(err));
