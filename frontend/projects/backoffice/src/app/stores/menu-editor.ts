import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggle, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import {
  BranchPrice,
  BrandClient,
  EditorItem,
  EditorMenu,
  EditorSection,
  OrderingSettingsClient,
  PriceChange,
  StoreClient,
  StoreSummary,
  Translated,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { TranslatedField, cleanTranslated, hasDefault } from '../shared/translated-field';
import { Session } from '../shared/session';
import { BranchPriceData, BranchPriceDialog, BranchPriceResult } from './branch-price-dialog';
import { DIETARY_LABELS, ItemDialog, ItemDialogData } from './item-dialog';
import { StoreContext } from './store-context';

/**
 * The brand's menu (shared by its branches) with this branch's sold-out switches. Names are
 * typed in every enabled language; customers see their own language, falling back to the default.
 */
@Component({
  selector: 'app-menu-editor',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    Icon,
    TranslatedField,
  ],
  template: `
    @if (menu(); as menu) {
      <div class="toolbar">
        <p class="lead">
          {{ itemCount() }} {{ itemCount() === 1 ? 'dish' : 'dishes' }} in
          {{ menu.sections.length }} {{ menu.sections.length === 1 ? 'section' : 'sections' }}.
          Sold-out switches and branch prices apply to this branch only.
          @if (!canEditBrand()) {
            The brand's owners edit the dishes themselves.
          }
        </p>
        @if (canEditBrand()) {
          <button mat-flat-button type="button" (click)="startSection()">
            <fs-icon name="add" /> Add section
          </button>
        }
      </div>
      @if (pending().length) {
        <div class="pending" role="status">
          <fs-icon name="schedule" />
          <div>
            <strong
              >{{ pending().length }}
              {{ pending().length === 1 ? 'price change waits' : 'price changes wait' }} for
              approval.</strong
            >
            Customers see the current prices until the platform approves.
          </div>
        </div>
      }

      @if (newSection() !== null) {
        <div class="card section-form">
          <h2>New section</h2>
          <app-translated-field
            label="Section name"
            [required]="true"
            [maxLength]="60"
            [(value)]="newSectionName"
          />
          <div class="form-actions">
            <button mat-button type="button" (click)="newSection.set(null)">Cancel</button>
            <button mat-flat-button type="button" (click)="createSection()" [disabled]="busy()">
              Add section
            </button>
          </div>
        </div>
      }

      @for (section of menu.sections; track section.id; let first = $first; let last = $last) {
        <section class="card">
          <header class="section-head">
            @if (renaming() === section.id) {
              <div class="rename">
                <app-translated-field
                  label="Section name"
                  [required]="true"
                  [maxLength]="60"
                  [(value)]="renameText"
                />
                <div class="form-actions">
                  <button mat-button type="button" (click)="renaming.set(null)">Cancel</button>
                  <button
                    mat-flat-button
                    type="button"
                    (click)="saveSection(section, renameText, section.sortOrder)"
                    [disabled]="busy()"
                  >
                    Save
                  </button>
                </div>
              </div>
            } @else {
              <div>
                <h2>{{ locale.pick(section.name) }}</h2>
                <span class="count"
                  >{{ section.items.length }}
                  {{ section.items.length === 1 ? 'dish' : 'dishes' }}</span
                >
              </div>
              @if (canEditBrand()) {
                <div class="section-actions">
                  <button mat-button type="button" (click)="openItem(section, null)">
                    <fs-icon name="add" /> Add dish
                  </button>
                  <button
                    mat-icon-button
                    type="button"
                    [matMenuTriggerFor]="sectionMenu"
                    aria-label="Section options"
                  >
                    <fs-icon name="more_vert" />
                  </button>
                  <mat-menu #sectionMenu="matMenu">
                    <button mat-menu-item type="button" (click)="startRename(section)">
                      Rename
                    </button>
                    <button
                      mat-menu-item
                      type="button"
                      [disabled]="first"
                      (click)="move(section, -1)"
                    >
                      Move up
                    </button>
                    <button
                      mat-menu-item
                      type="button"
                      [disabled]="last"
                      (click)="move(section, 1)"
                    >
                      Move down
                    </button>
                    <button mat-menu-item type="button" (click)="deleteSection(section)">
                      Delete section
                    </button>
                  </mat-menu>
                </div>
              }
            }
          </header>

          @if (section.items.length === 0) {
            <p class="empty-section">No dishes yet. Add the first one.</p>
          }
          <ul class="items">
            @for (item of section.items; track item.id) {
              <li class="item" [class.sold-out]="soldOut().has(item.id)">
                @if (thumb(item); as url) {
                  <img [src]="url" alt="" width="64" height="48" />
                } @else {
                  <span class="thumb-empty" aria-hidden="true"><fs-icon name="restaurant" /></span>
                }
                <div class="item-text">
                  <span class="item-name">{{ locale.pick(item.name) }}</span>
                  @if (locale.pick(item.description); as description) {
                    <span class="item-description">{{ description }}</span>
                  }
                  <span class="item-meta">
                    @for (tag of item.dietaryTags; track tag) {
                      <span class="tag">{{ dietaryLabels[tag] ?? tag }}</span>
                    }
                    @if (item.modifierGroups.length) {
                      <span class="tag"
                        >{{ item.modifierGroups.length }}
                        {{ item.modifierGroups.length === 1 ? 'choice' : 'choices' }}</span
                      >
                    }
                    @if (missing(item.name).length) {
                      <span class="tag warn">No {{ missing(item.name).join(', ') }}</span>
                    }
                    @if (item.branchIds?.length) {
                      <span class="tag">{{
                        item.branchIds!.includes(storeId()) ? 'Some branches' : 'Not at this branch'
                      }}</span>
                    }
                    @if (pendingFor(item.id)) {
                      <span class="tag warn">Price waiting for approval</span>
                    }
                  </span>
                </div>
                <span class="price">
                  @if (ownPrice(item.id); as own) {
                    {{ money(own.priceMinor) }}
                    <span class="brand-price">Brand {{ money(item.priceMinor) }}</span>
                  } @else {
                    {{ money(item.priceMinor) }}
                  }
                </span>
                <mat-slide-toggle
                  class="stock"
                  [checked]="!soldOut().has(item.id)"
                  (change)="setSoldOut(item, !$event.checked, $event.source)"
                  [attr.aria-label]="'In stock: ' + locale.pick(item.name)"
                >
                  <span class="stock-label">{{
                    soldOut().has(item.id) ? 'Sold out' : 'In stock'
                  }}</span>
                </mat-slide-toggle>
                <button
                  mat-icon-button
                  type="button"
                  [matMenuTriggerFor]="itemMenu"
                  aria-label="Dish options"
                >
                  <fs-icon name="more_vert" />
                </button>
                <mat-menu #itemMenu="matMenu">
                  @if (canEditBrand()) {
                    <button mat-menu-item type="button" (click)="openItem(section, item)">
                      Edit
                    </button>
                  }
                  <button mat-menu-item type="button" (click)="openPrices(item)">
                    Prices at this branch
                  </button>
                  @if (canEditBrand()) {
                    <button mat-menu-item type="button" (click)="deleteItem(item)">Delete</button>
                  }
                </mat-menu>
              </li>
            }
          </ul>
        </section>
      } @empty {
        @if (newSection() === null) {
          <div class="card empty">
            <fs-icon name="restaurant" />
            <h2>Start the menu</h2>
            @if (canEditBrand()) {
              <p>Make a section such as "Starters" or "Biryani", then add dishes to it.</p>
              <button mat-flat-button type="button" (click)="startSection()">
                <fs-icon name="add" /> Add section
              </button>
            } @else {
              <p>The brand's owners have not added dishes yet.</p>
            }
          </div>
        }
      }
    } @else if (loadError()) {
      <div class="card empty">
        <h2>The menu could not be loaded</h2>
        <p>Reload the page to try again.</p>
      </div>
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
      padding-block: 16px;
    }
    .pending {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      border-radius: 12px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
      font: var(--mat-sys-body-medium);
    }
    .brand-price {
      display: block;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      text-decoration: line-through;
      text-align: end;
    }
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .lead {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
    .card {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 16px;
      background: var(--mat-sys-surface-container-lowest);
      padding: 8px 0;
    }
    .section-form,
    .rename {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px 20px;
      flex: 1;
    }
    .rename {
      padding: 0;
    }
    .form-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
    h2 {
      font: var(--mat-sys-title-medium);
      margin: 0;
    }
    .section-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 12px 12px 20px;
    }
    .count {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .section-actions {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .empty-section {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
      padding: 0 20px 16px;
    }
    .items {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .item {
      display: grid;
      grid-template-columns: 64px minmax(0, 1fr) auto auto auto;
      align-items: center;
      gap: 16px;
      padding: 12px 12px 12px 20px;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
    .item img,
    .thumb-empty {
      inline-size: 64px;
      block-size: 48px;
      border-radius: 10px;
      object-fit: cover;
    }
    .thumb-empty {
      display: grid;
      place-items: center;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .item-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .item-name {
      font: var(--mat-sys-title-small);
    }
    .item-description {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .item-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-block-start: 2px;
    }
    .tag {
      font: var(--mat-sys-label-small);
      padding: 2px 8px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .tag.warn {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .price {
      font: var(--mat-sys-title-small);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .stock-label {
      display: inline-block;
      min-inline-size: 64px;
      font: var(--mat-sys-body-medium);
    }
    .sold-out .item-name,
    .sold-out .price {
      color: var(--mat-sys-on-surface-variant);
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 8px;
      padding: 40px 24px;
      color: var(--mat-sys-on-surface-variant);
    }
    .empty h2 {
      color: var(--mat-sys-on-surface);
    }
    .empty p {
      margin: 0 0 8px;
    }
    @media (max-width: 599.98px) {
      .item {
        grid-template-columns: 48px minmax(0, 1fr) auto;
        gap: 8px 12px;
        padding-inline-start: 16px;
      }
      .item img,
      .thumb-empty {
        inline-size: 48px;
        block-size: 40px;
      }
      .price {
        grid-column: 2;
        grid-row: 2;
      }
      .stock {
        grid-column: 2 / 4;
        grid-row: 3;
      }
      .item > button {
        grid-column: 3;
        grid-row: 1;
      }
      .section-head {
        padding-inline-start: 16px;
      }
    }
  `,
})
export class MenuEditor {
  protected readonly locale = inject(LocaleStore);
  private readonly session = inject(Session);
  private readonly ordering = inject(OrderingSettingsClient);
  private readonly context = inject(StoreContext);
  private readonly brands = inject(BrandClient);
  private readonly stores = inject(StoreClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly dietaryLabels = DIETARY_LABELS;
  protected readonly menu = signal<EditorMenu | null>(null);
  protected readonly soldOut = signal<Set<string>>(new Set());
  protected readonly prices = signal<BranchPrice[]>([]);
  protected readonly pending = signal<PriceChange[]>([]);
  private readonly branches = signal<StoreSummary[]>([]);
  private approval = false;

  /** Operator staff and the brand's owners edit the dishes; branch managers set sold out and branch prices. */
  protected readonly canEditBrand = computed(
    () => this.session.operator() || this.session.ownsBrand(this.context.store()?.brand.id),
  );
  protected readonly storeId = computed(() => this.context.store()?.id ?? '');
  protected readonly loadError = signal(false);
  protected readonly busy = signal(false);
  protected readonly newSection = signal<true | null>(null);
  protected readonly renaming = signal<string | null>(null);
  protected newSectionName: Translated = {};
  protected renameText: Translated = {};

  protected readonly itemCount = computed(() =>
    (this.menu()?.sections ?? []).reduce((count, section) => count + section.items.length, 0),
  );

  private loadedFor: string | null = null;

  constructor() {
    effect(() => {
      const store = this.context.store();
      if (store && store.id !== this.loadedFor) {
        this.loadedFor = store.id;
        void this.load();
      }
    });
  }

  private async load(): Promise<void> {
    const store = this.context.store()!;
    try {
      const editor = this.canEditBrand();
      const [menu, branchMenu, prices, brandPending, branches, ordering] = await Promise.all([
        firstValueFrom(this.brands.menu(store.brand.id, editor ? undefined : store.id)),
        firstValueFrom(this.stores.menu(store.id)).catch(() => null),
        firstValueFrom(this.stores.branchPrices(store.id)).catch(() => null),
        editor
          ? firstValueFrom(this.brands.priceChanges(store.brand.id)).catch(() => [])
          : Promise.resolve([] as PriceChange[]),
        editor
          ? firstValueFrom(this.brands.branches(store.brand.id)).catch(() => [])
          : Promise.resolve([] as StoreSummary[]),
        firstValueFrom(this.ordering.get()).catch(() => null),
      ]);
      this.menu.set(menu);
      this.prices.set(prices?.prices ?? []);
      const own = (prices?.pending ?? []).filter((c) => c.storeId === store.id);
      const brandWide = brandPending.filter((c) => c.storeId === null);
      this.pending.set([...brandWide, ...own]);
      this.branches.set(branches);
      this.approval = !!ordering?.value.priceApprovalRequired && !this.session.operator();
      const soldOut = new Set<string>();
      for (const section of branchMenu?.sections ?? []) {
        for (const item of section.items) if (item.soldOut) soldOut.add(item.id);
      }
      this.soldOut.set(soldOut);
      this.loadError.set(false);
    } catch {
      this.loadError.set(true);
    }
  }

  protected ownPrice(itemId: string): BranchPrice | undefined {
    return this.prices().find((p) => p.itemId === itemId && p.optionId === null);
  }

  protected pendingFor(itemId: string): boolean {
    return this.pending().some((c) => c.itemId === itemId);
  }

  protected async openPrices(item: EditorItem): Promise<void> {
    const store = this.context.store()!;
    const data: BranchPriceData = {
      storeId: store.id,
      branchName: store.branchName ?? store.displayName,
      currency: this.menu()!.currency,
      item,
      prices: this.prices().filter((p) => p.itemId === item.id),
      approval: this.approval,
    };
    const result = await firstValueFrom(
      this.dialog
        .open<BranchPriceDialog, BranchPriceData, BranchPriceResult>(BranchPriceDialog, {
          data,
          width: '520px',
          maxWidth: '96vw',
        })
        .afterClosed(),
    );
    if (!result || result.applied + result.pending === 0) return;
    this.snackBar.open(
      result.pending
        ? 'Sent for approval. Customers see the new price once the platform approves.'
        : 'Branch prices saved.',
      undefined,
      { duration: 5000 },
    );
    await this.load();
  }

  protected money(minor: number): string {
    return this.locale.money(minor);
  }

  protected thumb(item: EditorItem): string | null {
    return (
      item.imageUrls['thumb'] ?? item.imageUrls['card'] ?? Object.values(item.imageUrls)[0] ?? null
    );
  }

  /** Enabled languages the text has no translation for (shown so owners can fill them in). */
  protected missing(text: Translated): string[] {
    const locale = this.locale.locale();
    return locale.languages
      .filter((language) => language !== locale.defaultLanguage && !(text[language] ?? '').trim())
      .map((language) => this.locale.languageName(language));
  }

  protected startSection(): void {
    this.newSectionName = {};
    this.newSection.set(true);
  }

  protected async createSection(): Promise<void> {
    if (!hasDefault(this.newSectionName, this.locale.locale().defaultLanguage)) {
      this.snackBar.open('Give the section a name.', undefined, { duration: 4000 });
      return;
    }
    await this.run(async () => {
      await firstValueFrom(
        this.brands.createSection(this.brandId(), cleanTranslated(this.newSectionName)),
      );
      this.newSection.set(null);
    }, 'Section added.');
  }

  protected startRename(section: EditorSection): void {
    this.renameText = { ...section.name };
    this.renaming.set(section.id);
  }

  protected async saveSection(
    section: EditorSection,
    name: Translated,
    sortOrder: number,
  ): Promise<void> {
    if (!hasDefault(name, this.locale.locale().defaultLanguage)) {
      this.snackBar.open('Give the section a name.', undefined, { duration: 4000 });
      return;
    }
    await this.run(async () => {
      await firstValueFrom(
        this.brands.updateSection(
          this.brandId(),
          section.id,
          cleanTranslated(name),
          sortOrder,
          section.version,
        ),
      );
      this.renaming.set(null);
    }, 'Section saved.');
  }

  /** Swaps the section with its neighbour; sort orders are renumbered so the swap always sticks. */
  protected async move(section: EditorSection, step: -1 | 1): Promise<void> {
    const sections = [...this.menu()!.sections];
    const index = sections.findIndex((s) => s.id === section.id);
    const target = index + step;
    if (target < 0 || target >= sections.length) return;
    [sections[index], sections[target]] = [sections[target], sections[index]];
    await this.run(async () => {
      for (const [order, s] of sections.entries()) {
        if (s.sortOrder !== order) {
          await firstValueFrom(
            this.brands.updateSection(this.brandId(), s.id, s.name, order, s.version),
          );
        }
      }
    }, 'Order saved.');
  }

  protected async deleteSection(section: EditorSection): Promise<void> {
    const confirmed = await confirmAction(this.dialog, {
      title: 'Delete this section?',
      message: section.items.length
        ? `"${this.locale.pick(section.name)}" and its ${section.items.length} dishes are removed from every branch.`
        : `"${this.locale.pick(section.name)}" is removed from every branch.`,
      confirm: 'Delete',
      danger: true,
    });
    if (confirmed === null) return;
    await this.run(
      () => firstValueFrom(this.brands.deleteSection(this.brandId(), section.id)),
      'Section deleted.',
    );
  }

  protected async openItem(section: EditorSection, item: EditorItem | null): Promise<void> {
    const menu = this.menu()!;
    const data: ItemDialogData = {
      brandId: menu.brandId,
      currency: menu.currency,
      sections: menu.sections,
      sectionId: section.id,
      item,
      branches: this.branches().map((b) => ({ id: b.id, name: b.branchName ?? b.brandName })),
    };
    const saved = await firstValueFrom(
      this.dialog
        .open(ItemDialog, { data, width: '720px', maxWidth: '96vw', autoFocus: 'first-tabbable' })
        .afterClosed(),
    );
    if (saved) {
      this.snackBar.open(item ? 'Dish saved.' : 'Dish added.', undefined, { duration: 3000 });
      await this.load();
      void this.context.refreshReadiness();
    }
  }

  protected async deleteItem(item: EditorItem): Promise<void> {
    const confirmed = await confirmAction(this.dialog, {
      title: 'Delete this dish?',
      message: `"${this.locale.pick(item.name)}" is removed from every branch. To hide it for today, mark it sold out instead.`,
      confirm: 'Delete',
      danger: true,
    });
    if (confirmed === null) return;
    await this.run(
      () => firstValueFrom(this.brands.deleteItem(this.brandId(), item.id)),
      'Dish deleted.',
    );
  }

  protected async setSoldOut(
    item: EditorItem,
    soldOut: boolean,
    toggle: MatSlideToggle,
  ): Promise<void> {
    const store = this.context.store()!;
    try {
      await firstValueFrom(this.stores.setSoldOut(store.id, item.id, soldOut));
      this.soldOut.update((current) => {
        const next = new Set(current);
        if (soldOut) next.add(item.id);
        else next.delete(item.id);
        return next;
      });
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not save. Try again.'), 'Dismiss', {
        duration: 8000,
      });
      toggle.checked = soldOut;
    }
  }

  private brandId(): string {
    return this.menu()!.brandId;
  }

  private async run(action: () => Promise<unknown>, done: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      this.snackBar.open(done, undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work. Try again.'), 'Dismiss', {
        duration: 8000,
      });
    } finally {
      await this.load();
      void this.context.refreshReadiness();
      this.busy.set(false);
    }
  }
}
