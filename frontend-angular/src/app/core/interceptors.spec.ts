import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { API_BASE, user } from '../testing/fixtures';
import { AuthService } from './auth.service';
import { APP_CONFIG } from './config';
import { ApiClient, TicketApi } from './endpoints';
import { ApiError } from './errors';
import { authInterceptor, errorInterceptor } from './interceptors';
import { TokenStore } from './token-store';

describe('HTTP interceptors', () => {
  let http: HttpTestingController;
  let store: TokenStore;

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
    store = TestBed.inject(TokenStore);
  });
  afterEach(() => http.verify());

  const signIn = () => store.set({ accessToken: 'tok-123', expiresAt: Date.now() + 60_000, user: user() });

  it('adds Authorization bearer, Accept and a fresh X-Correlation-Id to API calls', () => {
    signIn();
    const api = TestBed.inject(ApiClient);
    api.get('/tickets').subscribe();
    api.get('/tickets').subscribe();
    const reqs = http.match(`${API_BASE}/api/v1/tickets`);
    expect(reqs.length).toBe(2);
    expect(reqs[0].request.headers.get('Authorization')).toBe('Bearer tok-123');
    expect(reqs[0].request.headers.get('Accept')).toBe('application/json');
    const ids = reqs.map((r) => r.request.headers.get('X-Correlation-Id'));
    expect(ids[0]).toBeTruthy();
    expect(ids[0]).not.toEqual(ids[1]);
    reqs.forEach((r) => r.flush([]));
  });

  it('sends no Authorization header when signed out', () => {
    TestBed.inject(ApiClient).get('/tickets').subscribe();
    const r = http.expectOne(`${API_BASE}/api/v1/tickets`);
    expect(r.request.headers.has('Authorization')).toBeFalse();
    r.flush([]);
  });

  it('does not touch non-API URLs (no token leak to third parties)', () => {
    signIn();
    TestBed.inject(HttpClient).get('https://other.example/x').subscribe();
    const r = http.expectOne('https://other.example/x');
    expect(r.request.headers.has('Authorization')).toBeFalse();
    expect(r.request.headers.has('X-Correlation-Id')).toBeFalse();
    r.flush({});
  });

  it('sends the Idempotency-Key header on ticket create', () => {
    TestBed.inject(TicketApi)
      .create({ type: 'SUPPORT', title: 'abcde', description: '0123456789', priority: 'LOW', severity: 'MINOR', categoryId: null, tags: [], attachments: [] }, 'key-1')
      .subscribe();
    const r = http.expectOne(`${API_BASE}/api/v1/tickets`);
    expect(r.request.headers.get('Idempotency-Key')).toBe('key-1');
    r.flush({});
  });

  it('maps an error body to ApiError (code, fieldErrors, correlationId)', (done) => {
    TestBed.inject(ApiClient).post('/tickets', {}).subscribe({
      error: (e: unknown) => {
        expect(e).toBeInstanceOf(ApiError);
        const err = e as ApiError;
        expect(err.kind).toBe('validation');
        expect(err.status).toBe(400);
        expect(err.fieldErrors).toEqual([{ field: 'title', message: 'must not be blank' }]);
        expect(err.correlationId).toBe('corr-9');
        done();
      },
    });
    http.expectOne(`${API_BASE}/api/v1/tickets`).flush(
      { status: 400, code: 'VALIDATION_FAILED', message: 'Invalid', correlationId: 'corr-9', fieldErrors: [{ field: 'title', message: 'must not be blank' }] },
      { status: 400, statusText: 'Bad Request' },
    );
  });

  it('maps a 409 conflict and a network failure', () => {
    const kinds: string[] = [];
    const api = TestBed.inject(ApiClient);
    api.put('/tickets/1', {}).subscribe({ error: (e: ApiError) => kinds.push(e.kind) });
    api.get('/tickets').subscribe({ error: (e: ApiError) => kinds.push(e.kind) });
    http.expectOne({ method: 'PUT', url: `${API_BASE}/api/v1/tickets/1` }).flush({ code: 'TICKET_VERSION_CONFLICT', message: 'stale' }, { status: 409, statusText: 'Conflict' });
    http.expectOne({ method: 'GET', url: `${API_BASE}/api/v1/tickets` }).error(new ProgressEvent('error'));
    expect(kinds).toEqual(['version_conflict', 'network']);
  });

  it('401 on an authenticated call ends the session and redirects to /login', () => {
    signIn();
    const auth = TestBed.inject(AuthService);
    const nav = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    expect(auth.user()).not.toBeNull();
    TestBed.inject(ApiClient).get('/tickets').subscribe({ error: () => undefined });
    http.expectOne(`${API_BASE}/api/v1/tickets`).flush({ code: 'UNAUTHENTICATED', message: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    expect(store.token()).toBeNull();
    expect(auth.user()).toBeNull();
    expect(auth.sessionExpired()).toBeTrue();
    expect(nav).toHaveBeenCalledWith(['/login']);
  });

  it('a 401 from the login call itself does NOT trigger the global logout', async () => {
    const auth = TestBed.inject(AuthService);
    const nav = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const p = auth.login('acme-retail', 'a@b.test', 'bad');
    http.expectOne(`${API_BASE}/api/v1/auth/login`).flush({ code: 'UNAUTHENTICATED', message: 'Bad credentials' }, { status: 401, statusText: 'Unauthorized' });
    await expectAsync(p).toBeRejected();
    expect(nav).not.toHaveBeenCalled();
    expect(auth.sessionExpired()).toBeFalse();
  });
});
