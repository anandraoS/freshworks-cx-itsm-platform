import { Routes } from '@angular/router';
import { authGuard, guestGuard, homeRedirectGuard, roleGuard } from './core/guards';

/** Every page is lazy-loaded (its own chunk). Order matters: static segments before `:id`. */
export const routes: Routes = [
  { path: 'login', canActivate: [guestGuard], title: 'Sign in', loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage) },
  { path: 'unauthorized', title: '401 Unauthorized', loadComponent: () => import('./features/status/status.pages').then((m) => m.UnauthorizedPage) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },
      { path: 'forbidden', title: '403 Forbidden', loadComponent: () => import('./features/status/status.pages').then((m) => m.ForbiddenPage) },
      { path: 'portal', title: 'Home', loadComponent: () => import('./features/portal/portal.page').then((m) => m.PortalPage) },
      { path: 'tickets', title: 'Tickets', loadComponent: () => import('./features/tickets/ticket-list.page').then((m) => m.TicketListPage) },
      { path: 'tickets/new', title: 'New ticket', loadComponent: () => import('./features/tickets/ticket-form.page').then((m) => m.TicketFormPage) },
      { path: 'tickets/:id', title: 'Ticket', loadComponent: () => import('./features/tickets/ticket-detail.page').then((m) => m.TicketDetailPage) },
      {
        path: 'tickets/:id/edit', title: 'Edit ticket', canActivate: [roleGuard({ staffOnly: true })],
        loadComponent: () => import('./features/tickets/ticket-form.page').then((m) => m.TicketFormPage),
      },
      { path: 'kb', title: 'Knowledge base', loadComponent: () => import('./features/kb/kb-list.page').then((m) => m.KbListPage) },
      {
        path: 'kb/new', title: 'New article', canActivate: [roleGuard({ min: 'AGENT', staffOnly: true })],
        loadComponent: () => import('./features/kb/kb-form.page').then((m) => m.KbFormPage),
      },
      {
        path: 'kb/:id/edit', title: 'Edit article', canActivate: [roleGuard({ min: 'AGENT', staffOnly: true })],
        loadComponent: () => import('./features/kb/kb-form.page').then((m) => m.KbFormPage),
      },
      { path: 'kb/:id', title: 'Article', loadComponent: () => import('./features/kb/kb-detail.page').then((m) => m.KbDetailPage) },
      { path: 'notifications', title: 'Notifications', loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage) },
      {
        path: 'dashboard', title: 'Dashboard', canActivate: [roleGuard({ min: 'MANAGER' })],
        loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'admin', canActivate: [roleGuard({ min: 'MANAGER' })],
        loadComponent: () => import('./features/admin/admin-layout').then((m) => m.AdminLayout),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'settings' },
          { path: 'workflows', title: 'Workflows', loadComponent: () => import('./features/admin/workflows.page').then((m) => m.WorkflowsPage) },
          {
            path: '', canActivate: [roleGuard({ min: 'ADMIN' })],
            children: [
              { path: 'settings', title: 'Organization settings', loadComponent: () => import('./features/admin/settings.page').then((m) => m.SettingsPage) },
              { path: 'users', title: 'Users & roles', loadComponent: () => import('./features/admin/users.page').then((m) => m.UsersPage) },
              { path: 'sla', title: 'SLA policies', loadComponent: () => import('./features/admin/sla.page').then((m) => m.SlaPage) },
              { path: 'categories', title: 'Categories', loadComponent: () => import('./features/admin/categories.page').then((m) => m.CategoriesPage) },
              { path: 'audit', title: 'Audit activity', loadComponent: () => import('./features/admin/audit.page').then((m) => m.AuditPage) },
            ],
          },
        ],
      },
      { path: '**', title: '404 Not found', loadComponent: () => import('./features/status/status.pages').then((m) => m.NotFoundPage) },
    ],
  },
];
