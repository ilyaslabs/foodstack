import { randomUrlSafe, s256Challenge } from './pkce';

describe('PKCE', () => {
  it('derives the RFC 7636 S256 challenge', async () => {
    // RFC 7636 appendix B
    expect(await s256Challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('makes URL-safe random values of the requested strength', () => {
    const value = randomUrlSafe(48);
    expect(value).toMatch(/^[A-Za-z0-9_-]{64}$/);
    expect(randomUrlSafe(48)).not.toBe(value);
  });
});
