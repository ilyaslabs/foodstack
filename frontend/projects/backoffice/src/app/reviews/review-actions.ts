import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';

import { Review, ReviewClient } from '@foodstack/api-client';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';

/** Hide (with a reason), show again, or keep a flagged review; shared by both review screens. */
export class ReviewActions {
  constructor(
    private readonly reviews: ReviewClient,
    private readonly dialog: MatDialog,
    private readonly snackBar: MatSnackBar,
  ) {}

  /** Resolves to the changed review, or null when cancelled or refused. */
  async run(action: 'hide' | 'show' | 'keep', review: Review): Promise<Review | null> {
    try {
      if (action === 'hide') {
        const reason = await confirmAction(this.dialog, {
          title: 'Hide this review?',
          message:
            'Customers stop seeing it and it no longer counts in the rating. The branch sees your reason.',
          confirm: 'Hide',
          danger: true,
          inputLabel: 'Reason',
          inputHint: 'For example: abusive language, not about this order',
          inputRequired: true,
        });
        if (reason === null) return null;
        const hidden = await firstValueFrom(this.reviews.hide(review.id, reason));
        this.snackBar.open('Review hidden.', undefined, { duration: 3000 });
        return hidden;
      }
      if (action === 'show') {
        const shown = await firstValueFrom(this.reviews.show(review.id));
        this.snackBar.open('Review shown again.', undefined, { duration: 3000 });
        return shown;
      }
      const kept = await firstValueFrom(this.reviews.keep(review.id));
      this.snackBar.open('Kept shown and taken out of the queue.', undefined, { duration: 3000 });
      return kept;
    } catch (error) {
      this.snackBar.open(describeError(error, 'The review could not be changed.'), undefined, {
        duration: 5000,
      });
      return null;
    }
  }
}
