import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthContext';
import { tokenStore } from '../auth/tokenStore';
import { ToastProvider } from '../components/ui';
import type { AuthUser, Role } from '../api/types';

export function makeUser(roles: Role[], tenantId = 'tenant-1'): AuthUser {
  return { id: 'u1', email: 'x@acme.test', displayName: 'Test User', roles, tenant: { id: tenantId, slug: 'acme-retail', name: 'Acme Retail' } };
}

export function signIn(user: AuthUser): void {
  tokenStore.set({ accessToken: 'test-token', expiresAt: Date.now() + 3600_000, user });
}

export function renderApp(ui: ReactElement, opts: { route?: string; user?: AuthUser | null } = {}) {
  if (opts.user) signIn(opts.user);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const result = render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[opts.route ?? '/']}>
          <AuthProvider>{ui}</AuthProvider>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
  return { ...result, client };
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}
