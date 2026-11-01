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

export interface Brand {
  id: string;
  name: string;
  description: Translated;
  cuisines: string[];
  logo: Image | null;
  cover: Image | null;
  commission: Commission;
  branchCount: number;
  version: number;
  updatedAt: number | null;
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
}

export interface BranchUpdate {
  branchName: string | null;
  address: StoreAddress;
  location: LatLng | null;
  prepMinutes: number;
  deliveryMode: DeliveryMode;
  pickupEnabled: boolean;
}

export interface StoreOwner {
  userId: string;
  email: string | null;
  displayName: string | null;
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

export interface OperatorPricing {
  enabled: boolean;
  baseChargeMinor: number;
  perKmMinor: number;
  maxDistanceMeters: number;
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
}

export interface StoreSearchPage {
  stores: StoreCard[];
  cuisines: { cuisine: string; count: number }[];
  total: number;
  page: number;
  size: number;
}

export interface StoreSearch {
  lat: number;
  lng: number;
  q?: string | null;
  cuisine?: string | null;
  fulfilment?: Fulfilment;
  page?: number;
  size?: number;
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
