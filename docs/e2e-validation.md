# End-to-end validation (Playwright)

Executed 2026-10-17 on Windows 11 + Git Bash, GraalVM JDK 21.0.7, Node 24, Chromium via Playwright, **no Docker**. Real stack, nothing mocked:
`core-service` (profile `local`, :8081, embedded notifications) behind the `gateway` (:8080), Vite dev server (:5173) pointed at the gateway, all with the same dummy `CX_JWT_SECRET`.
Seeded H2 data, dummy credentials only. Logs: `build/logs/*.log` (git-ignored scratch).

## Result of the final run

`E2E_FAULT_BASE_URL=http://localhost:5174 npx playwright test` -> **17 run, 17 passed, 0 failed, 0 skipped** (2.7 min). Without `E2E_FAULT_BASE_URL` the degraded-AI spec is skipped (16 passed, 1 skipped).

| Spec | Tests | Covers |
|---|---|---|
| `login.spec.ts` | 3 | bad credentials, sign in/out with tenant in header, protected-route redirect |
| `create-ticket.spec.ts` | 2 | client-side validation, create + find via search |
| `assign-transition.spec.ts` | 2 | assign + NEW->OPEN with reason, internal-notes tab separation |
| `customer-restrictions.spec.ts` | 2 | customer blocked from admin/dashboard, no AI panel / internal tab |
| `tenant-isolation.spec.ts` | 1 | other tenant gets 404 / no search hit |
| `ai-approve.spec.ts` | 1 | analyze -> edit -> approve posts the edited reply |
| `ai-reject.spec.ts` (new) | 1 | analyze -> reject with reason, nothing posted |
| `ai-degraded.spec.ts` (new) | 1 | provider failure -> degraded notice, no approve button, manual work still possible |
| `kb.spec.ts` (new) | 1 | agent creates draft (customer cannot see it) -> publishes -> customer finds and opens it |
| `notifications.spec.ts` (new) | 1 | assign to Ben Agent -> Ben's bell lists the ticket -> click opens the ticket |
| `dashboard-admin.spec.ts` (new) | 2 | manager dashboard shows non-zero real KPIs and charts; admin workflow editor renders |

### Degraded AI path (second stack)
`core-service` has the `cx.ai.fault-mode` hook (`NONE|FAIL|TIMEOUT`, refused in the docker profile). The degraded spec runs against a second stack:
core on :8083 with `-Dcx.ai.fault-mode=FAIL -Dcx.cors.allowed-origins=http://localhost:5174`, a second gateway on :8084 (`GATEWAY_MANAGEMENT_PORT=8091`, `-Droutes.core.uri=http://localhost:8083`)
and Vite on :5174 with `VITE_API_BASE_URL=http://localhost:8084`. `TIMEOUT` mode was not exercised in the browser (backend tests cover it).

## Failures found on the first run and their cause

| Failure | Side | Resolution |
|---|---|---|
| `internal notes ...`: `getByLabel('Add internal note')` matched both the form (`aria-label`) and the textarea (strict-mode violation) | test selector | use `getByRole('textbox', { name: 'Add internal note' })` |
| `agent assigns a ticket...`: `page.goto('/login')` aborted at the 30 s timeout | test infrastructure (cold Vite dev-server compile/dep re-optimisation, not an app bug); passed on re-run | test timeout raised to 60 s in `playwright.config.ts`. One test still took ~29-35 s on a cold server; expect first-run slowness |
| new KB test, first draft: asserted "No articles found" for the customer | test assumption (the KB search is fuzzy/relevance based and returns other published articles) | assert the specific article link is absent before publish and present after. Draft was verified not leaked to customers |
| `ai-reject` timed out once in a full run | same cold-start slowness as above; passed on all later runs | covered by the 60 s timeout |

No frontend or backend source was changed, so no backend or Vitest re-run was needed for fixes (only `e2e/` and docs changed). The frontend `npm test`, `tsc` and lint were not re-run in this session.

## Observations (not fixed)
- Dashboard resolution-trend chart: x-axis date labels are cramped/truncated ("09 18", "10 1") in `docs/screenshots/dashboard.png`. Cosmetic.
- Notification delivery is asynchronous (outbox relay); the spec reloads until the item appears (max 20 s). In the run it appeared within one or two reloads.

## Screenshots
`docs/screenshots/dashboard.png`, `ticket-detail-ai-panel.png`, `admin-workflows.png`, produced by `node e2e/scripts/screenshots.mjs` (needs the stack and Vite on :5173; dummy data).

## Not verified
Docker/PostgreSQL profile, standalone notification-service over RabbitMQ (embedded mode only), AI `TIMEOUT` fault mode in the browser, Firefox/WebKit, mobile viewport, production build of the frontend under e2e.
