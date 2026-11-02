import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatRadioModule } from '@angular/material/radio';

import {
  CartClient,
  CartLine,
  Checkout,
  CheckoutProblem,
  Fulfilment,
  OrderClient,
  PaymentMethod,
  PlaceOrder,
  SavedAddress,
} from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { AddressBook, addressIcon, addressTitle, locationOf } from '../addresses/address-book';
import { AddressEditor } from '../addresses/address-editor';
import { CartStore } from '../cart/cart-store';
import { Ui, UiKey } from '../i18n';
import { LocationPicker } from '../location/location-picker';
import { LocationStore } from '../location-store';

const PHONE_KEY = 'fs-contact-phone';

const TIP_PERCENTS = [0, 5, 10, 15];

/**
 * Checkout (PRD 4.5): how the food comes, where, how to pay, a coupon and a tip. Every change asks
 * the server for a fresh quote, so the total on the button is what the order costs. Placing sends
 * that total; if prices moved in between, the server refuses and the page shows the new total.
 */
@Component({
  selector: 'app-checkout-page',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatRadioModule,
    Icon,
    LocationPicker,
  ],
  templateUrl: './checkout-page.html',
  styleUrl: './checkout-page.scss',
})
export class CheckoutPage {
  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  protected readonly locations = inject(LocationStore);
  protected readonly cart = inject(CartStore);
  private readonly cartClient = inject(CartClient);
  private readonly orders = inject(OrderClient);
  private readonly router = inject(Router);
  private readonly dialogs = inject(MatDialog);
  protected readonly book = inject(AddressBook);

  protected readonly fulfilment = signal<Fulfilment>('DELIVERY');
  protected readonly paymentMethod = signal<PaymentMethod | null>(null);
  protected readonly couponCode = signal<string | null>(null);
  protected readonly tipPercent = signal(0);
  protected readonly quote = signal<Checkout | null>(null);
  protected readonly loading = signal(true);
  protected readonly placing = signal(false);
  protected readonly error = signal<UiKey | null>(null);
  protected readonly editingLocation = signal(false);
  protected readonly submitted = signal(false);
  protected readonly tipPercents = TIP_PERCENTS;

  protected couponInput = '';
  protected addressLine = '';
  protected instructions = '';
  protected phone = '';
  /** Save a typed address to the account after the order is placed (PRD ID-6). */
  protected saveAddress = true;

  protected readonly savedList = computed(() => this.book.addresses() ?? []);

  private quoteRun = 0;
  private attempt: { key: string; body: string } | null = null;

  protected readonly store = computed(() => this.quote()?.store ?? this.cart.cart()?.store ?? null);
  protected readonly tipMinor = computed(() => this.tipFor(this.tipPercent()));
  protected readonly couponProblem = computed(() => {
    const coupon = this.quote()?.coupon;
    return coupon && !coupon.applies ? coupon : null;
  });
  protected readonly blocking = computed(() =>
    (this.quote()?.problems ?? []).filter((problem) => problem.code !== 'COUPON_INVALID'),
  );

  constructor() {
    afterNextRender(() => {
      this.locations.restore();
      try {
        this.phone = localStorage.getItem(PHONE_KEY) ?? '';
      } catch {
        // storage blocked
      }
      void this.cart.load();
      void this.book.load().then(() => this.pickSaved());
    });
    effect(() => {
      // a fresh quote whenever something that changes the price changes
      const request = {
        fulfilment: this.fulfilment(),
        location: this.locations.location(),
        coupon: this.couponCode(),
        tip: this.tipMinor(),
        method: this.paymentMethod(),
        cartVersion: this.cart.cart()?.version,
      };
      if (request.cartVersion === undefined) return;
      untracked(() => void this.refresh());
    });
  }

