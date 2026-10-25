import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { APP_CONFIG } from '../../core/config';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import type { AiSuggestion } from '../../core/models';
import { API_BASE } from '../../testing/fixtures';
import { AI_LABEL, AiPanelComponent } from './ai-panel';

const URL = `${API_BASE}/api/v1`;

function suggestion(over: Partial<AiSuggestion> = {}): AiSuggestion {
  return {
    suggestionId: 's-1', provider: 'MOCK_RULE_BASED', generated: true, summary: 'Printer issue summary', suggestedCategory: { id: 'c1', name: 'Hardware' },
    suggestedPriority: 'HIGH', confidence: 0.82, explanation: 'Matched keywords: printer', recommendedArticles: [{ id: 'a1', title: 'Fix your printer', score: 0.9 }],
    suggestedReply: 'Please try turning it off and on again.', status: 'PENDING_REVIEW', degraded: false, fallbackMessage: null, ...over,
  };
}

describe('AiPanelComponent', () => {
  let fixture: ComponentFixture<AiPanelComponent>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let decided = 0;
  const q = <T extends Element>(s: string) => el.querySelector<T>(s) as T;
  const button = (text: string) => Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim().includes(text)).pop() as HTMLButtonElement; // last match: dialog buttons render after the panel's

  async function create(history: AiSuggestion[] = []) {
    fixture = TestBed.createComponent(AiPanelComponent);
    fixture.componentRef.setInput('ticketId', 'tk-1');
    fixture.componentInstance.decided.subscribe(() => decided++);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush(history);
    fixture.detectChanges();
  }

  beforeEach(() => {
    decided = 0;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, showDemoHints: false } }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('labels the feature as AI-generated / mock rule-based before anything is requested', async () => {
    await create();
    expect(el.textContent).toContain(AI_LABEL);
    expect(el.textContent).toContain('nothing is sent to the customer unless you approve it');
    expect(button('Analyze ticket')).toBeTruthy();
    expect(el.querySelector('[data-testid=ai-result]')).toBeNull();
  });

  it('analyze shows summary, confidence, explanation, recommended articles and an editable reply', async () => {
    await create();
    button('Analyze ticket').click();
    http.expectOne({ method: 'POST', url: `${URL}/tickets/tk-1/ai/analyze` }).flush(suggestion());
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush([]);
    fixture.detectChanges();
    const r = q<HTMLElement>('[data-testid=ai-result]');
    expect(r.textContent).toContain('Printer issue summary');
    expect(r.textContent).toContain('Matched keywords: printer');
    expect(r.textContent).toContain('Fix your printer');
    expect(r.textContent).toContain('90% match');
    expect(q('[role=meter]').getAttribute('aria-valuenow')).toBe('82');
    expect((q('#ai-reply-edit') as HTMLTextAreaElement).value).toBe('Please try turning it off and on again.');
    expect(r.querySelectorAll('.badge-ai').length).toBeGreaterThan(0);
  });

  it('degraded response (HTTP 200, degraded:true) shows the fallback message and no reply text', async () => {
    await create();
    button('Analyze ticket').click();
    http.expectOne({ method: 'POST', url: `${URL}/tickets/tk-1/ai/analyze` })
      .flush(suggestion({ degraded: true, fallbackMessage: 'AI is down, carry on manually', suggestedReply: null, summary: '' }));
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush([]);
    fixture.detectChanges();
    expect(q('[data-testid=ai-degraded]').textContent).toContain('AI is down, carry on manually');
    expect(el.querySelector('[data-testid=ai-result]')).toBeNull();
    expect(el.querySelector('#ai-reply-edit')).toBeNull();
  });

  it('a 503 AI_UNAVAILABLE also falls back to the degraded notice', async () => {
    await create();
    button('Analyze ticket').click();
    http.expectOne({ method: 'POST', url: `${URL}/tickets/tk-1/ai/analyze` })
      .flush({ status: 503, code: 'AI_UNAVAILABLE', message: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(q('[data-testid=ai-degraded]').textContent).toContain('temporarily unavailable');
  });

  it('approve: edited reply is sent as editedReply after an explicit confirmation, then marked approved', async () => {
    await create([suggestion()]); // pending suggestion restored from history
    const ta = q<HTMLTextAreaElement>('#ai-reply-edit');
    ta.value = 'Edited reply for the customer';
    ta.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('Approve & post reply').click();
    fixture.detectChanges();
    expect(document.body.textContent).toContain('Post this reply to the customer?');
    http.expectNone({ method: 'POST', url: `${URL}/ai/suggestions/s-1/approve` }); // nothing posted before confirming
    fixture.detectChanges();
    button('Approve & post').click();
    const req = http.expectOne({ method: 'POST', url: `${URL}/ai/suggestions/s-1/approve` });
    expect(req.request.body).toEqual({ editedReply: 'Edited reply for the customer', visibility: 'PUBLIC' });
    req.flush(suggestion({ status: 'EDITED_APPROVED' }));
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush([]);
    fixture.detectChanges();
    expect(el.textContent).toContain('Approved after editing; reply posted.');
    expect(el.querySelector('#ai-reply-edit')).toBeNull();
    expect(decided).toBe(1);
  });

  it('approve without edits sends no editedReply', async () => {
    await create([suggestion()]);
    button('Approve & post reply').click();
    fixture.detectChanges();
    button('Approve & post').click();
    const req = http.expectOne({ method: 'POST', url: `${URL}/ai/suggestions/s-1/approve` });
    expect(req.request.body.editedReply).toBeUndefined();
    req.flush(suggestion({ status: 'APPROVED' }));
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush([]);
  });

  it('reject requires a reason (>= 3 chars) and records REJECTED', async () => {
    await create([suggestion()]);
    button('Reject').click();
    fixture.detectChanges();
    expect(button('Confirm reject').disabled).toBeTrue();
    const ta = q<HTMLTextAreaElement>('#ai-reject-reason');
    ta.value = 'Not accurate';
    ta.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('Confirm reject').click();
    const req = http.expectOne({ method: 'POST', url: `${URL}/ai/suggestions/s-1/reject` });
    expect(req.request.body).toEqual({ reason: 'Not accurate' });
    req.flush(suggestion({ status: 'REJECTED' }));
    http.expectOne(`${URL}/tickets/tk-1/ai/suggestions`).flush([]);
    fixture.detectChanges();
    expect(el.textContent).toContain('This suggestion was rejected.');
  });
});
