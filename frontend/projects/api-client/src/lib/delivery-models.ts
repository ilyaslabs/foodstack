/** REST models of riders, dispatch, delivery settings and push devices (PRD 4.7, TDD 5.5, 5.6). */

export type Vehicle = 'BICYCLE' | 'MOTORBIKE' | 'SCOOTER' | 'CAR';
export type RiderKind = 'OPERATOR' | 'STORE';
export type RiderStatus = 'ACTIVE' | 'SUSPENDED';

export interface Rider {
  id: string;
  kind: RiderKind;
  storeId: string | null;
  name: string;
  phone: string | null;
  vehicle: Vehicle;
  plate: string | null;
  city: string | null;
  status: RiderStatus;
  online: boolean;
  lat: number | null;
  lng: number | null;
  /** Epoch seconds. */
  lastSeenAt: number | null;
  activeOrderIds: string[];
  acceptancePercent: number;
  deliveries: number;
  createdAt: number;
  version: number;
}

export interface RiderInput {
  /** Adding only. */
  email?: string | null;
  /** Adding only; an existing account keeps its own password. */
  initialPassword?: string | null;
  name: string;
  phone: string;
  vehicle: Vehicle;
  plate: string | null;
  city: string | null;
  version?: number | null;
}

export interface LocationPoint {
  lat: number;
  lng: number;
  accuracyMeters: number | null;
  /** Epoch seconds when the phone took it. */
  at: number | null;
}

export interface EarningsDay {
  date: string;
  deliveries: number;
  earningMinor: number;
  tipMinor: number;
  cashCollectedMinor: number;
}

export interface EarningsRow {
  orderId: string;
  orderNumber: string;
  storeName: string;
  earningMinor: number;
  tipMinor: number;
  cashCollectedMinor: number;
  distanceMeters: number;
  at: number;
}

export interface Earnings {
  currency: string;
  today: EarningsDay;
  days: EarningsDay[];
  recent: EarningsRow[];
}

export interface LiveRider {
  riderId: string;
  name: string;
  kind: RiderKind;
  storeId: string | null;
  vehicle: Vehicle;
  busy: boolean;
  lat: number | null;
  lng: number | null;
  at: number | null;
}

/** A message of the riders' live stream: a position, or a change of state. */
export interface RiderLiveMessage {
  riderId: string;
  lat?: number;
  lng?: number;
  at?: number;
  busy: boolean;
  kind?: RiderKind;
  storeId?: string;
  online?: boolean;
  state?: boolean;
}

export interface Tracking {
  orderId: string;
  riderName: string;
  vehicle: string;
  pickedUp: boolean;
  lat: number | null;
  lng: number | null;
  at: number | null;
  pickupLat: number;
  pickupLng: number;
  dropoffLat: number;
  dropoffLng: number;
  etaSeconds: number | null;
  remainingMeters: number | null;
}

export type DeliveryStatus =
  | 'WAITING'
  | 'SEARCHING'
  | 'OFFERED'
  | 'ASSIGNED'
  | 'AT_STORE'
  | 'PICKED_UP'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'HANDED_BACK';

export interface DeliveryJob {
  orderId: string;
  orderNumber: string;
  mode: 'OPERATOR' | 'SELF';
  status: DeliveryStatus;
  storeId: string;
  storeName: string;
  pickupLat: number;
  pickupLng: number;
  pickupAddress: string | null;
  customerName: string | null;
  customerPhone: string | null;
  dropoffLat: number;
  dropoffLng: number;
  dropoffAddress: string | null;
  instructions: string | null;
  riderId: string | null;
  riderName: string | null;
  /** Epoch seconds. */
  offerExpiresAt: number | null;
  earningMinor: number;
  tipMinor: number;
  cashToCollectMinor: number;
  currency: string;
  needsCode: boolean;
  distanceMeters: number;
  readyBy: number | null;
  searchingSince: number | null;
  closeReason: string | null;
  revision: number;
}

/** A message of the dispatch streams: a job changed; the screen reloads it. */
export interface JobChange {
  orderId: string;
  status: DeliveryStatus;
  kind: string;
  riderId?: string;
}

export interface DeliveryProof {
  code?: string | null;
  photoUrl?: string | null;
  cashCollectedMinor?: number | null;
}

export interface DeliverySettings {
  riderPayBasisPoints: number;
  riderPayMinMinor: number;
  offerTimeoutSeconds: number;
  searchRadiusMeters: number;
  searchLeadMinutes: number;
  noRiderAlertSeconds: number;
  /** A delivery code is asked above this order total; null means never, 0 means always. */
  proofCodeAboveMinor: number | null;
  locationRetentionDays: number;
  version?: number;
  updatedAt?: number | null;
}

export interface PushConfig {
  enabled: boolean;
  firebase: Record<string, string> | null;
  vapidKey: string | null;
}

export type RiderDocumentType =
  'NATIONAL_ID' | 'PASSPORT' | 'DRIVING_LICENCE' | 'VEHICLE_REGISTRATION' | 'OTHER';
export type PayoutMethod = 'BANK' | 'MOBILE_WALLET';

/** An ID or vehicle document; the number shows only by its last 4 characters. */
export interface RiderPaperDocument {
  id: string;
  type: RiderDocumentType;
  numberLast4: string;
  /** Short-lived links to the private photos. */
  frontUrl: string | null;
  backUrl: string | null;
  /** ISO date, or null when it does not expire. */
  expiresOn: string | null;
  expired: boolean;
  addedAt: number;
  verified: boolean;
  verifiedAt: number | null;
}

export interface RiderPayout {
  method: PayoutMethod;
  accountName: string;
  provider: string;
  accountLast4: string;
  updatedAt: number;
}

/** An operator rider's papers (PRD DR-1). */
export interface RiderPapers {
  riderId: string;
  documents: RiderPaperDocument[];
  payout: RiderPayout | null;
  identityVerified: boolean;
  payoutSet: boolean;
  complete: boolean;
  version: number;
}

export interface RiderDocumentInput {
  type: RiderDocumentType;
  number: string;
  frontMediaId: string;
  backMediaId: string | null;
  expiresOn: string | null;
}

export interface RiderPayoutInput {
  method: PayoutMethod;
  accountName: string;
  provider: string;
  /** The whole account number or IBAN, entered again on every change. */
  account: string;
}

/** A map area in degrees, for the live map and the dispatch board. */
export interface MapBounds {
  south: number;
  west: number;
  north: number;
  east: number;
}
