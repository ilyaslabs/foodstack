import { LedgerParty, PayoutRow } from '@foodstack/api-client';

/** Names of ledger kinds, as on the CSV and PDF. */
export const KIND_LABELS: Record<string, string> = {
  FOOD: 'Food',
  TAX: 'Tax',
  DELIVERY: 'Delivery',
  TIP: 'Tips',
  DISCOUNT: 'Discounts',
  COMMISSION: 'Commission',
  SERVICE_FEE: 'Service fee',
  REFUND: 'Refunds',
  CASH: 'Cash held',
  COLLECTED: 'Collected directly',
  EARNING: 'Delivery pay',
  SETTLEMENT: 'Settled',
  PAYMENT: 'Payments',
};

/** Names of what a transaction was. */
export const TYPE_LABELS: Record<string, string> = {
  ORDER_DONE: 'Order',
  REFUND: 'Refund',
  DELIVERY: 'Delivery',
  SETTLEMENT: 'Settlement',
  PAYMENT: 'Card payment',
  REFUND_PAID: 'Refund paid out',
};

/** Names of the operator's own accounts. */
export const OPERATOR_ACCOUNTS: Record<string, string> = {
  'operator:commission': 'Commission',
  'operator:delivery': 'Delivery charges',
  'operator:service-fee': 'Service fees',
  'operator:discounts': 'Discounts funded',
  'operator:rider-pay': 'Rider pay',
  'operator:refunds': 'Refunds borne',
};

/** A column of the payout list: the kinds it adds up. */
export interface PayoutColumn {
  label: string;
  kinds: string[];
}

/** The columns shown on screen; the CSV and PDF have every kind. */
export function payoutColumns(party: LedgerParty): PayoutColumn[] {
  return party === 'RIDER'
    ? [
        { label: 'Delivery pay', kinds: ['EARNING'] },
        { label: 'Tips', kinds: ['TIP'] },
        { label: 'Cash carried', kinds: ['CASH'] },
        { label: 'Settled', kinds: ['SETTLEMENT'] },
      ]
    : [
        { label: 'Sales', kinds: ['FOOD', 'TAX', 'DELIVERY', 'TIP'] },
        { label: 'Deductions', kinds: ['COMMISSION', 'DISCOUNT', 'SERVICE_FEE'] },
        { label: 'Refunds', kinds: ['REFUND'] },
        { label: 'Cash held', kinds: ['CASH', 'COLLECTED'] },
        { label: 'Settled', kinds: ['SETTLEMENT'] },
      ];
}

/** The sum of a row's kinds. */
export function columnTotal(kinds: Record<string, number>, column: PayoutColumn): number {
  return column.kinds.reduce((sum, kind) => sum + (kinds[kind] ?? 0), 0);
}

/** "We pay", "They pay" or "Settled", from the operator's side or from the branch's. */
export function directionLabel(row: PayoutRow, operatorView: boolean): string {
  if (row.direction === 'SETTLED') return 'Settled';
  if (operatorView) return row.direction === 'OPERATOR_PAYS' ? 'We pay' : 'They pay';
  return row.direction === 'OPERATOR_PAYS' ? 'Due to you' : 'You owe';
}

/** Monday to Sunday weeks and calendar months, as yyyy-MM-dd, from the viewer's today. */
export function presetPeriod(
  preset: 'this-week' | 'last-week' | 'this-month' | 'last-month',
  today: Date,
): { from: string; to: string } {
  const day = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const weekday = (day.getDay() + 6) % 7;
  switch (preset) {
    case 'this-week': {
      const from = addDays(day, -weekday);
      return { from: iso(from), to: iso(day) };
    }
    case 'last-week': {
      const from = addDays(day, -weekday - 7);
      return { from: iso(from), to: iso(addDays(from, 6)) };
    }
    case 'this-month':
      return { from: iso(new Date(day.getFullYear(), day.getMonth(), 1)), to: iso(day) };
    case 'last-month': {
      const from = new Date(day.getFullYear(), day.getMonth() - 1, 1);
      return { from: iso(from), to: iso(new Date(day.getFullYear(), day.getMonth(), 0)) };
    }
  }
}

/** yyyy-MM-dd of a local date. */
export function iso(date: Date): string {
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${m}-${d}`;
}

function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}
