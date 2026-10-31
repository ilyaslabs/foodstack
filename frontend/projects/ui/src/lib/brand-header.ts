import { Component, inject } from '@angular/core';
import { MatToolbarModule } from '@angular/material/toolbar';

import { BrandingStore } from './branding-store';

/** The operator's logo and name, for the top of every app. */
@Component({
  selector: 'fs-brand-header',
  imports: [MatToolbarModule],
  template: `
    <mat-toolbar class="fs-brand-header">
      <ng-content select="[fsHeaderStart]" />
      @if (store.logoUrl(); as logo) {
        <img class="fs-brand-logo" [src]="logo" alt="" width="40" height="40" />
      }
      <span class="fs-brand-name">{{ store.branding().name }}</span>
      <span class="fs-brand-spacer"></span>
      <ng-content />
    </mat-toolbar>
  `,
  styles: `
    .fs-brand-header {
      /* Material reads these for the bar and for every button projected into it. */
      --mat-toolbar-container-background-color: var(--mat-sys-primary);
      --mat-toolbar-container-text-color: var(--mat-sys-on-primary);
      --mat-button-text-label-text-color: var(--mat-sys-on-primary);
      --mat-button-text-state-layer-color: var(--mat-sys-on-primary);
      --mat-icon-button-icon-color: var(--mat-sys-on-primary);
      --mat-icon-button-state-layer-color: var(--mat-sys-on-primary);
      position: sticky;
      inset-block-start: 0;
      z-index: 10;
      gap: 12px;
      padding-inline: 16px;
      box-shadow: var(--mat-sys-level1);
    }
    @media (prefers-color-scheme: dark) {
      .fs-brand-header {
        --mat-toolbar-container-background-color: var(--mat-sys-surface-container);
        --mat-toolbar-container-text-color: var(--mat-sys-on-surface);
        --mat-button-text-label-text-color: var(--mat-sys-on-surface);
        --mat-button-text-state-layer-color: var(--mat-sys-on-surface);
        --mat-icon-button-icon-color: var(--mat-sys-on-surface);
        --mat-icon-button-state-layer-color: var(--mat-sys-on-surface);
        border-block-end: 1px solid var(--mat-sys-outline-variant);
        box-shadow: none;
      }
      .fs-brand-name {
        color: var(--mat-sys-primary);
      }
    }
    .fs-brand-logo {
      border-radius: 8px;
      object-fit: cover;
    }
    .fs-brand-name {
      font: var(--mat-sys-title-large);
      font-weight: 600;
      letter-spacing: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .fs-brand-spacer {
      flex: 1 1 auto;
    }
  `,
})
export class BrandHeader {
  protected readonly store = inject(BrandingStore);
}
