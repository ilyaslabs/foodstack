import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { DeadLetter, DeadLetterStatus, DeadLettersClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';

/** "order.OrderPlaced" or "foodstack.order.OrderPlaced" reads as "Order placed". */
function eventName(type: string | null): string {
  if (!type) return 'Unreadable event';
  const last = type.split('.').pop() ?? type;
  const words = last.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function title(module: string): string {
  return module.charAt(0).toUpperCase() + module.slice(1);
}

/**
 * Events a module could not handle after its retries (TDD 7.1). Usually a bug or an outage that
 * is fixed now: replay sends the event again to the module that failed, and only to it.
 */
@Component({
  selector: 'app-failed-events-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Failed events</h1>
      <p>
        When a module cannot handle an event after three tries, the event waits here, so nothing is
        lost. Fix the cause first (often an outage or an update), then replay it. Replay sends it
        only to the module that failed. Entries go after 30 days.
      </p>
    </header>
    <div class="filters">
      <mat-button-toggle-group
        [value]="status()"
        (change)="setStatus($event.value)"
        aria-label="Show"
      >
        <mat-button-toggle value="OPEN">Waiting</mat-button-toggle>
        <mat-button-toggle value="REPLAYED">Replayed</mat-button-toggle>
        <mat-button-toggle value="DISMISSED">Dismissed</mat-button-toggle>
      </mat-button-toggle-group>
      <button mat-button type="button" (click)="load()" [disabled]="loading()">
        <fs-icon name="refresh" /> Refresh
      </button>
    </div>
    <mat-card appearance="outlined">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @for (item of items(); track item.id) {
        <div class="line">
          <span class="badge" [attr.data-status]="item.status" aria-hidden="true">
            <fs-icon [name]="item.status === 'OPEN' ? 'warning' : 'check_circle'" />
          </span>
          <div class="text">
            <div class="what">
              <strong>{{ name(item.eventType) }}</strong>
              <span class="route">
                from {{ title(item.module) }} failed in {{ title(item.consumer) }}
              </span>
            </div>
            <div class="muted">
              {{ item.failedAt | date: 'medium' }}
              @if (item.handledAt) {
                · {{ item.status === 'REPLAYED' ? 'replayed' : 'dismissed' }}
                {{ item.handledAt | date: 'medium' }}
              }
            </div>
            <code class="error">{{ item.error || 'No error message' }}</code>
            @if (events()[item.id]; as value) {
              <pre class="event">{{ value }}</pre>
            }
          </div>
          <div class="actions">
            <button mat-button type="button" (click)="toggle(item)">
              {{ events()[item.id] ? 'Hide event' : 'Show event' }}
            </button>
            @if (item.status === 'OPEN') {
              <button mat-stroked-button type="button" (click)="dismiss(item)" [disabled]="busy()">
                Dismiss
              </button>
              <button mat-flat-button type="button" (click)="replay(item)" [disabled]="busy()">
                Replay
              </button>
            }
          </div>
        </div>
      } @empty {
        @if (!loading()) {
          <p class="empty">
            <fs-icon name="check_circle" />
            {{ status() === 'OPEN' ? 'No failed events. Everything was handled.' : 'Nothing here' }}
          </p>
        }
      }
    </mat-card>
  `,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px 16px;
      margin-block-end: 16px;
      max-inline-size: 960px;
    }
    mat-card {
      max-inline-size: 960px;
    }
    .line {
      display: flex;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px 16px;
      padding: 16px 20px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .badge {
      display: inline-grid;
      place-items: center;
      flex: none;
      inline-size: 36px;
      block-size: 36px;
      border-radius: 50%;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      --fs-icon-size: 20px;
    }
    .badge:not([data-status='OPEN']) {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .text {
      flex: 1 1 320px;
      min-inline-size: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .what {
      font: var(--mat-sys-body-large);
    }
    .route {
      color: var(--mat-sys-on-surface-variant);
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .error,
    .event {
      font-family: 'Roboto Mono', ui-monospace, monospace;
      font-size: 12px;
      line-height: 18px;
      padding: 6px 10px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      overflow-wrap: anywhere;
      white-space: pre-wrap;
      margin: 4px 0 0;
    }
    .event {
      max-block-size: 320px;
      overflow: auto;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-inline-start: auto;
    }
    .empty {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 32px 20px;
      color: var(--mat-sys-on-surface-variant);
    }
    @media (max-width: 599.98px) {
      .line {
        padding: 14px 16px;
      }
      .actions {
        inline-size: 100%;
        margin-inline-start: 0;
        justify-content: flex-end;
      }
      .actions > button:first-child {
        margin-inline-end: auto;
      }
    }
  `,
})
export class FailedEventsPage implements OnInit {
  private readonly client = inject(DeadLettersClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly status = signal<DeadLetterStatus>('OPEN');
  protected readonly items = signal<DeadLetter[]>([]);
  protected readonly events = signal<Record<string, string>>({});
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly name = eventName;
  protected readonly title = title;

  ngOnInit(): void {
    void this.load();
  }

  protected setStatus(status: DeadLetterStatus): void {
    this.status.set(status);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.items.set(await firstValueFrom(this.client.list(this.status())));
    } catch (error) {
      this.snackBar.open(describeError(error, 'The failed events could not be loaded.'), 'Close', {
        duration: 6000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  protected async toggle(item: DeadLetter): Promise<void> {
    if (this.events()[item.id]) {
      this.events.update(({ [item.id]: _, ...rest }) => rest);
      return;
    }
    try {
      const value = (await firstValueFrom(this.client.event(item.id))) ?? '';
      this.events.update((all) => ({ ...all, [item.id]: pretty(value) }));
    } catch (error) {
      this.snackBar.open(describeError(error, 'The event could not be loaded.'), 'Close', {
        duration: 6000,
      });
    }
  }

  protected async replay(item: DeadLetter): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: 'Replay this event?',
      message: `${title(item.consumer)} handles "${eventName(item.eventType)}" again. Replay once the cause is fixed; if it fails again, it comes back to this list.`,
      confirm: 'Replay',
    });
    if (ok === null) return;
    await this.act(item, () => this.client.replay(item.id), 'Sent again');
  }

  protected async dismiss(item: DeadLetter): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: 'Dismiss this event?',
      message: `${title(item.consumer)} never handles it. Do this only when it no longer matters, for example after fixing the data by hand.`,
      confirm: 'Dismiss',
      danger: true,
    });
    if (ok === null) return;
    await this.act(item, () => this.client.dismiss(item.id), 'Dismissed');
  }

  private async act(
    item: DeadLetter,
    call: () => ReturnType<DeadLettersClient['replay']>,
    done: string,
  ): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(call());
      this.items.update((all) => all.filter((other) => other.id !== item.id));
      this.snackBar.open(done, undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work.'), 'Close', { duration: 6000 });
      await this.load();
    } finally {
      this.busy.set(false);
    }
  }
}

function pretty(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
