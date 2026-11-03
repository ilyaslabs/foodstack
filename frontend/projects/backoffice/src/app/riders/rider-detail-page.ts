import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Earnings, Rider, RiderClient } from '@foodstack/api-client';
import { FsMap, Icon, MapMarker } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { EarningsView } from '../rider/earnings-view';
import { RiderFormDialog, RiderFormData, VEHICLE_LABELS } from './rider-form-dialog';

/** One rider: details, where they were last seen, suspending, and what they earned. */
@Component({
  selector: 'app-rider-detail-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    EarningsView,
    FsMap,
    Icon,
  ],
  template: `
    <a mat-button routerLink="/riders" class="back"><fs-icon name="arrow_back" /> Riders</a>
    @if (rider(); as r) {
      <header class="fs-page-header head">
        <div>
          <h1>{{ r.name }}</h1>
          <p>
            {{ r.kind === 'STORE' ? 'Store rider' : 'Operator rider' }} ·
            {{ vehicles[r.vehicle] }}{{ r.plate ? ' · ' + r.plate : '' }}
          </p>
        </div>
        <div class="head-actions">
          <button mat-stroked-button type="button" (click)="edit(r)">
            <fs-icon name="edit" /> Edit
          </button>
          @if (r.status === 'ACTIVE') {
            <button mat-stroked-button type="button" class="danger" (click)="suspend(r)">
              <fs-icon name="pause" /> Suspend
            </button>
          } @else {
            <button mat-flat-button type="button" (click)="reactivate(r)">
              <fs-icon name="play" /> Reactivate
            </button>
          }
        </div>
      </header>
      <div class="layout">
        <mat-card appearance="outlined" class="facts">
          <dl>
            <div>
              <dt>Status</dt>
              <dd>
                @if (r.status === 'SUSPENDED') {
                  <span class="chip closed">Suspended</span>
                } @else if (r.activeOrderIds.length > 0) {
                  <span class="chip busy">On {{ r.activeOrderIds.length }} delivery</span>
                } @else if (r.online) {
                  <span class="chip live">Online</span>
                } @else {
                  <span class="chip">Offline</span>
                }
              </dd>
            </div>
            <div>
              <dt>Phone</dt>
              <dd>
                @if (r.phone) {
                  <a [href]="'tel:' + r.phone">{{ r.phone }}</a>
                } @else {
                  –
                }
              </dd>
            </div>
            <div>
              <dt>City</dt>
              <dd>{{ r.city || '–' }}</dd>
            </div>
            <div>
              <dt>Deliveries</dt>
              <dd>{{ r.deliveries }}</dd>
            </div>
            <div>
              <dt>Offers accepted</dt>
              <dd>{{ r.acceptancePercent }}%</dd>
            </div>
            <div>
              <dt>Last seen</dt>
              <dd>{{ r.lastSeenAt ? (r.lastSeenAt * 1000 | date: 'd MMM y, HH:mm') : 'Never' }}</dd>
            </div>
            <div>
              <dt>Rider since</dt>
              <dd>{{ r.createdAt * 1000 | date: 'mediumDate' }}</dd>
            </div>
          </dl>
        </mat-card>
        <mat-card appearance="outlined" class="where">
          @if (marker().length > 0) {
            <fs-map [center]="{ lat: r.lat!, lng: r.lng! }" [zoom]="14" [markers]="marker()" label="Last known position" />
          } @else {
            <div class="empty">
              <span class="empty-icon"><fs-icon name="place" /></span>
              <p>No position yet. It shows once the rider goes online.</p>
            </div>
          }
        </mat-card>
      </div>
      <mat-card appearance="outlined" class="earnings">
        <h2>Last 7 days</h2>
        @if (earnings(); as e) {
          <app-earnings-view [earnings]="e" />
        } @else {
          <mat-progress-bar mode="indeterminate" />
        }
      </mat-card>
    } @else if (failed()) {
      <p>This rider could not be loaded.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .back {
      align-self: flex-start;
      margin-inline-start: -12px;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      flex-wrap: wrap;
    }
    .head-actions {
      display: flex;
      gap: 8px;
    }
    .danger {
      color: var(--mat-sys-error);
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
      gap: 16px;
    }
    @media (max-width: 899.98px) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    dl {
      margin: 0;
      padding: 8px 20px;
    }
    dl div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    dl div:last-child {
      border: 0;
    }
    dt {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 0;
      font: var(--mat-sys-body-large);
      text-align: end;
    }
    dd a {
      color: var(--mat-sys-primary);
    }
    .where {
      overflow: hidden;
      min-block-size: 280px;
    }
    .where fs-map {
      block-size: 100%;
      min-block-size: 280px;
    }
    .chip.busy {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .earnings {
      padding: 20px;
    }
    .earnings h2 {
      margin: 0 0 12px;
      font: var(--mat-sys-title-medium);
    }
  `,
})
export class RiderDetailPage implements OnInit {
  private readonly riders = inject(RiderClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly id = input.required<string>();

  protected readonly vehicles = VEHICLE_LABELS;
  protected readonly rider = signal<Rider | null>(null);
  protected readonly earnings = signal<Earnings | null>(null);
  protected readonly failed = signal(false);

  protected readonly marker = computed<MapMarker[]>(() => {
    const r = this.rider();
    if (!r || r.lat === null || r.lng === null) return [];
    const kind = !r.online ? 'rider-offline' : r.activeOrderIds.length > 0 ? 'rider-busy' : 'rider';
    return [{ id: r.id, lat: r.lat, lng: r.lng, kind, label: r.name }];
  });

  async ngOnInit(): Promise<void> {
    try {
      this.rider.set(await firstValueFrom(this.riders.get(this.id())));
      this.earnings.set(await firstValueFrom(this.riders.earningsOf(this.id(), 7)));
    } catch {
      if (!this.rider()) this.failed.set(true);
    }
  }

  protected async edit(rider: Rider): Promise<void> {
    const data: RiderFormData = {
      title: `Edit ${rider.name}`,
      rider,
      save: (input) => this.riders.update(rider.id, input),
    };
    const saved = await firstValueFrom(
      this.dialog.open(RiderFormDialog, { data, width: '560px', maxWidth: '94vw' }).afterClosed(),
    );
    if (saved) this.rider.set(saved);
  }

  protected async suspend(rider: Rider): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: `Suspend ${rider.name}?`,
      message:
        'They go offline at once and get no more offers. Deliveries they have now stay with them.',
      confirm: 'Suspend',
      danger: true,
    });
    if (ok === null) return;
    await this.setStatus(rider, 'SUSPENDED');
  }

  protected reactivate(rider: Rider): Promise<void> {
    return this.setStatus(rider, 'ACTIVE');
  }

  private async setStatus(rider: Rider, status: 'ACTIVE' | 'SUSPENDED'): Promise<void> {
    try {
      this.rider.set(await firstValueFrom(this.riders.setStatus(rider.id, status)));
    } catch (error) {
      this.snackBar.open(describeError(error, 'The change was not saved.'), 'OK', { duration: 6000 });
    }
  }
}
