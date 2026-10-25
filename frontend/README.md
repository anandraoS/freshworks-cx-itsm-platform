# CX + ITSM Frontend (React)

A second, feature-equivalent Angular UI lives in `../frontend-angular` (same API, same gateway).

React 18 + TypeScript (strict) + Vite. Talks only to the gateway (`VITE_API_BASE_URL`, default `http://localhost:8080`) following `docs/API-CONTRACT.md`. All data is dummy seed data.

## Scripts

| Script | Purpose |
|---|---|
| `npm run dev` | Vite dev server on :5173 |
| `npm run build` | `tsc --noEmit` then production build (`dist/`) |
| `npm run typecheck` | TypeScript only |
| `npm run lint` | ESLint, zero warnings allowed |
| `npm test` | Vitest + React Testing Library (jsdom) |

Config: copy `.env.example` to `.env` to override `VITE_API_BASE_URL`; `VITE_SHOW_DEMO_HINTS=true` shows the seed-login hints panel in a production build (always on in dev).

## Structure

```
src/
  api/        client.ts (fetch wrapper, ApiError mapping), endpoints.ts, types.ts (contract types)
  auth/       AuthContext, tokenStore, roles, Guards (RequireAuth / RequireRole)
  components/ ui/ (Button, Input, Select, Textarea, Badge, Card, Table, Modal, ConfirmDialog, Pagination,
              Tabs, Skeleton, EmptyState, Toast, SlaIndicator, PriorityBadge, StatusBadge, AiBadge, ConfidenceMeter),
              charts/ (inline-SVG bar, donut, line), AiPanel, Layout, NotificationBell, ErrorBoundary
  hooks/      useLookups, useNotifications
  lib/        queryKeys, queryClient, schemas (Zod), ticketFilters (URL <-> query params), format, formErrors
  pages/      route components (lazy loaded); pages/admin/* for administration
  test/       Vitest suites
```

## State management

- **Server state**: TanStack Query. Every query key starts with the tenant id (`lib/queryKeys.ts`), so cached data can never bleed across tenants even without a reload. Mutations invalidate the affected keys.
- **Optimistic updates**: only for notification read marks (with rollback on error). Everything else waits for the server.
- **URL state**: ticket list filters, sort, page and search live in the query string (`lib/ticketFilters.ts`) so lists are shareable and survive reload.
- **Forms**: React Hook Form + Zod; server `fieldErrors` are mapped back onto fields.
- **Auth**: React context over `tokenStore`.

### Token handling and the XSS trade-off
The JWT is held in memory and mirrored to `sessionStorage` (survives reload, dies with the tab; never `localStorage`). Any script running in our origin could read it, so XSS is the main risk; mitigations are no `dangerouslySetInnerHTML`, React output escaping, expiry checks, server-side revocation on logout, and tenant derived from the JWT only. HttpOnly cookies would need CSRF handling and backend support the contract does not define. A 401 on any authenticated call clears the session and sends the user to `/login` ("session expired"). Every request carries `Authorization` and a fresh `X-Correlation-Id`.

### Idempotency and conflicts
Create-ticket generates one `Idempotency-Key` per form mount and reuses it on retry; the submit button is disabled while pending and a ref blocks double submits. A 409 `TICKET_VERSION_CONFLICT` on edit/assign/transition shows a conflict notice with "Reload latest".

## Performance

- Route-level code splitting: every page (and the admin area) is `React.lazy`; the shell and login are in the entry chunk. Vendor `manualChunks`: `react`, `query`, `forms`.
- Memoisation (`useMemo`/`memo`) is used sparingly, never blanket.
- Notification polling is 30 s, paused implicitly when unauthenticated.

### Bundle report (`npm run build`, measured)

| Chunk | Size | gzip |
|---|---|---|
| react (react, react-dom, router) | 164.5 kB | 53.7 kB |
| forms (RHF, resolvers, zod) | 93.9 kB | 26.4 kB |
| query (TanStack Query) | 41.5 kB | 12.5 kB |
| index (app shell, login, UI kit) | 32.4 kB | 11.6 kB |
| TicketDetailPage | 15.4 kB | 5.0 kB |
| WorkflowsPage | 8.1 kB | 2.8 kB |
| DashboardPage | 5.7 kB | 2.3 kB |
| other pages | 0.3 - 5.3 kB each | < 2.3 kB |
| CSS | 12.3 kB | 3.4 kB |

A first visit to `/login` loads react + query + forms + index (about 104 kB gzip); other pages load on demand.

## Accessibility

Skip link to `#main`; every control has a programmatically associated label; errors use `role="alert"`, toasts and AI progress use `aria-live`; modals trap focus, close on Esc and restore focus to the trigger; tabs implement the ARIA tab pattern with arrow-key navigation; status/SLA/priority are conveyed by text as well as colour; visible focus rings; layout is responsive down to phone width.

## Tests

Vitest covers: UI components, Zod schemas, auth + route guards, ticket-list filter to query params, AI panel states (pending, degraded, approved), duplicate-submit protection and idempotency key reuse, error boundary, API client error mapping, notification optimistic update/rollback. Playwright specs live in `../e2e` and need the backend running. Executed 2026-10-17 against the real gateway + core-service (profile `local`): 17 tests, 17 passed (covers login, tickets, assignment, customer restrictions, tenant isolation, AI approve/reject/degraded, KB publish, notification bell, dashboard, admin workflows). See `../docs/e2e-validation.md`; screenshots in `../docs/screenshots/`.
