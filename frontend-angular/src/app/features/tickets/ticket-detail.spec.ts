import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { APP_CONFIG } from '../../core/config';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import type { Role, TicketComment } from '../../core/models';
import { TokenStore } from '../../core/token-store';
import { API_BASE, ticket, user } from '../../testing/fixtures';
import { TicketDetailPage } from './ticket-detail.page';

const URL = `${API_BASE}/api/v1`;
const comment = (id: string, visibility: 'PUBLIC' | 'INTERNAL', body: string): TicketComment => ({ id, authorId: 'u', authorName: 'Someone', body, visibility, createdAt: '2026-01-01T00:00:00Z', aiGenerated: false });

describe('TicketDetailPage', () => {
  let fixture: ComponentFixture<TicketDetailPage>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(roles: Role[]) {
    TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user(roles) });
    fixture = TestBed.createComponent(TicketDetailPage);
    fixture.componentRef.setInput('id', 'tk-1');
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/tk-1`).flush(ticket({ status: 'OPEN', version: 3 }));
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/tk-1/comments`).flush([comment('c1', 'PUBLIC', 'Public hello'), comment('c2', 'INTERNAL', 'Secret staff note')]);
    http.expectOne(`${URL}/tickets/tk-1/activity`).flush([{ id: 'a1', at: '2026-01-01T00:00:00Z', actorId: 'u', actorName: 'Agent One', type: 'TICKET_CREATED', details: 'created' }]);
    const users = http.match(`${URL}/users`);
    users.filter((u) => !u.cancelled).forEach((u) => u.flush([{ id: 'u-1', email: 'a@b.c', displayName: 'Agent One', roles: ['AGENT'], active: true }]));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    http.match(`${URL}/tickets/tk-1/ai/suggestions`).forEach((r) => r.flush([]));
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
  });

  it('staff see Public and Internal tabs; internal notes are only shown on the Internal tab, with a staff-only banner', async () => {
    await open(['AGENT']);
    const tabs = Array.from(el.querySelectorAll<HTMLButtonElement>('[role=tab]'));
    expect(tabs.map((t) => t.textContent?.trim())).toEqual(['Public replies1', 'Internal notes1']);
    expect(el.textContent).toContain('Public hello');
    expect(el.textContent).not.toContain('Secret staff note');
    expect(el.querySelector('.banner-public')).not.toBeNull();
    tabs[1].click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Secret staff note');
    expect(el.textContent).not.toContain('Public hello');
    expect(el.querySelector('.banner-internal')?.textContent).toContain('staff only');
  });

  it('customers get a single conversation tab and no staff controls (assignment, status, AI)', async () => {
    TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user(['CUSTOMER']) });
    fixture = TestBed.createComponent(TicketDetailPage);
    fixture.componentRef.setInput('id', 'tk-1');
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/tk-1`).flush(ticket());
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/tk-1/comments`).flush([comment('c1', 'PUBLIC', 'Public hello')]);
    http.expectOne(`${URL}/tickets/tk-1/activity`).flush([]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[role=tab]').length).toBe(1);
    expect(el.textContent).not.toContain('Assignment');
    expect(el.textContent).not.toContain('AI assist');
    http.expectNone(`${URL}/users`);
  });

  it('409 on a transition shows the conflict banner and "Reload latest" refetches the ticket', async () => {
    await open(['AGENT']);
    expect(el.querySelector('[data-testid=conflict-banner]')).toBeNull();
    const resolve = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Resolved') as HTMLButtonElement;
    resolve.click();
    fixture.detectChanges();
    const reason = document.querySelector('textarea[formcontrolname=reason]') as HTMLTextAreaElement;
    reason.value = 'Fixed it';
    reason.dispatchEvent(new Event('input'));
    (document.querySelector('form[aria-label="Transition reason"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: `${URL}/tickets/tk-1/transition` });
    expect(req.request.body).toEqual({ toStatus: 'RESOLVED', reason: 'Fixed it', version: 3 });
    req.flush({ code: 'TICKET_VERSION_CONFLICT', message: 'stale' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    const banner = el.querySelector('[data-testid=conflict-banner]') as HTMLElement;
    expect(banner.textContent).toContain('modified by someone else');
    (banner.querySelector('button') as HTMLButtonElement).click();
    http.expectOne(`${URL}/tickets/tk-1`).flush(ticket({ version: 4 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=conflict-banner]')).toBeNull();
  });

  it('a 422 INVALID_TRANSITION is shown inside the dialog (no conflict banner)', async () => {
    await open(['AGENT']);
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Resolved') as HTMLButtonElement).click();
    fixture.detectChanges();
    const reason = document.querySelector('textarea[formcontrolname=reason]') as HTMLTextAreaElement;
    reason.value = 'because';
    reason.dispatchEvent(new Event('input'));
    (document.querySelector('form[aria-label="Transition reason"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: `${URL}/tickets/tk-1/transition` })
      .flush({ code: 'INVALID_TRANSITION', message: 'Cannot go from OPEN to RESOLVED' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Cannot go from OPEN to RESOLVED');
    expect(el.querySelector('[data-testid=conflict-banner]')).toBeNull();
  });

  it('shows an empty state for a ticket that does not exist (404)', async () => {
    TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user(['AGENT']) });
    fixture = TestBed.createComponent(TicketDetailPage);
    fixture.componentRef.setInput('id', 'nope');
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/nope`).flush({ code: 'NOT_FOUND', message: 'x' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Ticket not found');
  });
});
