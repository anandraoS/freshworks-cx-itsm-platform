import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api/endpoints';
import { setUnauthorizedHandler } from '../api/client';
import type { AuthUser } from '../api/types';
import { tokenStore } from './tokenStore';

interface AuthState {
  user: AuthUser | null;
  login: (tenantSlug: string, email: string, password: string) => Promise<AuthUser>;
  logout: () => Promise<void>;
  /** True when the session ended because of a 401 (expired/revoked token). */
  sessionExpired: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [user, setUser] = useState<AuthUser | null>(() => tokenStore.get()?.user ?? null);
  const [sessionExpired, setSessionExpired] = useState(false);

  const clearSession = useCallback(() => {
    tokenStore.clear();
    qc.clear(); // never keep another tenant's/user's cache around
    setUser(null);
  }, [qc]);

  // Auto logout on any 401 from the API client.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      if (tokenStore.get() || user) setSessionExpired(true);
      clearSession();
    });
    return () => setUnauthorizedHandler(null);
  }, [clearSession, user]);

  const login = useCallback(async (tenantSlug: string, email: string, password: string) => {
    const res = await authApi.login({ tenantSlug, email, password });
    qc.clear();
    tokenStore.set({ accessToken: res.accessToken, expiresAt: Date.now() + res.expiresInSeconds * 1000, user: res.user });
    setSessionExpired(false);
    setUser(res.user);
    return res.user;
  }, [qc]);

  const logout = useCallback(async () => {
    try { await authApi.logout(); } catch { /* best effort: token is dropped locally regardless */ }
    setSessionExpired(false);
    clearSession();
  }, [clearSession]);

  const value = useMemo(() => ({ user, login, logout, sessionExpired }), [user, login, logout, sessionExpired]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

/** Tenant id of the signed-in user; used as first component of every query key. */
export function useTenantId(): string {
  const { user } = useAuth();
  return user?.tenant.id ?? 'anonymous';
}
