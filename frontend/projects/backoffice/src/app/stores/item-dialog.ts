import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import {
  BrandClient,
  DIETARY_TAGS,
  EditorItem,
  EditorSection,
  MediaClient,
  MenuItemInput,
  Translated,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { toMajor, toMinor } from '../shared/money';
import { TranslatedField, cleanTranslated, hasDefault } from '../shared/translated-field';

export interface ItemDialogData {
  brandId: string;
  currency: string;
  sections: EditorSection[];
  sectionId: string;
  item: EditorItem | null;
  /** The brand's branches, to sell a dish at some of them only (PRD BB-3). */
  branches?: { id: string; name: string }[];
}

interface OptionDraft {
  id: string | null;
  name: Translated;
  price: string;
}

interface GroupDraft {
  id: string | null;
  name: Translated;
  minSelect: number;
  maxSelect: number;
  options: OptionDraft[];
}

export const DIETARY_LABELS: Record<string, string> = {
  vegetarian: 'Vegetarian',
  vegan: 'Vegan',
  halal: 'Halal',
  'gluten-free': 'Gluten free',
  'dairy-free': 'Dairy free',
  spicy: 'Spicy',
  'contains-nuts': 'Contains nuts',
};

/** Adds or edits one dish: names in every language, price, photo, dietary tags and choices. */
@Component({
  selector: 'app-item-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatChipsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    Icon,
    TranslatedField,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.item ? 'Edit dish' : 'Add a dish' }}</h2>
    <mat-dialog-content>
      <div class="body">
        <app-translated-field label="Name" [required]="true" [(value)]="name" />
        <app-translated-field
          label="Description"
          [multiline]="true"
          [maxLength]="400"
          [(value)]="description"
        />

        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Price</mat-label>
            <input matInput inputmode="decimal" [(ngModel)]="price" required />
            <span matTextSuffix>&nbsp;{{ data.currency }}</span>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Section</mat-label>
            <mat-select [(ngModel)]="sectionId">
              @for (section of data.sections; track section.id) {
                <mat-option [value]="section.id">{{ locale.pick(section.name) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>

        <section class="photo">
          @if (image(); as img) {
            <img [src]="img.url" alt="" width="96" height="72" />
          } @else {
            <span class="photo-empty" aria-hidden="true"><fs-icon name="restaurant" /></span>
          }
          <div class="photo-actions">
            <button
              mat-stroked-button
              type="button"
              (click)="file.click()"
              [disabled]="uploading()"
            >
              <fs-icon name="upload" /> {{ image() ? 'Replace photo' : 'Add photo' }}
            </button>
            @if (image()) {
              <button mat-button type="button" (click)="image.set(null)">Remove</button>
            }
          </div>
          <input
            #file
            type="file"
            hidden
            accept="image/png,image/jpeg,image/webp"
            (change)="upload($event)"
          />
        </section>
        @if (uploading()) {
          <mat-progress-bar mode="indeterminate" />
        }

        @if ((data.branches?.length ?? 0) > 1) {
          <div>
            <h3>Sold at</h3>
            <mat-chip-listbox
              aria-label="Where the dish is sold"
              [ngModel]="everywhere ? 'all' : 'some'"
              (ngModelChange)="everywhere = $event !== 'some'"
            >
              <mat-chip-option value="all">Every branch</mat-chip-option>
              <mat-chip-option value="some">Chosen branches</mat-chip-option>
            </mat-chip-listbox>
            @if (!everywhere) {
              <mat-chip-listbox
                multiple
                aria-label="Branches"
                [(ngModel)]="branchIds"
                class="branches"
              >
                @for (branch of data.branches; track branch.id) {
                  <mat-chip-option [value]="branch.id">{{ branch.name }}</mat-chip-option>
                }
              </mat-chip-listbox>
              <p class="hint">Only these branches show the dish.</p>
            }
          </div>
        }

        <div>
          <h3>Dietary</h3>
          <mat-chip-listbox multiple aria-label="Dietary tags" [(ngModel)]="tags">
            @for (tag of dietaryTags; track tag) {
              <mat-chip-option [value]="tag">{{ dietaryLabels[tag] }}</mat-chip-option>
            }
          </mat-chip-listbox>
        </div>

        <div>
          <div class="groups-head">
            <h3>Choices</h3>
            <button mat-button type="button" (click)="addGroup()">
              <fs-icon name="add" /> Add a choice
            </button>
          </div>
          <p class="hint">
            For example "Size" with Regular and Large, or "Extras" a customer may add.
          </p>
          @for (group of groups(); track $index; let g = $index) {
            <div class="group">
              <div class="group-head">
                <app-translated-field
                  label="Choice name"
                  [required]="true"
                  [maxLength]="60"
                  [(value)]="group.name"
                />
                <button
                  mat-icon-button
                  type="button"
                  (click)="removeGroup(g)"
                  aria-label="Remove choice"
                >
                  <fs-icon name="close" />
                </button>
              </div>
              <div class="row">
                <mat-form-field appearance="outline">
                  <mat-label>Customer picks at least</mat-label>
                  <input matInput type="number" min="0" [(ngModel)]="group.minSelect" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>And at most</mat-label>
                  <input matInput type="number" min="1" [(ngModel)]="group.maxSelect" />
                </mat-form-field>
              </div>
              @for (option of group.options; track $index; let o = $index) {
                <div class="option">
                  <app-translated-field
                    label="Option"
                    [required]="true"
                    [maxLength]="60"
                    [(value)]="option.name"
                  />
                  <mat-form-field appearance="outline" class="delta">
                    <mat-label>Extra price</mat-label>
                    <input matInput inputmode="decimal" [(ngModel)]="option.price" />
                  </mat-form-field>
                  <button
                    mat-icon-button
                    type="button"
                    (click)="removeOption(g, o)"
                    aria-label="Remove option"
                  >
                    <fs-icon name="close" />
                  </button>
                </div>
              }
              <button mat-button type="button" (click)="addOption(g)">
                <fs-icon name="add" /> Add option
              </button>
            </div>
          }
        </div>

        @if (error()) {
          <div class="banner error" role="alert">{{ error() }}</div>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="button" (click)="save()" [disabled]="saving() || uploading()">
        {{ data.item ? 'Save dish' : 'Add dish' }}
      </button>
    </mat-dialog-actions>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .body {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding-block-start: 4px;
    }
    .row {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px 16px;
    }
    @media (max-width: 599.98px) {
      .row {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    h3 {
      font: var(--mat-sys-title-small);
      margin: 0 0 8px;
    }
    .hint {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
      margin: -4px 0 8px;
    }
    .photo {
      display: flex;
      align-items: center;
      gap: 16px;
    }
    .photo img,
    .photo-empty {
      inline-size: 96px;
      block-size: 72px;
      border-radius: 12px;
      object-fit: cover;
      flex: none;
    }
    .photo-empty {
      display: grid;
      place-items: center;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .photo-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .groups-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .group {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
      padding: 16px;
      margin-block-end: 12px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .group-head,
    .option {
      display: flex;
      align-items: flex-start;
      gap: 8px;
    }
    .group-head app-translated-field,
    .option app-translated-field {
      flex: 1;
      min-width: 0;
    }
    .delta {
      inline-size: 140px;
      flex: none;
    }
    @media (max-width: 599.98px) {
      .option {
        flex-wrap: wrap;
      }
      .delta {
        inline-size: auto;
        flex: 1;
      }
    }
  `,
})
export class ItemDialog {
  protected readonly data = inject<ItemDialogData>(MAT_DIALOG_DATA);
  protected readonly locale = inject(LocaleStore);
  private readonly ref = inject<MatDialogRef<ItemDialog, EditorItem>>(MatDialogRef);
  private readonly brands = inject(BrandClient);
  private readonly media = inject(MediaClient);

  protected readonly dietaryTags = DIETARY_TAGS;
  protected readonly dietaryLabels = DIETARY_LABELS;

  protected name: Translated = { ...(this.data.item?.name ?? {}) };
  protected description: Translated = { ...(this.data.item?.description ?? {}) };
  protected price = toMajor(this.data.item?.priceMinor, this.data.currency);
  protected sectionId = this.data.item?.sectionId ?? this.data.sectionId;
  protected tags: string[] = [...(this.data.item?.dietaryTags ?? [])];
  protected readonly image = signal<{ mediaId: string; url: string } | null>(
    this.data.item?.imageMediaId
      ? {
          mediaId: this.data.item.imageMediaId,
          url: this.data.item.imageUrls['card'] ?? Object.values(this.data.item.imageUrls)[0] ?? '',
        }
      : null,
  );
  protected readonly groups = signal<GroupDraft[]>(
    (this.data.item?.modifierGroups ?? []).map((group) => ({
      id: group.id ?? null,
      name: { ...group.name },
      minSelect: group.minSelect,
      maxSelect: group.maxSelect,
      options: group.options.map((option) => ({
        id: option.id ?? null,
        name: { ...option.name },
        price: toMajor(option.priceDeltaMinor, this.data.currency),
      })),
    })),
  );
  protected everywhere = !this.data.item?.branchIds?.length;
  protected branchIds: string[] = [...(this.data.item?.branchIds ?? [])];
  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected addGroup(): void {
    this.groups.update((groups) => [
      ...groups,
      {
        id: null,
        name: {},
        minSelect: 0,
        maxSelect: 1,
        options: [{ id: null, name: {}, price: '' }],
      },
    ]);
  }

  protected removeGroup(index: number): void {
    this.groups.update((groups) => groups.filter((_, i) => i !== index));
  }

  protected addOption(group: number): void {
    this.groups()[group].options.push({ id: null, name: {}, price: '' });
    this.groups.update((groups) => [...groups]);
  }

  protected removeOption(group: number, option: number): void {
    this.groups()[group].options.splice(option, 1);
    this.groups.update((groups) => [...groups]);
  }

  protected async upload(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    try {
      const asset = await firstValueFrom(this.media.upload(file, 'MENU_ITEM'));
      const url = asset.variants['card']?.url ?? Object.values(asset.variants)[0]?.url ?? '';
      this.image.set({ mediaId: asset.id, url });
    } catch (error) {
      this.error.set(describeError(error, 'The photo could not be uploaded.'));
    } finally {
      this.uploading.set(false);
      input.value = '';
    }
  }

  protected async save(): Promise<void> {
    const defaultLanguage = this.locale.locale().defaultLanguage;
    const currency = this.data.currency;
    const priceMinor = toMinor(this.price, currency);
    if (!hasDefault(this.name, defaultLanguage)) {
      this.error.set('Give the dish a name.');
      return;
    }
    if (priceMinor === null) {
      this.error.set('Enter a price, such as 850.');
      return;
    }
    const groups = [];
    for (const group of this.groups()) {
      if (!hasDefault(group.name, defaultLanguage)) {
        this.error.set('Every choice needs a name.');
        return;
      }
      const options = [];
      for (const option of group.options) {
        const delta = option.price.trim() === '' ? 0 : toMinor(option.price, currency);
        if (!hasDefault(option.name, defaultLanguage) || delta === null) {
          this.error.set('Every option needs a name and a valid extra price.');
          return;
        }
        options.push({ id: option.id, name: cleanTranslated(option.name), priceDeltaMinor: delta });
      }
      const min = Number(group.minSelect) || 0;
      const max = Number(group.maxSelect) || 0;
      if (options.length === 0 || max < 1 || min > max || max > options.length) {
        this.error.set(
          'Check each choice: it needs options, and "at most" must be between "at least" and the number of options.',
        );
        return;
      }
      groups.push({
        id: group.id,
        name: cleanTranslated(group.name),
        minSelect: min,
        maxSelect: max,
        options,
      });
    }

    if (!this.everywhere && (this.data.branches?.length ?? 0) > 1 && !this.branchIds.length) {
      this.error.set('Choose the branches that sell the dish, or pick every branch.');
      return;
    }
    const input: MenuItemInput = {
      sectionId: this.sectionId,
      name: cleanTranslated(this.name),
      description: cleanTranslated(this.description),
      priceMinor,
      imageMediaId: this.image()?.mediaId ?? null,
      dietaryTags: this.tags,
      modifierGroups: groups,
      branchIds: this.everywhere || !this.branchIds.length ? null : this.branchIds,
      sortOrder:
        this.data.item?.sortOrder ??
        this.data.sections.find((s) => s.id === this.sectionId)?.items.length ??
        0,
      version: this.data.item?.version,
    };
    this.saving.set(true);
    this.error.set('');
    try {
      const saved = await firstValueFrom(
        this.data.item
          ? this.brands.updateItem(this.data.brandId, this.data.item.id, input)
          : this.brands.createItem(this.data.brandId, input),
      );
      this.ref.close(saved);
    } catch (error) {
      this.error.set(describeError(error, 'The dish could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }
}