  protected async refresh(): Promise<void> {
    const run = ++this.quoteRun;
    const location = this.fulfilment() === 'DELIVERY' ? this.locations.location() : null;
    try {
      const quote = await firstValueFrom(
        this.cartClient.checkout({
          fulfilment: this.fulfilment(),
          lat: location?.lat ?? null,
          lng: location?.lng ?? null,
          couponCode: this.couponCode(),
          tipMinor: this.fulfilment() === 'DELIVERY' ? this.tipMinor() : 0,
          paymentMethod: this.paymentMethod(),
        }),
      );
      if (run !== this.quoteRun) return;
      this.quote.set(quote);
      if (quote.store && !quote.store.pickupEnabled && this.fulfilment() === 'PICKUP') {
        this.fulfilment.set('DELIVERY');
      }
      const method = this.paymentMethod();
      if (!method || !quote.paymentMethods.includes(method)) {
        this.paymentMethod.set(quote.paymentMethods[0] ?? null);
      }
    } catch {
      if (run === this.quoteRun) this.error.set('placeFailed');
    } finally {
      if (run === this.quoteRun) this.loading.set(false);
    }
  }

  /** Starts with the address the header shows, else the preferred one (PRD ID-6). */
  private pickSaved(): void {
    const list = this.savedList();
    if (!list.length) return;
    const current = this.locations.location();
    const match = list.find((a) => a.id === current?.addressId);
    this.useSaved((match ?? list.find((a) => a.preferred) ?? list[0]).id);
  }

  protected useSaved(id: string): void {
    const address = this.savedList().find((a) => a.id === id);
    if (!address) return;
    this.locations.set(
      locationOf(address, `${addressTitle(this.ui, address)} · ${address.addressLine}`),
    );
    this.addressLine = address.addressLine;
    this.instructions = address.instructions ?? '';
  }

  protected newAddress(): void {
    this.dialogs
      .open(AddressEditor, {
        data: { start: null },
        width: '560px',
        maxWidth: '96vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((saved?: SavedAddress) => {
        if (saved) this.useSaved(saved.id);
      });
  }

  protected addressTitle(address: SavedAddress): string {
    return addressTitle(this.ui, address);
  }

  protected addressIcon(address: SavedAddress) {
    return addressIcon(address);
  }

  protected applyCoupon(): void {
    const code = this.couponInput.trim().toUpperCase();
    this.couponCode.set(code || null);
  }

  protected removeCoupon(): void {
    this.couponInput = '';
    this.couponCode.set(null);
  }

  protected setFulfilment(value: Fulfilment): void {
    this.fulfilment.set(value);
    this.error.set(null);
  }

  protected money(minor: number): string {
    return this.locale.money(
      minor,
      this.quote()?.currency ?? this.cart.cart()?.currency ?? undefined,
    );
  }

  protected options(line: CartLine): string {
    return line.priced.options.map((option) => this.locale.pick(option.name)).join(', ');
  }

  protected problemText(problem: CheckoutProblem): string {
    switch (problem.code) {
      case 'STORE_CLOSED':
        return this.ui.t('problemStoreClosed');
      case 'STORE_NOT_LIVE':
        return this.ui.t('problemStoreNotLive');
      case 'PICKUP_UNAVAILABLE':
        return this.ui.t('problemPickupUnavailable');
      case 'ADDRESS_REQUIRED':
        return this.ui.t('setLocationFirst');
      case 'NOT_DELIVERABLE':
        return this.ui.t('problemNotDeliverable');
      case 'MIN_ORDER':
        return this.ui.t('problemMinOrder', {
          amount: this.money(Number(problem.details?.['minOrderMinor'] ?? 0)),
        });
      case 'LINE_PROBLEM':
        return this.ui.t('problemLine');
      case 'PAYMENT_METHOD_UNAVAILABLE':
        return this.ui.t('problemPayment');
      case 'CART_EMPTY':
        return this.ui.t('problemCartEmpty');
      case 'TIP_INVALID':
        return this.ui.t('problemTip');
      default:
        return this.ui.t('placeFailed');
    }
  }

  protected couponText(reason: string | null, details: Record<string, unknown> | null): string {
    switch (reason) {
      case 'COUPON_NOT_FOUND':
      case 'COUPON_INACTIVE':
        return this.ui.t('couponNotFound');
      case 'COUPON_EXPIRED':
        return this.ui.t('couponExpired');
      case 'COUPON_MIN_ORDER':
        return this.ui.t('couponMinOrder', {
          amount: this.money(Number(details?.['minOrderMinor'] ?? 0)),
        });
      default:
        return this.ui.t('couponNotApplicable');
    }
  }

