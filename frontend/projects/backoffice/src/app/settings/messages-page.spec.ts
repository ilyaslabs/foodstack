import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { MessageTemplate, NotificationClient } from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { MessagesPage } from './messages-page';

const TEMPLATE: MessageTemplate = {
  id: 'order-accepted',
  placeholders: ['store', 'number', 'minutes'],
  required: [],
  languages: [
    {
      language: 'en',
      builtIn: { title: '{{store}} accepted your order', body: 'Order {{number}}', subject: 'S' },
      builtInLanguage: 'en',
      edited: null,
      version: 0,
      updatedAt: null,
    },
    {
      language: 'ur',
      builtIn: { title: 'UR title', body: 'UR body', subject: 'UR subject' },
      builtInLanguage: 'ur',
      edited: { title: null, body: 'اپنا متن {{number}}', subject: null },
      version: 2,
      updatedAt: '2026-11-07T10:00:00Z',
    },
  ],
};

describe('MessagesPage', () => {
  async function render() {
    const saveTemplate = vi.fn(() =>
      of({
        ...TEMPLATE.languages[0],
        edited: { title: 'Hi', body: null, subject: null },
        version: 1,
      }),
    );
    const resetTemplate = vi.fn(() => of({ ...TEMPLATE.languages[1], edited: null, version: 0 }));
    TestBed.configureTestingModule({
      imports: [MessagesPage],
      providers: [
        {
          provide: NotificationClient,
          useValue: {
            templates: () =>
              of([
                {
                  id: 'order-accepted',
                  placeholders: TEMPLATE.placeholders,
                  editedLanguages: ['ur'],
                },
                { id: 'login-code', placeholders: ['code'], editedLanguages: [] },
              ]),
            template: () => of(TEMPLATE),
            previewTemplate: () => of({ subject: 'S', title: 'T', body: 'B', html: '<p>x</p>' }),
            saveTemplate,
            resetTemplate,
          },
        },
        {
          provide: LocaleStore,
          useValue: {
            language: signal('en'),
            languageName: (code: string) => ({ en: 'English', ur: 'اردو' })[code] ?? code,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(MessagesPage);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    return { fixture, saveTemplate, resetTemplate, element: fixture.nativeElement as HTMLElement };
  }

  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('groups messages in operator words and marks edited ones', async () => {
    // when
    const { element } = await render();

    // then
    const groups = Array.from(element.querySelectorAll('.group')).map((g) => g.textContent?.trim());
    expect(groups).toEqual(['Customers', 'Accounts']);
    expect(element.querySelector('.message')?.textContent).toContain('Order accepted');
    expect(element.querySelector('.message .edited')?.textContent).toContain('Edited');
  });

  it('saves the wording with the version read, and goes back to the built-in text', async () => {
    // given
    const { fixture, element, saveTemplate, resetTemplate } = await render();
    (element.querySelector('.message') as HTMLButtonElement).click();
    await settle(fixture);
    const title = element.querySelector('input[name=title]') as HTMLInputElement;
    expect(title.placeholder).toBe('{{store}} accepted your order');

    // when
    title.value = 'Hi';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (element.querySelector('button[type=submit]') as HTMLButtonElement).click();
    await settle(fixture);

    // then
    expect(saveTemplate).toHaveBeenCalledWith(
      'order-accepted',
      'en',
      { title: 'Hi', body: '', subject: '' },
      0,
    );

    // when: Urdu has an edit, which can go back to the built-in text
    const toggles = element.querySelectorAll('mat-button-toggle button');
    (toggles[1] as HTMLButtonElement).click();
    await settle(fixture);
    expect((element.querySelector('textarea[name=body]') as HTMLTextAreaElement).value).toBe(
      'اپنا متن {{number}}',
    );
    const reset = Array.from(element.querySelectorAll('button')).find((b) =>
      b.textContent?.includes("Use FoodStack's text"),
    ) as HTMLButtonElement;
    reset.click();
    await settle(fixture);

    // then
    expect(resetTemplate).toHaveBeenCalledWith('order-accepted', 'ur');
  });
});
