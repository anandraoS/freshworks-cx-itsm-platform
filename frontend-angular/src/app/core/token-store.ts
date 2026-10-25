import { Injectable } from '@angular/core';
import type { AuthUser } from './models';

/**
 * Token storage: in-memory copy (primary) + sessionStorage (survives reload, cleared when the tab closes).
 *
 * XSS trade-off (deliberate, documented): any script running in the page origin can read sessionStorage,
 * so a successful XSS could steal the JWT. HttpOnly+SameSite cookies would avoid that but need CSRF defences
 * and backend support the contract does not define (contract = Bearer header). Mitigations: Angular's built-in
 * template sanitisation (no bypassSecurityTrust* / innerHTML anywhere), expiry checks, no localStorage,
 * server-side revocation via POST /auth/logout (jti), tenant derived from the JWT only.
 */
export const SESSION_KEY = 'cx.ng.session.v1';

export interface StoredSession { accessToken: string; expiresAt: number; user: AuthUser }

@Injectable({ providedIn: 'root' })
export class TokenStore {
  private memory: StoredSession | null = null;
  private loaded = false;

  get(): StoredSession | null {
    if (!this.loaded) {
      this.loaded = true;
      try {
        const raw = sessionStorage.getItem(SESSION_KEY);
        if (raw) this.memory = JSON.parse(raw) as StoredSession;
      } catch {
        this.memory = null;
      }
    }
    if (this.memory && this.memory.expiresAt <= Date.now()) {
      this.clear();
      return null;
    }
    return this.memory;
  }

  set(s: StoredSession): void {
    this.memory = s;
    this.loaded = true;
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(s)); } catch { /* storage unavailable: memory only */ }
  }

  clear(): void {
    this.memory = null;
    this.loaded = true;
    try { sessionStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
  }

  token(): string | null {
    return this.get()?.accessToken ?? null;
  }
}
