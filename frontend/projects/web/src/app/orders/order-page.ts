import {
  Component,
  DestroyRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { FINAL_STATUSES, Order, OrderClient, OrderStatus } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { Icon, LocaleStore, PushRegistration } from '@foodstack/ui';

import { Ui } from '../i18n';
import { RateOrder } from '../reviews/rate-order';
import { CancelOrderDialog } from './cancel-order-dialog';
import { DeliveryTracking } from './delivery-tracking';
import { hintKey, statusKey, stepsFor, tone } from './order-status';

/**
 * One order, live (PRD 4.5 OR-6): the status moves by itself as the store works, through a server
 * sent event stream; each event reloads the order, so the page never shows a half update.
 */
@Component({
  selector: 'app-order-page',
  imports: [RouterLink, MatButtonModule, MatProgressBarModule, DeliveryTracking, Icon, RateOrder],
  templateUrl: './order-page.html',
  styleUrl: './order-page.scss',
})
export class OrderPage {
  readonly id = input.required<string>();
  /** `cancelled` when the customer came back from the card page without paying. */
  readonly payment = input<string | undefined>();

  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  private readonly orders = inject(OrderClient);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  protected readonly push = inject(PushRegistration);

  protected readonly order = signal<Order | null>(null);
  protected readonly missing = signal(false);
  protected readonly live = signal(false);
  protected readonly busy = signal(false);
  protected readonly statusKey = statusKey;
  protected readonly tone = tone;

  private stream: Subscription | null = null;
  private browser = false;

  protected readonly steps = computed(() => {
    const order = this.order();
    if (!order) return [];
    const steps = stepsFor(order);
    const reached = new Set(order.timeline.map((step) => step.status));
    const current = steps.indexOf(order.status);
    return steps.map((status, index) => ({
      status,
      done: reached.has(status) || (current >= 0 && index < current),
      current: status === order.status,
      at: order.timeline.find((step) => step.status === status)?.at ?? null,
    }));
  });

  protected readonly stopped = computed(() => {
    const status = this.order()?.status;
    return status === 'REJECTED' || status === 'CANCELLED' || status === 'FAILED';
  });

  protected readonly hint = computed(() => {
    const order = this.order();
    return order ? hintKey(order) : null;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => this.stream?.unsubscribe());
    afterNextRender(() => {
      this.browser = true;
      void this.load(this.id());
      void this.push.init('web');
    });
    effect(() => {
      const id = this.id();
      untracked(() => {
        if (this.browser) void this.load(id);
      });
    });
  }

  private async load(id: string): Promise<void> {
    try {
      const order = await firstValueFrom(this.orders.get(id));
      this.order.set(order);
      this.follow(order);
    } catch {
      this.missing.set(true);
    }
  }

  /** Follows live changes until the order is finished. */
  private follow(order: Order): void {
    if (FINAL_STATUSES.includes(order.status)) {
      this.stream?.unsubscribe();
      this.stream = null;
      this.live.set(false);
      return;
    }
    if (this.stream) return;
    this.stream = this.orders
      .watch(order.id, () => this.auth.freshToken())
      .subscribe({
        next: () => {
          this.live.set(true);
          void this.refresh();
        },
        error: () => this.live.set(false),
      });
  }

  private async refresh(): Promise<void> {
    const current = this.order();
    if (!current) return;
    try {
      const order = await firstValueFrom(this.orders.get(current.id));
      if (order.revision >= current.revision) this.order.set(order);
      this.follow(order);
    } catch {
      // keep what is shown
    }
  }

  protected money(minor: number): string {
    return this.locale.money(minor, this.order()?.currency);
  }

  protected time(epochSeconds: number | null): string {
    if (!epochSeconds) return '';
    try {
      return new Intl.DateTimeFormat(this.locale.language(), {
        hour: 'numeric',
        minute: '2-digit',
      }).format(new Date(epochSeconds * 1000));
    } catch {
      return new Date(epochSeconds * 1000).toLocaleTimeString();
    }
  }

  protected date(epochSeconds: number): string {
    try {
      return new Intl.DateTimeFormat(this.locale.language(), {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(new Date(epochSeconds * 1000));
    } catch {
      return new Date(epochSeconds * 1000).toLocaleString();
    }
  }

  protected options(line: Order['lines'][number]): string {
    return line.options.map((option) => this.locale.pick(option.name)).join(', ');
  }

  protected can(action: 'CANCEL' | 'PAY'): boolean {
    return this.order()?.actions.includes(action) ?? false;
  }

  protected pay(): void {
    const url = this.order()?.paymentUrl;
    if (url) window.location.assign(url);
  }

  protected async cancel(): Promise<void> {
    const order = this.order();
    if (!order) return;
    const confirmed = await firstValueFrom(
      this.dialog
        .open(CancelOrderDialog, { width: '420px', maxWidth: 'calc(100vw - 32px)' })
        .afterClosed(),
    );
    if (!confirmed) return;
    this.busy.set(true);
    try {
      this.order.set(await firstValueFrom(this.orders.cancel(order.id)));
      this.follow(this.order()!);
    } finally {
      this.busy.set(false);
    }
  }

  /** A delivery on its way gets the rider, the map and the delivery code. */
  protected tracks(order: Order): boolean {
    return (
      order.fulfilment === 'DELIVERY' &&
      order.status !== 'PAYMENT_PENDING' &&
      !FINAL_STATUSES.includes(order.status)
    );
  }

  protected statusLabel(status: OrderStatus): string {
    return this.ui.t(statusKey(status));
  }
}
