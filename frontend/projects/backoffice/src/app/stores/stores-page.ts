import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, debounceTime, of, startWith, switchMap, map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { StoreClient, StoreSummary } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { Session } from '../shared/session';
import { STATUS_LABELS } from './store-status';

/** Every branch for operator staff; a store owner sees their own. */
@Component({
  selector: 'app-stores-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header with-action">
      <div>
        <h1>{{ session.operator() ? 'Stores' : 'My store' }}</h1>
        <p>
          @if (session.operator()) {
            Onboard restaurants, check what keeps a draft from going live, and help owners.
          } @else {
            Your branches. Open one to edit its menu, hours and delivery area.
          }
        </p>
      </div>
      @if (session.operator()) {
        <a mat-flat-button routerLink="/stores/new"><fs-icon name="add" /> Onboard restaurant</a>
      }
    </header>

    <mat-card appearance="outlined" class="list-card">
      @if (session.operator()) {
        <div class="toolbar">
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
            <fs-icon matPrefix name="search" class="prefix" />
            <mat-label>Search by brand or branch</mat-label>
            <input matInput [value]="query()" (input)="query.set($any($event.target).value)" />
          </mat-form-field>
        </div>
      }
      @if (stores() === undefined) {
        <mat-progress-bar mode="indeterminate" />
      } @else if (stores()!.length === 0) {
        <div class="empty">
          <span class="empty-icon"><fs-icon name="storefront" /></span>
          @if (query()) {
            <h2>No stores match "{{ query() }}"</h2>
            <p>Try part of the brand or branch name.</p>
          } @else if (session.operator()) {
            <h2>No stores yet</h2>
            <p>
              Onboard your first restaurant. It stays a draft until its menu and delivery area are
              ready.
            </p>
            <a mat-flat-button routerLink="/stores/new"
              ><fs-icon name="add" /> Onboard restaurant</a
            >
          } @else {
            <h2>No branch is linked to your account</h2>
            <p>Ask the platform operator to give you access to your restaurant.</p>
          }
        </div>
      } @else {
        <ul class="rows" role="list">
          @for (store of stores(); track store.id) {
            <li>
              <a class="row" [routerLink]="['/stores', store.id]">
                <span class="avatar" aria-hidden="true">{{ store.brandName.charAt(0) }}</span>
                <span class="main">
                  <span class="name">
                    {{ store.brandName }}
                    @if (store.branchName) {
                      <span class="branch">· {{ store.branchName }}</span>
                    }
                  </span>
                  <span class="meta">
                    {{ store.city }} ·
                    {{ store.deliveryMode === 'SELF' ? 'Own delivery' : 'Platform riders' }}
                    @if (store.createdAt) {
                      · Added {{ store.createdAt * 1000 | date: 'mediumDate' }}
                    }
                  </span>
                </span>
                @if (store.temporarilyClosed) {
                  <span class="chip closed">Closed for now</span>
                }
                <span
                  class="chip"
                  [class.live]="store.status === 'LIVE'"
                  [class.paused]="store.status === 'PAUSED'"
                >
                  {{ statusLabel(store) }}
                </span>
                <fs-icon name="chevron_right" class="chevron" />
              </a>
            </li>
          }
        </ul>
      }
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .with-action {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .toolbar {
      padding: 16px 16px 8px;
    }
    .search {
      max-width: 420px;
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
      padding: 12px 16px;
      border-radius: 12px;
      color: inherit;
      text-decoration: none;
    }
    .row:hover,
    .row:focus-visible {
      background: var(--mat-sys-surface-container);
      outline: none;
    }
    .avatar {
      display: inline-grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      flex: none;
      border-radius: 12px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      font: var(--mat-sys-title-medium);
      font-weight: 600;
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
    .branch {
      color: var(--mat-sys-on-surface-variant);
      font-weight: 400;
    }
    .meta {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .chevron {
      color: var(--mat-sys-on-surface-variant);
    }
    :host-context([dir='rtl']) .chevron {
      transform: scaleX(-1);
    }
    @media (max-width: 599.98px) {
      .row {
        flex-wrap: wrap;
        gap: 8px 12px;
      }
      .main {
        flex-basis: calc(100% - 60px);
      }
      .chip {
        margin-inline-start: 56px;
      }
      .chip + .chip {
        margin-inline-start: 0;
      }
      .chevron {
        display: none;
      }
    }
  `,
})
export class StoresPage {
  protected readonly session = inject(Session);
  private readonly client = inject(StoreClient);
  private readonly router = inject(Router);

  protected readonly query = signal('');

  protected readonly stores = toSignal(
    toObservable(this.query).pipe(
      debounceTime(250),
      startWith(''),
      switchMap((query) =>
        this.client.list(query.trim() || undefined).pipe(
          map((stores): StoreSummary[] | undefined => stores),
          catchError(() => of([] as StoreSummary[])),
        ),
      ),
    ),
  );

  protected readonly single = computed(
    () => !this.session.operator() && this.stores()?.length === 1,
  );

  constructor() {
    // An owner with one branch goes straight to it.
    toObservable(this.single).subscribe((single) => {
      if (single) {
        void this.router.navigate(['/stores', this.stores()![0].id], { replaceUrl: true });
      }
    });
  }

  protected statusLabel(store: StoreSummary): string {
    return STATUS_LABELS[store.status];
  }
}
