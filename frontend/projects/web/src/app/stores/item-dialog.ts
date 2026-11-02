import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';

import { MenuGroup, MenuItem } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { CartStore, OtherStoreInCart } from '../cart/cart-store';
import { Ui } from '../i18n';

export interface ItemDialogData {
  storeId: string;
  item: MenuItem;
  currency: string;
  imageUrl: string | null;
  /** Ordering is off (store closed): the dialog only shows the dish. */
  canOrder: boolean;
}

/**
 * A dish with its choices: required groups are radios or checkboxes by how many may be picked, the
 * price updates as the customer chooses, and "Add" stays off until every group is satisfied.
 */
@Component({
  selector: 'app-item-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    Icon,
  ],
  template: `
    @if (data.imageUrl) {
      <img class="hero" [src]="data.imageUrl" alt="" />
    }
    <h2 mat-dialog-title>{{ locale.pick(data.item.name) }}</h2>
    <mat-dialog-content>
      @if (locale.pick(data.item.description); as description) {
        <p class="description">{{ description }}</p>
      }
      <p class="base-price">{{ money(data.item.priceMinor) }}</p>

      @for (group of data.item.modifierGroups; track group.id) {
        <fieldset class="group">
          <legend>
            <span class="group-name">{{ locale.pick(group.name) }}</span>
            <span class="group-rule" [class.required]="group.minSelect > 0">{{ rule(group) }}</span>
          </legend>
          @if (group.maxSelect === 1) {
            <mat-radio-group
              class="options"
              [ngModel]="chosen()[group.id]?.[0] ?? null"
              (ngModelChange)="pickOne(group, $event)"
              [attr.aria-label]="locale.pick(group.name)"
            >
              @for (option of group.options; track option.id) {
                <mat-radio-button [value]="option.id" [disabled]="option.soldOut">
                  <span class="option">
                    <span>{{ locale.pick(option.name) }}</span>
                    <span class="option-price">
                      @if (option.soldOut) {
                        {{ ui.t('soldOut') }}
                      } @else if (option.priceDeltaMinor) {
                        + {{ money(option.priceDeltaMinor) }}
                      }
                    </span>
                  </span>
                </mat-radio-button>
              }
            </mat-radio-group>
          } @else {
            <div class="options">
              @for (option of group.options; track option.id) {
                <mat-checkbox
                  [checked]="isChosen(group, option.id)"
                  [disabled]="option.soldOut || (!isChosen(group, option.id) && full(group))"
                  (change)="toggle(group, option.id, $event.checked)"
                >
                  <span class="option">
                    <span>{{ locale.pick(option.name) }}</span>
                    <span class="option-price">
                      @if (option.soldOut) {
                        {{ ui.t('soldOut') }}
                      } @else if (option.priceDeltaMinor) {
                        + {{ money(option.priceDeltaMinor) }}
                      }
                    </span>
                  </span>
                </mat-checkbox>
              }
            </div>
          }
        </fieldset>
      }

      @if (data.canOrder) {
        <mat-form-field appearance="outline" class="note">
          <mat-label>{{ ui.t('noteForStore') }}</mat-label>
          <input
            matInput
            maxlength="200"
            [placeholder]="ui.t('notePlaceholder')"
            [(ngModel)]="note"
          />
        </mat-form-field>
      }

      @if (replacing(); as store) {
        <div class="replace" role="alert">
          <strong>{{ ui.t('otherStoreTitle') }}</strong>
          <p>{{ ui.t('otherStoreText', { store }) }}</p>
        </div>
      }
      @if (failed()) {
        <p class="error" role="alert">{{ ui.t('addFailed') }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (data.canOrder) {
        <div class="stepper" role="group" [attr.aria-label]="ui.t('quantity')">
          <button
            mat-icon-button
            type="button"
            [disabled]="quantity() <= 1"
            [attr.aria-label]="ui.t('decrease')"
            (click)="quantity.set(quantity() - 1)"
          >
            <fs-icon name="remove" />
          </button>
          <span class="count" aria-live="polite">{{ ui.number(quantity()) }}</span>
          <button
            mat-icon-button
            type="button"
            [disabled]="quantity() >= 50"
            [attr.aria-label]="ui.t('increase')"
            (click)="quantity.set(quantity() + 1)"
          >
            <fs-icon name="add" />
          </button>
        </div>
        @if (replacing()) {
          <button mat-button type="button" (click)="replacing.set(null)">
            {{ ui.t('cancel') }}
          </button>
          <button mat-flat-button type="button" [disabled]="cart.busy()" (click)="add(true)">
            {{ ui.t('startNew') }}
          </button>
        } @else {
          <button
            mat-flat-button
            type="button"
            class="add"
            [disabled]="!valid() || cart.busy() || data.item.soldOut"
            (click)="add(false)"
          >
            {{ ui.t('addToOrder', { price: money(total()) }) }}
          </button>
        }
      } @else {
        <button mat-button type="button" mat-dialog-close>{{ ui.t('cancel') }}</button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    :host {
      display: block;
    }
    .hero {
      display: block;
      inline-size: 100%;
      max-block-size: 220px;
      object-fit: cover;
    }
    .description {
      margin: 0 0 4px;
      color: var(--mat-sys-on-surface-variant);
    }
    .base-price {
      margin: 0 0 8px;
      font: var(--mat-sys-title-medium);
    }
    .group {
      border: 0;
      padding: 0;
      margin: 16px 0 0;
    }
    legend {
      display: flex;
      inline-size: 100%;
      justify-content: space-between;
      align-items: baseline;
      gap: 12px;
      padding: 0;
      margin-block-end: 4px;
    }
    .group-name {
      font: var(--mat-sys-title-small);
      color: var(--mat-sys-on-surface);
    }
    .group-rule {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
      padding: 2px 8px;
      border-radius: 999px;
      background: var(--mat-sys-surface-container-high);
    }
    .group-rule.required {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .options {
      display: flex;
      flex-direction: column;
    }
    .option {
      display: inline-flex;
      gap: 12px;
      justify-content: space-between;
      min-inline-size: 220px;
    }
    .option-price {
      color: var(--mat-sys-on-surface-variant);
    }
    .note {
      inline-size: 100%;
      margin-block-start: 16px;
    }
    .replace {
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      p {
        margin: 4px 0 0;
      }
    }
    .error {
      color: var(--mat-sys-error);
    }
    mat-dialog-actions {
      gap: 8px;
      padding: 12px 24px 20px;
    }
    .stepper {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      margin-inline-end: auto;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 999px;
    }
    .count {
      min-inline-size: 24px;
      text-align: center;
      font: var(--mat-sys-title-medium);
    }
    .add {
      min-inline-size: 160px;
    }
  `,
})
export class ItemDialog {
  protected readonly data = inject<ItemDialogData>(MAT_DIALOG_DATA);
  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  protected readonly cart = inject(CartStore);
  private readonly ref = inject(MatDialogRef<ItemDialog, boolean>);

