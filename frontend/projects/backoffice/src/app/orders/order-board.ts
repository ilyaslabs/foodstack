import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  DispatchClient,
  Order,
  OrderAction,
  OrderClient,
  RiderClient,
  StoreClient,
  StoreSummary,
} from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';
import { Icon, LocaleStore } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import { AcceptData, AcceptDialog, AcceptResult } from './accept-dialog';
import { ACTION_LABELS, STATUS_LABELS, clock, primaryAction } from './order-labels';
import { OrderTicket } from './order-ticket';

const STORE_KEY = 'fs-board-store';
const SOUND_KEY = 'fs-board-sound';
const ALERT_EVERY_MS = 4000;

interface Column {
  key: string;
  title: string;
  orders: Order[];
}

/**
 * The live order board of a branch (PRD 4.6 OF-1..OF-5). Orders arrive over a server-sent event
 * stream; new ones ring until someone accepts or rejects them, with a countdown to the accept
 * deadline. Each card moves through accept, preparing, ready and handed over, and prints as a
 * kitchen ticket.
 */
@Component({
  selector: 'app-order-board',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSnackBarModule,
    MatTooltipModule,
    Icon,
    OrderTicket,
  ],
  templateUrl: './order-board.html',
  styleUrl: './order-board.scss',
})
export class OrderBoard implements OnInit {
  private readonly orders = inject(OrderClient);
  private readonly riders = inject(RiderClient);
  private readonly dispatch = inject(DispatchClient);
  private readonly stores = inject(StoreClient);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly document = inject(DOCUMENT);
  protected readonly locale = inject(LocaleStore);

  protected readonly storeList = signal<StoreSummary[]>([]);
  protected readonly storeId = signal<string | null>(null);
  protected readonly board = signal<Order[]>([]);
  protected readonly loading = signal(true);
  protected readonly live = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly soundOn = signal(false);
  protected readonly printing = signal<Order | null>(null);
  /** The branch's ticket paper width (PRD ST-9), read with the branch. */
  protected readonly paperMm = signal<80 | 58>(80);
  protected readonly now = signal(Date.now());
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly clock = clock;
  protected readonly primaryAction = primaryAction;

  private stream: Subscription | null = null;
  private audio: AudioContext | null = null;
  private reloadQueued = false;
  private reloading = false;
  private readonly title = this.document.title;

  protected readonly fresh = computed(() => this.board().filter((o) => o.status === 'CONFIRMED'));

