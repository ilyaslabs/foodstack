/** REST models of /api/v1. Hand-written for M0; generated from the OpenAPI document once it is published. */

export interface Branding {
  name: string;
  tagline: string | null;
  primaryColor: string;
  secondaryColor: string;
  fontFamily: string;
  logo: { mediaId: string; urls: Record<string, string> } | null;
  version: number;
  updatedAt: number | null;
}

export interface BrandingUpdate {
  name: string;
  tagline: string | null;
  primaryColor: string;
  secondaryColor: string;
  fontFamily: string;
  logoMediaId: string | null;
}

export interface LocaleSettings {
  defaultLanguage: string;
  languages: string[];
  rtlLanguages: string[];
  currency: string;
  timezone: string;
  version: number;
}

export type Role = 'ADMIN' | 'FINANCE' | 'SUPPORT' | 'CUSTOMER';

export interface Me {
  id: string;
  email: string;
  displayName: string;
  roles: Role[];
  permissions: string[];
  status: string;
  createdAt: number;
}

export type MediaPurpose = 'BRANDING' | 'STORE' | 'MENU_ITEM' | 'RIDER_DOCUMENT';

export interface MediaAsset {
  id: string;
  purpose: MediaPurpose;
  status: 'PENDING' | 'READY' | 'REJECTED';
  variants: Record<string, { url: string; width: number; height: number; bytes: number }>;
  createdAt: number;
}

export interface UploadTicket {
  media: MediaAsset;
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: number;
}

/** An RFC 9457 problem as the API returns it. */
export interface Problem {
  status: number;
  title: string;
  detail?: string;
  code: string;
  traceId?: string;
}

/** A value read with its ETag, for an update guarded by If-Match. */
export interface Versioned<T> {
  value: T;
  etag: string;
}
