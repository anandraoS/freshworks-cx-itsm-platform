import { TestBed } from '@angular/core/testing';
import { user } from '../testing/fixtures';
import { SESSION_KEY, TokenStore } from './token-store';

describe('TokenStore', () => {
  beforeEach(() => { sessionStorage.clear(); TestBed.resetTestingModule(); });

  it('stores in sessionStorage (never localStorage) and returns the token', () => {
    const s = TestBed.inject(TokenStore);
    s.set({ accessToken: 'abc', expiresAt: Date.now() + 60_000, user: user() });
    expect(s.token()).toBe('abc');
    expect(sessionStorage.getItem(SESSION_KEY)).toContain('abc');
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('restores a session after a reload', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ accessToken: 'zzz', expiresAt: Date.now() + 60_000, user: user() }));
    expect(TestBed.inject(TokenStore).token()).toBe('zzz');
  });

  it('drops an expired token', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ accessToken: 'old', expiresAt: Date.now() - 1, user: user() }));
    const s = TestBed.inject(TokenStore);
    expect(s.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('ignores corrupt storage', () => {
    sessionStorage.setItem(SESSION_KEY, '{not json');
    expect(TestBed.inject(TokenStore).get()).toBeNull();
  });

  it('clear removes everything', () => {
    const s = TestBed.inject(TokenStore);
    s.set({ accessToken: 'abc', expiresAt: Date.now() + 60_000, user: user() });
    s.clear();
    expect(s.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });
});
