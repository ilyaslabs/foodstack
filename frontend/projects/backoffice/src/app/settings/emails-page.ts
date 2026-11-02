import { Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { EmailDelivery, NotificationClient } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { describeError } from '../shared/errors';

const STATUS_LABELS: Record<EmailDelivery['status'], string> = {
  QUEUED: 'Waiting',
  SENDING: 'Sending',
  SENT: 'Sent',
  FAILED: 'Failed',
  SKIPPED: 'Not sent',
};

/** Every email the platform sent or tried to send, with why it failed and a retry (PRD NO-4). */
@Component({
  selector: 'app-emails-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatProgressBarModule,
    MatSnackBarModule,
    MatTooltipModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Email log</h1>
      <p>
        Order updates and sign-in codes. Failed emails are retried for about three hours; you can
        send one again here.
      </p>
    </header>
    <mat-button-toggle-group
      class="filter"
      [value]="status()"
      (change)="filter($event.value)"
      aria-label="Show"
    >
      <mat-button-toggle [value]="null">All</mat-button-toggle>
      <mat-button-toggle value="FAILED">Failed</mat-button-toggle>
      <mat-button-toggle value="SKIPPED">Not sent</mat-button-toggle>
      <mat-button-toggle value="QUEUED">Waiting</mat-button-toggle>
    </mat-button-toggle-group>
    <mat-card appearance="outlined">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @for (item of items(); track item.id) {
        <div class="row">
          <span class="status" [attr.data-status]="item.status">{{ labels[item.status] }}</span>
          <div class="text">
            <div class="subject">{{ item.subject }}</div>
            <div class="muted">
              {{ item.recipient }} · {{ when(item.createdAt) }}
              @if (item.attempts > 1) {
                · {{ item.attempts }} tries
              }
            </div>
            @if (item.lastError && item.status !== 'SENT') {
              <div class="error-text">{{ item.lastError }}</div>
            }
          </div>
          @if (item.status === 'FAILED' || item.status === 'SKIPPED') {
            <button mat-button type="button" (click)="retry(item)">
              <fs-icon name="refresh" /> Send again
            </button>
          }
        </div>
      } @empty {
        @if (!loading()) {
          <p class="empty"><fs-icon name="mail" /> No emails here</p>
        }
      }
      @if (more()) {
        <div class="more">
          <button mat-stroked-button type="button" (click)="load()">Show more</button>
        </div>
      }
    </mat-card>
  `,
  styles: `
    .filter {
      margin-block-end: 16px;
    }
    mat-card {
      max-inline-size: 960px;
    }
    .row {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      padding: 14px 20px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .text {
      flex: 1;
      min-inline-size: 0;
    }
    .subject {
      font: var(--mat-sys-title-small);
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .error-text {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-error);
      margin-block-start: 4px;
      word-break: break-word;
    }
    .status {
      flex: none;
      min-inline-size: 72px;
      text-align: center;
      padding: 2px 10px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-surface-container-high);
    }
    .status[data-status='SENT'] {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .status[data-status='FAILED'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .status[data-status='SKIPPED'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .empty {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 16px;
    }
  `,
})
export class EmailsPage implements OnInit {
  private readonly client = inject(NotificationClient);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly items = signal<EmailDelivery[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly more = signal(false);
  protected readonly labels = STATUS_LABELS;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected filter(status: string | null): void {
    this.status.set(status);
    this.items.set([]);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const last = this.items().at(-1);
      const page = await firstValueFrom(
        this.client.deliveries(this.status(), last?.createdAt ?? null, 50),
      );
      this.items.update((items) => [...items, ...page]);
      this.more.set(page.length === 50);
    } finally {
      this.loading.set(false);
    }
  }

  protected async retry(item: EmailDelivery): Promise<void> {
    try {
      await firstValueFrom(this.client.retry(item.id));
      this.snackBar.open('Sending again.', undefined, { duration: 3000 });
      this.items.set([]);
      await this.load();
    } catch (error) {
      this.snackBar.open(describeError(error, 'It could not be sent again.'), 'Close', {
        duration: 6000,
      });
    }
  }

  protected when(epochSeconds: number): string {
    return new Date(epochSeconds * 1000).toLocaleString([], {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  }
}
