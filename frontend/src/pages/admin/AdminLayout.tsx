import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { atLeast } from '../../auth/roles';

export default function AdminLayout() {
  const { user } = useAuth();
  const admin = atLeast(user, 'ADMIN');
  return (
    <div>
      <div className="page-head"><h1>Administration</h1></div>
      <nav aria-label="Administration sections" className="subnav">
        {admin && <NavLink to="/admin/settings">Organization</NavLink>}
        {admin && <NavLink to="/admin/users">Users &amp; roles</NavLink>}
        <NavLink to="/admin/workflows">Workflows</NavLink>
        {admin && <NavLink to="/admin/sla">SLA policies</NavLink>}
        {admin && <NavLink to="/admin/categories">Categories</NavLink>}
        {admin && <NavLink to="/admin/audit">Audit activity</NavLink>}
      </nav>
      <Outlet />
    </div>
  );
}
