import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';

import { RiderClient, RiderPapers as Papers } from '@foodstack/api-client';

import { RiderPapers } from './rider-papers';

const EMPTY: Papers = {
  riderId: 'r1',
  documents: [],
  payout: null,
  identityVerified: false,
  payoutSet: false,
  complete: false,
  version: 0,
};

const FULL: Papers = {
  riderId: 'r1',
  documents: [
    {
      id: 'd1',
      type: 'NATIONAL_ID',
      numberLast4: '5671',
      frontUrl: 'https://media.example/front',
      backUrl: null,
      expiresOn: '2030-01-31',
      expired: false,
      addedAt: 1_800_000_000,
      verified: false,
      verifiedAt: null,
    },
  ],
  payout: {
    method: 'BANK',
    accountName: 'Usman Ali',
    provider: 'City Bank',
    accountLast4: '6702',
    updatedAt: 1_800_000_000,
  },
  identityVerified: false,
  payoutSet: true,
  complete: false,
  version: 2,
};

describe('RiderPapers', () => {
  async function render(papers: Papers, confirm = true) {
    const verifyDocument = vi.fn(() =>
      of({
        ...papers,
        identityVerified: true,
        complete: true,
        documents: papers.documents.map((d) => ({
          ...d,
          verified: true,
          verifiedAt: 1_800_000_100,
        })),
      }),
    );
    TestBed.configureTestingModule({
      imports: [RiderPapers],
      providers: [
        { provide: RiderClient, useValue: { papers: () => of(papers), verifyDocument } },
        {
          provide: MatDialog,
          useValue: { open: () => ({ afterClosed: () => of(confirm ? '' : null) }) },
        },
      ],
    });
    const fixture = TestBed.createComponent(RiderPapers);
    fixture.componentRef.setInput('riderId', 'r1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, verifyDocument, element: fixture.nativeElement as HTMLElement };
  }

  it('says what is missing when a rider has no papers yet', async () => {
    // when
    const { element } = await render(EMPTY);

    // then
    expect(element.querySelector('.chip.warn')?.textContent).toContain('ID and payout missing');
    expect(element.textContent).toContain('No documents yet');
    expect(element.textContent).toContain('Not set');
  });

  it('shows documents and payout by their last 4 characters only', async () => {
    // when
    const { element } = await render(FULL);

    // then
    expect(element.textContent).toContain('National ID card');
    expect(element.textContent).toContain('Ends in 5671');
    expect(element.textContent).toContain('City Bank · ending 6702');
    expect(element.querySelector('.chip.warn')?.textContent).toContain('No checked ID');
  });

  it('marking a document checked completes the papers', async () => {
    // given
    const { fixture, verifyDocument, element } = await render(FULL);
    const button = Array.from(element.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Mark checked'),
    )!;

    // when
    button.click();
    await fixture.whenStable();
    fixture.detectChanges();

    // then
    expect(verifyDocument).toHaveBeenCalledWith('r1', 'd1');
    expect(element.textContent).toContain('Complete');
    expect(element.textContent).toContain('Checked');
  });
});
