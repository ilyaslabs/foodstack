import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggle, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';

import { StoreClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import { MenuEditor } from './menu-editor';
import { READINESS_LABELS, STATUS_LABELS } from './store-status';
import { StoreBrand } from './store-brand';
import { StoreContext } from './store-context';
import { StoreHours } from './store-hours';
import { StoreOwners } from './store-owners';
import { StoreRiders } from './store-riders';
import { StoreProfile } from './store-profile';
import { StoreZones } from './store-zones';

/** One branch: status and the tabs to edit it. */
@Component({
  selector: 'app-store-page',
  providers: [StoreContext],
  imports: [
    RouterLink,
    MatButtonModule,
    MatDialogModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatTabsModule,
    Icon,
    MenuEditor,
    StoreBrand,
    StoreHours,
    StoreOwners,
    StoreRiders,
    StoreProfile,
    StoreZones,
  ],
  template: `
    @if (session.operator() || (session.me()?.brandIds ?? []).length > 0) {
      <a mat-button routerLink="/stores" class="back"
        ><fs-icon name="arrow_back" /> {{ session.operator() ? 'Stores' : 'Branches' }}</a
      >
    }
    @if (context.store(); as store) {
      <header class="head">
        <div class="identity">
          @if (store.brand.logo; as logo) {
            <img
              class="logo"
              [src]="logo.urls['thumb'] ?? logo.urls['card']"
              alt=""
              width="56"
              height="56"
            />
          } @else {
            <span class="logo placeholder" aria-hidden="true">{{
              store.brand.name.charAt(0)
            }}</span>
          }
          <div>
            <h1>{{ store.displayName }}</h1>
            <div class="chips">
              <span
                class="chip"
                [class.live]="store.status === 'LIVE'"
                [class.paused]="store.status === 'PAUSED'"
              >
                {{ statusLabels[store.status] }}
              </span>
              @if (store.status === 'LIVE') {
                <span class="chip" [class.live]="store.openNow" [class.closed]="!store.openNow">
                  {{ store.openNow ? 'Open now' : 'Closed now' }}
                </span>
              }
              <span class="chip plain">{{ store.address.city }}</span>
            </div>
          </div>
        </div>
        <div class="head-actions">
          <mat-slide-toggle
            [checked]="!store.temporarilyClosed"
            (change)="setOpen($event.checked, $event.source)"
            [disabled]="busy()"
          >
            {{ store.temporarilyClosed ? 'Taking no orders' : 'Taking orders' }}
          </mat-slide-toggle>
          @if (session.operator()) {
            @if (store.status === 'LIVE') {
              <button
                mat-stroked-button
                type="button"
                (click)="setStatus('PAUSED')"
                [disabled]="busy()"
              >
                <fs-icon name="pause" /> Pause
              </button>
            } @else {
              <button
                mat-flat-button
                type="button"
                (click)="setStatus('LIVE')"
                [disabled]="busy() || context.readiness().length > 0"
              >
                <fs-icon name="play" /> Go live
              </button>
            }
          }
        </div>
      </header>

      @if (store.status !== 'LIVE' && context.readiness().length > 0) {
        <div class="readiness" role="status">
          <fs-icon name="info" />
          <div>
            <strong>Before this branch can go live</strong>
            <ul>
              @for (missing of context.readiness(); track missing) {
                <li>{{ readinessLabels[missing] ?? missing }}</li>
              }
            </ul>
          </div>
        </div>
      } @else if (store.status !== 'LIVE' && !session.operator()) {
        <div class="readiness ready" role="status">
          <fs-icon name="check_circle" />
          <div><strong>Ready.</strong> The platform team will make your branch live.</div>
        </div>
      }
      @if (store.temporarilyClosed) {
        <div class="readiness closed" role="status">
          <fs-icon name="warning" />
          <div>
            <strong>Closed for now.</strong> Customers see the branch as closed until you switch
            orders back on.
            @if (store.closedReason) {
              ({{ store.closedReason }})
            }
          </div>
        </div>
      }

      <mat-tab-group
        mat-stretch-tabs="false"
        animationDuration="150ms"
        class="tabs"
        [selectedIndex]="tab()"
        (selectedIndexChange)="tab.set($event)"
      >
        <mat-tab label="Profile"
          ><ng-template matTabContent><app-store-profile /></ng-template
        ></mat-tab>
        <mat-tab label="Menu"
          ><ng-template matTabContent><app-menu-editor /></ng-template
        ></mat-tab>
        <mat-tab label="Hours"
          ><ng-template matTabContent><app-store-hours /></ng-template
        ></mat-tab>
        <mat-tab label="Delivery"
          ><ng-template matTabContent><app-store-zones /></ng-template
        ></mat-tab>
        <mat-tab label="Riders"
          ><ng-template matTabContent><app-store-riders /></ng-template
        ></mat-tab>
        @if (session.operator() || session.ownsBrand(store.brand.id)) {
          <mat-tab label="Managers"
            ><ng-template matTabContent><app-store-owners /></ng-template
          ></mat-tab>
          <mat-tab label="Brand"
            ><ng-template matTabContent><app-store-brand /></ng-template
          ></mat-tab>
        }
      </mat-tab-group>
    } @else if (context.loadError()) {
      <div class="empty">
        <h2>This branch could not be loaded</h2>
        <p>It may not exist, or your account has no access to it.</p>
      </div>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .back {
      margin: -8px 0 8px -12px;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      margin-block-end: 16px;
    }
    .identity {
      display: flex;
      align-items: center;
      gap: 16px;
      min-width: 0;
    }
    h1 {
      font: var(--mat-sys-headline-medium);
      margin: 0 0 6px;
    }
    .logo {
      inline-size: 56px;
      block-size: 56px;
      border-radius: 16px;
      object-fit: cover;
      flex: none;
    }
    .placeholder {
      display: inline-grid;
      place-items: center;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      font: var(--mat-sys-headline-small);
      font-weight: 600;
    }
    .chips {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .head-actions {
      display: flex;
      align-items: center;
      gap: 16px;
      flex-wrap: wrap;
    }
    .readiness {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      margin-block-end: 12px;
      border-radius: 12px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      font: var(--mat-sys-body-medium);
    }
    .readiness ul {
      margin: 4px 0 0;
      padding-inline-start: 18px;
    }
    .readiness.ready {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .readiness.closed {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .tabs {
      margin-block-start: 8px;
      --mat-tab-divider-color: var(--mat-sys-outline-variant);
    }
  `,
})
export class StorePage {
  readonly id = input.required<string>();

  protected readonly session = inject(Session);
  protected readonly context = inject(StoreContext);
  private readonly client = inject(StoreClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly readinessLabels = READINESS_LABELS;
  protected readonly busy = signal(false);
  protected readonly tab = signal(0);

  constructor() {
    effect(() => void this.context.load(this.id()));
  }

  protected async setOpen(open: boolean, toggle: MatSlideToggle): Promise<void> {
    const store = this.context.store()!;
    let reason: string | null = null;
    if (!open) {
      reason = await confirmAction(this.dialog, {
        title: 'Stop taking orders?',
        message: 'Customers see the branch as closed until you switch orders back on.',
        confirm: 'Stop orders',
        danger: true,
        inputLabel: 'Reason (optional)',
        inputHint: 'Customers see this, such as "Back at 6 pm".',
      });
      if (reason === null) {
        toggle.checked = true;
        return;
      }
    }
    await this.run(
      () => firstValueFrom(this.client.setAvailability(store.id, !open, reason || null)),
      open ? 'Taking orders again.' : 'Closed for now. Switch back on when you are ready.',
    );
  }

  protected async setStatus(status: 'LIVE' | 'PAUSED'): Promise<void> {
    const store = this.context.store()!;
    await this.run(
      () => firstValueFrom(this.client.setStatus(store.id, status)),
      status === 'LIVE'
        ? 'The branch is live. Customers nearby can find it.'
        : 'The branch is hidden from customers.',
    );
  }

  private async run(
    action: () => Promise<Parameters<StoreContext['set']>[0]>,
    done: string,
  ): Promise<void> {
    this.busy.set(true);
    try {
      this.context.set(await action());
      this.snackBar.open(done, undefined, { duration: 4000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work. Try again.'), 'Dismiss', {
        duration: 8000,
      });
      await this.context.load(this.id());
    } finally {
      this.busy.set(false);
    }
  }
}
