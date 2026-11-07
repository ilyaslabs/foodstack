import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RUNTIME_CONFIG } from './runtime-config';

export type LedgerParty = 'STORE' | 'RIDER';

export type PayoutDirection = 'OPERATOR_PAYS' | 'PARTY_PAYS' | 'SETTLED';

/**
 * What a line was for: FOOD, TAX, DELIVERY, TIP, DISCOUNT, COMMISSION, SERVICE_FEE, REFUND, CASH,
 * COLLECTED, EARNING, SETTLEMENT or PAYMENT.
 */
export type LedgerKind = string;

/** One branch or rider on a payout report (PRD FI-3, FI-7). Amounts in minor units. */
export interface PayoutRow {
  accountId: string;
  partyType: LedgerParty;
  partyId: string;
  name: string;
  brandId: string | null;
  /** Balance before the period: positive, the operator owes the party. */
  openingMinor: number;
  /** What moved in the period, by kind. */
  kinds: Record<LedgerKind, number>;
  closingMinor: number;
  settledAfterMinor: number;
  /** Left to settle for the period: positive, the operator pays; negative, the party pays. */
  dueMinor: number;
  direction: PayoutDirection;
}

export interface PayoutReport {
  partyType: LedgerParty;
  from: string;
  to: string;
  brandId: string | null;
  currency: string;
  rows: PayoutRow[];
  totalKinds: Record<LedgerKind, number>;
  totalOpeningMinor: number;
  totalClosingMinor: number;
  totalDueMinor: number;
  operatorPaysMinor: number;
  partiesPayMinor: number;
}

export interface OperatorSummary {
  from: string;
  to: string;
  currency: string;
  /** Each operator account's movement, such as `operator:commission`. */
  accounts: Record<string, number>;
  netMinor: number;
  /** Money on its way at the end of the period, by clearing account. */
  heldMinor: Record<string, number>;
}

export interface StatementLine {
  transactionId: string;
  type: string;
  orderId: string | null;
  day: string;
  /** ISO instant. */
  at: string;
  kinds: Record<LedgerKind, number>;
  amountMinor: number;
  note: string | null;
}

export interface StatementPage {
  accountId: string;
  name: string;
  balanceMinor: number;
  currency: string;
  lines: StatementLine[];
  nextBefore: string | null;
}

export interface Settlement {
  id: string;
  accountId: string;
  partyType: LedgerParty;
  partyId: string;
  name: string;
  periodTo: string;
  /** What the operator paid; negative when the party paid the operator. */
  amountMinor: number;
  currency: string;
  reference: string;
  settledBy: string | null;
  /** ISO instant. */
  settledAt: string;
}

export interface PayoutQuery {
  party: LedgerParty;
  /** yyyy-MM-dd */
  from: string;
  /** yyyy-MM-dd */
  to: string;
  brandId?: string | null;
}

/** The ledger (PRD 4.11): payouts, statements and settlement. Access is decided by the server. */
@Injectable({ providedIn: 'root' })
export class LedgerClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  private get base(): string {
    return `${this.config.apiUrl}/api/v1/ledger`;
  }

  payouts(query: PayoutQuery): Observable<PayoutReport> {
    return this.http.get<PayoutReport>(`${this.base}/payouts`, { params: payoutParams(query) });
  }

  /** The same report as a CSV or PDF file. */
  download(query: PayoutQuery, format: 'csv' | 'pdf'): Observable<Blob> {
    return this.http.get(`${this.base}/payouts.${format}`, {
      params: payoutParams(query),
      responseType: 'blob',
    });
  }

  operator(from: string, to: string): Observable<OperatorSummary> {
    return this.http.get<OperatorSummary>(`${this.base}/operator`, { params: { from, to } });
  }

  statement(
    accountId: string,
    options: { from?: string; to?: string; before?: string; limit?: number } = {},
  ): Observable<StatementPage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(options)) {
      if (value !== undefined && value !== null && value !== '')
        params = params.set(key, String(value));
    }
    return this.http.get<StatementPage>(
      `${this.base}/accounts/${encodeURIComponent(accountId)}/statement`,
      { params },
    );
  }

  settlements(accountId?: string, limit = 50): Observable<Settlement[]> {
    let params = new HttpParams().set('limit', String(limit));
    if (accountId) params = params.set('accountId', accountId);
    return this.http.get<Settlement[]>(`${this.base}/settlements`, { params });
  }

  settle(accountId: string, to: string, reference: string): Observable<Settlement> {
    return this.http.post<Settlement>(`${this.base}/settlements`, { accountId, to, reference });
  }
}

function payoutParams(query: PayoutQuery): HttpParams {
  let params = new HttpParams()
    .set('party', query.party)
    .set('from', query.from)
    .set('to', query.to);
  if (query.brandId) params = params.set('brandId', query.brandId);
  return params;
}
