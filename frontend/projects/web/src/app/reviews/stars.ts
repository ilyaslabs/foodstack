import { ChangeDetectionStrategy, Component, computed, inject, input, model } from '@angular/core';

import { Icon } from '@foodstack/ui';

import { Ui } from '../i18n';

/** Five stars to pick from, as a radio group (arrow keys move, Enter or Space picks). */
@Component({
  selector: 'app-star-input',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stars" role="radiogroup" [attr.aria-label]="label()">
      @for (n of five; track n) {
        <button
          type="button"
          role="radio"
          class="star"
          [class.on]="n <= value()"
          [attr.aria-checked]="n === value()"
          [attr.aria-label]="ui.t('starsLabel', { n })"
          [attr.tabindex]="n === (value() || 1) ? 0 : -1"
          (click)="value.set(n)"
          (keydown)="key($event, n)"
        >
          <fs-icon [name]="n <= value() ? 'star' : 'star_border'" />
        </button>
      }
    </div>
  `,
  styles: `
    .stars {
      display: inline-flex;
      gap: 4px;
    }
    .star {
      display: inline-grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      border: 0;
      border-radius: 50%;
      background: transparent;
      color: var(--mat-sys-outline);
      cursor: pointer;
      --fs-icon-size: 32px;
      transition:
        transform 120ms ease,
        color 120ms ease;
    }
    .star:hover {
      transform: scale(1.08);
    }
    .star:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: 2px;
    }
    .star.on {
      color: #f5a623;
    }
  `,
})
export class StarInput {
  protected readonly ui = inject(Ui);
  readonly value = model(0);
  readonly label = input('');
  protected readonly five = [1, 2, 3, 4, 5];

  protected key(event: KeyboardEvent, n: number): void {
    // right-to-left pages lay the row out the other way
    const ahead = getComputedStyle(event.target as Element).direction === 'rtl' ? -1 : 1;
    const steps: Record<string, number> = {
      ArrowRight: ahead,
      ArrowLeft: -ahead,
      ArrowUp: 1,
      ArrowDown: -1,
    };
    const step = steps[event.key];
    if (!step) return;
    event.preventDefault();
    const next = Math.min(5, Math.max(1, n + step));
    this.value.set(next);
    const row = (event.target as HTMLElement).parentElement;
    (row?.children[next - 1] as HTMLElement | undefined)?.focus();
  }
}

/** A read-only star rating, such as 4.3 shown as four and a bit stars. */
@Component({
  selector: 'app-stars',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="stars"
      role="img"
      [attr.aria-label]="ui.t('starsLabel', { n: ui.number(value(), 1) })"
    >
      <span class="base">★★★★★</span>
      <span class="fill" [style.inline-size.%]="percent()">★★★★★</span>
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
      vertical-align: middle;
    }
    .stars {
      position: relative;
      display: inline-block;
      font-size: var(--fs-stars-size, 16px);
      line-height: 1;
      letter-spacing: 1px;
      white-space: nowrap;
    }
    .base {
      color: var(--mat-sys-outline-variant);
    }
    .fill {
      position: absolute;
      inset-block-start: 0;
      inset-inline-start: 0;
      overflow: hidden;
      color: #f5a623;
    }
  `,
})
export class Stars {
  protected readonly ui = inject(Ui);
  readonly value = input(0);
  protected readonly percent = computed(() => Math.max(0, Math.min(100, (this.value() / 5) * 100)));
}
