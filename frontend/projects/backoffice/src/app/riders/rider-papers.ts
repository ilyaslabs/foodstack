import { Component, OnInit, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { RiderClient, RiderPaperDocument, RiderPapers as Papers } from '@foodstack/api-client';
import { Icon } from '@foodstack/ui';

import { confirmAction } from '../shared/confirm-dialog';
import { describeError } from '../shared/errors';
import {
  DOCUMENT_LABELS,
  DocumentDialog,
  PAYOUT_LABELS,
  PayoutDialog,
  PayoutDialogData,
} from './papers-dialogs';

/**
 * An operator rider's papers (PRD DR-1): ID and vehicle documents, checked against their photos
 * by staff, and where the rider's pay goes. Complete means a checked, unexpired ID card or
 * passport and payout details.
 */
@Component({
  selector: 'app-rider-papers',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatProgressBarModule, Icon],
  template: `
    <mat-card appearance="outlined" class="papers">
      <header class="head">
        <div>
          <h2>Papers</h2>
          <p>ID documents and payout details. Numbers are kept encrypted.</p>
        </div>
        @if (papers(); as p) {
          @if (p.complete) {
            <span class="chip live">Complete</span>
          } @else {
            <span class="chip warn">{{ missing(p) }}</span>
          }
        }
      </header>
      @if (papers(); as p) {
        <section>
          <div class="section-head">
            <h3>Documents</h3>
            <button mat-stroked-button type="button" (click)="addDocument()">
              <fs-icon name="add" /> Add document
            </button>
          </div>
          @if (p.documents.length === 0) {
            <p class="none">No documents yet. Add the rider's ID card or passport first.</p>
          } @else {
            <ul class="docs" role="list">
              @for (d of p.documents; track d.id) {
                <li class="doc">
                  <div class="photos">
                    @for (url of [d.frontUrl, d.backUrl]; track $index) {
                      @if (url) {
                        <a [href]="url" target="_blank" rel="noopener" class="thumb">
                          <img
                            [src]="url"
                            [alt]="($index === 0 ? 'Front of ' : 'Back of ') + labels[d.type]"
                          />
                        </a>
                      }
                    }
                  </div>
                  <div class="doc-main">
                    <span class="doc-title">{{ labels[d.type] }}</span>
                    <span class="doc-meta">
                      Ends in {{ d.numberLast4 }}
                      @if (d.expiresOn) {
                        · {{ d.expired ? 'expired' : 'expires' }}
                        {{ d.expiresOn | date: 'd MMM y' }}
                      }
                    </span>
                    <span class="doc-meta">
                      @if (d.verified) {
                        Checked {{ d.verifiedAt! * 1000 | date: 'd MMM y' }}
                      } @else {
                        Added {{ d.addedAt * 1000 | date: 'd MMM y' }}, not checked yet
                      }
                    </span>
                  </div>
                  <div class="doc-side">
                    @if (d.expired) {
                      <span class="chip closed">Expired</span>
                    } @else if (d.verified) {
                      <span class="chip live">Checked</span>
                    } @else {
                      <button mat-flat-button type="button" (click)="verify(d)">
                        <fs-icon name="check" /> Mark checked
                      </button>
                    }
                    <button
                      mat-icon-button
                      type="button"
                      [attr.aria-label]="'Remove ' + labels[d.type]"
                      (click)="remove(d)"
                    >
                      <fs-icon name="delete" />
                    </button>
                  </div>
                </li>
              }
            </ul>
          }
        </section>
        <section>
          <div class="section-head">
            <h3>Payout</h3>
            <button mat-stroked-button type="button" (click)="editPayout()">
              <fs-icon name="edit" /> {{ p.payout ? 'Change' : 'Add payout details' }}
            </button>
          </div>
          @if (p.payout; as pay) {
            <dl class="payout">
              <div>
                <dt>{{ payoutLabels[pay.method] }}</dt>
                <dd>{{ pay.provider }} · ending {{ pay.accountLast4 }}</dd>
              </div>
              <div>
                <dt>Account holder</dt>
                <dd>{{ pay.accountName }}</dd>
              </div>
              <div>
                <dt>Updated</dt>
                <dd>{{ pay.updatedAt * 1000 | date: 'd MMM y' }}</dd>
              </div>
            </dl>
          } @else {
            <p class="none">Not set. Rider pay reports need it to pay out.</p>
          }
        </section>
      } @else if (failed()) {
        <p class="none">The papers could not be loaded.</p>
      } @else {
        <mat-progress-bar mode="indeterminate" />
      }
    </mat-card>
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    .papers {
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .head,
    .section-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }
    h2 {
      margin: 0;
      font: var(--mat-sys-title-medium);
    }
    h3 {
      margin: 0;
      font: var(--mat-sys-title-small);
      align-self: center;
    }
    .head p,
    .none {
      margin: 4px 0 0;
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    section {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding-block-start: 16px;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .chip.warn {
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .docs {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .doc {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 12px;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
      flex-wrap: wrap;
    }
    .photos {
      display: flex;
      gap: 6px;
      flex: none;
    }
    .thumb {
      display: block;
      inline-size: 72px;
      block-size: 48px;
      border-radius: 8px;
      overflow: hidden;
      background: var(--mat-sys-surface-container-high);
    }
    .thumb img {
      inline-size: 100%;
      block-size: 100%;
      object-fit: cover;
    }
    .doc-main {
      display: flex;
      flex-direction: column;
      flex: 1 1 200px;
      min-inline-size: 0;
    }
    .doc-title {
      font: var(--mat-sys-title-small);
    }
    .doc-meta {
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .doc-side {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-inline-start: auto;
    }
    .payout {
      margin: 0;
    }
    .payout div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 0;
      border-block-end: 1px solid var(--mat-sys-outline-variant);
    }
    .payout div:last-child {
      border: 0;
    }
    dt {
      font: var(--mat-sys-body-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 0;
      font: var(--mat-sys-body-large);
      text-align: end;
    }
  `,
})
export class RiderPapers implements OnInit {
  private readonly riders = inject(RiderClient);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly riderId = input.required<string>();

