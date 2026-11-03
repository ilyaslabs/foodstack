import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { DeviceClient, PushConfig } from '@foodstack/api-client';

/** Where this browser stands on push messages. */
export type PushState = 'unavailable' | 'ask' | 'on' | 'denied';

const TOKEN_KEY = 'foodstack.push-token';

/**
 * Push messages in the browser (TDD 8.3): when the server has FCM set up and the person allows
 * notifications, the browser gets an FCM token through the app's service worker (sw.js) and the
 * server keeps it for the signed-in person. The Firebase SDK loads only then. Off ("unavailable")
 * when the server has no FCM, the browser has no push, or on the server rendering pass.
 */
@Injectable({ providedIn: 'root' })
export class PushRegistration {
  private readonly devices = inject(DeviceClient);

  readonly state = signal<PushState>('unavailable');

  private config: PushConfig | null = null;

  /** Checks support and, when notifications are already allowed, registers silently. */
  async init(app: 'web' | 'backoffice'): Promise<PushState> {
    if (!supported()) return this.set('unavailable');
    try {
      this.config = await firstValueFrom(this.devices.pushConfig());
    } catch {
      return this.set('unavailable');
    }
    if (!this.config.enabled || !this.config.firebase || !this.config.vapidKey) {
      return this.set('unavailable');
    }
    if (Notification.permission === 'denied') return this.set('denied');
    if (Notification.permission === 'granted') {
      return (await this.register(app)) ? this.set('on') : this.set('ask');
    }
    return this.set('ask');
  }

  /** Asks the person (call from a tap) and registers when they allow it. */
  async enable(app: 'web' | 'backoffice'): Promise<PushState> {
    if (!this.config) await this.init(app);
    if (this.state() === 'unavailable' || this.state() === 'on') return this.state();
    const answer = await Notification.requestPermission();
    if (answer !== 'granted') return this.set(answer === 'denied' ? 'denied' : 'ask');
    return (await this.register(app)) ? this.set('on') : this.set('ask');
  }

  /** Stops pushes to this browser, as on sign-out. */
  async disable(): Promise<void> {
    const token = readToken();
    if (!token) return;
    try {
      await firstValueFrom(this.devices.remove(token));
    } catch {
      // the server forgets unused tokens by itself
    }
    writeToken(null);
  }

  private async register(app: 'web' | 'backoffice'): Promise<boolean> {
    const config = this.config!;
    try {
      const registration = await navigator.serviceWorker.register(
        new URL('sw.js', document.baseURI).href,
      );
      const [{ initializeApp, getApps }, { getMessaging, getToken }] = await Promise.all([
        import('firebase/app'),
        import('firebase/messaging'),
      ]);
      const firebase = getApps()[0] ?? initializeApp(config.firebase!);
      const token = await getToken(getMessaging(firebase), {
        vapidKey: config.vapidKey!,
        serviceWorkerRegistration: registration,
      });
      if (!token) return false;
      await firstValueFrom(this.devices.register(token, app));
      writeToken(token);
      return true;
    } catch {
      return false;
    }
  }

  private set(state: PushState): PushState {
    this.state.set(state);
    return state;
  }
}

function supported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'Notification' in window &&
    'serviceWorker' in navigator &&
    'PushManager' in window
  );
}

function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function writeToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // not kept
  }
}
