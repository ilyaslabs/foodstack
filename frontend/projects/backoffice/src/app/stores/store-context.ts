import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { StoreClient, StoreDetails, Versioned } from '@foodstack/api-client';

/** The branch open on the store page, shared by its tabs, with the ETag for the next edit. */
@Injectable()
export class StoreContext {
  private readonly client = inject(StoreClient);

  private readonly state = signal<Versioned<StoreDetails> | null>(null);
  readonly readiness = signal<string[]>([]);
  readonly loadError = signal(false);

  readonly store = computed(() => this.state()?.value ?? null);
  readonly etag = computed(() => this.state()?.etag ?? '');

  async load(storeId: string): Promise<void> {
    try {
      this.state.set(await firstValueFrom(this.client.details(storeId)));
      this.loadError.set(false);
      await this.refreshReadiness();
    } catch {
      this.loadError.set(true);
    }
  }

  set(details: Versioned<StoreDetails>): void {
    this.state.set(details);
    void this.refreshReadiness();
  }

  async refreshReadiness(): Promise<void> {
    const store = this.store();
    if (!store) return;
    try {
      this.readiness.set(await firstValueFrom(this.client.readiness(store.id)));
    } catch {
      this.readiness.set([]);
    }
  }
}
