import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from './AuthContext';
import { atLeast, homeFor, isCustomerOnly } from './roles';

/** Requires a signed-in user; otherwise redirects to /login remembering where the user wanted to go. */
export function RequireAuth() {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <Outlet />;
}

interface RoleProps { min?: Role; staffOnly?: boolean; customerOnly?: boolean }

/** Role guard: renders nested routes only when the user has at least `min`; else /forbidden. */
export function RequireRole({ min, staffOnly, customerOnly }: RoleProps) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  const ok =
    (!min || atLeast(user, min)) &&
    (!staffOnly || !isCustomerOnly(user)) &&
    (!customerOnly || isCustomerOnly(user));
  if (!ok) return <Navigate to="/forbidden" replace />;
  return <Outlet />;
}

export function HomeRedirect() {
  const { user } = useAuth();
  return <Navigate to={homeFor(user)} replace />;
}
