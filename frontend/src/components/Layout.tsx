import { Suspense } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { atLeast, isCustomerOnly } from '../auth/roles';
import { ErrorBoundary } from './ErrorBoundary';
import { NotificationBell } from './NotificationBell';
import { Skeleton } from './ui';

interface NavItem { to: string; label: string; show: boolean }

export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const customer = isCustomerOnly(user);

  const items: NavItem[] = customer
    ? [
        { to: '/portal', label: 'Home', show: true },
        { to: '/tickets', label: 'My tickets', show: true },
        { to: '/tickets/new', label: 'New ticket', show: true },
        { to: '/kb', label: 'Help articles', show: true },
      ]
    : [
        { to: '/dashboard', label: 'Dashboard', show: atLeast(user, 'MANAGER') },
        { to: '/tickets', label: 'Tickets', show: true },
        { to: '/kb', label: 'Knowledge base', show: true },
        { to: '/admin', label: 'Administration', show: atLeast(user, 'ADMIN') },
        { to: '/admin/workflows', label: 'Workflows', show: atLeast(user, 'MANAGER') && !atLeast(user, 'ADMIN') },
      ];

  return (
    <div className="app">
      <a href="#main" className="skip-link">Skip to main content</a>
      <header className="app-header">
        <div className="brand">Helpdesk</div>
        <span className="tenant-chip" data-testid="tenant-name" title={`Tenant: ${user?.tenant.slug}`}>{user?.tenant.name}</span>
        <nav aria-label="Main" className="main-nav">
          {items.filter((i) => i.show).map((i) => (
            <NavLink key={i.to} to={i.to} end={i.to === '/tickets' || i.to === '/admin'}>{i.label}</NavLink>
          ))}
        </nav>
        <div className="header-right">
          <NotificationBell />
          <span className="user-chip" data-testid="user-chip">{user?.displayName} <small>({user?.roles.join(', ')})</small></span>
          <button type="button" className="btn btn-secondary btn-sm" onClick={async () => { await logout(); navigate('/login'); }}>Sign out</button>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="app-main">
        <ErrorBoundary resetKey={location.pathname}>
          <Suspense fallback={<Skeleton lines={6} label="Loading page" />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
    </div>
  );
}
