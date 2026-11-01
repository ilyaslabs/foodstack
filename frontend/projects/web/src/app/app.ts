import { Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';

import { AuthService } from '@foodstack/auth';
import { BrandHeader, Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from './i18n';

@Component({
  imports: [RouterOutlet, MatButtonModule, MatMenuModule, BrandHeader, Icon],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly locale = inject(LocaleStore);
  protected readonly ui = inject(Ui);

  /** Offered only when the operator enabled more than one language. */
  protected readonly languages = computed(() => this.locale.languages());

  protected signIn(): void {
    void this.auth.signIn('/');
  }

  protected signOut(): void {
    this.auth.signOut();
  }
}
