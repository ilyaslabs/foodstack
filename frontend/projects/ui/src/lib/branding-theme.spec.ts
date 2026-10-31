import { brandColors, brandStylesheet } from './branding-theme';

describe('brandColors', () => {
  it('uses the operator primary colour family for the primary role', () => {
    // given / when
    const light = brandColors('#C62828', '#2E7D32', false);

    // then
    expect(light['--mat-sys-primary']).toMatch(/^#[0-9a-f]{6}$/);
    expect(light['--mat-sys-on-primary']).toBe('#ffffff');
    expect(Object.keys(light)).toContain('--mat-sys-surface-container-high');
  });

  it('gives dark mode a lighter primary than light mode', () => {
    // given
    const light = brandColors('#1565C0', '#00695C', false)['--mat-sys-primary'];
    const dark = brandColors('#1565C0', '#00695C', true)['--mat-sys-primary'];

    // then
    expect(luminance(dark)).toBeGreaterThan(luminance(light));
  });
});

describe('brandStylesheet', () => {
  it('writes light tokens, dark tokens and a sanitised font family', () => {
    // when
    const css = brandStylesheet('#C62828', '#2E7D32', "Inter'; } body { display:none");

    // then
    expect(css).toContain(':root {');
    expect(css).toContain('@media (prefers-color-scheme: dark)');
    expect(css).toContain("--fs-font-family: 'Inter  body  displaynone'");
    expect(css).not.toContain('display:none');
  });
});

function luminance(hex: string): number {
  const value = parseInt(hex.slice(1), 16);
  return ((value >> 16) & 255) * 0.299 + ((value >> 8) & 255) * 0.587 + (value & 255) * 0.114;
}
