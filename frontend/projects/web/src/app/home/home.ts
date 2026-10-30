import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

import { AuthService } from '@foodstack/auth';
import { BrandingStore } from '@foodstack/ui';

/** The storefront landing page. Store search and menus arrive in M1; M0 shows the operator's brand. */
@Component({
  selector: 'app-home',
  imports: [MatButtonModule],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly branding = inject(BrandingStore);
  protected readonly auth = inject(AuthService);

  protected signIn(): void {
    void this.auth.signIn('/');
  }
}
