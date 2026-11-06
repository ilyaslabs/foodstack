import { Component, DOCUMENT, ElementRef, OnDestroy, afterNextRender, input } from '@angular/core';

import { Order } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';
import { inject } from '@angular/core';

import { clock } from './order-labels';

/** The kitchen ticket: large, black on white, only what the kitchen needs. Shown only when printing. */
@Component({
  selector: 'app-order-ticket',
  template: `
    @if (order(); as o) {
      <article class="ticket" [class.narrow]="paperMm() === 58">
        <h1>#{{ o.number }}</h1>
        <p class="meta">
          {{ o.fulfilment === 'PICKUP' ? 'PICKUP' : 'DELIVERY' }} ·
          {{ clock(o.placedAt, o.store.timezone) }}
          @if (o.readyBy) {
            · ready by {{ clock(o.readyBy, o.store.timezone) }}
          }
        </p>
        <p class="meta">
          {{ o.customerName }} ·
          {{ o.paymentMethod === 'CASH' ? 'CASH ' + money(o.totals.totalMinor) : 'PAID' }}
        </p>
        <ul>
          @for (line of o.lines; track $index) {
            <li>
              <strong>{{ line.quantity }} × {{ locale.pick(line.name) }}</strong>
              @for (option of line.options; track $index) {
                <div class="option">+ {{ locale.pick(option.name) }}</div>
              }
              @if (line.note) {
                <div class="note">NOTE: {{ line.note }}</div>
              }
            </li>
          }
        </ul>
        @if (o.destination?.instructions) {
          <p class="note">Rider: {{ o.destination?.instructions }}</p>
        }
      </article>
    }
  `,
  styles: `
    :host {
      display: none;
    }
    @media print {
      :host-context(body.fs-printing-ticket) {
        display: block;
        page: kitchen-ticket;
      }
      .ticket {
        font-family: 'Roboto Mono', ui-monospace, monospace;
        color: #000;
        background: #fff;
        /* An 80 mm roll prints about 72 mm wide; a little inner padding keeps text off the edge. */
        box-sizing: border-box;
        inline-size: 72mm;
        padding: 2mm 3mm;
        font-size: 13pt;
        overflow-wrap: anywhere;
      }
      h1 {
        font-size: 28pt;
        margin: 0 0 4pt;
      }
      .meta {
        margin: 0 0 4pt;
      }
      ul {
        list-style: none;
        padding: 0;
        margin: 8pt 0;
        border-block: 1px dashed #000;
      }
      li {
        padding-block: 6pt;
      }
      .option {
        padding-inline-start: 12pt;
      }
      .note {
        font-weight: 700;
      }
      /* A 58 mm roll prints about 48 mm wide, so the text is smaller to keep a dish on one line. */
      .ticket.narrow {
        inline-size: 48mm;
        padding: 1mm 2mm;
        font-size: 10pt;
      }
      .ticket.narrow h1 {
        font-size: 20pt;
      }
    }
  `,
})
export class OrderTicket implements OnDestroy {
  readonly order = input<Order | null>(null);
  /** Paper roll width in mm: 80 or 58 (the branch's setting). */
  readonly paperMm = input<80 | 58>(80);
  protected readonly locale = inject(LocaleStore);
  protected readonly clock = clock;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    // The ticket lives directly under body, so printing can drop the rest of the page.
    const document = inject(DOCUMENT);
    afterNextRender(() => document.body.appendChild(this.host));
  }

  ngOnDestroy(): void {
    this.host.remove();
  }

  protected money(minor: number): string {
    return this.locale.money(minor, this.order()?.currency);
  }
}
