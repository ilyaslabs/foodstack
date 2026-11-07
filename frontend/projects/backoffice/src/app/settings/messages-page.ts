import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Subject, debounceTime, firstValueFrom, switchMap, catchError, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import {
  MessagePreview,
  MessageTemplate,
  MessageTemplateSummary,
  MessageTemplateText,
  MessageText,
  NotificationClient,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';

interface MessageInfo {
  label: string;
  group: 'Customers' | 'Stores' | 'Riders' | 'Accounts' | 'Finance';
  hint: string;
}

/** What each message is, in the operator's words. */
const MESSAGES: Record<string, MessageInfo> = {
  'order-confirmed': {
    label: 'Order received',
    group: 'Customers',
    hint: 'Right after the customer places an order',
  },
  'order-accepted': {
    label: 'Order accepted',
    group: 'Customers',
    hint: 'When the store accepts the order',
  },
  'order-ready-pickup': {
    label: 'Ready to collect',
    group: 'Customers',
    hint: 'A pickup order is ready',
  },
  'order-picked-up': { label: 'On the way', group: 'Customers', hint: 'The rider has the food' },
  'order-delivered': { label: 'Delivered', group: 'Customers', hint: 'The order arrived' },
  'order-rejected': {
    label: 'Order declined',
    group: 'Customers',
    hint: 'The store said no or did not answer',
  },
  'order-cancelled': {
    label: 'Order cancelled',
    group: 'Customers',
    hint: 'Support or the customer cancelled',
  },
  'order-failed': {
    label: 'Delivery failed',
    group: 'Customers',
    hint: 'The rider could not deliver',
  },
  'refund-issued': {
    label: 'Refund issued',
    group: 'Customers',
    hint: 'Money is going back to the card',
  },
  'store-new-order': { label: 'New order', group: 'Stores', hint: 'A store gets a new order' },
  'rider-offer': { label: 'Job offer', group: 'Riders', hint: 'A delivery is offered to a rider' },
  'rider-assigned': {
    label: 'Rider assigned',
    group: 'Customers',
    hint: 'The customer learns who is coming',
  },
  'rider-job-assigned': {
    label: 'Job assigned',
    group: 'Riders',
    hint: 'Dispatch gives a rider a job',
  },
  'dispatch-alert': {
    label: 'No rider found',
    group: 'Riders',
    hint: 'Dispatch staff are told to step in',
  },
  welcome: { label: 'Welcome', group: 'Accounts', hint: 'After someone signs up' },
  'login-code': { label: 'Sign-in code', group: 'Accounts', hint: 'Must keep the code' },
  'password-reset-code': {
    label: 'Password reset code',
    group: 'Accounts',
    hint: 'Must keep the code',
  },
  'account-deletion-scheduled': {
    label: 'Account deletion scheduled',
    group: 'Accounts',
    hint: 'When a customer asks to delete their account',
  },
  'credit-limit-exceeded': {
    label: 'Credit limit reached',
    group: 'Finance',
    hint: 'A branch owes too much',
  },
  'credit-limit-restored': {
    label: 'Card payments back',
    group: 'Finance',
    hint: 'A branch settled',
  },
};

const GROUPS: MessageInfo['group'][] = ['Customers', 'Stores', 'Riders', 'Accounts', 'Finance'];

/** The operator's own wording of every message, in every language (PRD NO-2). */
@Component({
  selector: 'app-messages-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Messages</h1>
      <p>
        The emails, notifications and push messages people get. Reword any of them in each of your
        languages; a field you leave empty keeps FoodStack's text.
      </p>
    </header>

    @if (loadError()) {
      <div class="banner error" role="alert">{{ loadError() }}</div>
    }

    <div class="layout" [class.has-selection]="selected()">
      <mat-card appearance="outlined" class="list">
        @if (!summaries()) {
          <mat-progress-bar mode="indeterminate" />
        }
        @for (group of groups(); track group.name) {
          <h2 class="group">{{ group.name }}</h2>
          @for (item of group.items; track item.id) {
            <button
              type="button"
              class="message"
              [class.active]="selected()?.id === item.id"
              [attr.aria-current]="selected()?.id === item.id ? 'true' : null"
              (click)="open(item.id)"
            >
              <span class="message-text">
                <span class="message-label">{{ info(item.id).label }}</span>
                <span class="muted">{{ info(item.id).hint }}</span>
              </span>
              @if (item.editedLanguages.length) {
                <span class="edited">Edited</span>
              }
              <fs-icon name="chevron_right" />
            </button>
          }
        }
      </mat-card>

      @if (selected(); as template) {
        <mat-card appearance="outlined" class="editor">
          <div class="editor-head">
            <button
              mat-icon-button
              type="button"
              class="back"
              (click)="close()"
              aria-label="Back to all messages"
            >
              <fs-icon name="arrow_back" />
            </button>
            <div>
              <h2>{{ info(template.id).label }}</h2>
              <p class="muted">{{ info(template.id).hint }}</p>
            </div>
          </div>

          @if (template.languages.length > 1) {
            <mat-button-toggle-group
              class="languages"
              [value]="language()"
              (change)="pickLanguage($event.value)"
              aria-label="Language"
            >
              @for (text of template.languages; track text.language) {
                <mat-button-toggle [value]="text.language">
                  {{ locale.languageName(text.language) }}
                  @if (text.edited) {
                    <span class="dot" aria-label="edited"></span>
                  }
                </mat-button-toggle>
              }
            </mat-button-toggle-group>
          }

          @if (current(); as text) {
            @if (text.builtInLanguage !== text.language) {
              <div class="banner info" role="status">
                FoodStack has no {{ locale.languageName(text.language) }} text for this message, so
                people see the English one until you write your own.
              </div>
            }
            <div class="placeholders">
              <span class="muted">Insert</span>
              <mat-chip-set aria-label="Placeholders">
                @for (name of template.placeholders; track name) {
                  <mat-chip (click)="insert(name)" [highlighted]="template.required.includes(name)">
                    {{ '{{' + name + '}}' }}
                  </mat-chip>
                }
              </mat-chip-set>
            </div>
            <form class="fields" (ngSubmit)="save()" [attr.dir]="rtl() ? 'rtl' : 'ltr'">
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Email subject</mat-label>
                <input
                  matInput
                  name="subject"
                  [(ngModel)]="draft.subject"
                  (ngModelChange)="changed()"
                  (focus)="focus = 'subject'"
                  [placeholder]="text.builtIn.subject ?? ''"
                  maxlength="200"
                />
                <mat-hint>Empty keeps: {{ text.builtIn.subject }}</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Heading</mat-label>
                <input
                  matInput
                  name="title"
                  [(ngModel)]="draft.title"
                  (ngModelChange)="changed()"
                  (focus)="focus = 'title'"
                  [placeholder]="text.builtIn.title ?? ''"
                  maxlength="200"
                />
                <mat-hint>Shown in the notification centre, push and the email</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Message</mat-label>
                <textarea
                  matInput
                  name="body"
                  rows="5"
                  [(ngModel)]="draft.body"
                  (ngModelChange)="changed()"
                  (focus)="focus = 'body'"
                  [placeholder]="text.builtIn.body ?? ''"
                  maxlength="2000"
                ></textarea>
                @if (template.required.length) {
                  <mat-hint>Must keep {{ requiredHint(template) }}</mat-hint>
                }
              </mat-form-field>
              @if (error()) {
                <div class="banner error" role="alert">{{ error() }}</div>
              }
              <div class="actions">
                @if (text.edited) {
                  <button mat-button type="button" (click)="reset()" [disabled]="saving()">
                    <fs-icon name="refresh" /> Use FoodStack's text
                  </button>
                }
                <span class="spacer"></span>
                @if (text.updatedAt) {
                  <span class="muted small">Saved {{ when(text.updatedAt) }}</span>
                }
                <button mat-flat-button type="submit" [disabled]="saving() || !dirty()">
                  Save
                </button>
              </div>
            </form>

            <section class="preview" aria-label="Preview">
              <h3>Preview <span class="muted small">with sample values</span></h3>
              @if (preview(); as p) {
                <div class="push">
                  <fs-icon name="notifications" />
                  <div [attr.dir]="rtl() ? 'rtl' : 'ltr'">
                    <strong>{{ p.title }}</strong>
                    <span>{{ p.body }}</span>
                  </div>
                </div>
                <div class="mail">
                  <div class="mail-subject" [attr.dir]="rtl() ? 'rtl' : 'ltr'">
                    <span class="muted small">Subject</span> {{ p.subject }}
                  </div>
                  <iframe title="Email preview" sandbox="" [attr.srcdoc]="previewHtml()"></iframe>
                </div>
              } @else {
                <mat-progress-bar mode="indeterminate" />
              }
            </section>
          }
        </mat-card>
      } @else {
        <div class="empty muted">
          <fs-icon name="mail" />
          <p>Pick a message to see and change its text.</p>
        </div>
      }
    </div>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
      gap: 20px;
      align-items: start;
    }
    .list {
      padding: 8px 0;
      position: sticky;
      top: 16px;
    }
    .group {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface-variant);
      margin: 16px 20px 4px;
    }
    .message {
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: start;
      padding: 10px 16px 10px 20px;
      cursor: pointer;
      border-radius: 0;
    }
    .message:hover {
      background: color-mix(in srgb, var(--mat-sys-on-surface) 6%, transparent);
    }
    .message.active {
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .message:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: -2px;
    }
    .message-text {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-width: 0;
    }
    .message-label {
      font: var(--mat-sys-body-large);
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
    .message.active .muted {
      color: inherit;
      opacity: 0.8;
    }
    .small {
      font: var(--mat-sys-body-small);
    }
    .edited {
      font: var(--mat-sys-label-small);
      padding: 2px 8px;
      border-radius: 8px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .editor {
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .editor-head {
      display: flex;
      gap: 8px;
      align-items: flex-start;
    }
    .editor-head h2 {
      font: var(--mat-sys-title-large);
      margin: 0;
    }
    .editor-head p {
      margin: 4px 0 0;
    }
    .back {
      display: none;
    }
    .languages {
      align-self: flex-start;
      flex-wrap: wrap;
    }
    .dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--mat-sys-tertiary);
      margin-inline-start: 6px;
      vertical-align: middle;
    }
    .placeholders {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .fields {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .actions {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .spacer {
      flex: 1;
    }
    .banner {
      margin: 0;
    }
    .banner.info {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .preview {
      border-top: 1px solid var(--mat-sys-outline-variant);
      padding-top: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .preview h3 {
      font: var(--mat-sys-title-medium);
      margin: 0;
    }
    .push {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      border-radius: 16px;
      background: var(--mat-sys-surface-container-high);
      max-width: 420px;
    }
    .push div {
      display: flex;
      flex-direction: column;
      gap: 2px;
      font: var(--mat-sys-body-medium);
    }
    .mail {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
      overflow: hidden;
    }
    .mail-subject {
      padding: 10px 16px;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
      font: var(--mat-sys-body-medium);
    }
    iframe {
      display: block;
      width: 100%;
      height: 380px;
      border: 0;
      background: #f4f1f6;
    }
    .empty {
      display: grid;
      place-items: center;
      gap: 8px;
      min-height: 280px;
      border: 1px dashed var(--mat-sys-outline-variant);
      border-radius: 16px;
      text-align: center;
    }
    @media (max-width: 959.98px) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
      }
      .list {
        position: static;
      }
      .layout.has-selection .list,
      .empty {
        display: none;
      }
      .back {
        display: inline-flex;
        margin-inline-start: -8px;
      }
      .editor {
        padding: 16px;
      }
    }
  `,
})
export class MessagesPage implements OnInit {
  private readonly client = inject(NotificationClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly sanitizer = inject(DomSanitizer);
  protected readonly locale = inject(LocaleStore);

  protected readonly summaries = signal<MessageTemplateSummary[] | null>(null);
  protected readonly selected = signal<MessageTemplate | null>(null);
  protected readonly language = signal('en');
  protected readonly preview = signal<MessagePreview | null>(null);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly error = signal('');
  protected readonly loadError = signal('');

  protected draft: MessageText = { title: '', body: '', subject: '' };
  protected focus: keyof MessageText = 'body';

  private readonly previews = new Subject<void>();

  protected readonly current = computed<MessageTemplateText | null>(
    () => this.selected()?.languages.find((t) => t.language === this.language()) ?? null,
  );

  protected readonly rtl = computed(() => ['ar', 'ur', 'fa', 'he'].includes(this.language()));

  protected readonly previewHtml = computed<SafeHtml | null>(() => {
    const html = this.preview()?.html;
    return html ? this.sanitizer.bypassSecurityTrustHtml(html) : null;
  });

  protected readonly groups = computed(() => {
    const list = this.summaries() ?? [];
    return GROUPS.map((name) => ({
      name,
      items: list.filter((item) => this.info(item.id).group === name),
    })).filter((group) => group.items.length);
  });

  constructor() {
    this.previews
      .pipe(
        debounceTime(300),
        switchMap(() => {
          const template = this.selected();
          if (!template) return of(null);
          return this.client
            .previewTemplate(template.id, this.language(), this.draft)
            .pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((preview) => this.preview.set(preview));
  }

  async ngOnInit(): Promise<void> {
    try {
      this.summaries.set(await firstValueFrom(this.client.templates()));
    } catch (error) {
      this.loadError.set(describeError(error, 'The messages could not be loaded.'));
    }
  }

  protected info(id: string): MessageInfo {
    return MESSAGES[id] ?? { label: id, group: 'Customers', hint: '' };
  }

  protected async open(id: string): Promise<void> {
    this.error.set('');
    try {
      const template = await firstValueFrom(this.client.template(id));
      this.selected.set(template);
      const keep = template.languages.some((t) => t.language === this.language());
      this.pickLanguage(keep ? this.language() : (template.languages[0]?.language ?? 'en'));
    } catch (error) {
      this.loadError.set(describeError(error, 'The message could not be loaded.'));
    }
  }

  protected close(): void {
    this.selected.set(null);
  }

  protected pickLanguage(language: string): void {
    this.language.set(language);
    const edited = this.current()?.edited;
    this.draft = {
      title: edited?.title ?? '',
      body: edited?.body ?? '',
      subject: edited?.subject ?? '',
    };
    this.dirty.set(false);
    this.error.set('');
    this.preview.set(null);
    this.previews.next();
  }

  protected changed(): void {
    this.dirty.set(true);
    this.previews.next();
  }

  /** Adds a placeholder to the end of the field last focused (the message by default). */
  protected insert(name: string): void {
    const field = this.focus;
    const value = this.draft[field] ?? '';
    this.draft = {
      ...this.draft,
      [field]: `${value}${value && !value.endsWith(' ') ? ' ' : ''}{{${name}}}`,
    };
    this.changed();
  }

  protected requiredHint(template: MessageTemplate): string {
    return template.required.map((name) => `{{${name}}}`).join(', ');
  }

  protected when(iso: string): string {
    return new Date(iso).toLocaleString(this.locale.language(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  }

  protected async save(): Promise<void> {
    const template = this.selected();
    const text = this.current();
    if (!template || !text) return;
    this.saving.set(true);
    this.error.set('');
    try {
      const saved = await firstValueFrom(
        this.client.saveTemplate(template.id, text.language, this.draft, text.version),
      );
      this.replace(saved);
      this.dirty.set(false);
      this.snackBar.open('Saved. New messages use this text.', undefined, { duration: 4000 });
    } catch (error) {
      this.error.set(describeError(error, 'The message could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }

  protected async reset(): Promise<void> {
    const template = this.selected();
    const text = this.current();
    if (!template || !text) return;
    this.saving.set(true);
    this.error.set('');
    try {
      this.replace(await firstValueFrom(this.client.resetTemplate(template.id, text.language)));
      this.pickLanguage(text.language);
      this.snackBar.open("Back to FoodStack's text.", undefined, { duration: 4000 });
    } catch (error) {
      this.error.set(describeError(error, 'The message could not be reset.'));
    } finally {
      this.saving.set(false);
    }
  }

  private replace(text: MessageTemplateText): void {
    const template = this.selected();
    if (!template) return;
    const languages = template.languages.map((t) => (t.language === text.language ? text : t));
    this.selected.set({ ...template, languages });
    const edited = languages.filter((t) => t.edited).map((t) => t.language);
    this.summaries.update((list) =>
      (list ?? []).map((s) => (s.id === template.id ? { ...s, editedLanguages: edited } : s)),
    );
  }
}
