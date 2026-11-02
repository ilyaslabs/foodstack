import { DeliveryMode, Fulfilment, Translated } from './catalogue-models';

/** Money is always in the currency's minor units (paisa, cents); see TDD 6.2. */
export type PaymentMethod = 'CASH' | 'CARD';

export type OrderStatus =
  | 'PLACED'
  | 'PAYMENT_PENDING'
  | 'CONFIRMED'
  | 'ACCEPTED'
  | 'PREPARING'
  | 'READY'
  | 'PICKED_UP'
  | 'DELIVERED'
  | 'COLLECTED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'FAILED';

export const FINAL_STATUSES: readonly OrderStatus[] = [
  'DELIVERED',
  'COLLECTED',
  'REJECTED',
  'CANCELLED',
  'FAILED',
];

/** What the viewer may do with an order now; screens show only these buttons. */
export type OrderAction =
  | 'ACCEPT'
  | 'REJECT'
  | 'PREPARING'
  | 'READY'
  | 'COLLECTED'
  | 'PICKED_UP'
  | 'DELIVERED'
  | 'CANCEL'
  | 'REFUND'
  | 'PAY';

export interface CartStore {
  storeId: string;
  slug: string | null;
  brandName: string;
  branchName: string | null;
  logoUrls: Record<string, string>;
  location: { lat: number; lng: number } | null;
  addressLine: string | null;
  city: string | null;
  deliveryMode: DeliveryMode;
  pickupEnabled: boolean;
  live: boolean;
  openNow: boolean;
  prepMinutes: number;
  timezone: string;
}

export interface PricedOption {
  groupId: string;
  groupName: Translated;
  optionId: string;
  name: Translated;
  priceDeltaMinor: number;
}

export interface CartLine {
  lineId: string;
  note: string | null;
  priced: {
    itemId: string;
    name: Translated;
    quantity: number;
    unitPriceMinor: number;
    options: PricedOption[];
    lineTotalMinor: number;
  };
}

export interface CheckoutProblem {
  code: string;
  details: Record<string, unknown> | null;
}

export interface Cart {
  version: number;
  store: CartStore | null;
  currency: string | null;
  lines: CartLine[];
  subtotalMinor: number;
  itemCount: number;
  problems: CheckoutProblem[];
}

export interface AddCartLine {
  storeId: string;
  itemId: string;
  quantity: number;
  optionIds: string[];
  note?: string | null;
  replaceOtherStore?: boolean;
}

export interface CheckoutRequest {
  fulfilment: Fulfilment;
  lat?: number | null;
  lng?: number | null;
  couponCode?: string | null;
  tipMinor?: number | null;
  paymentMethod?: PaymentMethod | null;
}

export interface CouponEvaluation {
  applies: boolean;
  reason: string | null;
  details: Record<string, unknown> | null;
  code: string | null;
  title: Translated;
  itemsDiscountMinor: number;
  deliveryDiscountMinor: number;
}

export interface Checkout {
  cartVersion: number;
  store: CartStore | null;
  currency: string | null;
  fulfilment: Fulfilment;
  lines: CartLine[];
  subtotalMinor: number;
  delivery: {
    deliverable: boolean;
    reason: string | null;
    deliveryMode: DeliveryMode;
    chargeMinor: number;
    minOrderMinor: number;
    freeDeliveryAboveMinor: number | null;
    distanceMeters: number;
  } | null;
  coupon: CouponEvaluation | null;
  itemsDiscountMinor: number;
  deliveryDiscountMinor: number;
  deliveryChargeMinor: number;
  serviceFeeMinor: number;
  taxMinor: number;
  tipMinor: number;
  totalMinor: number;
  etaMinutes: number;
  paymentMethods: PaymentMethod[];
  tipsEnabled: boolean;
  problems: CheckoutProblem[];
}

export interface PlaceOrder {
  fulfilment: Fulfilment;
  address: { lat: number; lng: number; addressLine: string; instructions: string | null } | null;
  couponCode: string | null;
  tipMinor: number;
  paymentMethod: PaymentMethod;
  expectedTotalMinor: number;
  contactPhone: string;
  language: string;
}

export interface PlacedOrder {
  orderId: string;
  number: string;
  status: OrderStatus;
  paymentUrl: string | null;
}

export interface OrderLine {
  itemId: string;
  name: Translated;
  quantity: number;
  unitPriceMinor: number;
  options: { groupName: Translated; name: Translated; priceDeltaMinor: number }[];
  lineTotalMinor: number;
  note: string | null;
}

