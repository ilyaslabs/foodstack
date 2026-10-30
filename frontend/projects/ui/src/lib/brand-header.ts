import { Component, inject } from '@angular/core';
import { MatToolbarModule } from '@angular/material/toolbar';

import { BrandingStore } from './branding-store';

/** The operator's logo and name, for the top of every app. */
@Component({
  selector: 'fs-brand-header',
  imports: [MatToolbarModule],
  template: `
    <mat-toolbar class="fs-brand-header">
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
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
      gap: 12px;
    }
    .fs-brand-logo {
      border-radius: 8px;
      object-fit: cover;
    }
    .fs-brand-name {
      font-family: var(--fs-font-family);
      font-weight: 500;
    }
    .fs-brand-spacer {
      flex: 1 1 auto;
    }
  `,
})
export class BrandHeader {
  protected readonly store = inject(BrandingStore);
}
