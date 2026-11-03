import { Injectable, NgZone, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationPoint, RiderClient } from '@foodstack/api-client';

/** How often points go to the server while online (PRD DR-2). */
export const SEND_EVERY_MS = 10_000;

/** Points kept while the phone has no signal; the server takes at most this many at once. */
const QUEUE_LIMIT = 120;

const QUEUE_KEY = 'foodstack.rider.pending-locations';

/**
 * Shares the rider's position while they are online: the browser reports positions as the rider
 * moves, the latest one is sent every 10 seconds, and points taken without a signal wait in the
 * browser (up to 120) and go out together when the signal returns.
 */
@Injectable({ providedIn: 'root' })
export class RiderLocation {
  private readonly riders = inject(RiderClient);
  private readonly zone = inject(NgZone);

  /** The latest position the phone gave, or null before the first fix. */
  readonly position = signal<LocationPoint | null>(null);
  /** Why there is no position: the person refused, or the phone cannot tell. */
  readonly problem = signal<'denied' | 'unavailable' | null>(null);
  /** Points waiting for a signal. */
  readonly pending = signal(0);

  private watchId: number | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastSent: LocationPoint | null = null;
  private sending = false;

  constructor() {
    this.pending.set(readQueue().length);
  }

  get supported(): boolean {
    return typeof navigator !== 'undefined' && 'geolocation' in navigator;
  }

  /** Asks the phone once for where the rider is, for going online. */
  current(): Promise<LocationPoint | null> {
    if (!this.supported) return Promise.resolve(null);
    return new Promise((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (p) => {
          const point = toPoint(p);
          this.position.set(point);
          this.problem.set(null);
          resolve(point);
        },
        (error) => {
          this.problem.set(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable');
          resolve(null);
        },
        { enableHighAccuracy: true, timeout: 15_000, maximumAge: 30_000 },
      );
    });
  }

  start(): void {
    if (!this.supported || this.watchId !== null) return;
    this.watchId = navigator.geolocation.watchPosition(
      (p) =>
        this.zone.run(() => {
          this.position.set(toPoint(p));
          this.problem.set(null);
        }),
      (error) =>
        this.zone.run(() =>
          this.problem.set(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable'),
        ),
      { enableHighAccuracy: true, maximumAge: 5_000, timeout: 30_000 },
    );
    this.timer = setInterval(() => void this.tick(), SEND_EVERY_MS);
    if (typeof window !== 'undefined') window.addEventListener('online', this.flushNow);
  }

  stop(): void {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (typeof window !== 'undefined') window.removeEventListener('online', this.flushNow);
  }

  private readonly flushNow = (): void => void this.tick();

  private async tick(): Promise<void> {
    const latest = this.position();
    const queue = readQueue();
    if (latest) {
      // a rider standing still gets no new fix; the same point again says they are still there
      const now = Math.floor(Date.now() / 1000);
      queue.push({ ...latest, at: latest === this.lastSent ? now : (latest.at ?? now) });
      this.lastSent = latest;
    }
    if (queue.length === 0 || this.sending) {
      writeQueue(queue);
      return;
    }
    const batch = queue.slice(-QUEUE_LIMIT);
    this.sending = true;
    try {
      await firstValueFrom(this.riders.sendLocations(batch));
      writeQueue([]);
    } catch (error) {
      // 4xx other than a rate limit means the points will never be taken: drop them
      const status = (error as { status?: number }).status ?? 0;
      writeQueue(status >= 400 && status < 500 && status !== 429 ? [] : batch);
    } finally {
      this.sending = false;
      this.pending.set(readQueue().length);
    }
  }
}

function toPoint(p: GeolocationPosition): LocationPoint {
  return {
    lat: Math.round(p.coords.latitude * 1e6) / 1e6,
    lng: Math.round(p.coords.longitude * 1e6) / 1e6,
    accuracyMeters: Number.isFinite(p.coords.accuracy) ? Math.round(p.coords.accuracy) : null,
    at: Math.floor(p.timestamp / 1000),
  };
}

function readQueue(): LocationPoint[] {
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    const parsed = raw ? (JSON.parse(raw) as LocationPoint[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeQueue(points: LocationPoint[]): void {
  try {
    if (points.length === 0) localStorage.removeItem(QUEUE_KEY);
    else localStorage.setItem(QUEUE_KEY, JSON.stringify(points.slice(-QUEUE_LIMIT)));
  } catch {
    // storage unavailable: points are simply not kept
  }
}