  protected readonly labels = DOCUMENT_LABELS;
  protected readonly payoutLabels = PAYOUT_LABELS;
  protected readonly papers = signal<Papers | null>(null);
  protected readonly failed = signal(false);

  async ngOnInit(): Promise<void> {
    try {
      this.papers.set(await firstValueFrom(this.riders.papers(this.riderId())));
    } catch {
      this.failed.set(true);
    }
  }

  protected missing(p: Papers): string {
    if (!p.identityVerified && !p.payoutSet) return 'ID and payout missing';
    return p.identityVerified ? 'Payout missing' : 'No checked ID';
  }

  protected async addDocument(): Promise<void> {
    const saved = await firstValueFrom(
      this.dialog
        .open(DocumentDialog, { data: this.riderId(), width: '560px', maxWidth: '94vw' })
        .afterClosed(),
    );
    if (saved) this.papers.set(saved);
  }

  protected async editPayout(): Promise<void> {
    const data: PayoutDialogData = {
      riderId: this.riderId(),
      payout: this.papers()?.payout ?? null,
    };
    const saved = await firstValueFrom(
      this.dialog.open(PayoutDialog, { data, width: '520px', maxWidth: '94vw' }).afterClosed(),
    );
    if (saved) this.papers.set(saved);
  }

  protected async verify(d: RiderPaperDocument): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: `Mark the ${this.labels[d.type].toLowerCase()} as checked?`,
      message: 'Confirm the photos are clear and the details match the rider.',
      confirm: 'Mark checked',
    });
    if (ok === null) return;
    await this.change(() => this.riders.verifyDocument(this.riderId(), d.id));
  }

  protected async remove(d: RiderPaperDocument): Promise<void> {
    const ok = await confirmAction(this.dialog, {
      title: `Remove the ${this.labels[d.type].toLowerCase()}?`,
      message: 'Its photos are deleted too.',
      confirm: 'Remove',
      danger: true,
    });
    if (ok === null) return;
    await this.change(() => this.riders.removeDocument(this.riderId(), d.id));
  }

  private async change(call: () => ReturnType<RiderClient['papers']>): Promise<void> {
    try {
      this.papers.set(await firstValueFrom(call()));
    } catch (error) {
      this.snackBar.open(describeError(error, 'The change was not saved.'), 'OK', {
        duration: 6000,
      });
    }
  }
}
