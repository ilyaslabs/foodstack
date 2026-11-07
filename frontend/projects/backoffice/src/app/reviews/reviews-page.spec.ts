import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { Review, ReviewClient } from '@foodstack/api-client';

import { ReviewsPage } from './reviews-page';

const FLAGGED: Review = {
  id: 'r1',
  storeId: 's1',
  storeName: 'Napoli Slice - DHA Phase 5',
  rating: 1,
  text: '***** service',
  photoThumbUrl: null,
  photoUrl: null,
  customerName: 'Ayesha K.',
  createdAt: 1_762_000_000,
  reply: null,
  status: 'PUBLISHED',
  orderNumber: '7K3Q-9XAB',
  flagged: true,
  originalText: 'awful service',
  hiddenReason: null,
};

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('ReviewsPage', () => {
  it('shows the queue with the text as written, and drops a review once hidden with a reason', async () => {
    // given
    const hide = vi.fn(() =>
      of({ ...FLAGGED, status: 'HIDDEN', flagged: false, hiddenReason: 'Abusive' } as Review),
    );
    TestBed.configureTestingModule({
      imports: [ReviewsPage],
      providers: [
        {
          provide: ReviewClient,
          useValue: { moderation: () => of({ items: [FLAGGED], next: null }), hide },
        },
        {
          provide: MatDialog,
          useValue: { open: () => ({ afterClosed: () => of('Abusive') }) },
        },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(ReviewsPage);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('***** service');
    expect(element.textContent).toContain('awful service');
    expect(element.textContent).toContain('Napoli Slice - DHA Phase 5');

    // when
    const button = [...element.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('Hide'),
    ) as HTMLButtonElement;
    button.click();
    await settle();
    fixture.detectChanges();

    // then
    expect(hide).toHaveBeenCalledWith('r1', 'Abusive');
    expect(element.textContent).toContain('Nothing to check');
  });
});
