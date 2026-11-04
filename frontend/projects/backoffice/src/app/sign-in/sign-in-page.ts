import { Component, inject } from '@angular/core';

import { SignIn } from '@foodstack/auth';
import { BrandingStore } from '@foodstack/ui';

/** The back office's own sign-in page; staff accounts are created by an administrator. */
@Component({
  selector: 'app-sign-in-page',
  imports: [SignIn],
  template: `
    <main class="page">
      <header class="brand">
        @if (branding.logoUrl(); as logo) {
          <img [src]="logo" alt="" width="56" height="56" />
        }
        <p class="name">{{ branding.branding().name }}</p>
        <p class="sub">Back office</p>
      </header>
      <fs-sign-in />
    </main>
  `,
  styles: `
    .page {
      min-block-size: 100dvh;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 24px;
      padding: 32px 16px;
      background: var(--mat-sys-surface);
    }
    .brand {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      text-align: center;
    }
    .brand img {
      border-radius: var(--mat-sys-corner-large);
      object-fit: cover;
      margin-block-end: 8px;
    }
    .name {
      margin: 0;
      font: var(--mat-sys-title-large);
      font-weight: 600;
      color: var(--mat-sys-on-surface);
    }
    .sub {
      margin: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class SignInPage {
  protected readonly branding = inject(BrandingStore);
}
