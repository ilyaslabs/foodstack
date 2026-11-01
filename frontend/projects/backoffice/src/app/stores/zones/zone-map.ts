import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import type { GeoJSONSource, Map as MapLibreMap, MapMouseEvent, Marker } from 'maplibre-gl';

import { LatLng, RUNTIME_CONFIG } from '@foodstack/api-client';

/** Zone colours, picked to stay apart from each other on light and dark map tiles. */
export const ZONE_COLOURS = [
  '#1b6ef3',
  '#e8710a',
  '#12a150',
  '#a142f4',
  '#d93025',
  '#007b83',
  '#b06000',
  '#c5221f',
];

export interface ZoneShape {
  polygon: LatLng[];
}

/**
 * A map of the branch's delivery zones. The selected zone is edited in place: a click on the map adds
 * a corner after the nearest edge, a corner can be dragged, and a click on a corner removes it.
 * MapLibre loads only in the browser, when the Delivery tab opens.
 */
@Component({
  selector: 'app-zone-map',
  template: `
    <div #canvas class="map" role="application" aria-label="Delivery zones map"></div>
    @if (failed()) {
      <p class="failed">
        The map could not load. Check the connection, or set the zone with "Start from a circle".
      </p>
    }
  `,
  styles: `
    :host {
      display: block;
      position: relative;
    }
    .map {
      block-size: 440px;
      border-radius: 16px;
      overflow: hidden;
      border: 1px solid var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface-container);
    }
    @media (max-width: 599.98px) {
      .map {
        block-size: 340px;
      }
    }
    .failed {
      position: absolute;
      inset: 16px;
      margin: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    :host ::ng-deep .zone-corner {
      inline-size: 14px;
      block-size: 14px;
      border-radius: 50%;
      background: #fff;
      border: 3px solid var(--corner-colour);
      box-shadow: 0 1px 3px rgb(0 0 0 / 40%);
      cursor: grab;
    }
    :host ::ng-deep .branch-pin {
      inline-size: 18px;
      block-size: 18px;
      border-radius: 50%;
      background: var(--mat-sys-primary);
      border: 3px solid #fff;
      box-shadow: 0 1px 4px rgb(0 0 0 / 50%);
    }
  `,
})
export class ZoneMap {
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly canvas = viewChild.required<ElementRef<HTMLElement>>('canvas');

  /** The branch, shown as a pin and used to centre the map. */
  readonly branch = input<LatLng | null>(null);
  readonly zones = input<ZoneShape[]>([]);
  /** Index of the zone being edited, or -1. */
  readonly selected = input(-1);
  /** The selected zone's new outline after a click or a drag. */
  readonly changed = output<LatLng[]>();

  protected readonly failed = signal(false);

