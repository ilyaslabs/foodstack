import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AuthService } from '@foodstack/auth';
import {
  DeliveryJob,
  DeliveryStatus,
  DispatchClient,
  LatLng,
  LiveRider,
  RiderClient,
  RiderLiveMessage,
} from '@foodstack/api-client';
import { FsMap, Icon, MapMarker } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { AssignDialog, AssignData, AssignResult } from './assign-dialog';

const STATUS_LABELS: Record<DeliveryStatus, string> = {
  WAITING: 'Waiting for the kitchen',
  SEARCHING: 'Finding a rider',
  OFFERED: 'Offered to a rider',
  ASSIGNED: 'Rider on the way to the store',
  AT_STORE: 'Rider at the store',
  PICKED_UP: 'On the way to the customer',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  HANDED_BACK: 'Handed back to the store',
};

/** After this long searching, a job counts as needing attention (matches the default alert). */
const ATTENTION_SECONDS = 300;

type Filter = 'all' | 'attention' | 'moving';

/**
 * Support's live map and board (PRD OF-8, DR-8): every online rider moving on the map, every open
 * delivery with how long it has waited, and assigning a rider or handing a delivery back to the
 * store when no rider takes it.
 */
@Component({
  selector: 'app-dispatch-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    FsMap,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Dispatch</h1>
      <p>Riders on the map and deliveries that are not finished. Updates arrive live.</p>
    </header>
    <dl class="stats">
      <div>
        <dt>Riders online</dt>
        <dd>{{ online().length }}</dd>
      </div>
      <div>
        <dt>Free now</dt>
        <dd>{{ free() }}</dd>
      </div>
      <div>
        <dt>Open deliveries</dt>
        <dd>{{ jobs().length }}</dd>
      </div>
      <div [class.alert]="attention().length > 0">
        <dt>Need attention</dt>
        <dd>{{ attention().length }}</dd>
      </div>
    </dl>
    <div class="layout">
      <mat-card appearance="outlined" class="map-card">
        <fs-map
          class="map"
          [center]="center()"
          [zoom]="12"
          [markers]="markers()"
          [route]="route()"
          [fit]="true"
          label="Riders and deliveries"
          (markerClick)="select($event)"
        />
        <div class="legend" aria-hidden="true">
          <span><i class="dot free"></i>Free</span>
          <span><i class="dot busy"></i>On a delivery</span>
          <span><i class="dot order"></i>Delivery</span>
          <span><i class="dot store"></i>Store</span>
        </div>
      </mat-card>
      <mat-card appearance="outlined" class="board">
        <div class="board-head">
          <mat-button-toggle-group
            hideSingleSelectionIndicator
            aria-label="Show"
            [value]="filter()"
            (change)="filter.set($event.value)"
          >
            <mat-button-toggle value="all">All</mat-button-toggle>
            <mat-button-toggle value="attention">Need attention</mat-button-toggle>
            <mat-button-toggle value="moving">With a rider</mat-button-toggle>
          </mat-button-toggle-group>
        </div>
        @if (loading()) {
          <mat-progress-bar mode="indeterminate" />
        }
        <ul class="jobs" role="list">
          @for (job of shown(); track job.orderId) {
            <li
              class="job"
              [class.selected]="selected() === job.orderId"
              [class.late]="needsAttention(job)"
            >
              <button type="button" class="job-main" (click)="select(job.orderId)">
                <span class="job-top">
                  <strong>#{{ job.orderNumber }}</strong>
                  <span class="status" [attr.data-status]="job.status">{{ labels[job.status] }}</span>
                </span>
                <span class="line">{{ job.storeName }} → {{ job.dropoffAddress || 'customer' }}</span>
                <span class="line muted">
                  @if (job.riderName) {
                    <fs-icon name="rider" /> {{ job.riderName }} ·
                  }
                  @if (job.searchingSince && !job.riderId) {
                    searching {{ minutes(job.searchingSince) }} min ·
                  }
                  {{ (job.distanceMeters / 1000).toFixed(1) }} km
                  @if (job.mode === 'SELF') {
                    · store delivers
                  }
                </span>
              </button>
              @if (job.status !== 'PICKED_UP') {
                <div class="job-actions">
                  <button mat-button type="button" (click)="assign(job)">
                    {{ job.riderId ? 'Reassign' : 'Assign' }}
                  </button>
                  @if (job.mode === 'OPERATOR') {
                    <button mat-button type="button" (click)="handBack(job)">Hand back</button>
                  }
                </div>
              }
            </li>
          } @empty {
            @if (!loading()) {
              <li class="none">
                <span class="empty-icon"><fs-icon name="check_circle" /></span>
                Nothing open here.
              </li>
            }
          }
        </ul>
      </mat-card>
    </div>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
      margin: 0;
    }
    .stats div {
      padding: 12px 16px;
      border-radius: 16px;
      background: var(--mat-sys-surface-container);
    }
    .stats div.alert {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .stats dt {
      font: var(--mat-sys-label-medium);
      opacity: 0.85;
    }
    .stats dd {
      margin: 4px 0 0;
      font: var(--mat-sys-headline-small);
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1.5fr) minmax(320px, 1fr);
      gap: 16px;
      align-items: start;
    }
    .map-card {
      overflow: hidden;
      position: sticky;
      top: 16px;
    }
    .map {
      block-size: min(68vh, 640px);
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      padding: 10px 16px;
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .legend span {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .dot {
      inline-size: 10px;
      block-size: 10px;
      border-radius: 50%;
    }
    .dot.free {
      background: #12a150;
    }
    .dot.busy {
      background: #1b6ef3;
    }
    .dot.order {
      background: #7c4dff;
    }
    .dot.store {
      background: #e8710a;
    }
    .board-head {
      padding: 12px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .jobs {
      list-style: none;
      margin: 0;
      padding: 8px;
      max-block-size: 70vh;
      overflow: auto;
    }
    .job {
      border-radius: 12px;
      border: 1px solid transparent;
      margin-block-end: 4px;
    }
    .job.selected {
      border-color: var(--mat-sys-primary);
      background: var(--mat-sys-surface-container-low);
    }
    .job.late {
      border-inline-start: 4px solid var(--mat-sys-error);
    }
    .job-main {
      all: unset;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      gap: 2px;
      inline-size: 100%;
      padding: 10px 12px 4px;
      cursor: pointer;
    }
    .job-main:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      border-radius: 12px;
    }
    .job-top {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      font: var(--mat-sys-title-small);
    }
    .status {
      font: var(--mat-sys-label-medium);
      padding: 2px 8px;
      border-radius: 999px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      white-space: nowrap;
    }
    .status[data-status='SEARCHING'],
    .status[data-status='OFFERED'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .status[data-status='PICKED_UP'],
    .status[data-status='ASSIGNED'],
    .status[data-status='AT_STORE'] {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .line {
      font: var(--mat-sys-body-medium);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .line fs-icon {
      --fs-icon-size: 16px;
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
    }
    .job-actions {
      display: flex;
      justify-content: flex-end;
      gap: 4px;
      padding: 0 4px 4px;
    }
    .none {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 32px 16px;
      color: var(--mat-sys-on-surface-variant);
    }
    .empty-icon {
      display: inline-grid;
      place-items: center;
      inline-size: 48px;
      block-size: 48px;
      border-radius: 16px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    @media (max-width: 1099.98px) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
      }
      .map-card {
        position: static;
      }
      .map {
        block-size: 52vh;
      }
    }
    @media (max-width: 599.98px) {
      .stats {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class DispatchPage implements OnInit {
  private readonly riders = inject(RiderClient);
  private readonly dispatch = inject(DispatchClient);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = STATUS_LABELS;
  protected readonly live = signal<Map<string, LiveRider>>(new Map());
  protected readonly jobs = signal<DeliveryJob[]>([]);
  protected readonly loading = signal(true);
  protected readonly selected = signal<string | null>(null);
  protected readonly filter = signal<Filter>('all');
  private readonly now = signal(Date.now() / 1000);

  protected readonly online = computed(() => [...this.live().values()]);
  protected readonly free = computed(() => this.online().filter((r) => !r.busy).length);
  protected readonly attention = computed(() => this.jobs().filter((j) => this.needsAttention(j)));

  protected readonly shown = computed(() => {
    const filter = this.filter();
    const jobs = this.jobs();
    if (filter === 'attention') return jobs.filter((j) => this.needsAttention(j));
    if (filter === 'moving') return jobs.filter((j) => !!j.riderId);
    return [...jobs].sort((a, b) => Number(this.needsAttention(b)) - Number(this.needsAttention(a)));
  });

  protected readonly center = computed<LatLng | null>(() => {
    const first = this.jobs()[0] ?? null;
    if (first) return { lat: first.pickupLat, lng: first.pickupLng };
    const rider = this.online().find((r) => r.lat !== null);
    return rider ? { lat: rider.lat!, lng: rider.lng! } : null;
  });

  protected readonly markers = computed<MapMarker[]>(() => {
    const markers: MapMarker[] = [];
    for (const r of this.online()) {
      if (r.lat === null || r.lng === null) continue;
      markers.push({
        id: `rider:${r.riderId}`,
        lat: r.lat,
        lng: r.lng,
        kind: r.busy ? 'rider-busy' : 'rider',
        label: `${r.name}${r.busy ? ' (on a delivery)' : ''}`,
      });
    }
    const stores = new Map<string, DeliveryJob>();
    for (const job of this.jobs()) {
      stores.set(job.storeId, job);
      markers.push({
        id: `order:${job.orderId}`,
        lat: job.dropoffLat,
        lng: job.dropoffLng,
        kind: 'order',
        label: `Order ${job.orderNumber}`,
      });
    }
    for (const [storeId, job] of stores) {
      markers.push({
        id: `store:${storeId}`,
        lat: job.pickupLat,
        lng: job.pickupLng,
        kind: 'store',
        label: job.storeName,
      });
    }
    return markers;
  });

  protected readonly route = computed(() => {
    const job = this.jobs().find((j) => j.orderId === this.selected());
    if (!job) return [];
    const points = [];
    const rider = job.riderId ? this.live().get(job.riderId) : undefined;
    if (rider?.lat != null && rider.lng != null && job.status !== 'PICKED_UP') {
      points.push({ lat: rider.lat, lng: rider.lng });
    }
    if (job.status !== 'PICKED_UP') points.push({ lat: job.pickupLat, lng: job.pickupLng });
    else if (rider?.lat != null && rider.lng != null) points.push({ lat: rider.lat, lng: rider.lng });
    points.push({ lat: job.dropoffLat, lng: job.dropoffLng });
    return points;
  });

  async ngOnInit(): Promise<void> {
    await Promise.all([this.loadRiders(), this.loadJobs()]);
    this.loading.set(false);
    const token = () => this.auth.freshToken();
    const riders = this.riders.watchLive(token).subscribe((m) => this.onRider(m));
    let pending: ReturnType<typeof setTimeout> | null = null;
    const jobs = this.dispatch.watchAll(token).subscribe(() => {
      // a burst of changes reloads once
      if (pending) return;
      pending = setTimeout(() => {
        pending = null;
        void this.loadJobs();
      }, 400);
    });
    const ticker = setInterval(() => this.now.set(Date.now() / 1000), 15_000);
    this.destroyRef.onDestroy(() => {
      riders.unsubscribe();
      jobs.unsubscribe();
      clearInterval(ticker);
      if (pending) clearTimeout(pending);
    });
  }

  protected needsAttention(job: DeliveryJob): boolean {
    return (
      !job.riderId &&
      job.mode === 'OPERATOR' &&
      job.searchingSince !== null &&
      this.now() - job.searchingSince >= ATTENTION_SECONDS
    );
  }

  protected minutes(since: number): number {
    return Math.max(0, Math.floor((this.now() - since) / 60));
  }

  protected select(id: string): void {
    const orderId = id.startsWith('order:') ? id.slice(6) : id;
    if (this.jobs().some((j) => j.orderId === orderId)) this.selected.set(orderId);
  }

  protected async assign(job: DeliveryJob): Promise<void> {
    this.selected.set(job.orderId);
    const data: AssignData = { job, riders: this.online() };
    const result = await firstValueFrom(
      this.dialog
        .open<AssignDialog, AssignData, AssignResult>(AssignDialog, {
          data,
          width: '480px',
          maxWidth: '94vw',
        })
        .afterClosed(),
    );
    if (!result) return;
    try {
      await firstValueFrom(this.dispatch.reassign(job.orderId, result.riderId, result.reason));
      this.snackBar.open(result.riderId ? 'Assigned.' : 'Searching for a rider again.', undefined, {
        duration: 3000,
      });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The delivery was not assigned.'), 'OK', {
        duration: 6000,
      });
    }
    await this.loadJobs();
  }

  protected async handBack(job: DeliveryJob): Promise<void> {
    const reason = await confirmAction(this.dialog, {
      title: `Hand order ${job.orderNumber} back to the store?`,
      message: `${job.storeName} delivers it with their own rider. The customer is not charged again.`,
      confirm: 'Hand back',
      inputLabel: 'Reason',
      inputRequired: true,
      danger: true,
    });
    if (!reason) return;
    try {
      await firstValueFrom(this.dispatch.handBack(job.orderId, reason));
    } catch (error) {
      this.snackBar.open(describeError(error, 'The delivery was not handed back.'), 'OK', {
        duration: 6000,
      });
    }
    await this.loadJobs();
  }

  private onRider(message: RiderLiveMessage): void {
    const next = new Map(this.live());
    const known = next.get(message.riderId);
    if (message.state && message.online === false) {
      next.delete(message.riderId);
    } else if (known) {
      next.set(message.riderId, {
        ...known,
        busy: message.busy,
        lat: message.lat ?? known.lat,
        lng: message.lng ?? known.lng,
        at: message.at ?? known.at,
      });
    } else {
      // a rider who just came online: their name comes with the next full read
      void this.loadRiders();
      return;
    }
    this.live.set(next);
  }

  private async loadRiders(): Promise<void> {
    try {
      const riders = await firstValueFrom(this.riders.live());
      this.live.set(new Map(riders.map((r) => [r.riderId, r])));
    } catch {
      // keep what is shown
    }
  }

  private async loadJobs(): Promise<void> {
    try {
      this.jobs.set(await firstValueFrom(this.dispatch.open()));
    } catch {
      // keep what is shown
    }
  }
}
