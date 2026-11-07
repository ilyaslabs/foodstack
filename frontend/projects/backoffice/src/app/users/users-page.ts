import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, combineLatest, debounceTime, map, of, startWith, switchMap } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';

import {
  Role,
  TwoStepClient,
  UserAccount,
  UserPage,
  UserStatus,
  UsersClient,
} from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { Session } from '../shared/session';

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin',
  FINANCE: 'Finance',
  SUPPORT: 'Support',
  STORE_OWNER: 'Store owner',
  STORE_STAFF: 'Store staff',
  CUSTOMER: 'Customer',
};

const PAGE = 25;

interface Filters {
  q: string;
  role: Role | '';
  status: UserStatus | '';
}

/** Every account on the platform, customers included, with blocking (PRD ID-4). */
@Component({
  selector: 'app-users-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Users</h1>
      <p>
        Everyone with an account: customers, restaurant owners and your staff. Block an account to
        end its sessions at once.
      </p>
    </header>

    <mat-card appearance="outlined" class="list-card">
      <div class="toolbar">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
          <fs-icon matPrefix name="search" class="prefix" />
          <mat-label>Name or full email</mat-label>
          <input
            matInput
            type="search"
            [value]="query()"
            (input)="query.set($any($event.target).value)"
          />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="role">
          <mat-label>Role</mat-label>
          <mat-select [value]="role()" (valueChange)="role.set($event)">
            <mat-option value="">All roles</mat-option>
            @for (entry of roles; track entry[0]) {
              <mat-option [value]="entry[0]">{{ entry[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-button-toggle-group
          class="status"
          hideSingleSelectionIndicator
          aria-label="Account status"
          [value]="status()"
          (change)="status.set($event.value)"
        >
          <mat-button-toggle value="">All</mat-button-toggle>
          <mat-button-toggle value="ACTIVE">Active</mat-button-toggle>
          <mat-button-toggle value="BLOCKED">Blocked</mat-button-toggle>
        </mat-button-toggle-group>
      </div>

      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (users(); as list) {
        @if (list.length === 0 && !loading()) {
          <div class="empty">
            <span class="empty-icon"><fs-icon name="group" /></span>
            @if (filtered()) {
              <h2>No users match</h2>
              <p>Search by part of a name, or by the whole email address.</p>
            } @else {
              <h2>No users yet</h2>
              <p>Customers appear here when they create an account in the app.</p>
            }
          </div>
        } @else {
          <ul class="rows" role="list">
            @for (user of list; track user.id) {
              <li class="row" [class.blocked]="user.status === 'BLOCKED'">
                <span class="avatar" aria-hidden="true">{{ initials(user) }}</span>
                <span class="main">
                  <span class="name">
                    {{ user.displayName }}
                    @if (user.id === session.me()?.id) {
                      <span class="you">(you)</span>
                    }
                  </span>
                  <span class="meta">{{ user.email }}</span>
                  <span class="meta">
                    {{ roleNames(user) }} · Joined {{ user.createdAt * 1000 | date: 'mediumDate' }}
                  </span>
                </span>
                @if (user.status === 'BLOCKED') {
                  <span class="chip closed">Blocked</span>
                } @else {
                  <span class="chip live">Active</span>
                }
                @if (canManage() && user.id !== session.me()?.id) {
                  <button
                    mat-icon-button
                    type="button"
                    [matMenuTriggerFor]="actions"
                    [attr.aria-label]="'Actions for ' + user.displayName"
                  >
                    <fs-icon name="more_vert" />
                  </button>
                  <mat-menu #actions="matMenu" xPosition="before">
                    @if (user.status === 'BLOCKED') {
                      <button mat-menu-item type="button" (click)="unblock(user)">
                        <fs-icon matMenuItemIcon name="lock_open" />
                        <span>Unblock</span>
                      </button>
                    } @else {
                      <button mat-menu-item type="button" (click)="block(user)">
                        <fs-icon matMenuItemIcon name="block" />
                        <span>Block</span>
                      </button>
                    }
                    @if (staff(user)) {
                      <button mat-menu-item type="button" (click)="resetTwoStep(user)">
                        <fs-icon matMenuItemIcon name="key" />
                        <span>Reset two-step sign-in</span>
                      </button>
                    }
                  </mat-menu>
                }
              </li>
            }
          </ul>
          @if (cursor()) {
            <div class="more">
              <button mat-stroked-button type="button" [disabled]="loading()" (click)="loadMore()">
                Show more
              </button>
            </div>
          }
        }
      }
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      padding: 16px 16px 8px;
    }
    .search {
      flex: 1 1 280px;
      max-width: 420px;
    }
    .role {
      flex: 0 1 200px;
    }
    .prefix {
      margin-inline: 12px 4px;
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
    }
    .row:hover {
      background: var(--mat-sys-surface-container);
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      flex: none;
      border-radius: 50%;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      font: var(--mat-sys-title-small);
      font-weight: 600;
    }
    .blocked .avatar {
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .main {
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      min-width: 0;
    }
    .name {
      font: var(--mat-sys-title-medium);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .you {
      font-weight: 400;
      color: var(--mat-sys-on-surface-variant);
    }
    .meta {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 8px 16px 20px;
    }
    @media (max-width: 599.98px) {
      .search,
      .role {
        flex-basis: 100%;
        max-width: none;
      }
      .row {
        flex-wrap: wrap;
        gap: 8px 12px;
      }
      .main {
        flex-basis: calc(100% - 60px - 48px);
      }
      .chip {
        margin-inline-start: 56px;
      }
    }
  `,
})
export class UsersPage {
  protected readonly session = inject(Session);
  private readonly client = inject(UsersClient);
  private readonly twoStep = inject(TwoStepClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly roles = Object.entries(ROLE_LABELS) as [Role, string][];

  protected readonly query = signal('');
  protected readonly role = signal<Role | ''>('');
  protected readonly status = signal<UserStatus | ''>('');

  protected readonly canManage = computed(() => this.session.can('identity:users:manage'));
  protected readonly filtered = computed(
    () => !!(this.query().trim() || this.role() || this.status()),
  );

  /** Pages loaded after the first one with "Show more". */
  private readonly extra = signal<UserAccount[]>([]);
  private readonly extraCursor = signal<string | null | undefined>(undefined);
  /** Accounts changed on this page (blocked or unblocked), shown without reloading. */
  private readonly changed = signal<Record<string, UserAccount>>({});
  protected readonly loading = signal(true);

  private readonly firstPage = toSignal(
    combineLatest([
      toObservable(this.query).pipe(debounceTime(300), startWith('')),
      toObservable(this.role),
      toObservable(this.status),
    ]).pipe(
      map(([q, role, status]): Filters => ({ q: q.trim(), role, status })),
      switchMap((filters) => {
        this.loading.set(true);
        this.extra.set([]);
        this.extraCursor.set(undefined);
        return this.client.search(this.toQuery(filters)).pipe(
          catchError((error) => {
            this.snackBar.open(describeError(error, 'The users could not be loaded.'), 'Close', {
              duration: 6000,
            });
            return of<UserPage>({ items: [], nextCursor: null });
          }),
        );
      }),
      map((page) => {
        this.loading.set(false);
        return page;
      }),
    ),
  );

  protected readonly users = computed(() => {
    const page = this.firstPage();
    if (!page) {
      return undefined;
    }
    const changed = this.changed();
    return [...page.items, ...this.extra()].map((user) => changed[user.id] ?? user);
  });

  protected readonly cursor = computed(() => {
    const extra = this.extraCursor();
    return extra !== undefined ? extra : (this.firstPage()?.nextCursor ?? null);
  });

  protected async loadMore(): Promise<void> {
    const after = this.cursor();
    if (!after) {
      return;
    }
    this.loading.set(true);
    try {
      const page = await firstValueFrom(
        this.client.search({
          ...this.toQuery({ q: this.query().trim(), role: this.role(), status: this.status() }),
          after,
        }),
      );
      this.extra.update((users) => [...users, ...page.items]);
      this.extraCursor.set(page.nextCursor);
    } catch (error) {
      this.snackBar.open(describeError(error, 'More users could not be loaded.'), 'Close', {
        duration: 6000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  protected async block(user: UserAccount): Promise<void> {
    const reason = await confirmAction(this.dialog, {
      title: `Block ${user.displayName}?`,
      message:
        'They are signed out everywhere at once and cannot sign in until you unblock them. Their orders and data stay.',
      confirm: 'Block',
      danger: true,
      inputLabel: 'Reason',
      inputHint: 'Kept with the account, for your team',
      inputRequired: true,
    });
    if (!reason) {
      return;
    }
    try {
      const updated = await firstValueFrom(this.client.block(user.id, reason));
      this.changed.update((all) => ({ ...all, [user.id]: updated }));
      this.snackBar.open(`${user.displayName} is blocked`, undefined, { duration: 4000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The account could not be blocked.'), 'Close', {
        duration: 6000,
      });
    }
  }

  /** Back-office accounts can have two-step sign-in; customers sign in without it. */
  protected staff(user: UserAccount): boolean {
    return user.roles.some((role) => role !== 'CUSTOMER');
  }

  /** For a lost phone and lost recovery codes: removes the authenticator app from the account. */
  protected async resetTwoStep(user: UserAccount): Promise<void> {
    const confirmed = await confirmAction(this.dialog, {
      title: `Reset two-step sign-in for ${user.displayName}?`,
      message:
        'Their authenticator app and recovery codes stop working and they are signed out everywhere. Check who is asking first. If their role requires two-step sign-in, they set up a new app at their next sign-in.',
      confirm: 'Reset',
      danger: true,
    });
    if (confirmed === null) {
      return;
    }
    try {
      await firstValueFrom(this.twoStep.reset(user.id));
      this.snackBar.open(`Two-step sign-in is reset for ${user.displayName}`, undefined, {
        duration: 4000,
      });
    } catch (error) {
      this.snackBar.open(describeError(error, 'Two-step sign-in could not be reset.'), 'Close', {
        duration: 6000,
      });
    }
  }

  protected async unblock(user: UserAccount): Promise<void> {
    try {
      const updated = await firstValueFrom(this.client.unblock(user.id));
      this.changed.update((all) => ({ ...all, [user.id]: updated }));
      this.snackBar.open(`${user.displayName} can sign in again`, undefined, { duration: 4000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The account could not be unblocked.'), 'Close', {
        duration: 6000,
      });
    }
  }

  protected initials(user: UserAccount): string {
    const letters = user.displayName
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join('');
    return letters || '?';
  }

  protected roleNames(user: UserAccount): string {
    return [...user.roles]
      .sort()
      .map((role) => ROLE_LABELS[role] ?? role)
      .join(', ');
  }

  private toQuery(filters: Filters) {
    return {
      q: filters.q || undefined,
      role: filters.role || undefined,
      status: filters.status || undefined,
      limit: PAGE,
    };
  }
}
