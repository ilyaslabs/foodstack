import { RenderMode, ServerRoute } from '@angular/ssr';

export const serverRoutes: ServerRoute[] = [
  // The callback only makes sense in the browser, which holds the PKCE verifier.
  { path: 'auth/callback', renderMode: RenderMode.Client },
  // Rendered per request: branding and content change at runtime, so nothing is prerendered.
  { path: '**', renderMode: RenderMode.Server },
];
