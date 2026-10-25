import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginPage } from '../pages/LoginPage';
import { tokenStore } from '../auth/tokenStore';
import { RequireAuth } from '../auth/Guards';
import { request } from '../api/client';
import { jsonResponse, makeUser, renderApp } from './utils';

const fetchMock = vi.fn();
beforeEach(() => { vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset(); });
afterEach(() => vi.unstubAllGlobals());

const loginResponse = { accessToken: 'jwt-abc', expiresInSeconds: 900, user: makeUser(['AGENT']) };

function Tree() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}><Route path="/tickets" element={<p>tickets page</p>} /></Route>
    </Routes>
  );
}

describe('login flow', () => {
  it('validates before calling the API', async () => {
    renderApp(<Tree />, { route: '/login' });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Organization is required')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('logs in, stores the session and redirects; tenant slug is sent in the body only', async () => {
    fetchMock.mockResolvedValue(jsonResponse(loginResponse));
    renderApp(<Tree />, { route: '/login' });
    await userEvent.type(screen.getByLabelText(/Organization/), 'acme-retail');
    await userEvent.type(screen.getByLabelText(/Email/), 'agent1@acme.test');
    await userEvent.type(screen.getByLabelText(/Password/), 'Passw0rd!demo');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('tickets page')).toBeInTheDocument();
    expect(tokenStore.token()).toBe('jwt-abc');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/auth/login');
    expect(JSON.parse(init.body as string)).toEqual({ tenantSlug: 'acme-retail', email: 'agent1@acme.test', password: 'Passw0rd!demo' });
  });

  it('shows a generic message on 401 without revealing which field was wrong', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: 'UNAUTHENTICATED', message: 'User not found in tenant' }, 401));
    renderApp(<Tree />, { route: '/login' });
    await userEvent.type(screen.getByLabelText(/Organization/), 'acme-retail');
    await userEvent.type(screen.getByLabelText(/Email/), 'a@b.test');
    await userEvent.type(screen.getByLabelText(/Password/), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid organization, email or password.');
    expect(tokenStore.token()).toBeNull();
  });

  it('demo hint buttons prefill the form (dev only)', async () => {
    renderApp(<Tree />, { route: '/login' });
    await userEvent.click(screen.getByRole('button', { name: 'customer1@acme.test' }));
    expect(screen.getByLabelText(/Email/)).toHaveValue('customer1@acme.test');
    expect(screen.getByLabelText(/Organization/)).toHaveValue('acme-retail');
  });
});

describe('auto logout on 401', () => {
  it('clears the session and sends the user to login when any API call returns 401', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: 'UNAUTHENTICATED', message: 'expired' }, 401));
    function Trigger() {
      return <button onClick={() => { request('/tickets').catch(() => undefined); }}>call api</button>;
    }
    renderApp(
      <Routes><Route path="/login" element={<p>login page</p>} /><Route element={<RequireAuth />}><Route path="/tickets" element={<Trigger />} /></Route></Routes>,
      { route: '/tickets', user: makeUser(['AGENT']) },
    );
    await userEvent.click(screen.getByRole('button', { name: 'call api' }));
    await waitFor(() => expect(screen.getByText('login page')).toBeInTheDocument());
    expect(tokenStore.token()).toBeNull();
    expect(sessionStorage.getItem('cx.session.v1')).toBeNull();
  });
});

describe('tokenStore', () => {
  it('drops expired sessions', () => {
    tokenStore.set({ accessToken: 't', expiresAt: Date.now() - 1, user: makeUser(['AGENT']) });
    expect(tokenStore.get()).toBeNull();
  });
  it('restores from sessionStorage after a reload', () => {
    tokenStore.set({ accessToken: 't2', expiresAt: Date.now() + 60_000, user: makeUser(['AGENT']) });
    tokenStore.reset();
    expect(tokenStore.token()).toBe('t2');
  });
});
