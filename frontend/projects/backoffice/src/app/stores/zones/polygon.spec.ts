import { areaKm2, circle, contains, selfIntersects } from './polygon';

describe('zone outlines', () => {
  const lahore = { lat: 31.512, lng: 74.344 };

  it('starts a circle with the asked radius around the branch', () => {
    // when
    const outline = circle(lahore, 3);

    // then
    expect(outline).toHaveLength(16);
    expect(contains(outline, lahore)).toBe(true);
    // a 3 km circle covers about 28 km²; 16 corners cut a little off
    expect(areaKm2(outline)).toBeGreaterThan(26.5);
    expect(areaKm2(outline)).toBeLessThan(28.5);
  });

  it('finds edges that cross, or a corner sitting on another edge', () => {
    const bowTie = [
      { lat: 0, lng: 0 },
      { lat: 1, lng: 1 },
      { lat: 1, lng: 0 },
      { lat: 0, lng: 1 },
    ];
    const lShape = [
      { lat: 0, lng: 0 },
      { lat: 0, lng: 2 },
      { lat: 1, lng: 2 },
      { lat: 1, lng: 1 },
      { lat: 2, lng: 1 },
      { lat: 2, lng: 0 },
    ];
    const touching = [
      { lat: 0, lng: 0 },
      { lat: 0, lng: 2 },
      { lat: 2, lng: 2 },
      { lat: 0, lng: 1 },
      { lat: 2, lng: 0 },
    ];

    expect(selfIntersects(bowTie)).toBe(true);
    expect(selfIntersects(lShape)).toBe(false);
    expect(selfIntersects(touching)).toBe(true);
    expect(selfIntersects(circle(lahore, 5))).toBe(false);
  });

  it('tells inside from outside of a concave outline', () => {
    const lShape = [
      { lat: 0, lng: 0 },
      { lat: 0, lng: 2 },
      { lat: 1, lng: 2 },
      { lat: 1, lng: 1 },
      { lat: 2, lng: 1 },
      { lat: 2, lng: 0 },
    ];

    expect(contains(lShape, { lat: 0.5, lng: 1.5 })).toBe(true);
    expect(contains(lShape, { lat: 1.5, lng: 1.5 })).toBe(false);
  });
});
