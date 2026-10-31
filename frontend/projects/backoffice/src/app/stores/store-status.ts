import { StoreStatus } from '@foodstack/api-client';

export const STATUS_LABELS: Record<StoreStatus, string> = {
  DRAFT: 'Draft',
  LIVE: 'Live',
  PAUSED: 'Paused',
};

export const READINESS_LABELS: Record<string, string> = {
  LOCATION: 'Set the branch location on the Profile tab',
  MENU: 'Add at least one dish on the Menu tab',
  DELIVERY_AREA: 'Add a delivery zone on the Delivery tab, or switch on pickup',
};
