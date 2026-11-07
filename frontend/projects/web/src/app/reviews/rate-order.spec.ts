import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { MediaClient, Order, ReviewClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';
import { signal } from '@angular/core';

import { RateOrder } from './rate-order';

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('RateOrder', () => {
  it('sends the store and rider stars, words and tags together, then thanks the customer', async () => {
    // given
    const submit = vi.fn(() =>
      of({
        orderId: 'o1',
        canRateStore: false,
        canRateRider: false,
        riderName: 'Hamza',
        closesAt: 1_763_000_000,
        storeReview: {
          id: 'r1',
          storeId: 's1',
          storeName: 'Spice Route',
          rating: 4,
          text: 'Hot and fresh',
          photoThumbUrl: null,
          photoUrl: null,
          customerName: 'Ayesha K.',
          createdAt: 1_762_000_000,
          reply: null,
          status: 'PUBLISHED' as const,
          orderNumber: null,
          flagged: false,
          originalText: null,
          hiddenReason: null,
        },
        riderRating: 5,
      }),
    );
    TestBed.configureTestingModule({
      imports: [RateOrder],
      providers: [
        {
          provide: ReviewClient,
          useValue: {
            forOrder: () =>
              of({
                orderId: 'o1',
                canRateStore: true,
                canRateRider: true,
                riderName: 'Hamza',
                closesAt: 1_763_000_000,
                storeReview: null,
                riderRating: null,
              }),
            submit,
          },
        },
        { provide: MediaClient, useValue: {} },
        { provide: LocaleStore, useValue: { language: signal('en') } },
      ],
    });
    const fixture = TestBed.createComponent(RateOrder);
    fixture.componentRef.setInput('order', { id: 'o1', store: { name: 'Spice Route' } } as Order);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const groups = element.querySelectorAll('[role=radiogroup]');
    (groups[0].querySelectorAll('button')[3] as HTMLButtonElement).click();
    (groups[1].querySelectorAll('button')[4] as HTMLButtonElement).click();
    const text = element.querySelector('textarea') as HTMLTextAreaElement;
    text.value = ' Hot and fresh ';
    text.dispatchEvent(new Event('input'));
    (
      [...element.querySelectorAll('.tag')].find((b) =>
        b.textContent?.includes('On time'),
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    // when
    (element.querySelector('button[type=submit]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    // then
    expect(submit).toHaveBeenCalledWith({
      orderId: 'o1',
      storeRating: 4,
      text: 'Hot and fresh',
      photoMediaId: null,
      riderRating: 5,
      riderTags: ['ON_TIME'],
    });
    expect(element.textContent).toContain('Thanks for your rating!');
    expect(element.textContent).toContain('You rated Hamza 5 out of 5.');
  });
});
