/** Cuisines are stored as lower-case tags such as "bbq" or "pakistani"; this makes them readable. */
const SPECIAL: Record<string, string> = { bbq: 'BBQ' };

export function cuisineLabel(cuisine: string): string {
  const key = cuisine.trim().toLowerCase();
  return SPECIAL[key] ?? key.replace(/(^|[\s-])\p{L}/gu, (match) => match.toUpperCase());
}
