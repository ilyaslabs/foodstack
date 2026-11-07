import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';

import { CardCheckout, OrderClient, PaymentClient } from '@foodstack/api-client';
import { Icon, LocaleStore } from '@foodstack/ui';

import { Ui, UiKey } from '../i18n';
import { cardBrand } from './card-brand';

/** The parts of Stripe.js this page uses (Embedded Checkout). */
interface StripeJs {
  initEmbeddedCheckout(options: { fetchClientSecret: () => Promise<string> }): Promise<{
    mount(element: HTMLElement): void;
    destroy(): void;
  }>;
}

declare global {
  interface Window {
    Stripe?: (publishableKey: string) => StripeJs;
  }
}

const STRIPE_JS = 'https://js.stripe.com/v3/';

/**
 * The app's own payment page for one order, when the operator picked embedded card fields (PRD
 * CO-5). With Stripe, the gateway's Embedded Checkout is mounted here, so card details go straight
 * to Stripe; with the sandbox, pretend fields stand in for it.
 */
@Component({
  selector: 'app-pay-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatRadioModule,
    Icon,
  ],
  template: `
    <div class="page">
      <section class="card">
        <div class="head">
          <div>
            <h1>{{ ui.t('payTitle') }}</h1>
            @if (orderNumber(); as number) {
              <p class="muted">{{ ui.t('payOrder', { number }) }}</p>
            }
          </div>
          @if (checkout(); as c) {
            <div class="amount">{{ locale.money(c.amountMinor, c.currency) }}</div>
          }
        </div>

        @if (loadFailed()) {
          <p class="error" role="alert">{{ ui.t('payLoadFailed') }}</p>
        } @else if (checkout(); as c) {
          @if (!c.clientSecret) {
            <p class="muted" role="status">{{ ui.t('payClosed') }}</p>
            <a mat-flat-button [href]="c.returnUrl">{{ ui.t('payViewOrder') }}</a>
          } @else if (c.gateway === 'stripe') {
            <div #stripeMount class="stripe"></div>
          } @else {
            <span class="test-badge">{{ ui.t('payTestMode') }}</span>
            <p class="muted">{{ ui.t('paySandboxHint') }}</p>
            <form class="fields" (ngSubmit)="paySandbox()">
              @if (c.savedCards.length) {
                <mat-radio-group
                  class="choices"
                  [(ngModel)]="choice"
                  name="choice"
                  [attr.aria-label]="ui.t('savedCards')"
                >
                  @for (card of c.savedCards; track card.id) {
                    <mat-radio-button [value]="card.id">
                      <span class="choice">
                        <fs-icon name="credit_card" />
                        {{ ui.t('cardEnding', { brand: brand(card.brand), last4: card.last4 }) }}
                      </span>
                    </mat-radio-button>
                  }
                  <mat-radio-button value="new">{{ ui.t('useNewCard') }}</mat-radio-button>
                </mat-radio-group>
              }
              @if (choice === 'new') {
                <mat-form-field appearance="outline">
                  <mat-label>{{ ui.t('cardNumber') }}</mat-label>
                  <input
                    matInput
                    name="number"
                    [(ngModel)]="number"
                    inputmode="numeric"
                    autocomplete="cc-number"
                    placeholder="4242 4242 4242 4242"
                    dir="ltr"
                    required
                  />
                  <fs-icon matSuffix name="credit_card" />
                </mat-form-field>
                <div class="pair">
                  <mat-form-field appearance="outline">
                    <mat-label>{{ ui.t('cardExpiry') }}</mat-label>
                    <input
                      matInput
                      name="expiry"
                      [(ngModel)]="expiry"
                      autocomplete="cc-exp"
                      placeholder="12/30"
                      dir="ltr"
                    />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>{{ ui.t('cardCvc') }}</mat-label>
                    <input
                      matInput
                      name="cvc"
                      [(ngModel)]="cvc"
                      inputmode="numeric"
                      autocomplete="cc-csc"
                      placeholder="123"
                      dir="ltr"
                    />
                  </mat-form-field>
                </div>
                @if (c.canSaveCard) {
                  <mat-checkbox name="save" [(ngModel)]="save">{{
                    ui.t('saveCardForNext')
                  }}</mat-checkbox>
                }
              }
              @if (message(); as key) {
                <p class="error" role="alert">{{ ui.t(key) }}</p>
              }
              <button mat-flat-button class="pay" type="submit" [disabled]="busy()">
                @if (busy()) {
                  <mat-spinner diameter="20" />
                } @else {
                  <fs-icon name="lock" />
                  {{ ui.t('payAmount', { amount: locale.money(c.amountMinor, c.currency) }) }}
                }
              </button>
              <a mat-button class="cancel" [href]="c.cancelUrl">{{ ui.t('payCancel') }}</a>
            </form>
          }
          @if (c.clientSecret) {
            <p class="secure muted"><fs-icon name="lock" /> {{ ui.t('paySecure') }}</p>
          }
        } @else {
          <div class="loading"><mat-spinner diameter="32" /></div>
        }
      </section>
    </div>
  `,
  styles: `
    .page {
      max-width: 520px;
      margin-inline: auto;
      padding: 32px 24px 64px;
    }
    @media (max-width: 599.98px) {
      .page {
        padding: 16px 16px 48px;
      }
    }
    .card {
      padding: 28px;
      border-radius: var(--fs-radius-card);
      background: var(--mat-sys-surface);
      border: 1px solid var(--mat-sys-outline-variant);
      box-shadow: var(--fs-shadow-card);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    @media (max-width: 599.98px) {
      .card {
        padding: 20px;
      }
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
    }
    h1 {
      font: var(--mat-sys-headline-small);
      margin: 0;
    }
    .head p {
      margin: 4px 0 0;
    }
    .amount {
      font: var(--mat-sys-headline-small);
      font-weight: 600;
      white-space: nowrap;
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
      margin: 0;
    }
    .error {
      color: var(--mat-sys-error);
      margin: 0;
    }
    .test-badge {
      align-self: flex-start;
      font: var(--mat-sys-label-medium);
      letter-spacing: 0.5px;
      padding: 4px 10px;
      border-radius: 8px;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .fields {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .choices {
      display: flex;
      flex-direction: column;
      margin-block-end: 12px;
    }
    .choice {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    mat-checkbox {
      margin-block-end: 8px;
    }
    .pay {
      height: 48px;
      margin-block-start: 8px;
    }
    .cancel {
      align-self: center;
    }
    .secure {
      display: flex;
      align-items: center;
      gap: 8px;
      font: var(--mat-sys-body-small);
    }
    .loading {
      display: grid;
      place-items: center;
      min-height: 160px;
    }
    .stripe {
      min-height: 360px;
    }
  `,
})
export class PayPage {
  readonly orderId = input.required<string>();

