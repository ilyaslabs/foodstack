import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

import { CartLine } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui } from '../i18n';
import { CartStore } from './cart-store';

/**
 * The order being built, beside the menu (and in a bar at the bottom on phones). Quantities change
 * in place; the server prices every line, so what this shows is what checkout charges.
 */
@Component({
  selector: 'app-cart-panel',
  imports: [RouterLink, MatButtonModule, Icon],
  template: `
    <section class="panel" [attr.aria-label]="ui.t('yourOrder')">
      <h2>{{ ui.t('yourOrder') }}</h2>
      @if (!auth.signedIn()) {
        <p class="muted">{{ ui.t('cartEmptyText') }}</p>
      } @else if (otherStore(); as name) {
        <p class="muted">{{ ui.t('orderFromOtherStore', { store: name }) }}</p>
        <a mat-stroked-button routerLink="/checkout">{{ ui.t('continueThere') }}</a>
      } @else if (cart.empty()) {
        <div class="empty">
          <fs-icon name="shopping_bag" class="empty-icon" />
          <p class="empty-title">{{ ui.t('cartEmpty') }}</p>
          <p class="muted">{{ ui.t('cartEmptyText') }}</p>
        </div>
      } @else if (cart.cart(); as c) {
        <ul class="lines">
          @for (line of c.lines; track line.lineId) {
            <li class="line">
              <div class="line-text">
                <span class="line-name">{{ locale.pick(line.priced.name) }}</span>
                @if (options(line); as text) {
                  <span class="line-options">{{ text }}</span>
                }
                @if (line.note) {
                  <span class="line-options">“{{ line.note }}”</span>
                }
                <span class="line-price">{{ money(line.priced.lineTotalMinor) }}</span>
              </div>
              <div class="stepper" role="group" [attr.aria-label]="ui.t('quantity')">
                <button
                  type="button"
                  class="step"
                  [disabled]="cart.busy()"
                  [attr.aria-label]="line.priced.quantity === 1 ? ui.t('remove') : ui.t('decrease')"
                  (click)="cart.setQuantity(line.lineId, line.priced.quantity - 1)"
                >
                  <fs-icon [name]="line.priced.quantity === 1 ? 'delete' : 'remove'" />
                </button>
                <span class="count">{{ ui.number(line.priced.quantity) }}</span>
                <button
                  type="button"
                  class="step"
                  [disabled]="cart.busy() || line.priced.quantity >= 50"
                  [attr.aria-label]="ui.t('increase')"
                  (click)="cart.setQuantity(line.lineId, line.priced.quantity + 1)"
                >
                  <fs-icon name="add" />
                </button>
              </div>
            </li>
          }
        </ul>
        <div class="subtotal">
          <span>{{ ui.t('subtotal') }}</span>
          <strong>{{ money(c.subtotalMinor) }}</strong>
        </div>
        <a mat-flat-button routerLink="/checkout" class="checkout">{{ ui.t('goToCheckout') }}</a>
      }
    </section>

    @if (auth.signedIn() && !cart.empty() && !otherStore()) {
      <a class="bar" routerLink="/checkout">
        <span class="bar-count">{{ ui.number(cart.itemCount()) }}</span>
        <span class="bar-label">{{ ui.t('goToCheckout') }}</span>
        <span class="bar-total">{{ money(cart.cart()?.subtotalMinor ?? 0) }}</span>
      </a>
    }
  `,
  styles: `
    .panel {
      padding: 20px;
      border-radius: var(--fs-radius-card, 16px);
      background: var(--mat-sys-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      box-shadow: var(--fs-shadow-card);
    }
    h2 {
      font-size: 20px;
      line-height: 28px;
      font-weight: 800;
      letter-spacing: -0.01em;
      margin: 0 0 12px;
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 12px;
      font: var(--mat-sys-body-medium);
    }
    .empty {
      text-align: center;
      padding-block: 12px;
    }
    .empty-icon {
      --fs-icon-size: 40px;
      color: var(--mat-sys-outline);
    }
    .empty-title {
      font: var(--mat-sys-title-small);
      margin: 8px 0 4px;
    }
    .lines {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .line {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      padding-block: 12px;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .line-text {
      flex: 1;
      min-inline-size: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .line-name {
      font: var(--mat-sys-title-small);
    }
    .line-options {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .line-price {
      font: var(--mat-sys-body-medium);
      margin-block-start: 4px;
    }
    .stepper {
      display: inline-flex;
      align-items: center;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 999px;
      flex: none;
    }
    .step {
      display: grid;
      place-items: center;
      inline-size: 32px;
      block-size: 32px;
      border: 0;
      border-radius: 999px;
      background: transparent;
      color: var(--mat-sys-primary);
      cursor: pointer;
      --fs-icon-size: 18px;
    }
    .step:hover:not(:disabled) {
      background: color-mix(in srgb, var(--mat-sys-primary) 10%, transparent);
    }
    .step:disabled {
      color: var(--mat-sys-outline);
      cursor: default;
    }
    .count {
      min-inline-size: 20px;
      text-align: center;
      font: var(--mat-sys-label-large);
    }
    .subtotal {
      display: flex;
      justify-content: space-between;
      padding-block: 16px;
      font: var(--mat-sys-title-medium);
    }
    .checkout {
      inline-size: 100%;
      block-size: 52px;
      font-weight: 700;
      font-size: 16px;
    }
    .bar {
      display: none;
    }
    @media (max-width: 959.98px) {
      .panel {
        display: none;
      }
      .bar {
        position: fixed;
        inset-inline: 16px;
        inset-block-end: 16px;
        z-index: 10;
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 0 20px;
        block-size: 56px;
        border-radius: 999px;
        background: var(--mat-sys-primary);
        color: var(--mat-sys-on-primary);
        box-shadow: var(--mat-sys-level3);
        text-decoration: none;
        font: var(--mat-sys-title-medium);
      }
      .bar-count {
        display: grid;
        place-items: center;
        min-inline-size: 28px;
        block-size: 28px;
        padding-inline: 6px;
        border-radius: 999px;
        background: color-mix(in srgb, var(--mat-sys-on-primary) 20%, transparent);
        font: var(--mat-sys-label-large);
      }
      .bar-label {
        flex: 1;
      }
    }
  `,
})
export class CartPanel {
  /** The store whose page shows the panel. */
  readonly storeId = input.required<string>();

  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  protected readonly cart = inject(CartStore);
  protected readonly auth = inject(AuthService);

  /** The cart belongs to another restaurant: say so instead of mixing its lines in here. */
  protected readonly otherStore = computed(() => {
    const store = this.cart.cart()?.store;
    return store && !this.cart.empty() && store.storeId !== this.storeId() ? store.brandName : null;
  });

  protected money(minor: number): string {
    return this.locale.money(minor, this.cart.cart()?.currency ?? undefined);
  }

  protected options(line: CartLine): string {
    return line.priced.options.map((option) => this.locale.pick(option.name)).join(', ');
  }
}
