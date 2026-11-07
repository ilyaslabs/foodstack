import { Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Rider, RiderClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { RiderFormDialog, RiderFormData, VEHICLE_LABELS } from '../riders/rider-form-dialog';
import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { StoreContext } from './store-context';

/**
 * The branch's own riders (PRD DR-1, store self-delivery): they sign in to the rider screen, and
 * the branch picks one when it accepts a delivery order it delivers itself. Riders of the brand's
 * other branches are listed too (PRD BB-12); only their home branch removes them.
 */
@Component({
  selector: 'app-store-riders',
  imports: [MatButtonModule, MatCardModule, MatSnackBarModule, Icon],
  template: `
    <mat-card appearance="outlined" class="card">
      <section class="card-section">
        <div class="head">
          <div>
            <h2>Riders</h2>
            <p class="section-hint">
              @if (context.store()?.deliveryMode === 'OPERATOR') {
                The operator's riders deliver this branch's orders. Your own riders take over when
                support hands a delivery back to you.
              } @else {
                Pick one of them when you accept a delivery order. They follow the order in the
                rider screen of this back office. Riders of the brand's other branches can deliver
                for this branch too.
              }
            </p>
          </div>
          <button mat-flat-button type="button" (click)="add()">
            <fs-icon name="add" /> Add rider
          </button>
        </div>
        @for (rider of riders(); track rider.id) {
          <div class="rider">
            <span class="avatar" [class.online]="rider.online" aria-hidden="true">
              <fs-icon name="rider" />
            </span>
            <div class="who">
              <strong>{{ rider.name }}</strong>
              <span>
                {{ vehicles[rider.vehicle] }}{{ rider.plate ? ' · ' + rider.plate : '' }}
                @if (rider.phone) {
                  · {{ rider.phone }}
                }
              </span>
            </div>
            @if (rider.activeOrderIds.length > 0) {
              <span class="chip busy">On a delivery</span>
            } @else if (rider.online) {
              <span class="chip live">Online</span>
            } @else {
              <span class="chip">Offline</span>
            }
            @if (rider.storeId === context.store()?.id) {
              <button mat-button type="button" (click)="remove(rider)">Remove</button>
            } @else {
              <span class="chip shared">From another branch</span>
            }
          </div>
        } @empty {
          <p class="none">No riders yet. Add the people who deliver for this branch.</p>
        }
      </section>
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: block;
      padding-block: 16px;
    }
    .card {
      max-width: 880px;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      flex-wrap: wrap;
    }
    .head > div {
      flex: 1 1 320px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .rider {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 40px;
      block-size: 40px;
      border-radius: 50%;
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .avatar.online {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .who {
      display: flex;
      flex-direction: column;
      flex: 1 1 200px;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .who strong {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface);
    }
    .chip.busy {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .chip.shared {
      white-space: nowrap;
    }
    .none {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
  `,
})
export class StoreRiders implements OnInit {
  protected readonly context = inject(StoreContext);
  private readonly client = inject(RiderClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly vehicles = VEHICLE_LABELS;
  protected readonly riders = signal<Rider[]>([]);

  ngOnInit(): void {
    void this.load();
  }

  protected async add(): Promise<void> {
    const storeId = this.context.store()!.id;
    const data: RiderFormData = {
      title: 'Add a rider',
      save: (input) => this.client.addStoreRider(storeId, input),
    };
    const added = await firstValueFrom(
      this.dialog.open(RiderFormDialog, { data, width: '560px', maxWidth: '94vw' }).afterClosed(),
    );
    if (added) await this.load();
  }

  protected async remove(rider: Rider): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: `Remove ${rider.name}?`,
      message: 'They can no longer sign in to the rider screen for this branch.',
      confirm: 'Remove',
      danger: true,
    });
    if (ok === null) return;
    try {
      await firstValueFrom(this.client.removeStoreRider(this.context.store()!.id, rider.id));
      await this.load();
    } catch (error) {
      this.snackBar.open(describeError(error, 'The rider was not removed.'), 'OK', {
        duration: 6000,
      });
    }
  }

  private async load(): Promise<void> {
    const store = this.context.store();
    if (!store) return;
    try {
      this.riders.set(await firstValueFrom(this.client.storeRiders(store.id)));
    } catch {
      this.riders.set([]);
    }
  }
}
