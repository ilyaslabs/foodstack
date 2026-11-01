import { currencyDigits } from '@foodstack/ui';

/** Minor units to a plain number string for an input, such as 85000 → "850.00" in PKR. */
export function toMajor(minor: number | null | undefined, currency: string): string {
  if (minor === null || minor === undefined) return '';
  const digits = currencyDigits(currency);
  return (minor / 10 ** digits).toFixed(digits);
}

/** A typed amount to minor units; null when it is not a non-negative number. */
export function toMinor(value: string, currency: string): number | null {
  const trimmed = (value ?? '').trim().replace(',', '.');
  if (trimmed === '') return null;
  const number = Number(trimmed);
  if (!Number.isFinite(number) || number < 0) return null;
  return Math.round(number * 10 ** currencyDigits(currency));
}
