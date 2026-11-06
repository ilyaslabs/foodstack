import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { City, CityClient, Promotion, PromotionClient, StoreClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { describeError } from '../shared/errors';
import { Session } from '../shared/session';
import { PromotionBranch, PromotionDialog, PromotionDialogData } from './promotion-dialog';

/** Coupons: the platform's own and each brand's (PRD PR-1..PR-4). */
@Component({
  selector: 'app-promotions-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    Icon,
  ],
  template: `
    <header class="fs-page-header with-action">
      <div>
        <h1>Coupons</h1>
        <p>
          Codes customers type at checkout. Switch one off to stop it at once; orders already placed
          keep their discount.
        </p>
      </div>
      @if (canCreate()) {
        <button mat-flat-button type="button" (click)="edit(null)">
          <fs-icon name="add" /> New coupon
        </button>
      }
    </header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else if (!promotions().length) {
      <mat-card appearance="outlined" class="empty">
        <fs-icon name="local_offer" class="empty-icon" />
        <h2>No coupons yet</h2>
        <p>Create a code such as WELCOME10 for new customers.</p>
      </mat-card>
    } @else {
      <div class="list">
        @for (promotion of promotions(); track promotion.id) {
          <mat-card appearance="outlined" class="coupon" [class.off]="!promotion.active">
            <div class="coupon-main">
              <div class="code">{{ promotion.code }}</div>
              <div class="title">{{ locale.pick(promotion.title) }}</div>
              <div class="muted">
                {{ describe(promotion) }} · {{ owner(promotion) }}{{ reach(promotion) }} · used
                {{ promotion.used }}{{ promotion.totalLimit ? ' of ' + promotion.totalLimit : '' }}
              </div>
            </div>
            <mat-slide-toggle
              [checked]="promotion.active"
              [disabled]="!canEdit(promotion)"
              (change)="setActive(promotion, $event.checked)"
              [attr.aria-label]="(promotion.active ? 'Switch off ' : 'Switch on ') + promotion.code"
            />
            @if (canEdit(promotion)) {
              <button mat-button type="button" (click)="edit(promotion)">
                <fs-icon name="edit" /> Edit
              </button>
            } @else {
              <span class="chip">From the brand</span>
            }
          </mat-card>
        }
      </div>
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .with-action {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-end;
      gap: 16px;
    }
    .list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      max-inline-size: 960px;
    }
    .coupon {
      display: flex;
      flex-direction: row;
      align-items: center;
      gap: 16px;
      padding: 16px 20px;
    }
    .coupon.off {
      opacity: 0.65;
    }
    .coupon-main {
      flex: 1;
      min-inline-size: 0;
    }
    .code {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 8px;
      border: 1px dashed var(--mat-sys-primary);
      color: var(--mat-sys-primary);
      font: var(--mat-sys-title-medium);
      font-family: 'Roboto Mono', ui-monospace, monospace;
      letter-spacing: 0.04em;
    }
    .title {
      font: var(--mat-sys-title-small);
      margin-block: 6px 2px;
    }
    .muted {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .empty {
      align-items: center;
      text-align: center;
      padding: 40px 16px;
      max-inline-size: 960px;
    }
    .empty p {
      color: var(--mat-sys-on-surface-variant);
    }
    .empty-icon {
      --fs-icon-size: 48px;
      color: var(--mat-sys-outline);
    }
    @media (max-width: 599.98px) {
      .coupon {
        flex-wrap: wrap;
      }
    }
  `,
})
export class PromotionsPage implements OnInit {
  private readonly client = inject(PromotionClient);
  private readonly stores = inject(StoreClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly session = inject(Session);
  protected readonly locale = inject(LocaleStore);

  protected readonly promotions = signal<Promotion[]>([]);
  protected readonly loading = signal(true);
  private readonly cityClient = inject(CityClient);
  private readonly brands = signal<{ id: string; name: string }[]>([]);
  private readonly branches = signal<PromotionBranch[]>([]);
  private readonly cities = signal<City[]>([]);
  /** Brands whose owner lets this branch manager make promotions for their branch (PRD BB-11). */
  private readonly allowed = signal<ReadonlySet<string>>(new Set());
  protected readonly operator = computed(() => this.session.can('promotion:manage'));
  /** Brands the user may make coupons for: owned, or allowed for their branch. */
  private readonly creatable = computed(() =>
    this.operator()
      ? this.brands()
      : this.brands().filter((b) => this.session.ownsBrand(b.id) || this.allowed().has(b.id)),
  );
  protected readonly canCreate = computed(() => this.operator() || this.creatable().length > 0);

  async ngOnInit(): Promise<void> {
    await firstValueFrom(this.session.me$).catch(() => null);
    try {
      const stores = await firstValueFrom(this.stores.list());
      const brands = new Map(stores.map((s) => [s.brandId, s.brandName]));
      this.brands.set([...brands].map(([id, name]) => ({ id, name })));
      this.branches.set(
        stores.map((s) => ({ id: s.id, brandId: s.brandId, name: s.branchName || s.brandName })),
      );
      const managed = [...brands.keys()].filter(
        (id) => !this.operator() && !this.session.ownsBrand(id),
      );
      const rules = await Promise.all(
        managed.map((id) =>
          firstValueFrom(this.client.brandRules(id))
            .then((r) => (r.branchPromotionsAllowed ? id : null))
            .catch(() => null),
        ),
      );
      this.allowed.set(new Set(rules.filter((id): id is string => id !== null)));
    } catch {
      this.brands.set([]);
    }
    if (this.operator()) {
      this.cities.set(await firstValueFrom(this.cityClient.list()).catch(() => []));
    }
    await this.load();
  }

  /** Whether the user may change the coupon: a branch manager only their own branch's. */
  protected canEdit(p: Promotion): boolean {
    if (this.operator()) return true;
    if (!p.brandId) return false;
    if (this.session.ownsBrand(p.brandId)) return true;
    const own = this.session.me()?.storeIds ?? [];
    return (
      this.allowed().has(p.brandId) &&
      p.storeIds.length > 0 &&
      p.storeIds.every((id) => own.includes(id))
    );
  }

  /** Which branches a brand's coupon reaches, when not all of them. */
  protected reach(p: Promotion): string {
    if (!p.brandId || p.storeIds.length === 0) return '';
    const names = p.storeIds.map(
      (id) => this.branches().find((b) => b.id === id)?.name ?? 'a branch',
    );
    return ` (${names.join(', ')})`;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.promotions.set(await firstValueFrom(this.client.list()));
    } finally {
      this.loading.set(false);
    }
  }

