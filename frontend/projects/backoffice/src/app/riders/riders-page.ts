import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { Rider, RiderClient, RiderKind } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { RiderFormDialog, RiderFormData, VEHICLE_LABELS } from './rider-form-dialog';

/** The operator's riders and stores' own riders, with who is online now (PRD DR-1). */
@Component({
  selector: 'app-riders-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header with-action">
      <div>
        <h1>Riders</h1>
        <p>
          Your riders take deliveries of branches that use operator delivery. Stores add their own
          riders on their branch page.
        </p>
      </div>
      <button mat-flat-button type="button" (click)="add()">
        <fs-icon name="add" /> Add rider
      </button>
    </header>

    <mat-card appearance="outlined" class="list-card">
      <div class="toolbar">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
          <fs-icon matPrefix name="search" class="prefix" />
          <mat-label>Name or phone</mat-label>
          <input
            matInput
            type="search"
            [value]="query()"
            (input)="query.set($any($event.target).value)"
          />
        </mat-form-field>
        <mat-button-toggle-group
          hideSingleSelectionIndicator
          aria-label="Kind of rider"
          [value]="kind()"
          (change)="kind.set($event.value); load()"
        >
          <mat-button-toggle value="OPERATOR">Operator</mat-button-toggle>
          <mat-button-toggle value="STORE">Store riders</mat-button-toggle>
          <mat-button-toggle value="">All</mat-button-toggle>
        </mat-button-toggle-group>
        <span class="count">{{ onlineCount() }} online</span>
      </div>
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (shown(); as list) {
        @if (list.length === 0 && !loading()) {
          <div class="empty">
            <span class="empty-icon"><fs-icon name="rider" /></span>
            <h2>{{ query() ? 'No riders match' : 'No riders yet' }}</h2>
            <p>Add a rider with their email. They sign in to this back office on their phone.</p>
          </div>
        } @else {
          <ul class="rows" role="list">
            @for (rider of list; track rider.id) {
              <li>
                <a class="row" [routerLink]="['/riders', rider.id]">
                  <span class="avatar" [class.online]="rider.online" aria-hidden="true">
                    <fs-icon name="rider" />
                  </span>
                  <span class="main">
                    <span class="name">{{ rider.name }}</span>
                    <span class="meta">
                      {{ vehicles[rider.vehicle] }}{{ rider.plate ? ' · ' + rider.plate : '' }}
                      {{ rider.city ? ' · ' + rider.city : '' }}
                    </span>
                    <span class="meta">
                      @if (rider.kind === 'OPERATOR' && complete() && !complete()!.has(rider.id)) {
                        <span class="papers-missing">Papers missing</span> ·
                      }
                      {{ rider.deliveries }} deliveries · {{ rider.acceptancePercent }}% accepted
                      @if (rider.lastSeenAt) {
                        · seen {{ rider.lastSeenAt * 1000 | date: 'd MMM, HH:mm' }}
                      }
                    </span>
                  </span>
                  @if (rider.status === 'SUSPENDED') {
                    <span class="chip closed">Suspended</span>
                  } @else if (rider.activeOrderIds.length > 0) {
                    <span class="chip busy">On a delivery</span>
                  } @else if (rider.online) {
                    <span class="chip live">Online</span>
                  } @else {
                    <span class="chip">Offline</span>
                  }
                  <fs-icon name="chevron_right" class="go" />
                </a>
              </li>
            }
          </ul>
        }
      }
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .with-action {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      padding: 16px 16px 8px;
    }
    .search {
      flex: 1 1 260px;
      max-width: 380px;
    }
    .prefix {
      margin-inline: 12px 4px;
      color: var(--mat-sys-on-surface-variant);
    }
    .count {
      margin-inline-start: auto;
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 8px;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 12px 8px 12px 16px;
      border-radius: 12px;
      color: inherit;
      text-decoration: none;
    }
    .row:hover,
    .row:focus-visible {
      background: var(--mat-sys-surface-container);
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      flex: none;
      border-radius: 50%;
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .avatar.online {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .main {
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      min-width: 0;
    }
    .name {
      font: var(--mat-sys-title-medium);
    }
    .meta {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .papers-missing {
      color: var(--mat-sys-error);
      font-weight: 500;
    }
    .chip.busy {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .go {
      color: var(--mat-sys-on-surface-variant);
    }
    :host-context([dir='rtl']) .go {
      transform: scaleX(-1);
    }
    @media (max-width: 599.98px) {
      .search {
        flex-basis: 100%;
        max-width: none;
      }
      .row {
        flex-wrap: wrap;
        gap: 8px 12px;
      }
      .main {
        flex-basis: calc(100% - 60px - 40px);
      }
      .chip {
        margin-inline-start: 56px;
      }
      .go {
        display: none;
      }
    }
  `,
})
export class RidersPage implements OnInit {
  private readonly riders = inject(RiderClient);
  private readonly dialog = inject(MatDialog);

  protected readonly vehicles = VEHICLE_LABELS;
  protected readonly kind = signal<RiderKind | ''>('OPERATOR');
  protected readonly query = signal('');
  protected readonly loading = signal(true);
  protected readonly list = signal<Rider[] | null>(null);
  /** Operator riders with complete papers; null until known (or for staff who cannot see them). */
  protected readonly complete = signal<Set<string> | null>(null);

  protected readonly shown = computed(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.list();
    if (!list || !q) return list;
    return list.filter(
      (r) => r.name.toLowerCase().includes(q) || (r.phone ?? '').replace(/\s/g, '').includes(q),
    );
  });

  protected readonly onlineCount = computed(
    () => (this.list() ?? []).filter((r) => r.online).length,
  );

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const kind = this.kind();
      this.list.set(await firstValueFrom(this.riders.list(kind ? { kind } : {})));
      if (kind !== 'STORE') {
        this.riders.completePapers().subscribe({
          next: (ids) => this.complete.set(new Set(ids)),
          error: () => this.complete.set(null),
        });
      }
    } catch {
      this.list.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected async add(): Promise<void> {
    const data: RiderFormData = {
      title: 'Add a rider',
      save: (input) => this.riders.add(input),
    };
    const added = await firstValueFrom(
      this.dialog.open(RiderFormDialog, { data, width: '560px', maxWidth: '94vw' }).afterClosed(),
    );
    if (added) await this.load();
  }
}