export interface OrderTotals {
  subtotalMinor: number;
  itemsDiscountMinor: number;
  deliveryChargeMinor: number;
  deliveryDiscountMinor: number;
  serviceFeeMinor: number;
  taxMinor: number;
  tipMinor: number;
  totalMinor: number;
}

export interface OrderStep {
  status: OrderStatus;
  /** Epoch seconds. */
  at: number;
  actorRole: string | null;
  note: string | null;
}

export interface Order {
  id: string;
  number: string;
  status: OrderStatus;
  fulfilment: Fulfilment;
  deliveryMode: DeliveryMode | null;
  paymentMethod: PaymentMethod;
  currency: string;
  customerId: string;
  customerName: string | null;
  contactPhone: string | null;
  store: {
    storeId: string;
    name: string;
    slug: string | null;
    addressLine: string | null;
    city: string | null;
    lat: number;
    lng: number;
    logoUrls: Record<string, string>;
  };
  destination: {
    lat: number;
    lng: number;
    addressLine: string | null;
    instructions: string | null;
  } | null;
  lines: OrderLine[];
  totals: OrderTotals;
  coupon: { code: string; title: Translated } | null;
  prepMinutes: number;
  /** Epoch seconds. */
  acceptBy: number | null;
  readyBy: number | null;
  estimatedAt: number | null;
  paymentUrl: string | null;
  paidMinor: number;
  refundRequestedMinor: number;
  refundedMinor: number;
  timeline: OrderStep[];
  closeReason: string | null;
  placedAt: number;
  revision: number;
  actions: OrderAction[];
}

export interface OrderRow {
  id: string;
  number: string;
  customerName: string | null;
  storeId: string;
  storeName: string;
  storeSlug: string | null;
  logoUrls: Record<string, string>;
  status: OrderStatus;
  fulfilment: Fulfilment;
  paymentMethod: PaymentMethod;
  itemNames: Translated[];
  itemCount: number;
  totalMinor: number;
  currency: string;
  placedAt: number;
}

export interface OrderPage {
  items: OrderRow[];
  next: string | null;
}

/** A change pushed by the live stream; the screen reloads the order to show it. */
export interface OrderChange {
  orderId?: string;
  storeId?: string;
  status?: OrderStatus;
  revision?: number;
  ready?: boolean;
}

export interface PaymentOptions {
  cash: boolean;
  card: boolean;
  gateway: string | null;
}

export interface InboxItem {
  id: string;
  template: string;
  title: string;
  body: string;
  link: string | null;
  createdAt: number;
  read: boolean;
}

export interface NotificationPreferences {
  orderEmails: boolean;
  marketingEmails: boolean;
}

export interface EmailDelivery {
  id: string;
  template: string;
  recipient: string;
  subject: string;
  status: 'QUEUED' | 'SENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
  attempts: number;
  lastError: string | null;
  createdAt: number;
  sentAt: number | null;
}

export interface OrderingSettings {
  cashEnabled: boolean;
  cardEnabled: boolean;
  serviceFeeMinor: number;
  serviceFeeBasisPoints: number;
  taxBasisPoints: number;
  tipsEnabled: boolean;
  acceptTimeoutSeconds: number;
}

export type PromotionType = 'PERCENT' | 'FIXED' | 'FREE_DELIVERY';

export interface PromotionInput {
  brandId?: string | null;
  code: string;
  title: Translated;
  type: PromotionType;
  percentBasisPoints?: number | null;
  maxDiscountMinor?: number | null;
  amountMinor?: number | null;
  minOrderMinor?: number | null;
  startsAt?: number | null;
  endsAt?: number | null;
  totalLimit?: number | null;
  perCustomerLimit?: number | null;
  firstOrderOnly?: boolean;
  storeIds?: string[];
  cities?: string[];
  paymentMethods?: PaymentMethod[];
  operatorMerchantOnly?: boolean;
  operatorFundingBasisPoints?: number | null;
}

export interface Promotion {
  id: string;
  owner: 'OPERATOR' | 'STORE';
  brandId: string | null;
  code: string;
  title: Translated;
  type: PromotionType;
  percentBasisPoints: number;
  maxDiscountMinor: number | null;
  amountMinor: number;
  minOrderMinor: number;
  startsAt: number | null;
  endsAt: number | null;
  totalLimit: number | null;
  perCustomerLimit: number | null;
  firstOrderOnly: boolean;
  storeIds: string[];
  cities: string[];
  paymentMethods: PaymentMethod[];
  operatorMerchantOnly: boolean;
  operatorFundingBasisPoints: number;
  active: boolean;
  used: number;
  version: number;
  updatedAt: number | null;
}
