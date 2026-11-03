import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Subscription, firstValueFrom } from 'rxjs';

import { Order, RiderClient, Tracking } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { FsMap, Icon, MapMarker } from '@foodstack/ui';

import { Ui, UiKey } from '../i18n';

/** ETA is asked again this often while the rider moves; positions arrive live in between. */
const ETA_EVERY_MS = 45_000;

/**
 * The rider on the way (PRD TR-1 to TR-3): who is coming, where they are on the map, about when
 * they arrive, and the delivery code to give them at the door.
 */
@Component({
  selector: 'app-delivery-tracking',
  imports: [FsMap, Icon],
  template: `
    @let o = order();
    <section class="card tracking" aria-live="polite">
      @if (o.rider; as rider) {
        <div class="rider">
          <span class="avatar" aria-hidden="true"><fs-icon name="rider" /></span>
          <div class="who">
            <strong>{{ ui.t('riderBringing', { name: rider.name }) }}</strong>
            <span>{{ vehicle(rider.vehicle) }}{{ stage() ? ' · ' + stage() : '' }}</span>
          </div>
          @if (eta(); as text) {
            <span class="eta">{{ text }}</span>
          }
        </div>
        @if (tracking(); as t) {
          <fs-map
            class="map"
            [markers]="markers()"
            [route]="route()"
            [fit]="true"
            [label]="ui.t('trackingMap')"
          />
        }
      } @else if (o.deliveryMode === 'SELF' || o.handedBackAt) {
        <p class="plain">
          <fs-icon name="storefront" /> {{ ui.t('storeDeliversItself', { store: o.store.name }) }}
        </p>
      } @else if (o.status !== 'CONFIRMED') {
        <p class="plain"><fs-icon name="rider" /> {{ ui.t('riderFinding') }}</p>
      }
      @if (o.deliveryCode) {
        <div class="code">
          <div>
            <span class="code-label">{{ ui.t('deliveryCode') }}</span>
            <span class="code-hint">{{ ui.t('deliveryCodeHint') }}</span>
          </div>
          <span class="digits" dir="ltr">{{ o.deliveryCode }}</span>
        </div>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
      margin-block-end: 24px;
    }
    .tracking {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 24px;
      border-radius: var(--fs-radius-card);
      background: var(--mat-sys-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      box-shadow: var(--fs-shadow-card);
    }
    .rider {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .avatar {
      display: grid;
      place-items: center;
      flex: none;
      inline-size: 48px;
      block-size: 48px;
      border-radius: 50%;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .who {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-inline-size: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .who strong {
      font: var(--mat-sys-title-medium);
      color: var(--mat-sys-on-surface);
    }
    .eta {
      flex: none;
      padding: 6px 12px;
      border-radius: 999px;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      font: var(--mat-sys-label-large);
    }
    .map {
      block-size: 280px;
      border-radius: 16px;
      overflow: hidden;
    }
    .plain {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      font: var(--mat-sys-body-large);
    }
    .code {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 16px;
      border-radius: 16px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .code div {
      display: flex;
      flex-direction: column;
    }
    .code-label {
      font: var(--mat-sys-title-small);
    }
    .code-hint {
      font: var(--mat-sys-body-small);
    }
    .digits {
      font: var(--mat-sys-display-small);
      letter-spacing: 0.2em;
      font-variant-numeric: tabular-nums;
    }
    @media (max-width: 599.98px) {
      .tracking {
        padding: 20px 16px;
      }
      .rider {
        flex-wrap: wrap;
      }
      .eta {
        margin-inline-start: 60px;
      }
      .map {
        block-size: 240px;
      }
    }
  `,
})
export class DeliveryTracking {
  readonly order = input.required<Order>();

  protected readonly ui = inject(Ui);
  private readonly riders = inject(RiderClient);
  private readonly auth = inject(AuthService);

  protected readonly tracking = signal<Tracking | null>(null);
  private stream: Subscription | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private followed: string | null = null;

  protected readonly markers = computed<MapMarker[]>(() => {
    const t = this.tracking();
    if (!t) return [];
    const markers: MapMarker[] = [
      { id: 'home', lat: t.dropoffLat, lng: t.dropoffLng, kind: 'home', label: this.ui.t('deliveringTo') },
    ];
    if (!t.pickedUp) {
      markers.push({ id: 'store', lat: t.pickupLat, lng: t.pickupLng, kind: 'store', label: this.order().store.name });
    }
    if (t.lat !== null && t.lng !== null) {
      markers.push({ id: 'rider', lat: t.lat, lng: t.lng, kind: 'rider-busy', label: t.riderName });
    }
    return markers;
  });

  protected readonly route = computed(() => {
    const t = this.tracking();
    if (!t || t.lat === null || t.lng === null) return [];
    const points = [{ lat: t.lat, lng: t.lng }];
    if (!t.pickedUp) points.push({ lat: t.pickupLat, lng: t.pickupLng });
    points.push({ lat: t.dropoffLat, lng: t.dropoffLng });
    return points;
  });

  /** Only once the food is on its way; before that the order's own time (kitchen first) applies. */
  protected readonly eta = computed(() => {
    if (!this.tracking()?.pickedUp) return null;
    const seconds = this.tracking()?.etaSeconds;
    if (seconds === null || seconds === undefined) return null;
    const minutes = Math.round(seconds / 60);
    return minutes <= 1 ? this.ui.t('arrivingNow') : this.ui.t('arrivesIn', { n: this.ui.number(minutes) });
  });

  protected readonly stage = computed(() => {
    const o = this.order();
    const t = this.tracking();
    if (o.status === 'PICKED_UP' || t?.pickedUp) {
      return t?.remainingMeters
        ? this.ui.t('kmAway', { n: this.ui.number(t.remainingMeters / 1000, 1) })
        : '';
    }
    return this.ui.t('riderToStore', { store: o.store.name });
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
    effect(() => {
      const o = this.order();
      untracked(() => {
        const riderId = o.rider?.riderId ?? null;
        const key = riderId ? `${o.id}:${riderId}:${o.status}` : null;
        if (!key) {
          this.stop();
          return;
        }
        if (key !== this.followed) {
          this.followed = key;
          void this.start(o.id);
        }
      });
    });
  }

  protected vehicle(vehicle: string): string {
    const key = `vehicle${vehicle}` as UiKey;
    return this.ui.t(key);
  }

  private async start(orderId: string): Promise<void> {
    this.stop();
    await this.refresh(orderId);
    this.stream = this.riders
      .watchTracking(orderId, () => this.auth.freshToken())
      .subscribe({
        next: (m) => {
          const t = this.tracking();
          if (t && m.lat !== undefined && m.lng !== undefined) {
            this.tracking.set({ ...t, lat: m.lat, lng: m.lng, at: m.at ?? t.at });
          }
        },
        error: () => undefined,
      });
    this.timer = setInterval(() => void this.refresh(orderId), ETA_EVERY_MS);
  }

  private stop(): void {
    this.stream?.unsubscribe();
    this.stream = null;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private async refresh(orderId: string): Promise<void> {
    try {
      this.tracking.set(await firstValueFrom(this.riders.tracking(orderId)));
    } catch {
      // no position yet: the map waits for one
    }
  }
}
