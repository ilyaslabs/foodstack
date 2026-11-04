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
  untracked,
  viewChild,
} from '@angular/core';
import type { GeoJSONSource, Map as MapLibreMap, Marker } from 'maplibre-gl';

import { LatLng, RUNTIME_CONFIG } from '@foodstack/api-client';

/** What a marker stands for; each kind has its own look. */
export type MapMarkerKind = 'store' | 'home' | 'rider' | 'rider-busy' | 'rider-offline' | 'order';

export interface MapMarker {
  id: string;
  lat: number;
  lng: number;
  kind: MapMarkerKind;
  /** Read out by screen readers and shown as a tooltip. */
  label?: string;
}

const MARKER_ICONS: Record<MapMarkerKind, string> = {
  store: 'M20 4H4v2h16V4zm1 10v-2l-1-5H4l-1 5v2h1v6h10v-6h4v6h2v-6h1zm-9 4H6v-4h6v4z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  rider:
    'M19 7c0-1.1-.9-2-2-2h-3v2h3v2.65L13.52 14H10V9H6c-2.21 0-4 1.79-4 4v3h2c0 1.66 1.34 3 3 3s3-1.34 3-3h4.48L19 10.35V7zM7 17c-.55 0-1-.45-1-1h2c0 .55-.45 1-1 1zM5 6h5v2H5zm14 7c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3zm0 4c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z',
  'rider-busy': '',
  'rider-offline': '',
  order:
    'M18 6h-2c0-2.21-1.79-4-4-4S8 3.79 8 6H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6-2c1.1 0 2 .9 2 2h-4c0-1.1.9-2 2-2z',
};
MARKER_ICONS['rider-busy'] = MARKER_ICONS.rider;
MARKER_ICONS['rider-offline'] = MARKER_ICONS.rider;

/**
 * A MapLibre map shared by both apps (TDD 14.1 maps): markers for stores, homes, riders and
 * orders, an optional line between points, and an optional fixed centre pin that the person moves
 * by dragging the map (address pins). MapLibre loads only in the browser, on first render; tiles
 * come from the runtime config (MAP_TILE_URL).
 */
@Component({
  selector: 'fs-map',
  template: `
    <div #canvas class="map" role="application" [attr.aria-label]="label()"></div>
    @if (centerPin()) {
      <div class="center-pin" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path
            d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5z"
          />
        </svg>
      </div>
    }
    @if (failed()) {
      <p class="failed">{{ failedText() }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
      position: relative;
      block-size: 320px;
    }
    .map {
      position: absolute;
      inset: 0;
      border-radius: inherit;
      overflow: hidden;
      background: var(--mat-sys-surface-container);
    }
    .center-pin {
      position: absolute;
      inset-inline-start: 50%;
      inset-block-start: 50%;
      inline-size: 44px;
      block-size: 44px;
      transform: translate(-50%, -100%);
      pointer-events: none;
      filter: drop-shadow(0 2px 3px rgb(0 0 0 / 45%));
    }
    :host-context([dir='rtl']) .center-pin {
      transform: translate(50%, -100%);
    }
    .center-pin svg {
      inline-size: 100%;
      block-size: 100%;
      fill: var(--mat-sys-primary);
      stroke: #fff;
      stroke-width: 1.2;
    }
    .failed {
      position: absolute;
      inset: 16px;
      margin: 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    :host ::ng-deep .fs-marker {
      display: grid;
      place-items: center;
      inline-size: 34px;
      block-size: 34px;
      border-radius: 50%;
      border: 3px solid #fff;
      box-shadow: 0 2px 6px rgb(0 0 0 / 40%);
      color: #fff;
      cursor: default;
    }
    :host ::ng-deep .fs-marker svg {
      inline-size: 18px;
      block-size: 18px;
      fill: currentColor;
    }
    :host ::ng-deep .fs-marker.store {
      background: #e8710a;
    }
    :host ::ng-deep .fs-marker.home {
      background: var(--mat-sys-primary);
    }
    :host ::ng-deep .fs-marker.order {
      background: #7c4dff;
      inline-size: 28px;
      block-size: 28px;
    }
    :host ::ng-deep .fs-marker.rider {
      background: #12a150;
    }
    :host ::ng-deep .fs-marker.rider-busy {
      background: #1b6ef3;
    }
    :host ::ng-deep .fs-marker.rider-offline {
      background: #80868b;
    }
    :host ::ng-deep .fs-marker.clickable {
      cursor: pointer;
    }
  `,
})
export class FsMap {
  private readonly config = inject(RUNTIME_CONFIG);
  private readonly canvas = viewChild.required<ElementRef<HTMLElement>>('canvas');

