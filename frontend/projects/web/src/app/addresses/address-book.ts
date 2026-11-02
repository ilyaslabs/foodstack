import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AddressInput, MeClient, SavedAddress } from '@foodstack/api-client';
import { AuthService } from '@foodstack/auth';

import { IconName } from '@foodstack/ui';

import { Ui } from '../i18n';
import { CustomerLocation } from '../location-store';

/** The signed-in customer's saved addresses (PRD ID-6), loaded once and kept in step with edits. */
@Injectable({ providedIn: 'root' })
export class AddressBook {
  private readonly me = inject(MeClient);
  private readonly auth = inject(AuthService);
  private readonly state = signal<SavedAddress[] | null>(null);
  private loading: Promise<SavedAddress[]> | null = null;

  /** `null` until loaded (or when signed out). */
  readonly addresses = this.state.asReadonly();
  readonly preferred = computed(() => this.state()?.find((a) => a.preferred) ?? null);

  /** Loads the list once per page view; signed-out visitors have none. */
  load(force = false): Promise<SavedAddress[]> {
    if (!this.auth.signedIn()) {
      this.state.set(null);
      return Promise.resolve([]);
    }
    if (this.loading && !force) return this.loading;
    this.loading = firstValueFrom(this.me.addresses())
      .then((list) => {
        this.state.set(list);
        return list;
      })
      .catch(() => {
        this.loading = null;
        return this.state() ?? [];
      });
    return this.loading;
  }

  async save(address: AddressInput, id?: string): Promise<SavedAddress> {
    const saved = await firstValueFrom(
      id ? this.me.updateAddress(id, address) : this.me.addAddress(address),
    );
    await this.load(true);
    return saved;
  }

  async remove(id: string): Promise<void> {
    await firstValueFrom(this.me.removeAddress(id));
    await this.load(true);
  }

  /** Whether the customer already has a Home or a Work address (other than `exceptId`). */
  taken(label: 'HOME' | 'WORK', exceptId?: string): boolean {
    return (this.state() ?? []).some((a) => a.label === label && a.id !== exceptId);
  }
}

/** The location the apps use for a saved address. */
export function locationOf(address: SavedAddress, title: string): CustomerLocation {
  return {
    lat: address.lat,
    lng: address.lng,
    label: title,
    addressId: address.id,
    addressLine: address.addressLine,
    instructions: address.instructions ?? '',
  };
}

/** How a saved address is named on screen: Home, Work, or the customer's own name. */
export function addressTitle(ui: Ui, address: SavedAddress): string {
  if (address.label === 'HOME') return ui.t('labelHome');
  if (address.label === 'WORK') return ui.t('labelWork');
  return address.name || ui.t('labelOther');
}

/** The icon of a saved address. */
export function addressIcon(address: SavedAddress): IconName {
  return address.label === 'HOME' ? 'home' : address.label === 'WORK' ? 'work' : 'place';
}
