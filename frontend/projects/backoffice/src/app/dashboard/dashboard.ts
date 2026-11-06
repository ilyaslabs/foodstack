import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';

import { BrandingStore, Icon } from '@foodstack/ui';

import { Session } from '../shared/session';

/** First page after sign-in: where the platform stands and what to do next. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatButtonModule, MatCardModule, Icon],
  template: `
    <header class="fs-page-header">
      <h1>Overview</h1>
      <p>
        Welcome back{{ me()?.displayName ? ', ' + me()?.displayName : '' }}. Here is what to do
        next.
      </p>
    </header>

    <mat-card appearance="outlined" class="setup">
      <mat-card-header>
        <mat-card-title>Get started</mat-card-title>
        <mat-card-subtitle>{{
          session.operator()
            ? 'Set up the platform, then bring stores on.'
            : 'A few steps before customers can order.'
        }}</mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <ol class="steps">
          @if (canManageSettings()) {
            <li [class.done]="brandingDone()">
              <span class="step-icon">
                <fs-icon [name]="brandingDone() ? 'check_circle' : 'palette'" />
              </span>
              <div class="step-text">
                <div class="step-title">Set up your brand</div>
                <div class="step-description">
                  @if (brandingDone()) {
                    Done. You can change name, colours, font and logo at any time.
                  } @else {
                    Name, colours, font and logo. Customers see changes within a minute.
                  }
                </div>
              </div>
              @if (canManageSettings()) {
                @if (brandingDone()) {
                  <a mat-stroked-button routerLink="/settings/branding">Edit</a>
                } @else {
                  <a mat-flat-button routerLink="/settings/branding">Start</a>
                }
              }
            </li>
          }
          @if (canManageSettings()) {
            <li>
              <span class="step-icon"><fs-icon name="language" /></span>
              <div class="step-text">
                <div class="step-title">Pick languages and currency</div>
                <div class="step-description">
                  Customers switch between your languages. Stores type menus in each one.
                </div>
              </div>
              <a mat-stroked-button routerLink="/settings/locale">Open</a>
            </li>
          }
          @if (session.operator()) {
            <li>
              <span class="step-icon"><fs-icon name="storefront" /></span>
              <div class="step-text">
                <div class="step-title">Add stores and menus</div>
                <div class="step-description">
                  Onboard a store, give its owner a login, and go live when it is ready.
                </div>
              </div>
              <a mat-flat-button routerLink="/stores">Open stores</a>
            </li>
          } @else if (session.storePortal()) {
            <li>
              <span class="step-icon"><fs-icon name="restaurant" /></span>
              <div class="step-text">
                <div class="step-title">Finish your store</div>
                <div class="step-description">Add your menu, opening hours and delivery area.</div>
              </div>
              <a mat-flat-button [routerLink]="storeLink()">Open my store</a>
            </li>
          }
        </ol>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .setup {
      --mat-card-title-text-font: var(--mat-sys-title-large-font);
    }
    mat-card-header {
      padding: 20px 24px 8px;
    }
    mat-card-content {
      padding: 0 8px 8px;
    }
    .steps {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    li {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 16px;
      border-radius: 16px;
    }
    li + li {
      border-block-start: 1px solid var(--mat-sys-outline-variant);
      border-radius: 0;
    }
    .step-icon {
      display: inline-grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      flex: none;
      border-radius: 12px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .done .step-icon {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .later .step-icon {
      background: var(--mat-sys-surface-container-highest);
      color: var(--mat-sys-on-surface-variant);
    }
    .step-text {
      flex: 1 1 auto;
      min-width: 0;
    }
    .step-title {
      font: var(--mat-sys-title-medium);
    }
    .later .step-title {
      color: var(--mat-sys-on-surface-variant);
    }
    .step-description {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .badge {
      flex: none;
      padding: 4px 12px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    @media (max-width: 599.98px) {
      li {
        flex-wrap: wrap;
      }
      .step-text {
        flex-basis: calc(100% - 60px);
      }
      li > a,
      .badge {
        margin-inline-start: 60px;
      }
    }
  `,
})
export class Dashboard {
  private readonly branding = inject(BrandingStore);

  protected readonly session = inject(Session);
  protected readonly me = this.session.me;
  protected readonly storeLink = computed(() => {
    const storeIds = this.me()?.storeIds ?? [];
    const brandOwner = (this.me()?.brandIds ?? []).length > 0;
    return !brandOwner && storeIds.length === 1 ? ['/stores', storeIds[0]] : ['/stores'];
  });
  protected readonly brandingDone = computed(() => this.branding.branding().version > 0);
  protected readonly canManageSettings = computed(
    () => this.me()?.permissions.includes('settings:manage') ?? false,
  );
}
