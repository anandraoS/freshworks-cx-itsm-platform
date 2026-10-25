import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE, user } from '../testing/fixtures';
import { AuthService } from './auth.service';
import { APP_CONFIG } from './config';
import { TokenStore } from './token-store';

describe('AuthService', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, showDemoHints: false } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('login stores the session and exposes role signals', async () => {
    const auth = TestBed.inject(AuthService);
    const p = auth.login('acme-retail', 'manager@acme.test', 'pw');
    const req = http.expectOne(`${API_BASE}/api/v1/auth/login`);
    expect(req.request.body).toEqual({ tenantSlug: 'acme-retail', email: 'manager@acme.test', password: 'pw' });
    req.flush({ accessToken: 'jwt', expiresInSeconds: 3600, user: user(['MANAGER']) });
    await p;
    expect(auth.user()?.roles).toEqual(['MANAGER']);
    expect(auth.isManager()).toBeTrue();
    expect(auth.isAdmin()).toBeFalse();
    expect(auth.isCustomer()).toBeFalse();
    expect(auth.tenantId()).toBe('t-1');
    expect(TestBed.inject(TokenStore).token()).toBe('jwt');
  });

  it('logout calls the API (revocation) and clears the session even if the call fails', async () => {
    TestBed.inject(TokenStore).set({ accessToken: 'jwt', expiresAt: Date.now() + 60_000, user: user() });
    const auth = TestBed.inject(AuthService);
    expect(auth.user()).not.toBeNull();
    const p = auth.logout();
    http.expectOne(`${API_BASE}/api/v1/auth/logout`).flush({ code: 'INTERNAL_ERROR', message: 'x' }, { status: 500, statusText: 'err' });
    await p;
    expect(TestBed.inject(TokenStore).token()).toBeNull();
    expect(auth.user()).toBeNull();
  });
});
