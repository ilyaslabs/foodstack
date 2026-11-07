/** REST models of the catalogue (M1): stores, menus, delivery zones and search. Amounts are minor units. */

/** Text per language tag, such as `{ en: 'Rice', ar: 'أرز' }`. */
export type Translated = Record<string, string>;

export interface LatLng {
  lat: number;
  lng: number;
}

export interface StoreAddress {
  line1: string;
  area: string | null;
  city: string;
  postalCode: string | null;
}

export interface Commission {
  percentBasisPoints: number;
  fixedMinor: number;
}

export interface TimeRange {
  open: string;
  close: string;
}

export interface Closure {
  from: string;
  to: string;
  reason: string | null;
}

export type Weekday =
  'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export const WEEKDAYS: readonly Weekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

export type StoreStatus = 'DRAFT' | 'LIVE' | 'PAUSED';
export type DeliveryMode = 'SELF' | 'OPERATOR';

export interface Image {
  mediaId: string;
  urls: Record<string, string>;
}

/** A commission and when it starts (epoch seconds); `effectiveFrom` 0 is the starting value. */
export interface CommissionChange {
  /** Null on a branch means "back to the brand's commission". */
  commission: Commission | null;
  effectiveFrom: number;
  setBy: string | null;
  setAt: number | null;
}

export interface Brand {
  id: string;
  name: string;
  description: Translated;
  cuisines: string[];
  logo: Image | null;
  cover: Image | null;
  /** The commission in force now. */
  commission: Commission;
  branchCount: number;
  version: number;
  updatedAt: number | null;
  /** Dated changes, oldest first, including planned ones. */
  commissionChanges: CommissionChange[];
  /** Brand owners: they run every branch and the brand's menu. */
  ownerUserIds: string[];
  /** Whether branch managers may run their own branch's promotions (PRD BB-11). */
  branchPromotionsAllowed: boolean;
}

export interface BrandUpdate {
  name: string;
  description: Translated;
  cuisines: string[];
  logoMediaId: string | null;
  coverMediaId: string | null;
}

export interface StoreDetails {
  id: string;
  /** Web address on the customer site: /stores/{slug}. */
  slug: string | null;
  brand: Brand;
  branchName: string | null;
  displayName: string;
  legalName: string;
  taxId: string | null;
  ownerUserIds: string[];
  address: StoreAddress;
  location: LatLng | null;
  openingHours: Record<Weekday, TimeRange[]>;
  closures: Closure[];
  temporarilyClosed: boolean;
  closedReason: string | null;
  prepMinutes: number;
  deliveryMode: DeliveryMode;
  operatorDeliveryAllowed: boolean;
  pickupEnabled: boolean;
  commission: Commission;
  status: StoreStatus;
  timezone: string;
  openNow: boolean;
  version: number;
  createdAt: number | null;
  updatedAt: number | null;
  /** The operator's city of the branch, or null. */
  cityId: string | null;
  /** Order ticket paper width in mm: 80 or 58. */
  ticketPaperMm: 80 | 58;
  /** The branch's own dated commission changes, over the brand's. */
  commissionChanges: CommissionChange[];
  brandOwnerUserIds: string[];
  /** Who collects card payments (PRD DP-2, DP-7); missing on servers before M4. */
  paymentCollection?: PaymentCollection;
}

/** Whether the branch collects card payments itself, with its credit limit. */
export interface PaymentCollection {
  mode: 'OPERATOR' | 'DIRECT';
  /** The branch's own credit limit in minor units, or null for the operator's default. */
  creditLimitMinor: number | null;
  reason: string | null;
  changedBy: string | null;
  changedAt: number | null;
}

export interface StoreSummary {
  id: string;
  brandId: string;
  brandName: string;
  branchName: string | null;
  city: string | null;
  status: StoreStatus;
  temporarilyClosed: boolean;
  deliveryMode: DeliveryMode;
  createdAt: number | null;
  /** The brand's logo by size (thumb, card, full); empty when no logo is uploaded. */
  logoUrls: Record<string, string>;
  cityId: string | null;
  /** How many branches the brand has. */
  branchCount: number;
}