  /** Where the map starts; with `centerPin`, also where the pin points. */
  readonly center = input<LatLng | null>(null);
  readonly zoom = input(14);
  readonly markers = input<MapMarker[]>([]);
  /** Points joined by a line, such as rider → store → customer. */
  readonly route = input<LatLng[]>([]);
  /** Keep every marker in view when they change. */
  readonly fit = input(false);
  /** A pin fixed in the middle; dragging the map moves the point under it. */
  readonly centerPin = input(false);
  readonly label = input('Map');
  readonly failedText = input('The map could not load.');

  /** With `centerPin`: the point under the pin after the person moved the map. */
  readonly moved = output<LatLng>();
  /** The visible area, once the map is ready and after every move or zoom. */
  readonly viewChanged = output<MapView>();
  readonly markerClick = output<string>();

  protected readonly failed = signal(false);

  private map: MapLibreMap | null = null;
  private library: typeof import('maplibre-gl') | null = null;
  private readonly placed = new Map<string, { marker: Marker; kind: MapMarkerKind }>();
  private ready = false;
  private fittedOnce = false;
  private movingProgrammatically = false;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => void this.start());
    destroyRef.onDestroy(() => this.map?.remove());
    effect(() => {
      const markers = this.markers();
      const route = this.route();
      if (this.ready) untracked(() => this.draw(markers, route));
    });
    effect(() => {
      const center = this.center();
      if (!this.ready || !center || !this.map) return;
      untracked(() => {
        const current = this.map!.getCenter();
        if (Math.abs(current.lat - center.lat) < 1e-7 && Math.abs(current.lng - center.lng) < 1e-7)
          return;
        this.movingProgrammatically = true;
        this.map!.jumpTo({ center: [center.lng, center.lat] });
        this.movingProgrammatically = false;
      });
    });
  }

  /** Re-fits the view to every marker, for example after the container changed size. */
  refit(): void {
    this.map?.resize();
    this.fitAll(true);
  }

  private async start(): Promise<void> {
    try {
      loadStyles();
      const library = await import('maplibre-gl');
      library.setWorkerUrl(new URL('maplibre/maplibre-gl-worker.mjs', document.baseURI).href);
      this.library = library;
      const center = this.center();
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
        center: center ? [center.lng, center.lat] : [0, 20],
        zoom: center ? this.zoom() : 1,
        attributionControl: { compact: true },
        dragRotate: false,
        pitchWithRotate: false,
      });
      map.touchZoomRotate.disableRotation();
      map.addControl(new library.NavigationControl({ showCompass: false }), 'top-right');
      map.on('moveend', () => {
        this.emitView(map);
        if (!this.centerPin() || this.movingProgrammatically) return;
        const point = map.getCenter();
        this.moved.emit({ lat: round(point.lat), lng: round(point.lng) });
      });
      map.on('load', () => {
        map.addSource('route', {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        });
        map.addLayer({
          id: 'route',
          type: 'line',
          source: 'route',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': '#1b6ef3', 'line-width': 4, 'line-dasharray': [1, 1.6] },
        });
        this.ready = true;
        this.draw(this.markers(), this.route());
        this.emitView(map);
      });
      this.map = map;
    } catch {
      this.failed.set(true);
    }
  }

  private emitView(map: MapLibreMap): void {
    const bounds = map.getBounds();
    this.viewChanged.emit({
      south: round(bounds.getSouth()),
      west: round(bounds.getWest()),
      north: round(bounds.getNorth()),
      east: round(bounds.getEast()),
    });
  }

  private draw(markers: MapMarker[], route: LatLng[]): void {
    const library = this.library;
    const map = this.map;
    if (!library || !map) return;
    const seen = new Set<string>();
    for (const item of markers) {
      seen.add(item.id);
      const existing = this.placed.get(item.id);
      if (existing && existing.kind === item.kind) {
        existing.marker.setLngLat([item.lng, item.lat]);
        continue;
      }
      existing?.marker.remove();
      const element = document.createElement('div');
      element.className = `fs-marker ${item.kind}`;
      element.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${MARKER_ICONS[item.kind]}"/></svg>`;
      if (item.label) {
        element.title = item.label;
        element.setAttribute('aria-label', item.label);
      }
      element.addEventListener('click', () => this.markerClick.emit(item.id));
      element.classList.add('clickable');
      const marker = new library.Marker({ element }).setLngLat([item.lng, item.lat]).addTo(map);
      this.placed.set(item.id, { marker, kind: item.kind });
    }
    for (const [id, entry] of this.placed) {
      if (!seen.has(id)) {
        entry.marker.remove();
        this.placed.delete(id);
      }
    }
    const source = map.getSource('route') as GeoJSONSource | undefined;
    source?.setData({
      type: 'FeatureCollection',
      features:
        route.length > 1
          ? [
              {
                type: 'Feature',
                properties: {},
                geometry: { type: 'LineString', coordinates: route.map((p) => [p.lng, p.lat]) },
              },
            ]
          : [],
    });
    if (this.fit()) this.fitAll(!this.fittedOnce);
  }

  private fitAll(force: boolean): void {
    const library = this.library;
    const map = this.map;
    const markers = this.markers();
    if (!library || !map || markers.length === 0) return;
    const bounds = new library.LngLatBounds();
    markers.forEach((m) => bounds.extend([m.lng, m.lat]));
    if (!force) {
      const view = map.getBounds();
      if (markers.every((m) => view.contains([m.lng, m.lat]))) return;
    }
    this.fittedOnce = true;
    map.fitBounds(bounds, { padding: 56, maxZoom: 16, duration: force ? 0 : 600 });
  }
}

