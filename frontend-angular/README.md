# CX + ITSM Frontend (Angular)

Second UI for the multi-tenant CX + ITSM platform, feature-equivalent to the React app in `../frontend`, against the **same** backend API
(`../docs/API-CONTRACT.md`). Angular 20, standalone components, signals, lazy-loaded routes, typed reactive forms, functional HTTP interceptors and
guards, plain responsive CSS (the same stylesheet vocabulary as the React app). No UI framework, no state library.

## Scripts

| Script | Purpose |
|---|---|
| `npm start` | `ng serve` on :4200, development config, API at `http://localhost:8080` (gateway) |
| `npm run start:e2e` | `ng serve --configuration e2e`: API at `http://localhost:8085` (core directly), used by the smoke test |
| `npm run build` | production build to `dist/frontend-angular` |
| `npm run build:dev` | development build |
| `npm run lint` | angular-eslint (TypeScript + inline-template accessibility rules) |
| `npm test` | Karma + Jasmine, headless Chrome, single run. Set `CHROME_BIN`, e.g. `C:/Program Files/Google/Chrome/Application/chrome.exe` |
| `npm run test:watch` | Karma in watch mode |
| `npm run e2e:smoke` | headless-Chrome smoke against a running core + `start:e2e` (see below) |

API base URL: `src/environments/environment*.ts` (`apiBaseUrl`, `showDemoHints`); selected through `fileReplacements` in `angular.json`
(`development` -> `environment.development.ts`, `e2e` -> `environment.e2e.ts`, production uses `environment.ts`, default `http://localhost:8080`, hints off).
The URL is read through the `APP_CONFIG` injection token, so tests override it without touching globals.

## Structure

```
src/app/
  core/       models.ts (contract types), config, errors (ApiError + kind mapping), interceptors (auth/correlation, error mapping + 401),
              http-context, token-store, auth.service (signals), guards (auth, role, guest, home), endpoints.ts (typed API services),
              validators + form-schemas (typed reactive forms mirroring the Zod rules), loadable (signal-based server-state holder),
              lookups.service, notification.store (30 s polling, optimistic read), toast.service, global-error-handler, ticket-filters, format, roles
  shared/ui/  button, badges (badge, priority, status, AI), card, table, pagination, modal, confirm-dialog, toast-container,
              feedback (skeleton, empty state, error notice, confidence meter), sla-indicator, tabs (ARIA), field (+ control directive)
  shared/charts/  inline-SVG bar, donut, line
  features/   auth (login), shell (layout, notification bell), dashboard, tickets (list, detail, form, parts, AI panel), kb (list, detail, form),
              notifications, portal, admin (layout, settings, users, workflows, sla, categories, audit), status (401/403/404)
  testing/    fixtures shared by the specs
e2e/smoke.mjs  real-backend smoke (puppeteer-core)
```

## How it maps to the React app

| React | Angular |
|---|---|
| `api/client.ts` fetch wrapper + `ApiError` | `HttpClient` + `authInterceptor` / `errorInterceptor` + `ApiError` (`core/errors.ts`) |
| `api/endpoints.ts` objects | injectable API services in `core/endpoints.ts` (same method names and paths) |
| TanStack Query | `Loadable` (signals: data/loading/error, latest request wins, previous data stays while reloading) + explicit `load()` after mutations |
| React Context auth | `AuthService` with signals (`user`, `isManager`, ...), `TokenStore` (memory + `sessionStorage`) |
| `RequireAuth` / `RequireRole` | `authGuard`, `roleGuard({min, staffOnly})`, `guestGuard`, `homeRedirectGuard` |
| React Hook Form + Zod | typed `FormGroup`s from `core/form-schemas.ts` + validators returning `{message}`; server `fieldErrors` mapped by `applyFieldErrors` |
| `ErrorBoundary` | `GlobalErrorHandler` -> `ErrorReporter` signal -> banner in the shell |
| `React.lazy` | `loadComponent` per route; AI panel additionally `@defer` (own chunk, staff only) |
| `useSearchParams` | `ActivatedRoute.queryParams` + `Router.navigate` (`core/ticket-filters.ts` is the pure URL <-> API mapping) |

## State and signals

