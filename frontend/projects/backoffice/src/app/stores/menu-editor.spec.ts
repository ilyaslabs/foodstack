import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';

import {
  BranchPrices,
  BrandClient,
  EditorMenu,
  Menu,
  OrderingSettingsClient,
  StoreClient,
} from '@foodstack/api-client';
import { LocaleStore } from '@foodstack/ui';

import { Session } from '../shared/session';
import { MenuEditor } from './menu-editor';
import { StoreContext } from './store-context';

const EDITOR: EditorMenu = {
  brandId: 'b1',
  currency: 'PKR',
  languages: ['en', 'ar', 'ur'],
  sections: [
    {
      id: 'sec1',
      name: { en: 'Biryani' },
      sortOrder: 0,
      version: 1,
      items: [
        {
          id: 'i1',
          sectionId: 'sec1',
          name: { en: 'Chicken biryani', ar: 'برياني دجاج', ur: 'چکن بریانی' },
          description: {},
          priceMinor: 65000,
          imageMediaId: null,
          imageUrls: {},
          dietaryTags: ['halal'],
          modifierGroups: [],
          branchIds: null,
          sortOrder: 0,
          version: 1,
        },
        {
          id: 'i2',
          sectionId: 'sec1',
          name: { en: 'Mutton biryani' },
          description: {},
          priceMinor: 95000,
          imageMediaId: null,
          imageUrls: {},
          dietaryTags: [],
          modifierGroups: [],
          branchIds: null,
          sortOrder: 1,
          version: 1,
        },
      ],
    },
  ],
};

const BRANCH_MENU: Menu = {
  storeId: 's1',
  brandId: 'b1',
  currency: 'PKR',
  sections: [
    {
      id: 'sec1',
      name: { en: 'Biryani' },
      items: [
        { ...EDITOR.sections[0].items[0], soldOut: false, modifierGroups: [] },
        { ...EDITOR.sections[0].items[1], soldOut: true, modifierGroups: [] },
      ],
    },
  ],
};

/** Lets the component's awaited client calls finish. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('MenuEditor', () => {
  async function render(
    setSoldOut = vi.fn(() => of(undefined)),
    as: { operator: boolean; prices?: BranchPrices; approval?: boolean } = { operator: true },
  ) {
    TestBed.configureTestingModule({
      imports: [MenuEditor],
      providers: [
        {
          provide: BrandClient,
          useValue: { menu: () => of(EDITOR), priceChanges: () => of([]), branches: () => of([]) },
        },
        {
          provide: StoreClient,
          useValue: {
            menu: () => of(BRANCH_MENU),
            setSoldOut,
            branchPrices: () => of(as.prices ?? { prices: [], pending: [] }),
          },
        },
        {
          provide: Session,
          useValue: { operator: signal(as.operator), ownsBrand: () => as.operator },
        },
        {
          provide: OrderingSettingsClient,
          useValue: {
            get: () =>
              of({ value: { priceApprovalRequired: !!as.approval }, etag: '"ordering-1"' }),
          },
        },
        {
          provide: StoreContext,
          useValue: { store: signal({ id: 's1', brand: { id: 'b1' } }), refreshReadiness: vi.fn() },
        },
        {
          provide: LocaleStore,
          useValue: {
            locale: signal({
              defaultLanguage: 'en',
              languages: ['en', 'ar', 'ur'],
              rtlLanguages: ['ar', 'ur'],
            }),
            pick: (text: Record<string, string>) => text['en'] ?? '',
            money: (minor: number) => `Rs ${minor / 100}`,
            languageName: (code: string) => ({ ar: 'العربية', ur: 'اردو' })[code] ?? code,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(MenuEditor);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return { fixture, setSoldOut, element: fixture.nativeElement as HTMLElement };
  }

  it('lists the brand menu with this branch’s sold-out state and missing translations', async () => {
    // when
    const { element } = await render();

    // then
    expect(element.textContent).toContain('2 dishes in 1 section');
    expect(element.textContent).toContain('Chicken biryani');
    expect(element.textContent).toContain('Rs 650');
    expect(element.querySelector('.item.sold-out')?.textContent).toContain('Mutton biryani');
    expect(element.querySelector('.item.sold-out')?.textContent).toContain('No العربية, اردو');
  });

  it('marks a dish sold out at this branch only', async () => {
    // given
    const { fixture, element, setSoldOut } = await render();

    // when
    (
      element.querySelector('.item:not(.sold-out) mat-slide-toggle button') as HTMLButtonElement
    ).click();
    await settle();
    fixture.detectChanges();

    // then
    expect(setSoldOut).toHaveBeenCalledWith('s1', 'i1', true);
    expect(element.querySelectorAll('.item.sold-out').length).toBe(2);
  });

  it('puts the switch back when the change is refused', async () => {
    // given
    const { fixture, element } = await render(vi.fn(() => throwError(() => new Error('403'))));
    const toggle = element.querySelector(
      '.item:not(.sold-out) mat-slide-toggle button',
    ) as HTMLButtonElement;

    // when
    toggle.click();
    await settle();
    fixture.detectChanges();

    // then
    expect(toggle.getAttribute('aria-checked')).toBe('true');
    expect(element.querySelectorAll('.item.sold-out').length).toBe(1);
  });

  it('shows a branch manager the branch’s own price over the brand’s, and a change waiting for approval', async () => {
    // when
    const { element } = await render(undefined, {
      operator: false,
      approval: true,
      prices: {
        prices: [{ itemId: 'i1', optionId: null, priceMinor: 70000 }],
        pending: [
          {
            id: 'b1:s1:i2',
            brandId: 'b1',
            brandName: 'Spice Route',
            storeId: 's1',
            branchName: 'Gulberg',
            itemId: 'i2',
            itemName: { en: 'Mutton biryani' },
            optionId: null,
            optionName: null,
            currentMinor: 95000,
            proposedMinor: 99000,
            requestedBy: 'u1',
            requestedAt: 1,
            status: 'PENDING',
          },
        ],
      },
    });

    // then
    const items = element.querySelectorAll('.item');
    expect(items[0].querySelector('.price')?.textContent).toContain('Rs 700');
    expect(items[0].querySelector('.brand-price')?.textContent).toContain('Brand Rs 650');
    expect(items[1].textContent).toContain('Price waiting for approval');
    expect(element.textContent).toContain('1 price change waits for approval');
  });
});
