import { Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { Earnings, RiderClient } from '@foodstack/api-client';

import { EarningsView } from './earnings-view';

/** What the signed-in rider earned, by day, with the deliveries behind it (PRD DR-7). */
@Component({
  selector: 'app-rider-earnings-page',
  imports: [MatButtonToggleModule, MatCardModule, MatProgressBarModule, EarningsView],
  template: `
    <header class="fs-page-header">
      <h1>Earnings</h1>
      <p>
        What you earned per delivery. Tips are all yours; cash you collected is handed in to the
        operator.
      </p>
    </header>
    <mat-button-toggle-group
      class="range"
      aria-label="Period"
      [value]="days()"
      (change)="load($event.value)"
    >
      <mat-button-toggle [value]="7">7 days</mat-button-toggle>
      <mat-button-toggle [value]="14">14 days</mat-button-toggle>
      <mat-button-toggle [value]="31">31 days</mat-button-toggle>
    </mat-button-toggle-group>
    @if (earnings(); as e) {
      <mat-card appearance="outlined" class="card">
        <app-earnings-view [earnings]="e" />
      </mat-card>
    } @else if (failed()) {
      <p>Your earnings could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-inline-size: 760px;
    }
    .range {
      align-self: flex-start;
    }
    .card {
      padding: 20px;
    }
  `,
})
export class RiderEarningsPage implements OnInit {
  private readonly riders = inject(RiderClient);

  protected readonly days = signal(7);
  protected readonly earnings = signal<Earnings | null>(null);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    void this.load(7);
  }

  protected async load(days: number): Promise<void> {
    this.days.set(days);
    this.failed.set(false);
    try {
      this.earnings.set(await firstValueFrom(this.riders.myEarnings(days)));
    } catch {
      this.failed.set(true);
    }
  }
}
