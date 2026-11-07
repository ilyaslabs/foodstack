const BRANDS: Record<string, string> = {
  visa: 'Visa',
  mastercard: 'Mastercard',
  amex: 'American Express',
  discover: 'Discover',
  unionpay: 'UnionPay',
  jcb: 'JCB',
  diners: 'Diners Club',
};

/** A gateway's card brand code as people write it ("visa" becomes "Visa"). */
export function cardBrand(code: string): string {
  return BRANDS[code?.toLowerCase()] ?? (code ? code[0].toUpperCase() + code.slice(1) : '');
}
