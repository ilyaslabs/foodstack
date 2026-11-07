import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';

import { LedgerClient, PayoutReport, PayoutRow, StoreClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { Session } from '../shared/session';
import { FinancePage } from './finance-page';
import { columnTotal, directionLabel, payoutColumns, presetPeriod } from './finance-labels';

const ROW: PayoutRow = {
  accountId: 'store:s1',
  partyType: 'STORE',
  partyId: 's1',
  name: 'Napoli DHA',
  brandId: 'b1',
  openingMinor: 0,
  kinds: { FOOD: 200_000, TAX: 10_000, COMMISSION: -21_000, REFUND: -5_000 },
  closingMinor: 184_000,
  settledAfterMinor: 0,
  dueMinor: 184_000,
  direction: 'OPERATOR_PAYS',
};

const OWES: PayoutRow = {
  ...ROW,
  accountId: 'store:s2',
  partyId: 's2',
  name: 'Napoli Gulberg',
  kinds: { CASH: -107_000, FOOD: 100_000, COMMISSION: -10_000 },
  closingMinor: -17_000,
  dueMinor: -17_000,
  direction: 'PARTY_PAYS',
};

const REPORT: PayoutReport = {
  partyType: 'STORE',
  from: '2026-11-02',
  to: '2026-11-08',
  brandId: null,
  currency: 'PKR',
  rows: [ROW, OWES],
  totalKinds: { FOOD: 300_000, TAX: 10_000, COMMISSION: -31_000, REFUND: -5_000, CASH: -107_000 },
  totalOpeningMinor: 0,
  totalClosingMinor: 167_000,
  totalDueMinor: 167_000,
  operatorPaysMinor: 184_000,
  partiesPayMinor: 17_000,
};

describe('FinancePage', () => {
  async function render(permissions: string[], brandIds: string[] = []) {
    const payouts = vi.fn(() => of(REPORT));
    const operator = vi.fn(() =>
      of({
        from: '2026-11-02',
        to: '2026-11-08',
        currency: 'PKR',
        accounts: { 'operator:commission': 31_000, 'operator:rider-pay': -10_500 },
        netMinor: 20_500,
        heldMinor: {},
      }),
    );
    const settle = vi.fn(() => of({}));
    const me = { permissions, brandIds, storeIds: [] };
    TestBed.configureTestingModule({
      imports: [FinancePage],
      providers: [
        { provide: LedgerClient, useValue: { payouts, operator, settle, download: vi.fn() } },
        {
          provide: StoreClient,
          useValue: {
            list: () =>
              of([
                { id: 's1', brandId: 'b1', brandName: 'Napoli' },
                { id: 's9', brandId: 'b2', brandName: 'Spice Route' },
              ]),
          },
        },
        {
          provide: Session,
          useValue: {
            me: () => me,
            me$: of(me),
            can: (p: string) => permissions.includes(p),
          },
        },
        {
          provide: LocaleStore,
          useValue: {
            money: (minor: number) => `Rs ${(minor / 100).toFixed(2)}`,
            currency: () => 'PKR',
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(FinancePage);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    return { fixture, payouts, operator, settle, element: fixture.nativeElement as HTMLElement };
  }

  it('shows finance every branch netted, who pays whom, the totals and the operator earnings', async () => {
    // when
    const { element, payouts, operator } = await render(['ledger:read', 'ledger:settle']);

    // then
    expect(element.querySelector('h1')?.textContent).toContain('Finance');
    expect(payouts).toHaveBeenCalledWith(
      expect.objectContaining({ party: 'STORE', brandId: null }),
    );
    expect(operator).toHaveBeenCalled();
    const rows = element.querySelectorAll('.row:not(.head):not(.total)');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Napoli DHA');
    expect(rows[0].textContent).toContain('We pay');
    expect(rows[0].textContent).toContain('Rs 2100.00');
    expect(rows[1].textContent).toContain('They pay');
    expect(rows[1].textContent).toContain('Rs 170.00');
    expect(element.querySelector('.row.total')?.textContent).toContain('Rs 1670.00');
    expect(element.textContent).toContain('We pay out');
    expect(element.textContent).toContain('Operator earnings');
    expect(element.textContent).toContain('Commission Rs 310.00');
    expect(element.textContent).toContain('Riders');
  });

  it('settles a row with the reference typed', async () => {
    // given
    const { fixture, element, settle } = await render(['ledger:read', 'ledger:settle']);
    const dialog = fixture.debugElement.injector.get(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of('TRX-42') } as never);

    // when
    const button = [...element.querySelectorAll('.row button')].find((b) =>
      b.textContent?.includes('Settle'),
    ) as HTMLButtonElement;
    button.click();
    await new Promise((resolve) => setTimeout(resolve));

    // then
    expect(settle).toHaveBeenCalledWith('store:s1', expect.any(String), 'TRX-42');
  });

  it("shows an owner their brand's branches from the branch's side, without settling or riders", async () => {
    // when
    const { element, payouts, operator } = await render(['ledger:store', 'store:portal'], ['b1']);

    // then
    expect(element.querySelector('h1')?.textContent).toContain('Payouts');
    expect(payouts).toHaveBeenCalledWith(
      expect.objectContaining({ party: 'STORE', brandId: 'b1' }),
    );
    expect(operator).not.toHaveBeenCalled();
    expect(element.textContent).toContain('Due to you');
    expect(element.textContent).toContain('You owe');
    expect(element.textContent).not.toContain('Riders');
    expect(
      [...element.querySelectorAll('button')].some((b) => b.textContent?.trim() === 'Settle'),
    ).toBe(false);
  });
});

describe('finance labels', () => {
  it('builds weeks from Monday and calendar months', () => {
    const thursday = new Date(2026, 10, 12);
    expect(presetPeriod('this-week', thursday)).toEqual({ from: '2026-11-09', to: '2026-11-12' });
    expect(presetPeriod('last-week', thursday)).toEqual({ from: '2026-11-02', to: '2026-11-08' });
    expect(presetPeriod('this-month', thursday)).toEqual({ from: '2026-11-01', to: '2026-11-12' });
    expect(presetPeriod('last-month', thursday)).toEqual({ from: '2026-10-01', to: '2026-10-31' });
  });

  it('adds up columns and names directions from either side', () => {
    const [sales, deductions] = payoutColumns('STORE');
    expect(columnTotal(ROW.kinds, sales!)).toBe(210_000);
    expect(columnTotal(ROW.kinds, deductions!)).toBe(-21_000);
    expect(payoutColumns('RIDER').map((c) => c.label)).toContain('Cash carried');
    expect(directionLabel(OWES, true)).toBe('They pay');
    expect(directionLabel(OWES, false)).toBe('You owe');
    expect(directionLabel({ ...ROW, direction: 'SETTLED' }, false)).toBe('Settled');
  });
});
