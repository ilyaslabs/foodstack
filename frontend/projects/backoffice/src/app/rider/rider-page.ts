import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AuthService } from '@foodstack/auth';
import { DeliveryJob, DispatchClient, Earnings, Rider, RiderClient } from '@foodstack/api-client';
import { FsMap, Icon, LocaleStore, MapMarker, PushRegistration, formatMoney } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { DeliveredDialog } from './delivered-dialog';
import { RiderLocation } from './rider-location';

/** The steps of a job as the rider sees them. */
const STEPS = [
  { status: 'ASSIGNED', label: 'To the store' },
  { status: 'AT_STORE', label: 'At the store' },
  { status: 'PICKED_UP', label: 'To the customer' },
] as const;

const VEHICLES: Record<string, string> = {
  BICYCLE: 'Bicycle',
  MOTORBIKE: 'Motorbike',
  SCOOTER: 'Scooter',
  CAR: 'Car',
};

/**
 * The rider's screen (PRD DR-2 to DR-7), made for a phone: going online, offers with a countdown,
 * the steps of each delivery with directions, handing the order over, and today's earnings.
 */
@Component({
  selector: 'app-rider-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    FsMap,
    Icon,
  ],
  templateUrl: './rider-page.html',
  styleUrls: ['../shared/page.scss', './rider-page.scss'],
})
export class RiderPage implements OnInit {
  private readonly riders = inject(RiderClient);
  private readonly dispatch = inject(DispatchClient);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly locale = inject(LocaleStore);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly location = inject(RiderLocation);
  protected readonly push = inject(PushRegistration);

  protected readonly me = signal<Rider | null>(null);
  protected readonly jobs = signal<DeliveryJob[]>([]);
  protected readonly earnings = signal<Earnings | null>(null);
  protected readonly loadError = signal(false);
  protected readonly switching = signal(false);
  protected readonly busy = signal<string | null>(null);
  /** Ticks every second while an offer is open, for its countdown. */
  protected readonly now = signal(Date.now() / 1000);

  protected readonly steps = STEPS;
  protected readonly vehicles = VEHICLES;

  protected readonly offers = computed(() => this.jobs().filter((j) => j.status === 'OFFERED'));
  protected readonly active = computed(() => this.jobs().filter((j) => j.status !== 'OFFERED'));

  private readonly seenOffers = new Set<string>();
  /** How long each offer had when it first showed, for the countdown bar. */
  private readonly offerTotals = new Map<string, number>();
  private ticker: ReturnType<typeof setInterval> | null = null;

  async ngOnInit(): Promise<void> {
    try {
      const [me, jobs] = await Promise.all([
        firstValueFrom(this.riders.me()),
        firstValueFrom(this.dispatch.myJobs()),
      ]);
      this.me.set(me);
      this.setJobs(jobs);
      if (me.online) this.location.start();
    } catch {
      this.loadError.set(true);
      return;
    }
    void this.loadEarnings();
    void this.push.init('backoffice');
    const changes = this.dispatch
      .watchMine(() => this.auth.freshToken())
      .subscribe({ next: () => void this.reload() });
    this.ticker = setInterval(() => this.now.set(Date.now() / 1000), 1000);
    this.destroyRef.onDestroy(() => {
      changes.unsubscribe();
      if (this.ticker) clearInterval(this.ticker);
      this.location.stop();
    });
  }

  protected async toggleOnline(online: boolean): Promise<void> {
    this.switching.set(true);
    try {
      const at = online ? await this.location.current() : null;
      if (online && !at && this.location.problem() === 'denied') {
        this.snackBar.open('Allow location for this site to go online.', 'OK', { duration: 6000 });
        return;
      }
      const me = await firstValueFrom(this.riders.setOnline(online, at));
      this.me.set(me);
      if (me.online) this.location.start();
      else this.location.stop();
    } catch (error) {
      this.snackBar.open(describeError(error, 'Could not change your status. Try again.'), 'OK', {
        duration: 6000,
      });
    } finally {
      this.switching.set(false);
    }
  }

  protected secondsLeft(job: DeliveryJob): number {
    return Math.max(0, Math.round((job.offerExpiresAt ?? 0) - this.now()));
  }

  protected offerProgress(job: DeliveryJob): number {
    const total = this.offerTotals.get(job.orderId) ?? 30;
    return Math.min(100, (this.secondsLeft(job) / total) * 100);
  }

  protected accept(job: DeliveryJob): Promise<void> {
    return this.run(job, () => this.dispatch.accept(job.orderId), 'The offer has gone.');
  }

  protected decline(job: DeliveryJob): Promise<void> {
    return this.run(job, () => this.dispatch.decline(job.orderId), 'Could not decline.');
  }

  protected arrived(job: DeliveryJob): Promise<void> {
    return this.run(job, () => this.dispatch.arrived(job.orderId), 'Could not save that.');
  }

  protected pickedUp(job: DeliveryJob): Promise<void> {
    return this.run(job, () => this.dispatch.pickedUp(job.orderId), 'Could not save that.');
  }

