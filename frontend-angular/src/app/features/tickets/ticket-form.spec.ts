import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { APP_CONFIG } from '../../core/config';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { TokenStore } from '../../core/token-store';
import { API_BASE, ticket, user } from '../../testing/fixtures';
import { TicketFormPage } from './ticket-form.page';

const URL = `${API_BASE}/api/v1`;

describe('TicketFormPage', () => {
  let fixture: ComponentFixture<TicketFormPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  const input = (s: string) => el.querySelector(s) as HTMLInputElement;

  function fill(title: string, description: string) {
    const t = input('input[formcontrolname=title]');
    t.value = title; t.dispatchEvent(new Event('input'));
    const d = el.querySelector('textarea[formcontrolname=description]') as HTMLTextAreaElement;
    d.value = description; d.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
  const submit = () => (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));

  async function create(id?: string) {
    fixture = TestBed.createComponent(TicketFormPage);
    if (id) fixture.componentRef.setInput('id', id);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/ticket-categories`).flush([]);
    return fixture;
  }

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, showDemoHints: false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user(['AGENT']) });
  });
  afterEach(() => http.verify());

  it('shows validation errors and sends nothing when invalid', async () => {
    await create();
    fill('abc', 'short');
    submit();
    fixture.detectChanges();
    expect(el.textContent).toContain('Title must be at least 5 characters');
    expect(el.textContent).toContain('Please describe the issue (at least 10 characters)');
    http.expectNone(`${URL}/tickets`);
  });

  it('double submit sends exactly one request, with an Idempotency-Key', async () => {
    await create();
    fill('Printer is broken', 'The office printer jams on every page.');
    submit();
    submit();
    submit();
    const reqs = http.match({ method: 'POST', url: `${URL}/tickets` });
    expect(reqs.length).toBe(1);
    expect(reqs[0].request.headers.get('Idempotency-Key')).toBe(fixture.componentInstance.idempotencyKey);
    expect(reqs[0].request.body.title).toBe('Printer is broken');
    expect(reqs[0].request.body.tags).toEqual([]);
    const nav = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    reqs[0].flush(ticket({ id: 'new-1', number: 7 }), { status: 201, statusText: 'Created' });
    expect(nav).toHaveBeenCalledWith(['/tickets', 'new-1']);
  });

  it('retry after a failure reuses the SAME Idempotency-Key and re-enables the button', async () => {
    await create();
    fill('Printer is broken', 'The office printer jams on every page.');
    submit();
    const first = http.expectOne({ method: 'POST', url: `${URL}/tickets` });
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    first.flush({ code: 'INTERNAL_ERROR', message: 'boom' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el.querySelector('.notice-error')?.textContent).toContain('server had a problem');
    expect((el.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBeFalse();
    submit();
    const second = http.expectOne({ method: 'POST', url: `${URL}/tickets` });
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush(ticket(), { status: 201, statusText: 'Created' });
  });

  it('a different form instance gets a different Idempotency-Key', async () => {
    const a = (await create()).componentInstance.idempotencyKey;
    const b = (await create()).componentInstance.idempotencyKey;
    expect(a).not.toEqual(b);
  });

  it('maps server fieldErrors back onto the field', async () => {
    await create();
    fill('Printer is broken', 'The office printer jams on every page.');
    submit();
    http.expectOne({ method: 'POST', url: `${URL}/tickets` }).flush(
      { status: 400, code: 'VALIDATION_FAILED', message: 'Invalid', fieldErrors: [{ field: 'title', message: 'Title already used' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el.textContent).toContain('Title already used');
  });

  it('edit: loads the ticket, sends the optimistic-lock version, and shows a conflict notice on 409', async () => {
    fixture = TestBed.createComponent(TicketFormPage);
    fixture.componentRef.setInput('id', 'tk-1');
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/ticket-categories`).flush([]);
    http.expectOne(`${URL}/tickets/tk-1`).flush(ticket({ version: 5, title: 'Existing title' }));
    fixture.detectChanges();
    expect(input('input[formcontrolname=title]').value).toBe('Existing title');
    submit();
    const put = http.expectOne({ method: 'PUT', url: `${URL}/tickets/tk-1` });
    expect(put.request.body.version).toBe(5);
    put.flush({ code: 'TICKET_VERSION_CONFLICT', message: 'stale' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('.notice-warn')?.textContent).toContain('Someone else changed this ticket');
    (el.querySelector('.notice-warn button') as HTMLButtonElement).click();
    http.expectOne(`${URL}/tickets/tk-1`).flush(ticket({ version: 6 }));
  });
});
