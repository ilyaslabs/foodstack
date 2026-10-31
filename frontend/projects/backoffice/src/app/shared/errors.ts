import { HttpErrorResponse } from '@angular/common/http';

import { Problem } from '@foodstack/api-client';

const CONFLICTS: Record<string, string> = {
  STORE_VERSION_CONFLICT:
    'Someone else changed this branch meanwhile. Reload to see their changes.',
  BRAND_VERSION_CONFLICT: 'Someone else changed this brand meanwhile. Reload to see their changes.',
  MENU_VERSION_CONFLICT:
    'Someone else changed this menu entry meanwhile. Reload to see their changes.',
  PRICING_VERSION_CONFLICT:
    'Someone else changed the pricing meanwhile. Reload to see their changes.',
  SETTINGS_VERSION_CONFLICT:
    'Someone else changed the settings meanwhile. Reload to see their changes.',
  SECTION_NOT_EMPTY: 'Move or delete the items in this section first.',
  OPERATOR_DELIVERY_NOT_ALLOWED: 'The operator has not enabled rider delivery for this branch.',
  STORE_NOT_READY: 'The branch is not ready to go live yet.',
};

/** A sentence for the person from an API error, using field errors when the server sent them. */
export function describeError(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'The server could not be reached. Check your connection and try again.';
    }
    const problem = error.error as (Partial<Problem> & { errors?: Record<string, string> }) | null;
    if (problem?.code && CONFLICTS[problem.code]) {
      return CONFLICTS[problem.code];
    }
    const fields = problem?.errors ? Object.entries(problem.errors) : [];
    if (fields.length > 0) {
      return fields.map(([field, message]) => `${humanize(field)} ${message}`).join('. ') + '.';
    }
    return problem?.detail ?? fallback;
  }
  return fallback;
}

function humanize(field: string): string {
  const last = field.split('.').pop() ?? field;
  const words = last
    .replace(/\[\d+\]/g, '')
    .replace(/([A-Z])/g, ' $1')
    .toLowerCase()
    .trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