  protected readonly ui = inject(Ui);
  protected readonly locale = inject(LocaleStore);
  private readonly payments = inject(PaymentClient);
  private readonly orders = inject(OrderClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stripeMount = viewChild<ElementRef<HTMLElement>>('stripeMount');

  protected readonly checkout = signal<CardCheckout | null>(null);
  protected readonly orderNumber = signal<string | null>(null);
  protected readonly loadFailed = signal(false);
  protected readonly busy = signal(false);
  protected readonly message = signal<UiKey | null>(null);

  protected choice = 'new';
  protected number = '';
  protected expiry = '';
  protected cvc = '';
  protected save = false;

  constructor() {
    afterNextRender(async () => {
      this.orders.get(this.orderId()).subscribe({
        next: (order) => this.orderNumber.set(order.number),
        error: () => undefined,
      });
      try {
        const checkout = await firstValueFrom(this.payments.checkout(this.orderId()));
        if (checkout.savedCards.length) {
          this.choice = checkout.savedCards[0].id;
        }
        this.checkout.set(checkout);
        if (checkout.clientSecret && checkout.gateway === 'stripe') {
          await this.mountStripe(checkout);
        }
      } catch {
        this.loadFailed.set(true);
      }
    });
  }

  protected brand(code: string): string {
    return cardBrand(code);
  }

  protected async paySandbox(): Promise<void> {
    const checkout = this.checkout();
    if (!checkout?.clientSecret) {
      return;
    }
    const savedCardId = this.choice !== 'new' ? this.choice : undefined;
    if (!savedCardId && !/^\d{12,19}$/.test(this.number.replace(/[\s-]/g, ''))) {
      this.message.set('cardInvalid');
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    try {
      const result = await firstValueFrom(
        this.payments.sandboxPay(checkout.orderId, {
          clientSecret: checkout.clientSecret,
          cardNumber: savedCardId ? undefined : this.number,
          savedCardId,
          saveCard: !savedCardId && this.save,
        }),
      );
      window.location.assign(result.next);
    } catch (error) {
      const invalid = error instanceof HttpErrorResponse && error.error?.code === 'CARD_INVALID';
      this.message.set(invalid ? 'cardInvalid' : 'payFailed');
      this.busy.set(false);
    }
  }

  /** Loads Stripe.js and mounts its Embedded Checkout; Stripe sends the customer on when paid. */
  private async mountStripe(checkout: CardCheckout): Promise<void> {
    if (!checkout.publishableKey || !checkout.clientSecret) {
      throw new Error('No publishable key');
    }
    const stripe = (await loadStripe())(checkout.publishableKey);
    const secret = checkout.clientSecret;
    const embedded = await stripe.initEmbeddedCheckout({ fetchClientSecret: async () => secret });
    await new Promise((resolve) => setTimeout(resolve));
    const element = this.stripeMount()?.nativeElement;
    if (!element) {
      embedded.destroy();
      return;
    }
    embedded.mount(element);
    this.destroyRef.onDestroy(() => embedded.destroy());
  }
}

let stripeLoading: Promise<(key: string) => StripeJs> | null = null;

function loadStripe(): Promise<(key: string) => StripeJs> {
  stripeLoading ??= new Promise((resolve, reject) => {
    if (window.Stripe) {
      resolve(window.Stripe);
      return;
    }
    const script = document.createElement('script');
    script.src = STRIPE_JS;
    script.async = true;
    script.onload = () => (window.Stripe ? resolve(window.Stripe) : reject(new Error('Stripe.js')));
    script.onerror = () => {
      stripeLoading = null;
      reject(new Error('Stripe.js'));
    };
    document.head.appendChild(script);
  });
  return stripeLoading;
}
