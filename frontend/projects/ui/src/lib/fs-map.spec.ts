import { MAX_MAP_CELLS, mapCells } from './fs-map';

describe('mapCells', () => {
  it('counts the live-map cells a view touches, like the server', () => {
    // 0.25° cells: one city view sits in one or a few cells
    expect(mapCells({ south: 31.5, west: 74.3, north: 31.6, east: 74.4 })).toBe(1);
    expect(mapCells({ south: 31.45, west: 74.2, north: 31.6, east: 74.4 })).toBe(4);
  });

  it('flags a view wider than the server follows', () => {
    expect(mapCells({ south: 24, west: 61, north: 37, east: 77 })).toBeGreaterThan(MAX_MAP_CELLS);
  });
});
