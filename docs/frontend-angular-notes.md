# Angular frontend notes (`frontend-angular/`)

Companion to `frontend/` (React). Both UIs speak the same contract (`docs/API-CONTRACT.md`) to the same backend; neither imports code from the other.

## React vs Angular

| Concern | React app (`frontend/`) | Angular app (`frontend-angular/`) |
|---|---|---|
| Framework / build | React 18, Vite 5, TS strict | Angular 20.3, `@angular/build` (esbuild), TS strict + `strictTemplates` |
| Components | function components + hooks | standalone components, `input()`/`output()`, signals, new control flow (`@if/@for/@defer`) |
| Server state | TanStack Query (cache, keys per tenant, invalidation) | `Loadable` (signals, latest-wins, keep previous data), explicit reloads, tenant-scoped lookup caches reset on identity change |
| Forms | React Hook Form + Zod | typed reactive forms + validator functions (same limits/messages), `app-field` wrapper for labels/errors/ARIA |
| HTTP | custom `fetch` wrapper | `HttpClient` + functional interceptors; `ApiError` identical semantics |
| Routing | react-router, `Outlet` guards | Angular Router, lazy `loadComponent`, functional guards, component input binding for route params |
| URL-synced filters | `useSearchParams` | `ActivatedRoute.queryParams` signal + `Router.navigate` |
| Global errors | `ErrorBoundary` | `ErrorHandler` + banner |
| Styling | plain CSS | same stylesheet vocabulary (copied, plus `display:block` for custom host elements) |
| Tests | Vitest + RTL (jsdom) | Karma + Jasmine in real headless Chrome (83 specs) |
| E2E | Playwright (`e2e/`) | puppeteer-core smoke (`frontend-angular/e2e/smoke.mjs`, 25 steps) |
| Lint | ESLint zero warnings | angular-eslint, all files pass |
| Initial JS (prod) | ~104 kB gzip first visit | 95.9 kB transfer (333.7 kB raw) initial |

## API-client parity

Every call of `frontend/src/api/endpoints.ts` exists in `core/endpoints.ts` with the same path, params and body: auth (login/me/logout), tickets (list/get/create with `Idempotency-Key`/update/assign/transition/comments/activity), categories CRUD, AI (analyze/suggestions/approve with `visibility: PUBLIC` and optional `editedReply`/reject), KB (list/get/create/update/publish/versions/categories), analytics dashboard, notifications (list/unread-count/read), users (array-or-page tolerant), settings, audit, workflows (list/get/validate/create/activate), SLA. Error mapping (`mapKind`, `describeError`) is a line-for-line port. Every request carries `Authorization` (when signed in), `Accept` and a fresh `X-Correlation-Id`; 401 clears the session.
Not ported because unused by the React UI too: `register-organization`, `POST /articles/{id}/link-ticket/{ticketId}`, `GET /workflows/{id}` (service method exists, no screen).

## Backend compatibility (both UIs)

- `core-service` `cx.cors.allowed-origins` is a list property; default is now `http://localhost:5173,http://localhost:4200` and can be overridden with `CX_CORS_ALLOWED_ORIGINS`. Allowed headers: `Authorization, Content-Type, Idempotency-Key, X-Correlation-Id`; exposed: `X-Correlation-Id, Retry-After`; methods GET/POST/PUT/DELETE/OPTIONS; credentials off.
- `PlatformEndpointsTest.corsAllowsOnlyTheConfiguredOrigins` asserts preflight (200, matching `Access-Control-Allow-Origin`, allowed headers incl. `idempotency-key` and `x-correlation-id`) for both origins, `Access-Control-Expose-Headers` containing `X-Correlation-Id` on a real response, and 403 for an unknown origin.
- Gateway: has no CORS code. It forwards `OPTIONS` (the auth filter skips it), forwards `Origin`, and copies upstream response headers (except hop-by-hop and its own `X-Correlation-Id`), so core's CORS headers reach the browser unchanged and the same list applies behind the gateway. No gateway change was needed. Not run through the gateway in this session (the smoke targets core on :8085).
- Browser proof: the smoke test drives real Chrome from origin :4200 against core: preflights succeed, bearer + correlation id + `Idempotency-Key` headers are sent, `X-Correlation-Id` is readable from JS.

## Not implemented / limitations

- Not run through the gateway or against the docker profile (no Docker); only core (profile `local`) on :8085. The React app was not re-tested by this work (a later, small change to both dashboard trend charts' x-axis labels was verified with the React Vitest/tsc/lint runs and the Angular Karma/`ng build` runs, 2026-10-17).
- No `register-organization` screen (React has none either), no KB "link ticket" UI, no attachment upload (metadata only, as in React).
- Transition buttons use the default contract transition map; a customised workflow can reject with 422, which is shown inside the dialog (same as React).
- Notifications list on the page and the bell share one store list: opening the bell while the page is filtered "unread only" replaces the list with all items until the page reloads.
- No SSR, i18n or service worker. Styles are one global stylesheet (no per-component CSS).
- Component specs cover the key components (UI kit, AI panel, ticket form/detail, login); admin pages, KB pages and dashboard are covered only by the real-backend smoke, not by unit specs.
- The smoke test is not wired into CI and needs a manually started core + dev server.
- One smoke run failed once on the workflow step while the dev server was recompiling because of a stylesheet edit; three later full runs passed (25/25). The step now reports notice contents if it fails.

## Defect found by the smoke test

Custom host elements (`app-card`, `app-field`, ...) are `display: inline` by default, which made the AI panel overlap the status buttons so real mouse clicks missed them (DOM clicks and unit tests did not show it). Fixed with `display:block` rules in `src/styles.css`.