  protected describe(p: Promotion): string {
    const min = p.minOrderMinor ? ` over ${this.locale.money(p.minOrderMinor)}` : '';
    switch (p.type) {
      case 'PERCENT':
        return `${p.percentBasisPoints / 100}% off${p.maxDiscountMinor ? ` up to ${this.locale.money(p.maxDiscountMinor)}` : ''}${min}`;
      case 'FIXED':
        return `${this.locale.money(p.amountMinor)} off${min}`;
      default:
        return `Free delivery${min}`;
    }
  }

  protected owner(p: Promotion): string {
    return p.owner === 'OPERATOR'
      ? 'Platform'
      : (this.brands().find((b) => b.id === p.brandId)?.name ?? 'Brand');
  }

  protected async edit(promotion: Promotion | null): Promise<void> {
    let editing: PromotionDialogData['editing'] = null;
    if (promotion) {
      const fresh = await firstValueFrom(this.client.get(promotion.id));
      editing = { promotion: fresh.value, etag: fresh.etag };
    }
    const saved = await firstValueFrom(
      this.dialog
        .open<PromotionDialog, PromotionDialogData, boolean>(PromotionDialog, {
          data: {
            brands: this.creatable(),
            operator: this.operator(),
            editing,
            branches: this.branches(),
            ownBranchesOnly: (brandId: string) =>
              !this.operator() && !this.session.ownsBrand(brandId),
            cities: this.cities(),
          },
          width: '720px',
          maxWidth: 'calc(100vw - 32px)',
        })
        .afterClosed(),
    );
    if (saved) await this.load();
  }

  protected async setActive(promotion: Promotion, active: boolean): Promise<void> {
    try {
      await firstValueFrom(this.client.setActive(promotion.id, active));
      this.promotions.update((list) =>
        list.map((p) => (p.id === promotion.id ? { ...p, active } : p)),
      );
    } catch (error) {
      this.snackBar.open(describeError(error, 'The coupon could not be changed.'), 'Close', {
        duration: 6000,
      });
      await this.load();
    }
  }
}