/** The visible area of a map, in degrees. */
export interface MapView {
  south: number;
  west: number;
  north: number;
  east: number;
}

/** Size of the server's live-map cells, in degrees (GeoCells.SIZE_DEGREES). */
export const MAP_CELL_DEGREES = 0.25;
/** Most cells one live map may follow (GeoCells.MAX_CELLS). */
export const MAX_MAP_CELLS = 64;

/** How many live-map cells a view touches; above {@link MAX_MAP_CELLS} the server refuses it. */
export function mapCells(view: MapView): number {
  const row = (lat: number) =>
    Math.floor((Math.max(-90, Math.min(90, lat)) + 90) / MAP_CELL_DEGREES);
  const column = (lng: number) =>
    Math.floor((Math.max(-180, Math.min(180, lng)) + 180) / MAP_CELL_DEGREES);
  const rows = row(Math.max(view.south, view.north)) - row(Math.min(view.south, view.north)) + 1;
  const columns =
    column(Math.max(view.west, view.east)) - column(Math.min(view.west, view.east)) + 1;
  return rows * columns;
}

/** MapLibre's own styles, added once when the first map opens, so pages without a map stay light. */
function loadStyles(): void {
  if (document.querySelector('link[data-maplibre]')) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = new URL('maplibre/maplibre-gl.css', document.baseURI).href;
  link.dataset['maplibre'] = '';
  document.head.appendChild(link);
}

function round(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}