  protected readonly chosen = signal<Record<string, string[]>>(this.defaults());
  protected readonly quantity = signal(1);
  protected readonly replacing = signal<string | null>(null);
  protected readonly failed = signal(false);
  protected note = '';

  protected readonly valid = computed(() =>
    this.data.item.modifierGroups.every((group) => {
      const count = this.chosen()[group.id]?.length ?? 0;
      return count >= group.minSelect && count <= group.maxSelect;
    }),
  );

  protected readonly total = computed(() => {
    let unit = this.data.item.priceMinor;
    for (const group of this.data.item.modifierGroups) {
      const picked = this.chosen()[group.id] ?? [];
      for (const option of group.options) {
        if (picked.includes(option.id)) unit += option.priceDeltaMinor;
      }
    }
    return unit * this.quantity();
  });

  protected money(minor: number): string {
    return this.locale.money(minor, this.data.currency);
  }

  protected rule(group: MenuGroup): string {
    if (group.minSelect === 0) {
      return group.maxSelect === 1
        ? this.ui.t('optional')
        : this.ui.t('chooseUpTo', { n: this.ui.number(group.maxSelect) });
    }
    if (group.minSelect === group.maxSelect) {
      return group.minSelect === 1
        ? this.ui.t('required')
        : this.ui.t('chooseExactly', { n: this.ui.number(group.minSelect) });
    }
    return this.ui.t('chooseBetween', {
      min: this.ui.number(group.minSelect),
      max: this.ui.number(group.maxSelect),
    });
  }

  protected isChosen(group: MenuGroup, optionId: string): boolean {
    return (this.chosen()[group.id] ?? []).includes(optionId);
  }

  protected full(group: MenuGroup): boolean {
    return (this.chosen()[group.id]?.length ?? 0) >= group.maxSelect;
  }

  protected pickOne(group: MenuGroup, optionId: string): void {
    this.chosen.update((chosen) => ({ ...chosen, [group.id]: [optionId] }));
  }

  protected toggle(group: MenuGroup, optionId: string, on: boolean): void {
    this.chosen.update((chosen) => {
      const current = (chosen[group.id] ?? []).filter((id) => id !== optionId);
      return { ...chosen, [group.id]: on ? [...current, optionId] : current };
    });
  }

  protected async add(replaceOtherStore: boolean): Promise<void> {
    this.failed.set(false);
    try {
      await this.cart.add({
        storeId: this.data.storeId,
        itemId: this.data.item.id,
        quantity: this.quantity(),
        optionIds: Object.values(this.chosen()).flat(),
        note: this.note.trim() || null,
        replaceOtherStore,
      });
      this.ref.close(true);
    } catch (error) {
      if (error instanceof OtherStoreInCart) {
        this.replacing.set(error.storeName || '…');
      } else {
        this.failed.set(true);
      }
    }
  }

  /** A required single choice starts on its first available option, so most dishes add in one tap. */
  private defaults(): Record<string, string[]> {
    const chosen: Record<string, string[]> = {};
    for (const group of this.data.item.modifierGroups) {
      if (group.minSelect === 1 && group.maxSelect === 1) {
        const first = group.options.find((option) => !option.soldOut);
        if (first) chosen[group.id] = [first.id];
      }
    }
    return chosen;
  }
}