  private map: MapLibreMap | null = null;
  private library: typeof import('maplibre-gl') | null = null;
  private corners: Marker[] = [];
  private branchPin: Marker | null = null;
  private fitted = false;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => void this.start());
    destroyRef.onDestroy(() => this.map?.remove());
    effect(() => {
      const zones = this.zones();
      const selected = this.selected();
      const branch = this.branch();
      if (this.map?.loaded() || this.map?.isStyleLoaded()) this.draw(zones, selected, branch);
    });
  }

  private async start(): Promise<void> {
    try {
      const library = await import('maplibre-gl');
      library.setWorkerUrl(new URL('maplibre/maplibre-gl-worker.mjs', document.baseURI).href);
      this.library = library;
      const branch = this.branch();
      const map = new library.Map({
        container: this.canvas().nativeElement,
        style: {
          version: 8,
          sources: {
            tiles: {
              type: 'raster',
              tiles: [this.config.mapTiles!],
              tileSize: 256,
              maxzoom: 19,
              attribution: this.config.mapAttribution,
            },
          },
          layers: [{ id: 'tiles', type: 'raster', source: 'tiles' }],
        },
        center: branch ? [branch.lng, branch.lat] : [0, 20],
        zoom: branch ? 12 : 1,
        attributionControl: { compact: true },
      });
      map.addControl(new library.NavigationControl({ showCompass: false }), 'top-right');
      map.on('load', () => {
        map.addSource('zones', {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        });
        map.addLayer({
          id: 'zone-fill',
          type: 'fill',
          source: 'zones',
          paint: {
            'fill-color': ['get', 'colour'],
            'fill-opacity': ['case', ['get', 'selected'], 0.28, 0.12],
          },
        });
        map.addLayer({
          id: 'zone-line',
          type: 'line',
          source: 'zones',
          paint: {
            'line-color': ['get', 'colour'],
            'line-width': ['case', ['get', 'selected'], 3, 1.5],
            'line-dasharray': [
              'case',
              ['get', 'selected'],
              ['literal', [1, 0]],
              ['literal', [2, 2]],
            ],
          },
        });
        this.draw(this.zones(), this.selected(), this.branch());
      });
      map.on('click', (event: MapMouseEvent) => this.addCorner(event.lngLat.lat, event.lngLat.lng));
      map.on('error', (event) => {
        // Tile errors are common (offline, blocked provider); only a missing map is a failure.
        if (!map.isStyleLoaded() && String(event.error?.message ?? '').includes('WebGL'))
          this.failed.set(true);
      });
      this.map = map;
    } catch {
      this.failed.set(true);
    }
  }

  private draw(zones: ZoneShape[], selected: number, branch: LatLng | null): void {
    const map = this.map;
    const library = this.library;
    if (!map || !library) return;
    const source = map.getSource('zones') as GeoJSONSource | undefined;
    source?.setData({
      type: 'FeatureCollection',
      features: zones
        .map((zone, index) => ({ zone, index }))
        .filter(({ zone }) => zone.polygon.length >= 3)
        .map(({ zone, index }) => ({
          type: 'Feature' as const,
          properties: {
            colour: ZONE_COLOURS[index % ZONE_COLOURS.length],
            selected: index === selected,
          },
          geometry: {
            type: 'Polygon' as const,
            coordinates: [[...zone.polygon, zone.polygon[0]].map((p) => [p.lng, p.lat])],
          },
        })),
    });

    this.branchPin?.remove();
    this.branchPin = null;
    if (branch) {
      const pin = document.createElement('div');
      pin.className = 'branch-pin';
      pin.title = 'Branch';
      this.branchPin = new library.Marker({ element: pin })
        .setLngLat([branch.lng, branch.lat])
        .addTo(map);
    }

    this.corners.forEach((corner) => corner.remove());
    this.corners = [];
    const editing = zones[selected];
    if (editing) {
      const colour = ZONE_COLOURS[selected % ZONE_COLOURS.length];
      editing.polygon.forEach((point, index) => {
        const element = document.createElement('div');
        element.className = 'zone-corner';
        element.style.setProperty('--corner-colour', colour);
        element.title = 'Drag to move, click to remove';
        let dragged = false;
        element.addEventListener('click', (event) => {
          event.stopPropagation();
          // the browser also reports a click when a drag ends on the corner
          if (dragged) {
            dragged = false;
            return;
          }
          this.changed.emit(editing.polygon.filter((_, i) => i !== index));
        });
        const corner = new library.Marker({ element, draggable: true })
          .setLngLat([point.lng, point.lat])
          .addTo(map);
        corner.on('dragstart', () => (dragged = true));
        corner.on('dragend', () => {
          const moved = corner.getLngLat();
          this.changed.emit(
            editing.polygon.map((p, i) => (i === index ? round(moved.lat, moved.lng) : p)),
          );
        });
        this.corners.push(corner);
      });
    }

    if (!this.fitted) {
      const all = [...zones.flatMap((zone) => zone.polygon), ...(branch ? [branch] : [])];
      if (all.length > 1) {
        const bounds = new library.LngLatBounds();
        all.forEach((p) => bounds.extend([p.lng, p.lat]));
        map.fitBounds(bounds, { padding: 40, maxZoom: 15, duration: 0 });
        this.fitted = true;
      }
    }
  }

  /** Puts the new corner into the edge it is nearest to, so clicks reshape the outline naturally. */
  private addCorner(lat: number, lng: number): void {
    const zone = this.zones()[this.selected()];
    if (!zone) return;
    const point = round(lat, lng);
    const polygon = zone.polygon;
    if (polygon.length < 3) {
      this.changed.emit([...polygon, point]);
      return;
    }
    let best = 0;
    let bestDistance = Infinity;
    for (let i = 0; i < polygon.length; i++) {
      const distance = segmentDistance(point, polygon[i], polygon[(i + 1) % polygon.length]);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    this.changed.emit([...polygon.slice(0, best + 1), point, ...polygon.slice(best + 1)]);
  }
}

function round(lat: number, lng: number): LatLng {
  return { lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6 };
}

function segmentDistance(p: LatLng, a: LatLng, b: LatLng): number {
  const dx = b.lng - a.lng;
  const dy = b.lat - a.lat;
  const length = dx * dx + dy * dy;
  const t =
    length === 0
      ? 0
      : Math.max(0, Math.min(1, ((p.lng - a.lng) * dx + (p.lat - a.lat) * dy) / length));
  const x = a.lng + t * dx - p.lng;
  const y = a.lat + t * dy - p.lat;
  return x * x + y * y;
}
