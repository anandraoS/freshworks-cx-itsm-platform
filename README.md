# Multi-Tenant CX and ITSM SaaS Platform with AI-Assisted Support

A portfolio project inspired by the public job description for a Lead Full Stack Engineer (IC3). It is not Freshworks software and makes no claim about any proprietary system.

Start here:
- `RUN-AND-TEST-GUIDE.md` - build, run (no Docker needed), test, seed logins.
- `FINAL-VALIDATION-REPORT.md` - what was actually executed and what was not.
- `01-...Architecture-and-Workflow.pdf` (88 pages) and `02-...Technical-Deep-Dive-and-Interview-Guide.pdf` (414 pages).
- `job-requirement-traceability-matrix.md`, `ARCHITECTURE-DECISIONS.md`, `INTERVIEW-QUESTION-BANK.md` (270 questions), `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`.

Layout: `core-service` (identity, tenancy, tickets, workflow/SLA, KB, AI, analytics, outbox), `notification-service`, `gateway`, `platform-events`, `dsa-lab`, `frontend` (React + TypeScript), `frontend-angular` (Angular), `e2e` (Playwright), `infra` (compose, Prometheus, Grafana; never executed), `perf` (k6 + node load test), `docs` (API contract, HLD, LLD, diagrams, PDF sources), `tools/render` (Mermaid and PDF rendering).

Both UIs talk to the same REST API (`docs/API-CONTRACT.md`); core allows the origins `http://localhost:5173` (React) and `http://localhost:4200` (Angular).
Local profile uses H2, in-memory cache and in-memory event relay. Docker-only parts (PostgreSQL, Redis, RabbitMQ, MongoDB) are implemented but were not executed. AI is a deterministic mock rule-based provider.
# freshworks-cx-itsm-platform
