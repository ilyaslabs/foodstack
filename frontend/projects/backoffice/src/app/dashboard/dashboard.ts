import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';

import { MeClient } from '@foodstack/api-client';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatButtonModule, MatCardModule],
  template: `
    <h1>Overview</h1>
    @if (me(); as me) {
      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title>Welcome, {{ me.displayName }}</mat-card-title>
          <mat-card-subtitle>{{ me.email }} · {{ me.roles.join(', ') }}</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <p>Your platform is running. Start by setting your brand: name, colours and logo.</p>
        </mat-card-content>
        <mat-card-actions>
          @if (me.permissions.includes('settings:manage')) {
            <a mat-flat-button routerLink="/settings/branding">Set up branding</a>
          }
        </mat-card-actions>
      </mat-card>
    }
  `,
})
export class Dashboard {
  protected readonly me = toSignal(inject(MeClient).me());
}