  protected async delivered(job: DeliveryJob): Promise<void> {
    const proof = await firstValueFrom(
      this.dialog
        .open(DeliveredDialog, { data: job, autoFocus: 'first-tabbable', width: '440px' })
        .afterClosed(),
    );
    if (!proof) return;
    await this.run(job, () => this.dispatch.delivered(job.orderId, proof), 'Not delivered yet.');
    // Earnings are recorded from the delivery event a moment later, so look again shortly.
    void this.loadEarnings();
    for (const wait of [2500, 7000]) {
      setTimeout(() => void this.loadEarnings(), wait);
    }
  }

  protected async giveUp(job: DeliveryJob): Promise<void> {
    const reason = await confirmAction(this.dialog, {
      title: `Give up order ${job.orderNumber}?`,
      message: 'Support and the store are told, and the delivery goes to another rider.',
      confirm: 'Give up delivery',
      inputLabel: 'Why can you not do it?',
      inputRequired: true,
      danger: true,
    });
    if (!reason) return;
    await this.run(
      job,
      () => this.dispatch.unassign(job.orderId, reason),
      'Could not give up the delivery.',
    );
  }

  protected async enablePush(): Promise<void> {
    const state = await this.push.enable('backoffice');
    if (state === 'denied') {
      this.snackBar.open(
        'Notifications are blocked for this site in your browser settings.',
        'OK',
        {
          duration: 6000,
        },
      );
    }
  }

  protected step(job: DeliveryJob): number {
    return STEPS.findIndex((s) => s.status === job.status);
  }

  protected markers(job: DeliveryJob): MapMarker[] {
    const markers: MapMarker[] = [
      { id: 'store', lat: job.pickupLat, lng: job.pickupLng, kind: 'store', label: job.storeName },
      {
        id: 'home',
        lat: job.dropoffLat,
        lng: job.dropoffLng,
        kind: 'home',
        label: job.customerName ?? 'Customer',
      },
    ];
    const here = this.location.position();
    if (here) markers.push({ id: 'me', lat: here.lat, lng: here.lng, kind: 'rider', label: 'You' });
    return markers;
  }

  /** Directions in the phone's own maps app: to the store first, then to the customer. */
  protected directions(job: DeliveryJob): string {
    const toStore = job.status === 'ASSIGNED' || job.status === 'AT_STORE';
    const lat = toStore ? job.pickupLat : job.dropoffLat;
    const lng = toStore ? job.pickupLng : job.dropoffLng;
    const agent = typeof navigator !== 'undefined' ? navigator.userAgent : '';
    if (/android/i.test(agent)) return `geo:${lat},${lng}?q=${lat},${lng}`;
    if (/iphone|ipad/i.test(agent)) return `maps://?daddr=${lat},${lng}`;
    return `https://www.openstreetmap.org/directions?to=${lat}%2C${lng}`;
  }

  protected money(minor: number, currency: string): string {
    return formatMoney(minor, currency, this.locale.language());
  }

  protected km(meters: number): string {
    return (meters / 1000).toFixed(meters < 10_000 ? 1 : 0);
  }

  private async reload(): Promise<void> {
    try {
      this.setJobs(await firstValueFrom(this.dispatch.myJobs()));
    } catch {
      // the next change or a manual reload tries again
    }
  }

  private setJobs(jobs: DeliveryJob[]): void {
    const fresh = jobs.filter((j) => j.status === 'OFFERED' && !this.seenOffers.has(j.orderId));
    fresh.forEach((j) => {
      this.seenOffers.add(j.orderId);
      this.offerTotals.set(j.orderId, Math.max(1, (j.offerExpiresAt ?? 0) - Date.now() / 1000));
    });
    if (fresh.length > 0) ring();
    this.jobs.set(jobs);
  }

  private async loadEarnings(): Promise<void> {
    try {
      this.earnings.set(await firstValueFrom(this.riders.myEarnings(1)));
    } catch {
      this.earnings.set(null);
    }
  }

  private async run(
    job: DeliveryJob,
    call: () => Observable<unknown>,
    fallback: string,
  ): Promise<void> {
    this.busy.set(job.orderId);
    try {
      await firstValueFrom(call(), { defaultValue: null });
    } catch (error) {
      this.snackBar.open(describeError(error, fallback), 'OK', { duration: 6000 });
    } finally {
      this.busy.set(null);
      await this.reload();
    }
  }
}

/** A short alert for a new offer: a vibration and two tones, where the phone allows them. */
function ring(): void {
  try {
    navigator.vibrate?.([300, 120, 300]);
    const AudioContextType =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextType) return;
    const context = new AudioContextType();
    [880, 1175].forEach((frequency, i) => {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = frequency;
      gain.gain.setValueAtTime(0.0001, context.currentTime + i * 0.25);
      gain.gain.exponentialRampToValueAtTime(0.3, context.currentTime + i * 0.25 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + i * 0.25 + 0.22);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(context.currentTime + i * 0.25);
      oscillator.stop(context.currentTime + i * 0.25 + 0.24);
    });
    setTimeout(() => void context.close(), 1000);
  } catch {
    // sound is a nicety
  }
}
