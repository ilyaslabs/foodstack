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

export function clock(epochSeconds: number | null): string {
  if (!epochSeconds) return '';
  return new Date(epochSeconds * 1000).toLocaleTimeString([], {
    hour: 'numeric',
    minute: '2-digit',
  });
}

export function dateTime(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleString([], {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}
