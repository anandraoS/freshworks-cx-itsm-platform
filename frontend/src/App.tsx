import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { HomeRedirect, RequireAuth, RequireRole } from './auth/Guards';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { ForbiddenPage, NotFoundPage, UnauthorizedPage } from './pages/StatusPages';

// Route-level code splitting: each page is its own chunk loaded on first navigation.
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const TicketListPage = lazy(() => import('./pages/TicketListPage'));
const TicketDetailPage = lazy(() => import('./pages/TicketDetailPage'));
const TicketFormPage = lazy(() => import('./pages/TicketFormPage'));
const KbListPage = lazy(() => import('./pages/KbListPage'));
const KbDetailPage = lazy(() => import('./pages/KbDetailPage'));
const KbFormPage = lazy(() => import('./pages/KbFormPage'));
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'));
const PortalPage = lazy(() => import('./pages/PortalPage'));
const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'));
const SettingsPage = lazy(() => import('./pages/admin/SettingsPage'));
const UsersPage = lazy(() => import('./pages/admin/UsersPage'));
const WorkflowsPage = lazy(() => import('./pages/admin/WorkflowsPage'));
const SlaPage = lazy(() => import('./pages/admin/SlaPage'));
const CategoriesPage = lazy(() => import('./pages/admin/CategoriesPage'));
const AuditPage = lazy(() => import('./pages/admin/AuditPage'));

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/unauthorized" element={<UnauthorizedPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route index element={<HomeRedirect />} />
          <Route path="/forbidden" element={<ForbiddenPage />} />
          <Route path="/portal" element={<PortalPage />} />
          <Route path="/tickets" element={<TicketListPage />} />
          <Route path="/tickets/new" element={<TicketFormPage />} />
          <Route path="/tickets/:id" element={<TicketDetailPage />} />
          <Route path="/tickets/:id/edit" element={<RequireRole staffOnly />}>
            <Route index element={<TicketFormPage />} />
          </Route>
          <Route path="/kb" element={<KbListPage />} />
          <Route element={<RequireRole min="AGENT" staffOnly />}>
            <Route path="/kb/new" element={<KbFormPage />} />
            <Route path="/kb/:id/edit" element={<KbFormPage />} />
          </Route>
          <Route path="/kb/:id" element={<KbDetailPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route element={<RequireRole min="MANAGER" />}>
            <Route path="/dashboard" element={<DashboardPage />} />
          </Route>
          <Route path="/admin" element={<RequireRole min="MANAGER" />}>
            <Route element={<AdminLayout />}>
              <Route index element={<Navigate to="settings" replace />} />
              <Route path="workflows" element={<WorkflowsPage />} />
              <Route element={<RequireRole min="ADMIN" />}>
                <Route path="settings" element={<SettingsPage />} />
                <Route path="users" element={<UsersPage />} />
                <Route path="sla" element={<SlaPage />} />
                <Route path="categories" element={<CategoriesPage />} />
                <Route path="audit" element={<AuditPage />} />
              </Route>
            </Route>
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