  protected tipFor(percent: number): number {
    const subtotal = this.cart.cart()?.subtotalMinor ?? 0;
    return roundTip((subtotal * percent) / 100, this.cart.cart()?.currency ?? '');
  }

  protected tipLabel(percent: number): string {
    return percent === 0 ? this.ui.t('tipNone') : `${this.ui.number(percent)}%`;
  }

  protected addressMissing(): boolean {
    return this.submitted() && this.fulfilment() === 'DELIVERY' && !this.addressLine.trim();
  }

  protected phoneValid(): boolean {
    return /^[+0-9][0-9 ()-]{6,19}$/.test(this.phone.trim());
  }

  /** Keeps a typed delivery address as the customer's first saved one; best effort. */
  private async rememberAddress(address: NonNullable<PlaceOrder['address']>): Promise<void> {
    try {
      await this.book.save({
        label: 'HOME',
        lat: address.lat,
        lng: address.lng,
        addressLine: address.addressLine,
        instructions: address.instructions,
        preferred: true,
      });
    } catch {
      // the order went through; the address can be saved later from the account page
    }
  }

  protected async place(): Promise<void> {
    this.submitted.set(true);
    this.error.set(null);
    const quote = this.quote();
    const method = this.paymentMethod();
    const location = this.locations.location();
    const delivery = this.fulfilment() === 'DELIVERY';
    if (!quote || !method || this.blocking().length || this.couponProblem()) return;
    if (!this.phoneValid() || (delivery && (!location || !this.addressLine.trim()))) return;

    const order: PlaceOrder = {
      fulfilment: this.fulfilment(),
      address:
        delivery && location
          ? {
              lat: location.lat,
              lng: location.lng,
              addressLine: this.addressLine.trim(),
              instructions: this.instructions.trim() || null,
            }
          : null,
      couponCode: quote.coupon?.applies ? (this.couponCode() ?? null) : null,
      tipMinor: delivery ? quote.tipMinor : 0,
      paymentMethod: method,
      expectedTotalMinor: quote.totalMinor,
      contactPhone: this.phone.trim(),
      language: this.locale.language(),
    };
    // the same key for the same order, so a double tap or a retry after a timeout places it once
    const body = JSON.stringify(order);
    if (!this.attempt || this.attempt.body !== body) {
      this.attempt = { key: crypto.randomUUID(), body };
    }
    try {
      localStorage.setItem(PHONE_KEY, order.contactPhone);
    } catch {
      // storage blocked
    }
    this.placing.set(true);
    try {
      const placed = await firstValueFrom(this.orders.place(order, this.attempt.key));
      if (delivery && order.address && !this.savedList().length && this.saveAddress) {
        await this.rememberAddress(order.address);
      }
      if (placed.paymentUrl) {
        location_assign(placed.paymentUrl);
        return;
      }
      void this.cart.load();
      await this.router.navigateByUrl(`/orders/${placed.orderId}`);
    } catch (error) {
      const code = error instanceof HttpErrorResponse ? error.error?.code : null;
      if (code === 'PRICE_CHANGED' || code === 'CHECKOUT_PROBLEMS') {
        this.error.set(code === 'PRICE_CHANGED' ? 'priceChanged' : null);
        await this.cart.load();
        await this.refresh();
      } else if (error instanceof HttpErrorResponse && error.status === 429) {
        this.error.set('tooManyOrders');
      } else {
        this.error.set('placeFailed');
      }
    } finally {
      this.placing.set(false);
    }
  }
}

/** Card payments go to the gateway's page; a separate function so tests can replace it. */
let location_assign = (url: string): void => {
  window.location.assign(url);
};

export function setPaymentRedirect(redirect: (url: string) => void): void {
  location_assign = redirect;
}

/** Tips are whole units of money, never odd fractions. */
function roundTip(minor: number, currency: string): number {
  const unit = ['JPY', 'KRW'].includes(currency) ? 1 : 100;
  return Math.round(minor / unit) * unit;
}
