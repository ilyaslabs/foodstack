import { Component, DestroyRef, afterNextRender, inject, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';

import { InboxItem, NotificationClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';

const REFRESH_MS = 60_000;

/**
 * The bell (PRD NO-1): the unread count, refreshed on each page change and every minute while the
 * tab is visible, and the latest messages; opening one marks it read and goes to its page.
 */
@Component({
  selector: 'app-notification-bell',
  imports: [MatBadgeModule, MatButtonModule, MatMenuModule, Icon],
  template: `
    <button
      mat-icon-button
      type="button"
      class="bell"
      [matMenuTriggerFor]="menu"
      (menuOpened)="open()"
      [attr.aria-label]="ui.t('notifications') + (unread() ? ' (' + unread() + ')' : '')"
    >
      <fs-icon
        name="notifications"
        [matBadge]="unread() > 9 ? '9+' : unread()"
        [matBadgeHidden]="!unread()"
        matBadgeSize="small"
        matBadgeColor="warn"
        aria-hidden="false"
      />
    </button>
    <mat-menu #menu="matMenu" xPosition="before" class="fs-notifications">
      <div class="head" (click)="$event.stopPropagation()">
        <span class="title">{{ ui.t('notifications') }}</span>
        @if (unread()) {
          <button mat-button type="button" (click)="readAll()">{{ ui.t('markAllRead') }}</button>
        }
      </div>
      @for (item of items(); track item.id) {
        <button
          mat-menu-item
          type="button"
          class="item"
          [class.unread]="!item.read"
          (click)="go(item)"
        >
          <span class="item-title">{{ item.title }}</span>
          <span class="item-body">{{ item.body }}</span>
          <span class="item-time">{{ ago(item.createdAt) }}</span>
        </button>
      } @empty {
        <p class="empty">{{ ui.t('noNotifications') }}</p>
      }
    </mat-menu>
  `,
  styles: `
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 4px 8px 4px 16px;
      flex-wrap: wrap;
    }
    .title {
      font: var(--mat-sys-title-small);
    }
    .item {
      block-size: auto;
      min-block-size: 64px;
      max-inline-size: 360px;
      padding-block: 10px;
      white-space: normal;
      line-height: 1.3;
    }
    .item ::ng-deep .mat-mdc-menu-item-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .item.unread {
      background: color-mix(in srgb, var(--mat-sys-primary) 8%, transparent);
    }
    .item-title {
      font: var(--mat-sys-title-small);
    }
    .item-body {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .item-time {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .empty {
      margin: 0;
      padding: 16px;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class NotificationBell {
  protected readonly ui = inject(Ui);
  private readonly locale = inject(LocaleStore);
  private readonly client = inject(NotificationClient);
  private readonly router = inject(Router);

  protected readonly unread = signal(0);
  protected readonly items = signal<InboxItem[]>([]);

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      void this.count();
      const timer = setInterval(() => {
        if (document.visibilityState === 'visible') void this.count();
      }, REFRESH_MS);
      const navigation = this.router.events
        .pipe(filter((event) => event instanceof NavigationEnd))
        .subscribe(() => void this.count());
      destroyRef.onDestroy(() => {
        clearInterval(timer);
        navigation.unsubscribe();
      });
    });
  }

  protected async open(): Promise<void> {
    try {
      this.items.set(await firstValueFrom(this.client.list(null, 10)));
    } catch {
      // the menu shows what it had
    }
  }

  protected async go(item: InboxItem): Promise<void> {
    if (!item.read) {
      this.items.update((items) => items.map((i) => (i.id === item.id ? { ...i, read: true } : i)));
      this.unread.update((n) => Math.max(0, n - 1));
      void firstValueFrom(this.client.markRead(item.id)).catch(() => undefined);
    }
    if (item.link) await this.router.navigateByUrl(item.link);
  }

  protected async readAll(): Promise<void> {
    await firstValueFrom(this.client.markAllRead());
    this.items.update((items) => items.map((i) => ({ ...i, read: true })));
    this.unread.set(0);
  }

  protected ago(epochSeconds: number): string {
    const seconds = Math.round(epochSeconds - Date.now() / 1000);
    const format = new Intl.RelativeTimeFormat(this.locale.language(), { numeric: 'auto' });
    const abs = Math.abs(seconds);
    if (abs < 60) return format.format(seconds, 'second');
    if (abs < 3600) return format.format(Math.round(seconds / 60), 'minute');
    if (abs < 86400) return format.format(Math.round(seconds / 3600), 'hour');
    return format.format(Math.round(seconds / 86400), 'day');
  }

  private async count(): Promise<void> {
    try {
      this.unread.set(await firstValueFrom(this.client.unread()));
    } catch {
      // keep the last count
    }
  }
}
