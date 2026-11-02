import { Order, OrderStatus } from '@foodstack/api-client';

import { UiKey } from '../i18n';

/** The steps a customer sees, in order, for each way of getting the food. */
export function stepsFor(order: Pick<Order, 'fulfilment' | 'paymentMethod'>): OrderStatus[] {
  const head: OrderStatus[] = ['CONFIRMED', 'ACCEPTED', 'PREPARING', 'READY'];
  return order.fulfilment === 'PICKUP'
    ? [...head, 'COLLECTED']
    : [...head, 'PICKED_UP', 'DELIVERED'];
}

export function statusKey(status: OrderStatus): UiKey {
  return `status_${status}` as UiKey;
}

/** One line under the status that says what happens next. */
export function hintKey(order: Pick<Order, 'status' | 'fulfilment'>): UiKey | null {
  switch (order.status) {
    case 'PAYMENT_PENDING':
      return 'hintPaymentPending';
    case 'PLACED':
    case 'CONFIRMED':
      return 'hintConfirmed';
    case 'ACCEPTED':
      return 'hintAccepted';
    case 'PREPARING':
      return 'hintPreparing';
    case 'READY':
      return order.fulfilment === 'PICKUP' ? 'hintReadyPickup' : 'hintReadyDelivery';
    case 'PICKED_UP':
      return 'hintPickedUp';
    case 'REJECTED':
      return 'hintRejected';
    case 'CANCELLED':
      return 'hintCancelled';
    case 'FAILED':
      return 'hintFailed';
    case 'DELIVERED':
    case 'COLLECTED':
      return 'hintDone';
    default:
      return null;
  }
}

/** Colour family of a status chip. */
export function tone(status: OrderStatus): 'progress' | 'done' | 'stopped' | 'waiting' {
  switch (status) {
    case 'DELIVERED':
    case 'COLLECTED':
      return 'done';
    case 'REJECTED':
    case 'CANCELLED':
    case 'FAILED':
      return 'stopped';
    case 'PAYMENT_PENDING':
    case 'PLACED':
    case 'CONFIRMED':
      return 'waiting';
    default:
      return 'progress';
  }
}
