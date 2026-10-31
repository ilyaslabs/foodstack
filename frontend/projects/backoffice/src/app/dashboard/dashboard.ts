import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';

import { MeClient } from '@foodstack/api-client';
import { BrandingStore, Icon } from '@foodstack/ui';

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
        <mat-card-subtitle>Finish these steps before you invite stores.</mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <ol class="steps">
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
                  Name, colours, font and logo. Customers see changes right away.
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
          <li class="later">
            <span class="step-icon"><fs-icon name="storefront" /></span>
            <div class="step-text">
              <div class="step-title">Add stores and menus</div>
              <div class="step-description">Arrives with the next release.</div>
            </div>
            <span class="badge">Coming soon</span>
          </li>
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

  protected readonly me = toSignal(inject(MeClient).me());
  protected readonly brandingDone = computed(() => this.branding.branding().version > 0);
  protected readonly canManageSettings = computed(
    () => this.me()?.permissions.includes('settings:manage') ?? false,
  );
}
