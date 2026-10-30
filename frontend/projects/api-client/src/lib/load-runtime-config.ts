import { RuntimeConfig, normalizeConfig } from './runtime-config';

/** Reads `config.json` next to the app (relative to the base href) before the app boots. */
export async function loadRuntimeConfig(url = 'config.json'): Promise<RuntimeConfig> {
  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`Could not load ${url}: ${response.status}`);
  }
  return normalizeConfig((await response.json()) as RuntimeConfig);
}
