import { Order, OrderAction, OrderStatus } from '@foodstack/api-client';

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PLACED: 'Placed',
  PAYMENT_PENDING: 'Awaiting payment',
  CONFIRMED: 'New',
  ACCEPTED: 'Accepted',
  PREPARING: 'Preparing',
  READY: 'Ready',
  PICKED_UP: 'Out for delivery',
  DELIVERED: 'Delivered',
  COLLECTED: 'Collected',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  FAILED: 'Payment failed',
};

/** The store's buttons, in the order they appear on a ticket. */
export const ACTION_LABELS: Partial<Record<OrderAction, string>> = {
  ACCEPT: 'Accept',
  PREPARING: 'Start preparing',
  READY: 'Mark ready',
  COLLECTED: 'Collected',
  PICKED_UP: 'Out for delivery',
  DELIVERED: 'Delivered',
  REJECT: 'Reject',
  CANCEL: 'Cancel order',
};

/** The one step that moves the order on, shown as the main button. */
export function primaryAction(order: Order): OrderAction | null {
  for (const action of [
    'ACCEPT',
    'READY',
    'COLLECTED',
    'PICKED_UP',
    'DELIVERED',
  ] as OrderAction[]) {
    if (order.actions.includes(action)) return action;
  }
  return null;
}

export function statusTone(status: OrderStatus): 'new' | 'progress' | 'ready' | 'done' | 'stopped' {
  switch (status) {
    case 'CONFIRMED':
    case 'PAYMENT_PENDING':
    case 'PLACED':
      return 'new';
    case 'ACCEPTED':
    case 'PREPARING':
      return 'progress';
    case 'READY':
    case 'PICKED_UP':
      return 'ready';
    case 'DELIVERED':
    case 'COLLECTED':
      return 'done';
    default:
      return 'stopped';
  }
}

/** A time of day, in the branch's own time zone when given (tickets show the kitchen's clock). */
export function clock(epochSeconds: number | null, timeZone?: string | null): string {
  if (!epochSeconds) return '';
  return new Date(epochSeconds * 1000).toLocaleTimeString([], {
    hour: 'numeric',
    minute: '2-digit',
    ...zone(timeZone),
  });
}

export function dateTime(epochSeconds: number, timeZone?: string | null): string {
  return new Date(epochSeconds * 1000).toLocaleString([], {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...zone(timeZone),
  });
}

function zone(timeZone?: string | null): { timeZone?: string } {
  if (!timeZone) return {};
  try {
    new Intl.DateTimeFormat([], { timeZone });
    return { timeZone };
  } catch {
    return {};
  }
}