  protected readonly columns = computed<Column[]>(() => {
    const board = this.board();
    const columns: Column[] = [
      { key: 'new', title: 'New', orders: board.filter((o) => o.status === 'CONFIRMED') },
      {
        key: 'kitchen',
        title: 'In the kitchen',
        orders: board.filter((o) => o.status === 'ACCEPTED' || o.status === 'PREPARING'),
      },
      { key: 'ready', title: 'Ready', orders: board.filter((o) => o.status === 'READY') },
    ];
    const away = board.filter((o) => o.status === 'PICKED_UP');
    if (away.length) columns.push({ key: 'away', title: 'Out for delivery', orders: away });
    return columns;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    const ticker = setInterval(() => this.now.set(Date.now()), 1000);
    const alarm = setInterval(() => this.ring(), ALERT_EVERY_MS);
    destroyRef.onDestroy(() => {
      clearInterval(ticker);
      clearInterval(alarm);
      this.stream?.unsubscribe();
      void this.audio?.close();
      this.document.title = this.title;
    });
    effect(() => {
      const id = this.storeId();
      untracked(() => {
        this.open(id);
        void this.loadPaper(id);
      });
    });
    effect(() => {
      const count = this.fresh().length;
      this.document.title = count ? `(${count}) New orders · ${this.title}` : this.title;
    });
  }

  async ngOnInit(): Promise<void> {
    this.soundOn.set(this.read(SOUND_KEY) === 'on');
    try {
      const stores = (await firstValueFrom(this.stores.list())).filter(
        (s) => s.status === 'LIVE' || s.status === 'PAUSED',
      );
      this.storeList.set(stores);
      const saved = this.read(STORE_KEY);
      this.storeId.set(stores.find((s) => s.id === saved)?.id ?? stores[0]?.id ?? null);
    } catch {
      this.storeList.set([]);
    }
    if (!this.storeId()) this.loading.set(false);
  }

  private async loadPaper(storeId: string | null): Promise<void> {
    if (!storeId) return;
    try {
      const { value } = await firstValueFrom(this.stores.details(storeId));
      if (storeId === this.storeId()) this.paperMm.set(value.ticketPaperMm === 58 ? 58 : 80);
    } catch {
      // the ticket prints on 80 mm paper by default
    }
  }

  protected pickStore(id: string): void {
    this.write(STORE_KEY, id);
    this.storeId.set(id);
  }

  private open(storeId: string | null): void {
    this.stream?.unsubscribe();
    this.stream = null;
    this.live.set(false);
    this.board.set([]);
    if (!storeId) return;
    this.loading.set(true);
    void this.reload();
    this.stream = this.orders
      .watchStore(storeId, () => this.auth.freshToken())
      .subscribe({
        next: () => {
          this.live.set(true);
          void this.reload();
        },
        error: () => this.live.set(false),
      });
  }

  /** One reload at a time; events that arrive meanwhile cause one more. */
  private async reload(): Promise<void> {
    const storeId = this.storeId();
    if (!storeId) return;
    if (this.reloading) {
      this.reloadQueued = true;
      return;
    }
    this.reloading = true;
    try {
      const board = await firstValueFrom(this.orders.board(storeId));
      if (storeId === this.storeId()) this.board.set(board);
    } catch {
      // keep the last board; the next event tries again
    } finally {
      this.reloading = false;
      this.loading.set(false);
      if (this.reloadQueued) {
        this.reloadQueued = false;
        void this.reload();
      }
    }
  }

  protected secondsLeft(order: Order): number | null {
    return order.acceptBy ? Math.max(0, Math.round(order.acceptBy - this.now() / 1000)) : null;
  }

  protected countdown(order: Order): string {
    const left = this.secondsLeft(order) ?? 0;
    return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  }

  protected ago(epochSeconds: number): string {
    const minutes = Math.max(0, Math.floor((this.now() / 1000 - epochSeconds) / 60));
    return minutes < 1 ? 'just now' : `${minutes} min ago`;
  }

  protected money(order: Order, minor: number): string {
    return this.locale.money(minor, order.currency);
  }

  protected secondary(order: Order): OrderAction[] {
    const primary = primaryAction(order);
    return order.actions.filter((a) => a !== primary && a !== 'REFUND' && a in ACTION_LABELS);
  }

  /** A delivery the branch makes itself, accepted without a rider: pick one now. */
  protected async assignRider(order: Order): Promise<void> {
    const riders = await firstValueFrom(this.riders.storeRiders(order.store.storeId)).catch(
      () => [],
    );
    const chosen = await firstValueFrom(
      this.dialog
        .open<AcceptDialog, AcceptData, AcceptResult | null>(AcceptDialog, {
          data: { number: order.number, prepMinutes: order.prepMinutes, riders, riderOnly: true },
        })
        .afterClosed(),
    );
    if (!chosen?.storeRiderId) return;
    try {
      await firstValueFrom(this.dispatch.assignStoreRider(order.id, chosen.storeRiderId));
      this.snackBar.open('The rider has the delivery.', undefined, { duration: 3000 });
    } catch (error) {
      this.snackBar.open(describeError(error, 'The rider was not assigned.'), 'Close', {
        duration: 6000,
      });
    }
    void this.reload();
  }

  protected async act(order: Order, action: OrderAction): Promise<void> {
    let body: {
      prepMinutes?: number | null;
      reason?: string | null;
      storeRiderId?: string | null;
    } = {};
    if (action === 'ACCEPT') {
      const selfDelivery = order.fulfilment === 'DELIVERY' && order.deliveryMode === 'SELF';
      const riders = selfDelivery
        ? await firstValueFrom(this.riders.storeRiders(order.store.storeId)).catch(() => [])
        : undefined;
      const accepted = await firstValueFrom(
        this.dialog
          .open<AcceptDialog, AcceptData, AcceptResult | null>(AcceptDialog, {
            data: { number: order.number, prepMinutes: order.prepMinutes, riders },
          })
          .afterClosed(),
      );
      if (!accepted) return;
      body = { prepMinutes: accepted.minutes, storeRiderId: accepted.storeRiderId };
    } else if (action === 'REJECT' || action === 'CANCEL') {
      const reason = await confirmAction(this.dialog, {
        title:
          action === 'REJECT' ? `Reject order ${order.number}?` : `Cancel order ${order.number}?`,
        message:
          order.paymentMethod === 'CARD'
            ? 'The customer is told at once and their card payment is refunded.'
            : 'The customer is told at once.',
        confirm: action === 'REJECT' ? 'Reject' : 'Cancel order',
        danger: true,
        inputLabel: 'Reason for the customer',
        inputHint: 'For example: out of chicken today',
        inputRequired: true,
      });
      if (reason === null) return;
      body = { reason };
    }
    this.busy.set(order.id);
    try {
      const updated =
        action === 'CANCEL'
          ? await firstValueFrom(this.orders.cancel(order.id, body.reason))
          : await firstValueFrom(this.orders.act(order.id, action, body));
      this.board.update((board) =>
        board.map((o) => (o.id === updated.id ? updated : o)).filter((o) => !isFinal(o)),
      );
      if (action === 'ACCEPT') this.print(updated);
    } catch (error) {
      this.snackBar.open(describeError(error, 'That did not work. Try again.'), 'Close', {
        duration: 6000,
      });
      void this.reload();
    } finally {
      this.busy.set(null);
    }
  }

  /** Prints the kitchen ticket through the browser's print dialog. */
  protected print(order: Order): void {
    this.printing.set(order);
    const body = this.document.body;
    body.classList.add('fs-printing-ticket');
    setTimeout(() => {
      this.document.defaultView?.print();
      body.classList.remove('fs-printing-ticket');
    }, 50);
  }

  /** Browsers allow sound only after a click, so sound starts from this button. */
  protected async toggleSound(): Promise<void> {
    const on = !this.soundOn();
    this.soundOn.set(on);
    this.write(SOUND_KEY, on ? 'on' : 'off');
    if (on) {
      this.audio ??= new AudioContext();
      await this.audio.resume();
      this.beep();
    }
  }

  protected soundBlocked(): boolean {
    return this.soundOn() && (!this.audio || this.audio.state !== 'running');
  }

  private ring(): void {
    if (!this.fresh().length || !this.soundOn()) return;
    if (!this.audio || this.audio.state !== 'running') return;
    this.beep();
  }

  private beep(): void {
    const audio = this.audio;
    if (!audio) return;
    const start = audio.currentTime;
    for (const [offset, frequency] of [
      [0, 880],
      [0.18, 1175],
    ]) {
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      oscillator.type = 'sine';
      oscillator.frequency.value = frequency;
      gain.gain.setValueAtTime(0.0001, start + offset);
      gain.gain.exponentialRampToValueAtTime(0.35, start + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + offset + 0.16);
      oscillator.connect(gain).connect(audio.destination);
      oscillator.start(start + offset);
      oscillator.stop(start + offset + 0.17);
    }
  }

  private read(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private write(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // storage blocked
    }
  }
}

function isFinal(order: Order): boolean {
  return ['DELIVERED', 'COLLECTED', 'REJECTED', 'CANCELLED', 'FAILED'].includes(order.status);
}