export interface NewBranch {
  branchName: string;
  legalName: string;
  taxId: string | null;
  address: StoreAddress;
  location: LatLng | null;
  /** Empty picks the nearest of the operator's cities. */
  cityId: string | null;
  prepMinutes: number;
  pickupEnabled: boolean;
}

/** One of the operator's cities (PRD AD-4): branches and riders belong to one. */
export interface City {
  id: string;
  name: Translated;
  centre: LatLng;
  active: boolean;
  /** The city's own operator delivery pricing, or null to use the operator-wide one. */
  pricing: CityPricing | null;
  version: number;
  updatedAt: number | null;
}

export interface CityPricing {
  baseChargeMinor: number;
  perKmMinor: number;
  maxDistanceMeters: number;
  minOrderMinor: number;
  freeDeliveryAboveMinor: number | null;
  peakMultiplierPercent: number;
  peakWindows: PeakWindow[];
}

export interface CityInput {
  name: Translated;
  centre: LatLng;
  active: boolean;
  pricing: CityPricing | null;
}

export interface Onboarding {
  brandName: string;
  branchName: string | null;
  legalName: string;
  taxId: string | null;
  description: Translated;
  cuisines: string[];
  address: StoreAddress;
  location: LatLng | null;
  pickupEnabled: boolean;
  prepMinutes: number;
  commission: Commission;
  cityId?: string | null;
}

export interface BranchUpdate {
  branchName: string | null;
  address: StoreAddress;
  location: LatLng | null;
  prepMinutes: number;
  deliveryMode: DeliveryMode;
  pickupEnabled: boolean;
  /** IANA zone; empty finds it from the location. Unchanged while the location moves, it is found again. */
  timezone?: string | null;
  /** Order ticket paper width: 80 or 58 mm. */
  ticketPaperMm?: 80 | 58;
}

export interface StoreOwner {
  userId: string;
  email: string | null;
  displayName: string | null;
}

/** A branch's own price for a brand item, or for an option's extra price (PRD BB-4). */
export interface BranchPrice {
  itemId: string;
  optionId: string | null;
  priceMinor: number;
}

/** A price change waiting for operator approval (PRD ST-6). */
export interface PriceChange {
  id: string;
  brandId: string;
  brandName: string | null;
  /** Null for the brand menu price. */
  storeId: string | null;
  branchName: string | null;
  itemId: string;
  itemName: Translated;
  optionId: string | null;
  optionName: Translated | null;
  currentMinor: number;
  /** Null means "back to the brand price". */
  proposedMinor: number | null;
  requestedBy: string | null;
  requestedAt: number | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
}

export interface BranchPrices {
  prices: BranchPrice[];
  pending: PriceChange[];
}

export interface ModifierOption {
  id?: string | null;
  name: Translated;
  priceDeltaMinor: number;
}

export interface ModifierGroup {
  id?: string | null;
  name: Translated;
  minSelect: number;
  maxSelect: number;
  options: ModifierOption[];
}

export interface MenuItemInput {
  sectionId: string;
  name: Translated;
  description: Translated;
  priceMinor: number;
  imageMediaId: string | null;
  dietaryTags: string[];
  modifierGroups: ModifierGroup[];
  branchIds: string[] | null;
  sortOrder: number;
  version?: number;
}

export interface EditorItem extends MenuItemInput {
  id: string;
  imageUrls: Record<string, string>;
  version: number;
}

export interface EditorSection {
  id: string;
  name: Translated;
  sortOrder: number;
  version: number;
  items: EditorItem[];
}

export interface EditorMenu {
  brandId: string;
  currency: string;
  languages: string[];
  sections: EditorSection[];
}

export interface MenuOption {
  id: string;
  name: Translated;
  priceDeltaMinor: number;
  soldOut: boolean;
}

export interface MenuGroup {
  id: string;
  name: Translated;
  minSelect: number;
  maxSelect: number;
  options: MenuOption[];
}

