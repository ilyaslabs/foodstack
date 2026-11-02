import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

export interface CustomerLocation {
  lat: number;
  lng: number;
  /** What the customer picked, such as "Gulberg III, Lahore"; empty for a device location. */
  label: string;
  /** The saved address this came from (PRD ID-6), if any. */
  addressId?: string;
  /** Street and house of a saved address, for checkout. */
  addressLine?: string;
  /** Rider notes of a saved address, for checkout. */
  instructions?: string;
}

const STORAGE_KEY = 'fs-location';

/**
 * Where the customer wants food, kept in this browser only. Nothing location-based is server
 * rendered, so the server never needs it.
 */
@Injectable({ providedIn: 'root' })
export class LocationStore {
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<CustomerLocation | null>(null);
  private restored = false;

  readonly location = this.state.asReadonly();

  /** Reads the saved location; call from browser-only code (afterNextRender). */
  restore(): void {
    if (this.restored) return;
    this.restored = true;
    try {
      const raw = this.document.defaultView?.localStorage?.getItem(STORAGE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw) as CustomerLocation;
      if (Number.isFinite(saved.lat) && Number.isFinite(saved.lng)) {
        this.state.set({
          lat: saved.lat,
          lng: saved.lng,
          label: String(saved.label ?? ''),
          ...(saved.addressId
            ? {
                addressId: String(saved.addressId),
                addressLine: String(saved.addressLine ?? ''),
                instructions: String(saved.instructions ?? ''),
              }
            : {}),
        });
      }
    } catch {
      // storage blocked or bad data: ask again
    }
  }

  set(location: CustomerLocation): void {
    this.state.set(location);
    try {
      this.document.defaultView?.localStorage?.setItem(STORAGE_KEY, JSON.stringify(location));
    } catch {
      // storage blocked: the location lasts for this page only
    }
  }

  /** Whether a location was restored or picked in this browser. */
  has(): boolean {
    return this.state() !== null;
  }

  clear(): void {
    this.state.set(null);
    try {
      this.document.defaultView?.localStorage?.removeItem(STORAGE_KEY);
    } catch {
      // nothing saved
    }
  }

  /** Asks the browser for the device position. */
  locate(): Promise<{ lat: number; lng: number }> {
    const geolocation = this.document.defaultView?.navigator?.geolocation;
    if (!geolocation) return Promise.reject(new Error('unavailable'));
    return new Promise((resolve, reject) =>
      geolocation.getCurrentPosition(
        (position) => resolve({ lat: position.coords.latitude, lng: position.coords.longitude }),
        reject,
        { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
      ),
    );
  }
}
