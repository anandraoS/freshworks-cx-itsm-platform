import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { user } from '../testing/fixtures';
import { authGuard, guestGuard, roleGuard } from './guards';
import type { Role } from './models';
import { atLeast, homeFor, isCustomerOnly } from './roles';
import { TokenStore } from './token-store';

function run(guard: (r: ActivatedRouteSnapshot, s: RouterStateSnapshot) => unknown, url = '/x'): unknown {
  return TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot));
}

describe('guards and roles', () => {
  const signInAs = (roles: Role[]) => TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user(roles) });
  const url = (t: unknown) => TestBed.inject(Router).serializeUrl(t as UrlTree);

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  });

  it('authGuard redirects anonymous users to /login keeping the target', () => {
    expect(url(run(authGuard, '/tickets?status=OPEN'))).toBe('/login?from=%2Ftickets%3Fstatus%3DOPEN');
  });

  it('authGuard allows signed-in users', () => {
    signInAs(['AGENT']);
    expect(run(authGuard)).toBeTrue();
  });

  it('roleGuard MANAGER+ blocks an AGENT (-> /forbidden)', () => {
    signInAs(['AGENT']);
    expect(url(run(roleGuard({ min: 'MANAGER' })))).toBe('/forbidden');
  });

  it('roleGuard MANAGER+ allows MANAGER', () => {
    signInAs(['MANAGER']);
    expect(run(roleGuard({ min: 'MANAGER' }))).toBeTrue();
  });

  it('roleGuard ADMIN blocks MANAGER', () => {
    signInAs(['MANAGER']);
    expect(url(run(roleGuard({ min: 'ADMIN' })))).toBe('/forbidden');
  });

  it('roleGuard staffOnly blocks customers', () => {
    signInAs(['CUSTOMER']);
    expect(url(run(roleGuard({ staffOnly: true })))).toBe('/forbidden');
  });

  it('roleGuard sends anonymous users to /login', () => {
    expect(url(run(roleGuard({ min: 'AGENT' })))).toBe('/login');
  });

  it('guestGuard sends a signed-in customer to the portal', () => {
    signInAs(['CUSTOMER']);
    expect(url(run(guestGuard))).toBe('/portal');
  });

  it('role helpers', () => {
    expect(atLeast(user(['MANAGER']), 'AGENT')).toBeTrue();
    expect(atLeast(user(['AGENT']), 'MANAGER')).toBeFalse();
    expect(atLeast(user(['CUSTOMER']), 'AGENT')).toBeFalse();
    expect(isCustomerOnly(user(['CUSTOMER']))).toBeTrue();
    expect(isCustomerOnly(user(['CUSTOMER', 'AGENT']))).toBeFalse();
    expect(homeFor(user(['ADMIN']))).toBe('/dashboard');
    expect(homeFor(user(['AGENT']))).toBe('/tickets');
    expect(homeFor(null)).toBe('/login');
  });
});
