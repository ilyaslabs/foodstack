import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AddCartLine, Cart, CartClient } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';

/** Thrown when the cart holds dishes from another restaurant; the screen asks before replacing them. */
export class OtherStoreInCart extends Error {
  constructor(readonly storeName: string) {
    super('The cart has dishes from another restaurant');
  }
}

/**
 * The signed-in customer's cart, shared by the store page, the cart panel and checkout. The server
 * keeps it (so it follows the customer between devices) and prices every line on each answer.
 */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly client = inject(CartClient);
  private readonly auth = inject(AuthService);

  readonly cart = signal<Cart | null>(null);
  readonly busy = signal(false);

  readonly itemCount = computed(() => this.cart()?.itemCount ?? 0);
  readonly empty = computed(() => (this.cart()?.lines.length ?? 0) === 0);

  constructor() {
    effect(() => {
      // reload when the customer signs in or out
      const signedIn = this.auth.signedIn();
      untracked(() => {
        if (signedIn) void this.load();
        else this.cart.set(null);
      });
    });
  }

  async load(): Promise<void> {
    if (typeof window === 'undefined' || !this.auth.hasSession()) return;
    try {
      this.cart.set(await firstValueFrom(this.client.get()));
    } catch {
      // the panel shows the last known cart
    }
  }

  async add(line: AddCartLine): Promise<void> {
    await this.run(async () => {
      try {
        this.cart.set(await firstValueFrom(this.client.add(line)));
      } catch (error) {
        if (error instanceof HttpErrorResponse && error.error?.code === 'CART_OTHER_STORE') {
          throw new OtherStoreInCart(this.cart()?.store?.brandName ?? '');
        }
        throw error;
      }
    });
  }

  async setQuantity(lineId: string, quantity: number): Promise<void> {
    await this.run(async () => {
      const update =
        quantity > 0 ? this.client.setQuantity(lineId, quantity) : this.client.remove(lineId);
      this.cart.set(await firstValueFrom(update));
    });
  }

  async clear(): Promise<void> {
    await this.run(async () => {
      await firstValueFrom(this.client.clear());
      await this.load();
    });
  }

  private async run(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    try {
      await work();
    } finally {
      this.busy.set(false);
    }
  }
}
