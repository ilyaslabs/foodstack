import { LatLng } from '@foodstack/api-client';

/** A circle of `radiusKm` around `center` as an outline, a starting shape the owner then reshapes. */
export function circle(center: LatLng, radiusKm: number, points = 16): LatLng[] {
  const metres = radiusKm * 1000;
  const dLat = metres / 111_320;
  const dLng = metres / (111_320 * Math.cos((center.lat * Math.PI) / 180));
  return Array.from({ length: points }, (_, i) => {
    const angle = (2 * Math.PI * i) / points;
    return {
      lat: round(center.lat + dLat * Math.sin(angle)),
      lng: round(center.lng + dLng * Math.cos(angle)),
    };
  });
}

/** True when two edges of the outline cross, which no map database accepts. */
export function selfIntersects(points: LatLng[]): boolean {
  const n = points.length;
  for (let i = 0; i < n; i++) {
    const a1 = points[i];
    const a2 = points[(i + 1) % n];
    for (let j = i + 1; j < n; j++) {
      // neighbouring edges share a corner
      if (j === i || (j + 1) % n === i || (i + 1) % n === j) continue;
      if (crosses(a1, a2, points[j], points[(j + 1) % n])) return true;
    }
  }
  return false;
}

/** Whether the point lies inside the outline (ray casting; fine for city-sized areas). */
export function contains(points: LatLng[], point: LatLng): boolean {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const a = points[i];
    const b = points[j];
    if (
      a.lat > point.lat !== b.lat > point.lat &&
      point.lng < ((b.lng - a.lng) * (point.lat - a.lat)) / (b.lat - a.lat) + a.lng
    ) {
      inside = !inside;
    }
  }
  return inside;
}

/** Rough area in km², shown so an owner notices a zone drawn far too big or small. */
export function areaKm2(points: LatLng[]): number {
  if (points.length < 3) return 0;
  const lat0 = (points.reduce((sum, p) => sum + p.lat, 0) / points.length) * (Math.PI / 180);
  const xy = points.map((p) => [p.lng * 111.32 * Math.cos(lat0), p.lat * 110.574]);
  let twice = 0;
  for (let i = 0; i < xy.length; i++) {
    const [x1, y1] = xy[i];
    const [x2, y2] = xy[(i + 1) % xy.length];
    twice += x1 * y2 - x2 * y1;
  }
  return Math.abs(twice) / 2;
}

function crosses(p1: LatLng, p2: LatLng, p3: LatLng, p4: LatLng): boolean {
  const d1 = side(p3, p4, p1);
  const d2 = side(p3, p4, p2);
  const d3 = side(p1, p2, p3);
  const d4 = side(p1, p2, p4);
  if (d1 * d2 < 0 && d3 * d4 < 0) return true;
  // a corner lying on another edge also counts, as the server refuses it too
  return (
    (d1 === 0 && within(p3, p4, p1)) ||
    (d2 === 0 && within(p3, p4, p2)) ||
    (d3 === 0 && within(p1, p2, p3)) ||
    (d4 === 0 && within(p1, p2, p4))
  );
}

function within(a: LatLng, b: LatLng, c: LatLng): boolean {
  return (
    Math.min(a.lng, b.lng) <= c.lng &&
    c.lng <= Math.max(a.lng, b.lng) &&
    Math.min(a.lat, b.lat) <= c.lat &&
    c.lat <= Math.max(a.lat, b.lat)
  );
}

function side(a: LatLng, b: LatLng, c: LatLng): number {
  return (b.lng - a.lng) * (c.lat - a.lat) - (b.lat - a.lat) * (c.lng - a.lng);
}

function round(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}