- **Auth**: `AuthService` holds private writable signals exposed read-only; role flags are `computed`. A 401 from any authenticated call (interceptor) clears the session, sets `sessionExpired` and navigates to `/login`. The login call is tagged with an `HttpContextToken` so its own 401 does not log out.
- **Server state**: each page owns `loadable(() => api.call(...))`. Effects re-run `load()` when route params / URL filters / signal inputs change (inside `untracked`, to avoid dependency leaks). Mutations call the API and then update the signal (`q.set(updated)`) or reload.
- **Tenant isolation**: `LookupsService` and `NotificationStore` reset on every identity change (effect on `auth.user()`), so one tenant's cached lookups/notifications never survive a re-login.
- **Optimistic updates**: only notification read marks (`NotificationStore.markRead`: flip + decrement immediately, rollback on error, re-sync on success).
- **Idempotency**: `TicketFormPage.idempotencyKey` is created once per component instance and reused on retries; a synchronous `submitting` flag plus the `busy` signal block double submits.
- **Conflicts**: 409 `TICKET_VERSION_CONFLICT` on assign/transition shows the banner with "Reload latest"; on edit the form shows a reload notice.
- **Timers** (SLA countdown 30 s, notification poll 30 s) run outside the Angular zone and are cleared on destroy.
- Presentational UI components are `OnPush`; form-bearing pages use default change detection because field errors are read from controls on each check (see `eslint.config.js`).

## Security notes

JWT in memory + `sessionStorage` only (never `localStorage`), same trade-off as the React app (documented in `core/token-store.ts`). Angular's template sanitisation is relied upon; there is no `innerHTML`/`bypassSecurityTrust*`. The interceptors only attach the token and correlation id to URLs under `apiBaseUrl` (tested: no leak to other hosts). The post-login `?from=` target is accepted only as a same-origin absolute path (no open redirect). Login errors are generic (no tenant/email enumeration). Seed-login hints show only when `showDemoHints` is true (development / e2e configs).

## Accessibility

Skip link, labelled controls (`app-field` links label, hint, error and `aria-invalid`/`aria-describedby` to the control), `role="alert"` errors, `aria-live` toasts, modals with `role=dialog`/`aria-modal`, focus trap, Esc, focus restore, ARIA tabs with arrow keys, `aria-sort` on tables, `role=meter` for AI confidence, status/SLA/priority conveyed as text, SVG charts with text alternatives, visible focus, responsive down to phone width.

## Tests (executed 2026-10-17: 83 of 83 passed, `npm test`, Chrome 155 headless)

Karma + Jasmine: error mapping, token store (sessionStorage, expiry, corrupt data), interceptors (bearer, correlation id uniqueness, no leak to other hosts, Idempotency-Key, error body mapping, 401 -> logout, login 401 exempt), auth service, guards and role helpers, form validation for every form (mirrors Zod rules incl. cross-field), ticket filters <-> URL, UI components (button, table, pagination, modal focus/Esc/trap, tabs, field a11y wiring, SLA indicator, badges, meter), notification optimistic update/rollback, AI panel (labelling, analyze, degraded 200 and 503, approve with confirmation and edited reply, reject needs reason), ticket form (validation, double submit, same Idempotency-Key on retry, server fieldErrors, edit + 409), ticket detail (PUBLIC vs INTERNAL tabs, customer view, 409 banner + reload, 422 in dialog, 404), login page.

## Real-backend smoke (`e2e/smoke.mjs`, executed: 25 of 25 steps passed, three consecutive runs)

Prerequisites: core on :8085 (`--spring.profiles.active=local --server.port=8085`) and `npm run start:e2e`. Steps: health, CORS preflight for origin :4200, manager login -> dashboard KPIs + SVG charts, days selector, `X-Correlation-Id` exposed to JS, URL-synced ticket list filters, open a ticket, AI analyze, create ticket (validation, double click = one POST with `Idempotency-Key`, 201), search finds it, internal vs public comments, assign + transition, role guard (`/forbidden`), admin login, admin pages (users, SLA, categories, audit, settings save, workflow validate), KB create + publish + search, invalid token -> 401 -> login with "session expired", customer portal. The script resolves `puppeteer-core` from `../tools/render/node_modules`.

## Bundle report (`npm run build`, measured)

Initial total **333.7 kB raw / 95.9 kB transfer** (Angular runtime + router + forms + app shell chunks 175.7 + 88.2 kB, polyfills (zone.js) 34.6 kB, `styles.css` 12.7 kB / 3.1 kB, `main` 5.9 kB). Lazy chunks (raw / transfer): shared forms+common chunk 47.2 / 10.7, TicketDetailPage 20.1 / 5.6, WorkflowsPage 11.6 / 3.3, TicketListPage 10.4 / 3.3, DashboardPage 10.3 / 3.4, AI panel 9.5 / 3.0, TicketFormPage 8.7 / 3.0, UsersPage 8.6 / 2.7, other pages 4.8 - 7 kB. Compare with the React app in `../frontend/README.md` (about 104 kB gzip for a first visit to `/login`; Angular's first visit is about 96 kB transfer before lazy chunks).

## Known gaps

See `../docs/frontend-angular-notes.md` ("Not implemented / limitations").
