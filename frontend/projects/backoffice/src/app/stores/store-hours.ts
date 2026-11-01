import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import { Closure, StoreClient, TimeRange, WEEKDAYS, Weekday } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { StoreContext } from './store-context';

const DAY_NAMES: Record<Weekday, string> = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

/** Weekly hours, with ranges past midnight, and holiday closures (PRD ST-2). */
@Component({
  selector: 'app-store-hours',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    Icon,
  ],
  template: `
    <mat-card appearance="outlined" class="card">
      <section class="card-section">
        <h2>Opening hours</h2>
        <p class="section-hint">
          Times are in {{ context.store()?.timezone }}. A closing time before the opening time runs
          past midnight.
        </p>
        <ul class="days" role="list">
          @for (day of weekdays; track day) {
            <li class="day">
              <mat-slide-toggle
                [checked]="hours()[day].length > 0"
                (change)="toggleDay(day, $event.checked)"
                class="day-toggle"
              >
                {{ dayNames[day] }}
              </mat-slide-toggle>
              <div class="ranges">
                @for (range of hours()[day]; track $index; let i = $index) {
                  <div class="range">
                    <input
                      type="time"
                      [value]="range.open"
                      (change)="setTime(day, i, 'open', $event)"
                      [attr.aria-label]="dayNames[day] + ' opens'"
                    />
                    <span class="to">to</span>
                    <input
                      type="time"
                      [value]="range.close"
                      (change)="setTime(day, i, 'close', $event)"
                      [attr.aria-label]="dayNames[day] + ' closes'"
                    />
                    <button
                      mat-icon-button
                      type="button"
                      (click)="removeRange(day, i)"
                      [attr.aria-label]="'Remove ' + dayNames[day] + ' range'"
                    >
                      <fs-icon name="close" />
                    </button>
                  </div>
                } @empty {
                  <span class="closed">Closed</span>
                }
              </div>
              @if (hours()[day].length > 0 && hours()[day].length < 4) {
                <button mat-button type="button" (click)="addRange(day)">
                  <fs-icon name="add" /> Add hours
                </button>
              }
            </li>
          }
        </ul>
        <div>
          <button mat-stroked-button type="button" (click)="copyMonday()">
            Copy Monday to every day
          </button>
        </div>
      </section>
      <section class="card-section">
        <h2>Holiday closures</h2>
        <p class="section-hint">Closed all day, both dates included.</p>
        @for (closure of closures(); track $index; let i = $index) {
          <div class="closure">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>From</mat-label>
              <input
                matInput
                type="date"
                [value]="closure.from"
                (change)="setClosure(i, 'from', $event)"
              />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>To</mat-label>
              <input
                matInput
                type="date"
                [value]="closure.to"
                (change)="setClosure(i, 'to', $event)"
              />
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic" class="reason">
              <mat-label>Reason (optional)</mat-label>
              <input
                matInput
                maxlength="120"
                [value]="closure.reason ?? ''"
                (input)="setClosure(i, 'reason', $event)"
              />
            </mat-form-field>
            <button
              mat-icon-button
              type="button"
              (click)="removeClosure(i)"
              aria-label="Remove closure"
            >
              <fs-icon name="delete" />
            </button>
          </div>
        }
        <div>
          <button mat-stroked-button type="button" (click)="addClosure()">
            <fs-icon name="add" /> Add closure
          </button>
        </div>
      </section>
      <div class="actions">
        <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">
          {{ saving() ? 'Saving…' : 'Save hours' }}
        </button>
      </div>
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    :host {
      display: block;
      padding-block: 16px;
    }
    .card {
      max-width: 880px;
    }
    .days {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .day {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 16px;
      padding: 10px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .day-toggle {
      inline-size: 160px;
    }
    .ranges {
      display: flex;
      flex-direction: column;
      gap: 6px;
      flex: 1 1 auto;
    }
    .range {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .range input {
      font: var(--mat-sys-body-large);
      color: var(--mat-sys-on-surface);
      background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline);
      border-radius: 8px;
      padding: 8px 10px;
      color-scheme: light dark;
    }
    .to,
    .closed {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .closure {
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
    }
    .closure mat-form-field {
      inline-size: 170px;
    }
    .closure .reason {
      flex: 1 1 200px;
    }
  `,
})
export class StoreHours {
  protected readonly context = inject(StoreContext);
  private readonly client = inject(StoreClient);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly weekdays = WEEKDAYS;
  protected readonly dayNames = DAY_NAMES;
  protected readonly hours = signal<Record<Weekday, TimeRange[]>>(emptyWeek());
  protected readonly closures = signal<Closure[]>([]);
  protected readonly saving = signal(false);

  constructor() {
    effect(() => {
      const store = this.context.store();
      if (!store) return;
      const week = emptyWeek();
      for (const day of WEEKDAYS) week[day] = [...(store.openingHours[day] ?? [])];
      this.hours.set(week);
      this.closures.set(store.closures.map((c) => ({ ...c })));
    });
  }

  protected toggleDay(day: Weekday, open: boolean): void {
    this.patch(day, open ? [{ open: '11:00', close: '23:00' }] : []);
  }

  protected addRange(day: Weekday): void {
    this.patch(day, [...this.hours()[day], { open: '18:00', close: '23:00' }]);
  }

  protected removeRange(day: Weekday, index: number): void {
    this.patch(
      day,
      this.hours()[day].filter((_, i) => i !== index),
    );
  }

  protected setTime(day: Weekday, index: number, field: 'open' | 'close', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.patch(
      day,
      this.hours()[day].map((r, i) => (i === index ? { ...r, [field]: value } : r)),
    );
  }

  protected copyMonday(): void {
    const monday = this.hours().MONDAY;
    const week = emptyWeek();
    for (const day of WEEKDAYS) week[day] = monday.map((r) => ({ ...r }));
    this.hours.set(week);
  }

  protected addClosure(): void {
    const today = new Date().toISOString().slice(0, 10);
    this.closures.update((list) => [...list, { from: today, to: today, reason: null }]);
  }

  protected setClosure(index: number, field: keyof Closure, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.closures.update((list) =>
      list.map((c, i) => (i === index ? { ...c, [field]: value || null } : c)),
    );
  }

  protected removeClosure(index: number): void {
    this.closures.update((list) => list.filter((_, i) => i !== index));
  }

  protected async save(): Promise<void> {
    const store = this.context.store()!;
    this.saving.set(true);
    try {
      this.context.set(
        await firstValueFrom(
          this.client.updateHours(
            store.id,
            this.hours(),
            this.closures().filter((c) => c.from && c.to),
            this.context.etag(),
          ),
        ),
      );
      this.snackBar.open('Hours saved.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The hours could not be saved.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      this.saving.set(false);
    }
  }

  private patch(day: Weekday, ranges: TimeRange[]): void {
    this.hours.update((week) => ({ ...week, [day]: ranges }));
  }
}

function emptyWeek(): Record<Weekday, TimeRange[]> {
  return {
    MONDAY: [],
    TUESDAY: [],
    WEDNESDAY: [],
    THURSDAY: [],
    FRIDAY: [],
    SATURDAY: [],
    SUNDAY: [],
  };
}
