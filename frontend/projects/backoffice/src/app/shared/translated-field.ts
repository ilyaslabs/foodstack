import { Component, computed, inject, input, model } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { Translated } from '@foodstack/api-client';
import { LANGUAGE_NAMES, LocaleStore } from '@foodstack/ui';

/**
 * One input per enabled language for translated text (PRD ST-5). The default language comes first
 * and is required when `required` is set; right-to-left languages are typed right to left.
 */
@Component({
  selector: 'app-translated-field',
  imports: [MatFormFieldModule, MatInputModule],
  template: `
    <div class="fields" [class.multiline]="multiline()">
      @for (language of languages(); track language; let first = $first) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>{{ label() }} · {{ name(language) }}</mat-label>
          @if (multiline()) {
            <textarea
              matInput
              rows="2"
              [attr.maxlength]="maxLength()"
              [attr.dir]="locale.isRtl(language) ? 'rtl' : 'ltr'"
              [attr.lang]="language"
              [value]="value()[language] ?? ''"
              (input)="update(language, $event)"
              [required]="first && required()"
            ></textarea>
          } @else {
            <input
              matInput
              [attr.maxlength]="maxLength()"
              [attr.dir]="locale.isRtl(language) ? 'rtl' : 'ltr'"
              [attr.lang]="language"
              [value]="value()[language] ?? ''"
              (input)="update(language, $event)"
              [required]="first && required()"
            />
          }
          @if (first && required()) {
            <mat-hint>Required</mat-hint>
          } @else if (!first) {
            <mat-hint>Optional. Falls back to {{ name(languages()[0]) }}.</mat-hint>
          }
        </mat-form-field>
      }
    </div>
  `,
  styles: `
    .fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px 16px;
    }
    .fields.multiline {
      grid-template-columns: minmax(0, 1fr);
    }
    mat-form-field {
      width: 100%;
    }
  `,
})
export class TranslatedField {
  protected readonly locale = inject(LocaleStore);

  readonly label = input.required<string>();
  readonly required = input(false);
  readonly multiline = input(false);
  readonly maxLength = input(80);
  readonly value = model<Translated>({});

  protected readonly languages = computed(() => {
    const locale = this.locale.locale();
    return [
      locale.defaultLanguage,
      ...locale.languages.filter((l) => l !== locale.defaultLanguage),
    ];
  });

  protected name(language: string): string {
    return LANGUAGE_NAMES[language] ?? language;
  }

  protected update(language: string, event: Event): void {
    const text = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.value.update((current) => ({ ...current, [language]: text }));
  }
}

/** True when the default language has text. */
export function hasDefault(text: Translated, defaultLanguage: string): boolean {
  return (text[defaultLanguage] ?? '').trim().length > 0;
}

/** Drops blank translations before sending. */
export function cleanTranslated(text: Translated): Translated {
  return Object.fromEntries(
    Object.entries(text)
      .map(([language, value]) => [language, (value ?? '').trim()])
      .filter(([, value]) => value.length > 0),
  );
}
