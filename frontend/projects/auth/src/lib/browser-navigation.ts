import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

/** Full-page navigation to another origin (the login page), replaceable in tests. */
@Injectable({ providedIn: 'root' })
export class BrowserNavigation {
  private readonly document = inject(DOCUMENT);

  assign(url: string): void {
    this.document.location.assign(url);
  }
}
