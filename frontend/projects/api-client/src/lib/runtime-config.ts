import { InjectionToken } from '@angular/core';

/**
 * Settings each front-end container serves at `/config.json`, written from environment variables
 * when the container starts, so one image works for every operator (TDD 14.2).
 */
export interface RuntimeConfig {
  /** Base URL of the REST API, without a trailing slash; empty for the same origin. */
  apiUrl: string;
  /** OIDC issuer: the identity module's public URL. */
  issuer: string;
  /** OAuth client id of this app. */
  clientId: string;
}

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('RUNTIME_CONFIG');

/** Removes a trailing slash so URLs can be joined with `${base}/path`. */
export function normalizeConfig(config: RuntimeConfig): RuntimeConfig {
  const trim = (value: string) => (value ?? '').replace(/\/+$/, '');
  return { apiUrl: trim(config.apiUrl), issuer: trim(config.issuer), clientId: config.clientId };
}
