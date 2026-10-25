# Frontend component tree

Source of truth is `frontend/src` (React); the Angular twin in `frontend-angular/src/app` has the same feature set (see `frontend-angular/README.md`); the diagram is also in `diagrams/03-frontend-component-tree.mmd`.

```mermaid
flowchart TB
  main --> EB[ErrorBoundary] --> QCP[QueryClientProvider] --> TP[ToastProvider] --> BR[BrowserRouter] --> AP[AuthProvider] --> Routes
  Routes --> Login[LoginPage + seed hints]
  Routes --> Unauth[UnauthorizedPage]
  Routes --> RA[RequireAuth] --> Layout[Layout: skip link, header with tenant, Main nav, NotificationBell, ErrorBoundary + Suspense]
  Layout --> Portal[PortalPage - customer]
  Layout --> TL[TicketListPage: filters, Table, Pagination, SlaIndicator]
  Layout --> TF[TicketFormPage: create/edit]
  Layout --> TD[TicketDetailPage]
  TD --> Assign[AssignControl]
  TD --> Trans[TransitionControl + Modal]
  TD --> Comments[Comments: Tabs PUBLIC / INTERNAL]
  TD --> Activity[ActivityTimeline]
  TD --> AI[AiPanel: ConfidenceMeter, ConfirmDialog]
  Layout --> KB[KbListPage / KbDetailPage / KbFormPage]
  Layout --> Notif[NotificationsPage]
  Layout --> RR1[RequireRole MANAGER] --> Dash[DashboardPage: bar, donut, line charts]
  Layout --> RR2[RequireRole MANAGER] --> Admin[AdminLayout]
  Admin --> WF[WorkflowsPage]
  Admin --> RR3[RequireRole ADMIN] --> AdminPages[Settings, Users, SLA, Categories, Audit]
  Layout --> NF[Forbidden / NotFound]
```

All pages except Login and the status pages are `React.lazy` chunks. Query keys are prefixed with the tenant id. Roles: customers get the portal nav (Home, My tickets, New ticket, Help articles); staff get Dashboard (MANAGER+), Tickets, Knowledge base, Administration (ADMIN) and Workflows (MANAGER).
