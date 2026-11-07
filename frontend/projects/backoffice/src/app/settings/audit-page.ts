import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AuditClient, AuditKind, AuditLine } from '@foodstack/api-client';
import { Icon, IconName } from '@foodstack/ui';

import { describeError } from '../shared/errors';

const PAGE = 50;

const VERBS: Record<string, string> = {
  POST: 'Created or ran',
  PUT: 'Changed',
  PATCH: 'Changed',
  DELETE: 'Removed',
};

/** One field of a change: its value before and after. */
export interface FieldChange {
  field: string;
  before: string;
  after: string;
  changed: boolean;
}

/**
 * Who changed what in the back office, with the state before and after, and who signed in
 * (PRD AD-3, ID-8). Operator staff with `identity:audit:read` only.
 */
@Component({
  selector: 'app-audit-page',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Audit log</h1>
      <p>
        Every change made in the back office, by whom and when, with the values before and after.
        Sign-ins to the back office are here too. Passwords and keys are never shown.
      </p>
    </header>
    <div class="filters">
      <mat-button-toggle-group
        [value]="kind()"
        (change)="setKind($event.value)"
        aria-label="Show"
        class="kinds"
      >
        <mat-button-toggle [value]="null">All</mat-button-toggle>
        <mat-button-toggle value="WRITE">Changes</mat-button-toggle>
        <mat-button-toggle value="SIGN_IN">Sign-ins</mat-button-toggle>
        <mat-button-toggle value="SIGN_IN_FAILED">Refused sign-ins</mat-button-toggle>
      </mat-button-toggle-group>
      <form (ngSubmit)="search()" class="search">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Search</mat-label>
          <fs-icon matPrefix name="search" />
          <input matInput name="q" [(ngModel)]="text" placeholder="A person, page or path" />
        </mat-form-field>
      </form>
    </div>
    <mat-card appearance="outlined">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @for (line of lines(); track line.id) {
        <div class="line">
          <span class="badge" [attr.data-kind]="line.kind" aria-hidden="true">
            <fs-icon [name]="icon(line)" />
          </span>
          <div class="text">
            <div class="what">
              <strong>{{ line.actorName ?? 'Someone' }}</strong> {{ summary(line) }}
            </div>
            <div class="muted">
              {{ line.at * 1000 | date: 'medium' }}
              @if (line.roles.length) {
                · {{ line.roles.join(', ').toLowerCase().replaceAll('_', ' ') }}
              }
              @if (line.ip) {
                · {{ line.ip }}
              }
            </div>
            @if (line.kind === 'WRITE') {
              <code class="endpoint">{{ line.action }}</code>
              @if (open().has(line.id)) {
                @if (changes(line); as rows) {
                  @if (rows.length) {
                    <table class="diff">
                      <thead>
                        <tr>
                          <th scope="col">Field</th>
                          <th scope="col">Before</th>
                          <th scope="col">After</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (row of rows; track row.field) {
                          <tr [class.changed]="row.changed">
                            <th scope="row">{{ row.field }}</th>
                            <td>{{ row.before }}</td>
                            <td>{{ row.after }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  } @else {
                    <p class="muted">No details were sent or answered.</p>
                  }
                }
                @if (line.request && line.before === null) {
                  <p class="muted">
                    No earlier change of this item is in the log, so only the new values show.
                  </p>
                }
              }
            }
          </div>
          @if (line.kind === 'WRITE') {
            <button
              mat-button
              type="button"
              (click)="toggle(line)"
              [attr.aria-expanded]="open().has(line.id)"
            >
              {{ open().has(line.id) ? 'Hide' : 'Details' }}
            </button>
          }
        </div>
      } @empty {
        @if (!loading()) {
          <p class="empty"><fs-icon name="history" /> Nothing here yet</p>
        }
      }
      @if (next()) {
        <div class="more">
          <button mat-stroked-button type="button" (click)="load()">Show more</button>
        </div>
      }
    </mat-card>
  `,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 16px;
      margin-block-end: 16px;
      max-inline-size: 960px;
    }
    .kinds {
      max-inline-size: 100%;
      overflow-x: auto;
    }
    .kinds mat-button-toggle {
      white-space: nowrap;
    }
    .search {
      flex: 1 1 240px;
    }
    .search mat-form-field {
      inline-size: 100%;
    }
    mat-card {
      max-inline-size: 960px;
    }
    .line {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      padding: 14px 20px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .badge {
      display: inline-grid;
      place-items: center;
      flex: none;
      inline-size: 36px;
      block-size: 36px;
      border-radius: 50%;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      --fs-icon-size: 20px;
    }
    .badge[data-kind='SIGN_IN'] {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .badge[data-kind='SIGN_IN_FAILED'] {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .text {
      flex: 1;
      min-inline-size: 0;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .what {
      font: var(--mat-sys-body-large);
    }
    .muted {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
    .endpoint {
      align-self: flex-start;
      font: var(--mat-sys-label-medium);
      font-family: 'Roboto Mono', ui-monospace, monospace;
      padding: 2px 8px;
      border-radius: 6px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      max-inline-size: 100%;
      overflow-wrap: anywhere;
    }
    .diff {
      inline-size: 100%;
      border-collapse: collapse;
      margin-block-start: 8px;
      font: var(--mat-sys-body-small);
      table-layout: fixed;
    }
    .diff th,
    .diff td {
      text-align: start;
      vertical-align: top;
      padding: 6px 8px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
      overflow-wrap: anywhere;
    }
    .diff thead th {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .diff tbody th {
      font-weight: 500;
      inline-size: 28%;
    }
    .diff tr.changed td:last-child {
      background: color-mix(in srgb, var(--mat-sys-primary) 10%, transparent);
      color: var(--mat-sys-on-surface);
      font-weight: 500;
    }
    .empty {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .more {
      display: flex;
      justify-content: center;
      padding: 16px;
    }
    @media (max-width: 599.98px) {
      .line {
        flex-wrap: wrap;
        gap: 8px 12px;
        padding: 12px 16px;
      }
      .text {
        flex-basis: calc(100% - 48px);
      }
      .line > button {
        margin-inline-start: 36px;
      }
      .diff tbody th {
        inline-size: 40%;
      }
    }
  `,
})
export class AuditPage implements OnInit {
  private readonly client = inject(AuditClient);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly lines = signal<AuditLine[]>([]);
  protected readonly kind = signal<AuditKind | null>(null);
  protected readonly loading = signal(true);
  protected readonly next = signal<string | null>(null);
  protected readonly open = signal<ReadonlySet<string>>(new Set());
  protected text = '';

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected setKind(kind: AuditKind | null): void {
    this.kind.set(kind);
    this.reset();
  }

  protected search(): void {
    this.reset();
  }

  protected toggle(line: AuditLine): void {
    this.open.update((open) => {
      const copy = new Set(open);
      if (!copy.delete(line.id)) copy.add(line.id);
      return copy;
    });
  }

  protected icon(line: AuditLine): IconName {
    if (line.kind === 'SIGN_IN') return 'key';
    if (line.kind === 'SIGN_IN_FAILED') return 'block';
    return line.action.startsWith('DELETE') ? 'delete' : 'edit';
  }

  /** "changed stores", "signed in with a password". */
  protected summary(line: AuditLine): string {
    if (line.kind === 'SIGN_IN') return `signed in with ${article(line.action)}`;
    if (line.kind === 'SIGN_IN_FAILED') return `was refused sign-in with ${article(line.action)}`;
    const verb = VERBS[line.action.split(' ')[0]] ?? 'Changed';
    return `${verb.toLowerCase()} ${line.area.replaceAll('-', ' ')}`;
  }

  /** Field by field: before and after, top-level fields of the JSON bodies. */
  protected changes(line: AuditLine): FieldChange[] {
    return fieldChanges(line.before, line.after ?? line.request);
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    try {
      const page = await firstValueFrom(
        this.client.search({
          kind: this.kind() ?? undefined,
          q: this.text.trim() || undefined,
          before: this.next() ?? undefined,
          limit: PAGE,
        }),
      );
      this.lines.update((lines) => [...lines, ...page.items]);
      this.next.set(page.nextBefore);
    } catch (error) {
      this.snackBar.open(describeError(error, 'The audit log could not be loaded.'), 'Close', {
        duration: 6000,
      });
    } finally {
      this.loading.set(false);
    }
  }

  private reset(): void {
    this.lines.set([]);
    this.next.set(null);
    this.open.set(new Set());
    void this.load();
  }
}

function article(how: string): string {
  return how === 'email code'
    ? 'an email code'
    : how === 'password reset'
      ? 'a password reset'
      : `a ${how}`;
}

/** Compares two JSON object texts field by field; anything else is shown whole. */
export function fieldChanges(before: string | null, after: string | null): FieldChange[] {
  const old = parse(before);
  const now = parse(after);
  if (old === undefined && now === undefined) return [];
  if (!isObject(old) && !isObject(now)) {
    const b = show(old);
    const a = show(now);
    return [{ field: 'Value', before: b, after: a, changed: b !== a }];
  }
  const fields = [...new Set([...Object.keys(asObject(old)), ...Object.keys(asObject(now))])];
  return fields.map((field) => {
    const b = show(asObject(old)[field]);
    const a = show(asObject(now)[field]);
    return { field, before: b, after: a, changed: b !== a };
  });
}

function parse(text: string | null): unknown {
  if (text === null || text === '') return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asObject(value: unknown): Record<string, unknown> {
  return isObject(value) ? value : {};
}

function show(value: unknown): string {
  if (value === undefined) return '—';
  if (value === null) return 'empty';
  return typeof value === 'string' ? value : JSON.stringify(value);
}
