import type { AuthUser } from '../api/types';

/**
 * Token storage: in-memory copy (primary) + sessionStorage (survives reload, cleared when the tab closes).
 *
 * XSS trade-off (deliberate, documented): any script running in the page origin can read sessionStorage,
 * so a successful XSS could steal the JWT. HttpOnly+SameSite cookies would avoid that but need CSRF defences
 * and backend support the contract does not define (contract = Bearer header). Mitigations here: short-lived
 * token, no localStorage (not shared across tabs / not persisted), no dangerouslySetInnerHTML anywhere
 * (React escapes all output), server-side revocation via POST /auth/logout (jti), tenant derived from the JWT.
 */
const KEY = 'cx.session.v1';

export interface StoredSession { accessToken: string; expiresAt: number; user: AuthUser }

let memory: StoredSession | null = null;
let loaded = false;

export const tokenStore = {
  get(): StoredSession | null {
    if (!loaded) {
      loaded = true;
      try {
        const raw = sessionStorage.getItem(KEY);
        if (raw) memory = JSON.parse(raw) as StoredSession;
      } catch {
        memory = null;
      }
    }
    if (memory && memory.expiresAt <= Date.now()) {
      this.clear();
      return null;
    }
    return memory;
  },
  set(s: StoredSession): void {
    memory = s;
    loaded = true;
    try { sessionStorage.setItem(KEY, JSON.stringify(s)); } catch { /* storage unavailable: memory only */ }
  },
  clear(): void {
    memory = null;
    loaded = true;
    try { sessionStorage.removeItem(KEY); } catch { /* ignore */ }
  },
  token(): string | null {
    return this.get()?.accessToken ?? null;
  },
  /** test helper */
  reset(): void { memory = null; loaded = false; },
};
