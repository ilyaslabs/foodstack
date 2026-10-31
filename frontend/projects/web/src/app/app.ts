import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

import { AuthService } from '@foodstack/auth';
import { BrandHeader } from '@foodstack/ui';

@Component({
  imports: [RouterOutlet, MatButtonModule, BrandHeader],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(AuthService);

  protected signIn(): void {
    void this.auth.signIn('/');
  }

  protected signOut(): void {
    this.auth.signOut();
  }
}
