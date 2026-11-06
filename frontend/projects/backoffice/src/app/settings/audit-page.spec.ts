import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { AuditClient, AuditLine } from '@foodstack/api-client';

import { AuditPage, fieldChanges } from './audit-page';

const CHANGE: AuditLine = {
  id: 'e2',
  kind: 'WRITE',
  at: 1_793_966_400,
  actorId: 'admin-1',
  actorName: 'Demo Admin',
  roles: ['ADMIN'],
  area: 'stores',
  action: 'PUT /api/v1/stores/{storeId}',
  resource: '/api/v1/stores/s1',
  status: 200,
  request: '{"name":"Spice Route"}',
  before: '{"name":"Spice","prepMinutes":20}',
  after: '{"name":"Spice Route","prepMinutes":20}',
  ip: '10.0.0.1',
  traceId: null,
};

const SIGN_IN: AuditLine = {
  ...CHANGE,
  id: 'e1',
  kind: 'SIGN_IN',
  area: 'auth',
  action: 'password',
  resource: null,
  request: null,
  before: null,
  after: null,
};

describe('AuditPage', () => {
  async function render() {
    const search = vi.fn(() => of({ items: [CHANGE, SIGN_IN], nextBefore: 'e1' }));
    TestBed.configureTestingModule({
      imports: [AuditPage],
      providers: [{ provide: AuditClient, useValue: { search } }],
    });
    const fixture = TestBed.createComponent(AuditPage);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    return { fixture, search, element: fixture.nativeElement as HTMLElement };
  }

  it('lists who changed what and who signed in, and pages on', async () => {
    // when
    const { element, search } = await render();

    // then
    const lines = element.querySelectorAll('.line');
    expect(lines.length).toBe(2);
    expect(lines[0].textContent).toContain('Demo Admin changed stores');
    expect(lines[0].textContent).toContain('PUT /api/v1/stores/{storeId}');
    expect(lines[1].textContent).toContain('signed in with a password');
    expect(search).toHaveBeenCalledWith({
      kind: undefined,
      q: undefined,
      before: undefined,
      limit: 50,
    });
    expect(element.textContent).toContain('Show more');
  });

  it('shows the fields before and after, marking the changed ones', async () => {
    // given
    const { fixture, element } = await render();

    // when
    (element.querySelector('.line button') as HTMLButtonElement).click();
    fixture.detectChanges();

    // then
    const rows = element.querySelectorAll('.diff tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].classList).toContain('changed');
    expect(rows[0].textContent).toContain('Spice Route');
    expect(rows[1].classList).not.toContain('changed');
  });
});

describe('fieldChanges', () => {
  it('compares JSON objects field by field and shows other values whole', () => {
    expect(fieldChanges('{"a":1,"b":[1]}', '{"a":2,"b":[1],"c":null}')).toEqual([
      { field: 'a', before: '1', after: '2', changed: true },
      { field: 'b', before: '[1]', after: '[1]', changed: false },
      { field: 'c', before: '—', after: 'empty', changed: true },
    ]);
    expect(fieldChanges(null, '[1,2]')).toEqual([
      { field: 'Value', before: '—', after: '[1,2]', changed: true },
    ]);
    expect(fieldChanges(null, null)).toEqual([]);
  });
});
