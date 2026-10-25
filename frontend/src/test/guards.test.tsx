import { describe, expect, it } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { screen } from '@testing-library/react';
import { RequireAuth, RequireRole } from '../auth/Guards';
import { atLeast, homeFor, isCustomerOnly } from '../auth/roles';
import { makeUser, renderApp } from './utils';

function Tree() {
  return (
    <Routes>
      <Route path="/login" element={<p>login page</p>} />
      <Route path="/forbidden" element={<p>forbidden page</p>} />
      <Route element={<RequireAuth />}>
        <Route path="/tickets" element={<p>tickets page</p>} />
        <Route element={<RequireRole min="MANAGER" />}><Route path="/dashboard" element={<p>dashboard page</p>} /></Route>
        <Route element={<RequireRole min="ADMIN" />}><Route path="/admin" element={<p>admin page</p>} /></Route>
        <Route element={<RequireRole staffOnly />}><Route path="/kb/new" element={<p>kb new</p>} /></Route>
      </Route>
    </Routes>
  );
}

describe('route guards', () => {
  it('redirects anonymous users to login', () => {
    renderApp(<Tree />, { route: '/tickets' });
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
  it('lets authenticated users through', () => {
    renderApp(<Tree />, { route: '/tickets', user: makeUser(['CUSTOMER']) });
    expect(screen.getByText('tickets page')).toBeInTheDocument();
  });
  it('blocks AGENT from manager dashboard but allows MANAGER', () => {
    const { unmount } = renderApp(<Tree />, { route: '/dashboard', user: makeUser(['AGENT']) });
    expect(screen.getByText('forbidden page')).toBeInTheDocument();
    unmount();
    renderApp(<Tree />, { route: '/dashboard', user: makeUser(['MANAGER']) });
    expect(screen.getByText('dashboard page')).toBeInTheDocument();
  });
  it('blocks customers from admin and staff-only routes', () => {
    const { unmount } = renderApp(<Tree />, { route: '/admin', user: makeUser(['CUSTOMER']) });
    expect(screen.getByText('forbidden page')).toBeInTheDocument();
    unmount();
    renderApp(<Tree />, { route: '/kb/new', user: makeUser(['CUSTOMER']) });
    expect(screen.getByText('forbidden page')).toBeInTheDocument();
  });
  it('admin reaches admin page', () => {
    renderApp(<Tree />, { route: '/admin', user: makeUser(['ADMIN']) });
    expect(screen.getByText('admin page')).toBeInTheDocument();
  });
});

describe('role helpers', () => {
  it('ranks roles and picks home route', () => {
    expect(atLeast(makeUser(['AGENT']), 'AGENT')).toBe(true);
    expect(atLeast(makeUser(['CUSTOMER']), 'AGENT')).toBe(false);
    expect(isCustomerOnly(makeUser(['CUSTOMER']))).toBe(true);
    expect(isCustomerOnly(makeUser(['CUSTOMER', 'AGENT']))).toBe(false);
    expect(homeFor(makeUser(['CUSTOMER']))).toBe('/portal');
    expect(homeFor(makeUser(['MANAGER']))).toBe('/dashboard');
    expect(homeFor(makeUser(['AGENT']))).toBe('/tickets');
    expect(homeFor(null)).toBe('/login');
  });
});