export interface MenuItem {
  id: string;
  name: Translated;
  description: Translated;
  priceMinor: number;
  imageUrls: Record<string, string>;
  dietaryTags: string[];
  soldOut: boolean;
  modifierGroups: MenuGroup[];
}

export interface Menu {
  storeId: string;
  brandId: string;
  currency: string;
  sections: { id: string; name: Translated; items: MenuItem[] }[];
}

export interface StorePage {
  id: string;
  slug: string | null;
  brandId: string;
  brandName: string;
  branchName: string | null;
  displayName: string;
  description: Translated;
  cuisines: string[];
  logoUrls: Record<string, string>;
  coverUrls: Record<string, string>;
  address: string | null;
  location: LatLng | null;
  openNow: boolean;
  temporarilyClosed: boolean;
  closedReason: string | null;
  openingHours: Record<Weekday, TimeRange[]>;
  prepMinutes: number;
  deliveryMode: DeliveryMode;
  pickupEnabled: boolean;
  timezone: string;
}

export interface DeliveryQuote {
  deliverable: boolean;
  reason: string | null;
  chargeMinor: number;
  minOrderMinor: number;
  freeDeliveryAboveMinor: number | null;
  distanceMeters: number;
  etaMinutes: number;
  currency: string;
}

export interface Zone {
  id?: string;
  name: string;
  center: LatLng | null;
  radiusMeters: number | null;
  polygon?: LatLng[];
  chargeMinor: number;
  minOrderMinor: number;
  freeDeliveryAboveMinor: number | null;
}

/** Hours when operator delivery costs more, in the deployment's time zone. */
export interface PeakWindow {
  /** Empty for every day. */
  days: Weekday[];
  /** HH:mm. */
  start: string;
  /** HH:mm; before the start for a window past midnight. */
  end: string;
}

export interface OperatorPricing {
  enabled: boolean;
  baseChargeMinor: number;
  perKmMinor: number;
  maxDistanceMeters: number;
  /** Smallest basket subtotal, 0 for none. */
  minOrderMinor: number;
  /** Subtotal from which delivery is free, or null. */
  freeDeliveryAboveMinor: number | null;
  /** Peak charge as a percentage of the normal one, 100 to 300. */
  peakMultiplierPercent: number;
  peakWindows: PeakWindow[];
  version: number;
  updatedAt: number | null;
}

export interface AddressSuggestion {
  label: string;
  lat: number;
  lng: number;
}

export type Fulfilment = 'DELIVERY' | 'PICKUP';

export interface StoreCard {
  storeId: string;
  slug: string | null;
  brandId: string;
  brandName: string;
  branchName: string | null;
  branchCount: number;
  description: Translated;
  cuisines: string[];
  logoUrls: Record<string, string>;
  coverUrls: Record<string, string>;
  area: string | null;
  city: string | null;
  distanceMeters: number;
  deliveryMode: DeliveryMode;
  pickupEnabled: boolean;
  openNow: boolean;
  temporarilyClosed: boolean;
  prepMinutes: number;
  dietaryTags: string[];
  /** Published reviews of the branch. */
  ratingCount?: number;
  ratingAverage?: number;
}

export interface StoreSearchPage {
  stores: StoreCard[];
  cuisines: { cuisine: string; count: number }[];
  total: number;
  page: number;
  size: number;
  /** Whether another page follows; results stop at a fixed depth. */
  hasMore: boolean;
  /** How far this search looked, road metres. */
  radiusMeters: number;
  /** The next, wider step for "show restaurants further away", or null at the widest (and for pickup). */
  widerRadiusMeters: number | null;
}

export interface StoreSearch {
  lat: number;
  lng: number;
  q?: string | null;
  cuisine?: string | null;
  fulfilment?: Fulfilment;
  page?: number;
  size?: number;
  /** Delivery only: road metres to look within; the first discovery step when left out. */
  radius?: number | null;
}

export const DIETARY_TAGS = [
  'vegetarian',
  'vegan',
  'halal',
  'gluten-free',
  'dairy-free',
  'spicy',
  'contains-nuts',
] as const;
