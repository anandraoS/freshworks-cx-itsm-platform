# Part N. Senior engineering and leadership

Technical skill gets a system built. Senior engineering and leadership decide whether the right system gets built, whether it can be changed safely, and whether the team around it grows. This part uses the multi-tenant CX and ITSM platform as the worked example for the practices an IC3 (a senior individual contributor who leads work without managing people) is expected to show: roadmaps, design reviews, decision records, debt management, prioritisation, collaboration, mentoring, review, incident ownership, communication, estimation and the bar for the level.

> Honesty rules. This repository is a self-built learning project, not Freshworks code, and no team, stakeholders, customers or roadmap exist outside the documents. Everything about people practices is conceptual or an illustrative example, and is labelled so. Everything about the code (the technical debt list, the sample review, the ADR examples) is grounded in real files. Leadership skills are not testable by code; the traceability matrix marks this part CONCEPTUAL ONLY.

## N.1 What the IC3 level means

### What it is

IC3 titles vary by company, but the common expectation is a senior engineer who owns the technical outcome of a medium to large project end to end, makes sound decisions with incomplete information, raises the engineering level of the people around them, and reduces the load on managers rather than adding to it. The scope is a team or a product area, not a company.

### Typical expectations by dimension

| Dimension | Mid-level (IC2) | Senior (IC3) |
|---|---|---|
| Scope | A feature or a component | A product area; cross-team initiatives |
| Ambiguity | Needs a defined task | Turns a vague problem into a plan and milestones |
| Design | Implements a given design | Writes and defends designs, weighs alternatives, documents decisions |
| Quality | Writes tests, fixes bugs | Sets the test strategy, prevents classes of bugs, owns reliability |
| Operations | Follows runbooks | Writes runbooks, leads incidents, drives postmortem actions |
| People | Asks for help, reviews peers | Mentors, unblocks, raises the bar through review and examples |
| Communication | Reports status | Explains trade-offs to non-engineers, influences without authority |
| Delivery | Delivers own work | Delivers through others; estimates honestly; manages risk |

### How THIS PROJECT demonstrates the level (artifacts, not claims)

The repository is deliberately organised as evidence of senior behaviours: `ARCHITECTURE-DECISIONS.md` (20 ADRs plus "decisions deliberately not taken"), `docs/HLD.md`, `docs/LLD.md` and `docs/ARCH-CONSTRAINTS.md`, 18 Mermaid diagrams, an architecture test that turns design rules into build failures (`ArchitectureRulesTest`), a validation record that states what was and was not run, a troubleshooting guide with measured and unmeasured sections labelled, and a requirement traceability matrix that marks each capability IMPLEMENTED, PARTIAL or CONCEPTUAL ONLY. The common thread is honest scope: knowing and saying what you have not proven.

### Interview Q&A

**Q1. What differentiates a senior engineer from a strong mid-level engineer?**
Scope and independence rather than raw coding speed. A senior handles ambiguity (turns "make notifications better" into a measurable plan), makes decisions that hold up, anticipates failure modes, and multiplies others through review, design and mentoring. They are also explicit about risk and what they do not know.

**Q2. How do you show impact without managing people?**
Through outcomes: reliability improved (error budget, incident count), speed improved (lead time), risk removed (security gaps closed), and capability built (people who now work independently). Each should be backed by a number or an artifact.

**Q3. What is the biggest mistake new seniors make?**
Doing everything themselves. The role shifts from "best coder" to "makes the team's output better", which sometimes means letting someone else do the work, slower, and teaching along the way.

**Q4. How do you decide when to escalate?**
When the decision needs authority or information I do not have, when a risk exceeds my remit (security, legal, customer commitments), or when a disagreement persists after a time-boxed attempt. I escalate with a recommendation and options, not a complaint.

## N.2 Technical roadmaps

### What it is and why it exists

A technical roadmap is a time-ordered plan of engineering investments (reliability, scalability, security, developer experience, platform capabilities), tied to business outcomes and sequenced by dependency and risk. It exists because product roadmaps naturally under-weight work that has no visible feature; without a technical roadmap, debt compounds until it blocks delivery.

### How a good one is built

1. Inputs: product strategy, customer commitments, incident history, SLO burn, security findings, debt register, team capacity, upcoming scale events.
2. Themes, not tasks: "prove what exists", "production hardening", "scale", "product enablers". Themes survive re-planning.
3. For each item: problem, outcome, evidence, effort range, risk, dependency, owner, and the signal that will show it worked.
4. Horizon: now (this quarter, committed), next (next quarter, likely), later (directional). Commitment decreases with distance.
5. Review cadence: quarterly re-prioritisation; publish changes and reasons.
6. Capacity allocation: a standing share (often 15 to 30 percent) for reliability and debt so it does not need a fresh negotiation each sprint.

### Worked roadmap for THIS PROJECT (illustrative, derived from the honest gaps)

![Figure: Illustrative 12-month roadmap for the platform grouped by theme](docs/diagrams/png/pdf2N-roadmap.png)

| Theme | Item | Why now (evidence) | Outcome measure |
|---|---|---|---|
| Prove what exists | Run the 7 docker-tagged ITs and the PostgreSQL migrations in CI | The validation record lists RLS, Redis, RabbitMQ and Mongo behaviour as never run | Green docker-tests job; first PostgreSQL boot of `db/migration-pg` |
| Prove what exists | Run Playwright (17 tests), Vitest (86), Karma (83) and the Angular smoke in CI | Each ran green once locally on 2026-10-17; no CI run | Required checks on every pull request |
| Prove what exists | Enable percentile histograms and alert rules | p95 panel empty; no alerts | p95 visible; 6 to 8 alerts with runbook links |
| Production hardening | OIDC login, refresh tokens, key rotation | HS256 shared secret across 3 services, 60 min token, no refresh | Secret no longer shared; rotation without global logout |
| Production hardening | OpenTelemetry tracing | Correlation ids only; no timing tree | Trace for create ticket across gateway, core, broker |
| Production hardening | Idempotency keys for comments | Runbook 5: client retry creates a second comment | Zero duplicate comments in replay test |
| Scale | Per-tenant quotas and limits | None today; noisy neighbour risk | Tenant-level rate and size limits |
| Scale | Load test on PostgreSQL and Redis | All figures are H2 plus Caffeine | Baselines for SLO setting |
| Scale | Extract notification-service as an independent deployable | Embedded locally; split-ready by design (ADR-001) | Separate release cadence |
| Product enablers | Real email delivery; real LLM pilot behind a flag | EMAIL simulated; AI is a mock | Pilot tenant, flag, degraded-path alert |

The ordering logic: first make the existing claims true (cheap, high confidence), then close security and operability gaps, then scale work that needs baselines from step one, then product features that depend on a trustworthy platform.

### Implemented versus not implemented

The roadmap is a document pattern, not code. The inputs exist (debt list, runbook "Prevent" lines, validation gaps), the planning process does not.

### Common mistakes

A roadmap that is a list of tasks, dates promised a year out, no capacity reserved for debt, no outcome measures, and a roadmap that nobody outside engineering reads.

### Interview Q&A

**Q1. How do you build a technical roadmap with no product input yet?**
Start from evidence: incident history, SLO burn, the debt register and security findings. Convert each into a problem statement with a measurable outcome, then propose sequencing and ask product to weigh in on timing and trade-offs.

**Q2. How do you defend reliability work to someone who wants features?**
Use the error budget and cost of delay: "we have used 80 percent of this month's budget; another incident costs us the quarter's launch window." Show lead time and incident hours saved, not abstractions.

**Q3. How far ahead do you commit?**
Firmly to the current quarter, probabilistically to the next, directionally beyond. Publish confidence levels.

**Q4. What goes first on this platform's roadmap and why?**
Running what is already written (docker ITs, Playwright, Vitest, Compose stack). It converts many "NOT EXECUTED" labels into evidence at low cost and de-risks everything after it.

## N.3 Design reviews

### What it is and why it exists

A design review is a structured critique of a proposed design before significant implementation. It exists to catch costly mistakes when they are cheap to fix, to share context, and to record why the design is what it is.

### How to run one

1. Author writes a design document (problem, goals and non-goals, options considered, chosen design, data model, API, failure modes, security and tenancy, observability, rollout and rollback, cost, open questions). Circulate it two to three days ahead.
2. Reviewers read in advance and comment asynchronously; the meeting resolves open threads only.
3. Roles: author presents briefly; a facilitator keeps time; a scribe records decisions; reviewers represent different concerns (security, operations, data, frontend).
4. Outcomes: approved, approved with changes, or needs another round. Record decisions as ADRs.
5. Set a decision deadline so review does not become a veto.

### A checklist tuned to this platform

- Tenancy: how is `tenant_id` derived (JWT only), how is it enforced (filter, RLS), what is the cross-tenant test?
- Data: migration compatible with the old version running during deploy? Indexes lead with `tenant_id`? Expected row counts and growth?
- API: idempotency for retried writes, pagination bounds, error contract, authorisation per role, 404 for foreign objects.
- Asynchrony: at-least-once delivery handled by idempotent consumers, outbox for events, ordering assumptions stated.
- Failure modes: what happens when Redis, RabbitMQ, the AI provider, or the database is slow or down; is the behaviour degraded and bounded?
- Observability: metrics, logs with correlation id, alerts, runbook.
- Security: input validation, secrets, logging of sensitive fields, abuse cases.
- Rollout: flag, canary, rollback, data repair.
- Cost and operability: who is on call for this, and what will they need?

### Example: how a design review would have shaped the ticket-number allocator

The original design (lock the counter row in the create transaction) is simple and gap-free. A review asking "what is the concurrency profile for one busy tenant?" would have predicted the contention that was later measured. The reviewer's questions would be: expected creates per second per tenant, lock hold time, and whether gap-free numbers are a real requirement. Measured later: with the lock, same-tenant p99 up to 1 033 ms and max 12.9 s; without it, 81 to 84 ms p99 at the same concurrency, at the cost of gaps. The decision (accept gaps) is a product question the review should have surfaced early.

### Implemented versus not implemented

The platform's `docs/HLD.md`, `docs/LLD.md` and ADRs are design-review artifacts. There is no review template or process document in the repo.

### Common mistakes

Reviewing after the code is written, design documents that restate the code, reviews dominated by the loudest voice, no recorded decision, and treating the review as approval of the author rather than critique of the design.

### Interview Q&A

**Q1. How do you give feedback on a design you think is wrong?**
Ask questions that expose the assumption (what happens at 10 times the load?), propose an alternative with trade-offs, and agree on evidence that would settle it (a prototype or measurement), instead of asserting a preference.

**Q2. How long should a design document be?**
As short as the decision allows: a page of summary plus appendices. Long documents are read less.

**Q3. What do you do when a senior colleague pushes a design you disagree with?**
State the concern with evidence in writing, propose a time-boxed experiment, and if overruled by the decision owner, commit and help it succeed; record the dissent and the revisit trigger in the ADR.

**Q4. When is a design review overkill?**
Small, reversible changes inside one component. Use a lightweight note or a pull request description; reserve full reviews for one-way doors.

## N.4 Architecture decision records

### What it is and why it exists

An ADR records one significant decision: the context, the options, the choice, and the consequences. It exists because the reasoning evaporates faster than the code; a later engineer facing the same question can see what was known and what was traded away.

### How it works

One short file or section per decision; immutable once accepted (supersede rather than edit); numbered; includes status (proposed, accepted, superseded), context, decision, alternatives considered and consequences, including negative ones.

### How THIS PROJECT does it

`ARCHITECTURE-DECISIONS.md` has 20 ADRs, for example:

| ADR | Decision | Key trade-off |
|---|---|---|
| 001 | Modular core plus split-ready services, not full microservices | Operational simplicity vs independent scaling |
| 002 | Shared database with `tenant_id`, Hibernate filter, composite FKs | Cost and simplicity vs isolation strength |
| 003 | PostgreSQL RLS as optional defence in depth | Extra safety vs complexity and unrun verification |
| 004 | Tenant from the signed JWT claim, never client input | Safety vs flexibility |
| 006 and 007 | Transactional outbox, at-least-once with idempotent consumers | Reliability vs duplicate handling |
| 009 | Optimistic locking for ticket edits | Throughput and UX vs conflict handling |
| 013 | Mock AI provider by default, human approval before customer-visible output | Safety vs automation |
| 015 | Hand-written WebFlux gateway instead of Spring Cloud Gateway | Control and learning vs maintenance |
| 018 | Two Flyway locations | Portability vs duplication |
| 019 | Bounded offset pagination, whitelisted sort | Simplicity vs deep paging cost |

And a section "Decisions deliberately not taken" (no service mesh, no tracing backend, no per-tenant quotas, no search engine, no file storage), which is the most valuable kind of honesty for a reader.

### A compact ADR template

```text
# ADR-0NN: <decision in one line>
Status: proposed | accepted | superseded by ADR-0MM
Context: forces, constraints, what we know and do not know
Decision: what we will do
Alternatives: option A (why not), option B (why not)
Consequences: positive, negative, what we must now do or monitor
Revisit when: the signal that should make us reconsider
```

The last line, "Revisit when", is the one most teams omit and the most useful.

### Implemented versus not implemented

Implemented: ADRs in one file. Not implemented: per-file ADRs with status history, an index with supersession links, linking ADRs from code or pull requests.

### Common mistakes

Writing ADRs after the fact to justify, recording decisions with no alternatives, hiding the negative consequences, and never revisiting.

### Interview Q&A

**Q1. Give an ADR you would write for this project and its main trade-off.**
ADR-009, optimistic locking: edits are rarer than reads, holding a lock across human think time is unacceptable, so we add a `version` and return 409 on conflict. Trade-off: users see conflicts and need a "reload latest" flow; the parallel-update test proves exactly one winner.

**Q2. How do you make ADRs part of the workflow?**
Require one for any decision that is expensive to reverse, link it in the design review and pull request, and review the log in retrospectives.

**Q3. How do you handle a decision that turned out wrong?**
Write a new ADR superseding the old, explain what was learned, and plan the migration. Do not edit history.

**Q4. One-way and two-way doors?**
One-way (hard to reverse: data model, tenancy model, public API) deserve rigorous review. Two-way (a library, an internal interface) do not. The platform hides the broker behind an `EventPublisher` interface specifically to keep that choice a two-way door.

## N.5 Technical debt: the project's honest list

### What it is and why it matters

Technical debt is the accumulated cost of shortcuts and unfinished work that makes future change slower or riskier. Like financial debt, it carries interest: some debt is cheap to carry indefinitely, some compounds. Deliberate, documented debt is a tool; hidden debt is a hazard.

### Classification

| Type | Description |
|---|---|
| Deliberate and prudent | "We ship now and will fix in Q3" with a ticket |
| Deliberate and reckless | "We do not have time for tests" |
| Inadvertent | Learned later that the design was poor |
| Bit rot | Dependencies and environment drift |

### The project's debt register (every item traceable to the repository)

| # | Debt | Evidence | Interest (what it costs) | Priority |
|---|---|---|---|---|
| 1 | Docker-dependent behaviour never executed: 7 core Testcontainers tests, the PostgreSQL migrations (including RLS, GIN, trigram, triggers), Redis, RabbitMQ confirms, MongoDB store | `docs/core-service-validation.md` section 4 | A first PostgreSQL run may expose errors in code believed done; the RLS claim is unproven | High |
| 2 | No CI pipeline run; sample workflow only | `job-requirement-traceability-matrix.md` row 28 | Every claim relies on manual runs | High |
| 3 | Playwright (17), Vitest (86) and Karma (83) ran green once locally but are not in CI; k6 scripts unexecuted | `RUN-AND-TEST-GUIDE.md`, `perf/README.md` | UI regressions undetected | High |
| 4 | Compose stack, images, Prometheus config and Grafana dashboard never started | `infra/README.md` | Observability unproven | High |
| 5 | HS256 shared secret across three services, 60 min token, no refresh, no key rotation | `docs/security-hardening.md` | One leak forges tenants; rotation logs everyone out | High |
| 6 | No SSO, MFA, GDPR erasure, backup and restore tooling, multi-region | `docs/HLD.md` section 1.4 | Enterprise blockers | Medium (product dependent) |
| 7 | Percentile histograms off, no alert rules, no SLOs | `infra/README.md`, guide section 8 | p95 panel empty; failures found by users | High |
| 8 | No distributed tracing | ADR "not taken" | Slow-request diagnosis is manual | Medium |
| 9 | Comment creation not idempotent | Runbook 5 | Duplicate comments on client retry | Medium |
| 10 | Ticket numbers have gaps (accepted trade-off) | `TicketNumberAllocator` | Auditors or customers may expect gap-free numbers | Low, communicate |
| 11 | No replay endpoint for FAILED outbox rows; manual SQL | Runbook 3 | Operational risk during incidents | Medium |
| 12 | `notif_processed_event` has no purge | Runbook 5 | Table grows without bound | Medium |
| 13 | Document store isolation on MongoDB is application-enforced only; index and aggregation untested on real Mongo | `docs/security-hardening.md`, validation record | Isolation relies on discipline | Medium |
| 14 | KB search is `LIKE` plus keyword scoring; PG GIN and trigram prepared but not executed | validation record | Relevance and scale limits | Medium |
| 15 | Gateway rate limit and AI limit are per node | gateway notes, runbooks | N replicas multiply the limits | Medium |
| 16 | No per-tenant quotas, no tenant kill switch | ADR "not taken" | Noisy neighbour | Medium |
| 17 | Frontend API types are hand-written; no generated client or OpenAPI diff | `frontend/src/api/types.ts` | Silent contract drift | Medium |
| 18 | Email and Slack are placeholders; attachments are metadata only; AI is a mock | HLD | Features are demonstrations | Product decision |
| 19 | Hibernate tenant filter can be bypassed by native SQL and `JdbcTemplate`; protection is convention plus architecture test | Runbook 8 | Leak risk on new code | High (mitigated by tests) |
| 20 | `StartupGuards`, seeding and Swagger/Prometheus exposure depend on correct profile and config | `docs/security-hardening.md` section 6 | A misconfigured deployment exposes demo data | High |
| 21 | Chunk-load error handling after a deploy not verified | Runbook 6 | Users see blank screens after releases | Low to Medium |
| 22 | No coverage tooling, no mutation testing, no dependency vulnerability scan | build files, security doc | Unknown test strength and CVE exposure | Medium |
| 23 | Comparative latency figures are H2 and Caffeine only | guide | No capacity baseline | Medium |

### How to manage debt

1. Make it visible: a register like the table above, with evidence, interest and owner.
2. Measure interest: debt that slows delivery (lead time, onboarding time), causes incidents, or blocks features. Prioritise by interest rate, not by distaste.
3. Reserve capacity: a standing allocation, not a heroic cleanup sprint.
4. Pay debt when you touch the area ("boy scout" rule) and in focused tranches when the interest is high.
5. Prevent: architecture tests, review checklists, templates, a definition of done that includes tests and observability.
6. Avoid rewrites: prefer incremental strangler patterns with milestones.

### Implemented versus not implemented

The register exists in prose across several documents; consolidating it is a documentation task. No tooling tracks it.

### Interview Q&A

**Q1. Product wants three features this quarter but velocity has dropped because of debt. What do you do?**
Quantify: which debt slows delivery or causes incidents (flake rate, lead time, incident causes)? Propose a capacity split, such as 70 percent features and 30 percent targeted debt, with the debt items chosen by interest rate and tied to measurable outcomes. Show the cost of not doing it, and deliver features in smaller increments so progress stays visible.

**Q2. What is the project's most dangerous debt?**
Unverified infrastructure behaviour (items 1 and 4): claims about RLS, Redis fallback and broker confirms rest on tests that have not run. The danger is not that they fail but that nobody knows. The fix is cheap: run them.

**Q3. How do you convince the team to pay down debt?**
Link each item to a concrete pain (an incident, a slow review, a blocked feature), propose the smallest fix with a clear finish line, and celebrate removal.

**Q4. Rewrite or refactor?**
Refactor incrementally unless the architecture prevents meeting a hard requirement. The ADR approach keeps decisions reversible so rewrites are rarely needed.

## N.6 Prioritisation frameworks

### What they are and why they exist

A prioritisation framework is a structured way to compare unlike work (features, reliability, security, debt) so that the choice is explicit and defensible instead of driven by volume or seniority.

### Common frameworks

| Framework | Formula or idea | Good for | Weakness |
|---|---|---|---|
| RICE | (Reach x Impact x Confidence) / Effort | Product features with estimable reach | Garbage in, garbage out; confidence guessed |
| ICE | Impact x Confidence x Ease | Quick ranking of experiments | Subjective |
| WSJF (weighted shortest job first) | Cost of delay / job size | Sequencing under delay cost | Needs delay estimates |
| MoSCoW | Must, Should, Could, Won't | Scoping a release | Everything becomes Must |
| Kano | Basic, performance, delight | Product discovery | Needs customer data |
| Eisenhower | Urgent versus important | Personal time | Not for teams |
| Risk-based | Likelihood x severity | Security and reliability | Estimates of likelihood |
| Error-budget policy | Reliability work takes priority when the budget is exhausted | Balancing reliability and features | Needs SLOs |

### Worked example (illustrative scoring) with this platform's backlog

WSJF = cost of delay / job size, where cost of delay is the sum of user or business value, time criticality and risk reduction (each scored 1 to 10) and job size in weeks:

| Item | Value | Time criticality | Risk reduction | Cost of delay | Size (weeks) | WSJF |
|---|---:|---:|---:|---:|---:|---:|
| Run docker ITs and Postgres migrations in CI | 5 | 6 | 9 | 20 | 3 | 6.7 |
| Percentile histograms and alert rules | 6 | 6 | 8 | 20 | 2 | 10.0 |
| Idempotency keys for comments | 5 | 4 | 4 | 13 | 2 | 6.5 |
| OIDC login with refresh tokens | 8 | 5 | 9 | 22 | 8 | 2.8 |
| OpenTelemetry tracing | 5 | 3 | 5 | 13 | 5 | 2.6 |
| Per-tenant quotas | 6 | 3 | 7 | 16 | 6 | 2.7 |
| Real email delivery | 7 | 5 | 2 | 14 | 6 | 2.3 |

Reading the result: the cheap, high-risk-reduction items rise to the top (histograms and alerts, then CI for the Docker tests) even though OIDC has a higher raw cost of delay, because it costs four times more. The scores are illustrative and subjective; the value is in forcing the conversation about each number.

### Practical heuristics

- Do security and data-integrity work before convenience features.
- Prefer small items that unblock others.
- Protect an explicit slice for unplanned work.
- Re-score when facts change; never treat a score as an oracle.
- Say "no" with a reason and a condition ("not this quarter; we revisit when X").

### Common mistakes

False precision, scoring in isolation without the stakeholders, letting the highest-paid opinion override the framework silently, and measuring effort only in coding time (ignoring review, rollout, on-call).

### Interview Q&A

**Q1. Everything is a priority. How do you decide?**
Make criteria explicit (customer impact, revenue and retention risk, reliability and security risk, cost of delay, effort, dependencies), score with stakeholders present, share the ranking and the reasoning, and agree what is out of scope.

**Q2. Which framework do you use and why?**
A lightweight WSJF or RICE for features, a risk matrix for security and reliability, and an error-budget policy as the override: if the budget is spent, reliability goes first. One framework is never enough; the habit is the same: explicit criteria and written reasons.

**Q3. A stakeholder disagrees with your ranking. Now what?**
Show the data and assumptions, ask which input they dispute, offer a small experiment or a time-boxed re-evaluation, and escalate with a recommendation if still unresolved.

**Q4. How do you prioritise a security fix against a revenue feature?**
Estimate exposure and likelihood, apply the risk matrix, and take the matrix to leadership; unmitigated tenant-isolation risk or credential issues usually win because the downside is existential and the fix is bounded.

## N.7 Cross-functional collaboration

### What it is and why it matters

Software is built with product managers, designers, QA, security, data, support, sales engineering and operations. Cross-functional effectiveness decides whether the right thing is built and whether it works in the field.

### Working agreements by partner

| Partner | What they need from you | What you need from them |
|---|---|---|
| Product | Options with trade-offs, honest estimates, early risk calls | Problem definition, success metrics, priorities |
| Design | Technical constraints early, feasible alternatives | User flows, accessibility requirements |
| QA | Testable designs, test hooks, seed data | Risk-based test plans, exploratory findings |
| Security | Threat model, early review, logging and audit hooks | Clear requirements, practical guidance |
| Support and success | Correlation ids in error messages, runbooks, status updates | Real customer symptoms, repro steps |
| Operations and SRE | Metrics, alerts, runbooks, rollbacks | SLO policy, capacity data |
| Sales engineering | Capabilities with honest limits | Customer requirements, deal context |

### Concrete mechanisms in this project

- The correlation id appears in every error body and response header, so support can paste one value and engineering can find the exact request.
- `docs/API-CONTRACT.md` is the interface between frontend and backend; `ArchitectureRulesTest` is the interface between architecture and every contributor.
- The traceability matrix maps each capability to files, tests and an execution status, giving sales engineering and product an honest capability list ("IMPLEMENTED", "PARTIAL", "CONCEPTUAL ONLY").
- Runbooks give support and on-call a shared script.
- AI: human approval and a visible degraded state were product and security decisions made together (ADR-013).

### Practices that work

1. Shared definition of done: tests, observability, documentation, rollout plan.
2. Early involvement: invite security and operations to the design review, not the release meeting.
3. Written decisions and status updates in a predictable place.
4. Joint ownership of metrics: a feature is not done until its adoption and reliability are visible.
5. Disagree and commit, with recorded dissent.
6. Respect each function's constraints: a product deadline, a compliance requirement, a support queue.

### Common mistakes

Handing requirements over a wall, surprising partners with late bad news, using engineering jargon, hiding trade-offs, and treating QA or security as a gate at the end.

### Interview Q&A

**Q1. Product asks for a feature that conflicts with tenant isolation. How do you respond?**
Explain the risk in business terms (a cross-tenant leak is a contractual and reputational event), present alternatives that meet the underlying goal (for example aggregated analytics computed per tenant, or an explicit admin tool with auditing), and agree on a decision with security in the room.

**Q2. How do you work with a QA partner on a flaky e2e suite?**
Agree on a quarantine process, find causes together (shared data, timing), move checks down the pyramid, and measure flake rate.

**Q3. Support says customers cannot describe the failure. What do you add?**
Show the correlation id in the UI error, and make support able to search by it. Provide a short troubleshooting guide with the top five symptoms.

**Q4. How do you handle a stakeholder who changes priorities weekly?**
Introduce a lightweight intake and a stable sprint boundary, make the cost of switching visible, and reserve capacity for genuine urgent requests.

## N.8 Mentoring: principles and a 90-day plan

### What it is and why it exists

Mentoring is deliberate investment in another person's growth, through feedback, stretch assignments, access and advice. For a senior IC it is a core responsibility: the team's ceiling is set by how many people can do senior work.

### Principles

1. Start from the mentee's goals; agree the target level and the gaps together.
2. Prefer questions to answers; show when stuck, but let them struggle productively first.
3. Give stretch work with a safety net (review, pairing, rollback), not abandonment.
4. Make feedback specific, timely and about behaviour and impact.
5. Teach how to learn: how to read code, use the runbooks, form hypotheses and measure.
6. Model what you teach: write down decisions, admit uncertainty, review your own work visibly.
7. Share credit and visibility.

### A 90-day mentoring plan (example)

Mentee: a mid-level engineer, two years of experience, goal of owning a feature end to end and reaching senior within 12 to 18 months. The plan uses this repository as the training ground.

**Days 1 to 30: Foundations and trust**

| Week | Focus | Activities | Evidence of progress |
|---|---|---|---|
| 1 | Setup, goals | Agree goals, working agreement and meeting cadence (30 minutes weekly, ad hoc pairing). Run `mvn -pl core-service -am test` and read the validation record | Environment working; goals written |
| 2 | Architecture literacy | Walk through `docs/HLD.md`, diagrams 02, 05, 11 and 18; trace one request from SPA to database using a correlation id in the logs | Mentee explains tenancy and the outbox in their own words |
| 3 | Tests as documentation | Read `TicketApiTest`, `TenantIsolationTest`, `OutboxTest`; mentee adds one test for an uncovered edge | First merged pull request with a test |
| 4 | Code review | Mentee reviews two pull requests with the checklist in N.9; mentor reviews the reviews | Comments classified blocking, suggestion, nit |

**Days 31 to 60: Ownership of a small slice**

| Week | Focus | Activities | Evidence |
|---|---|---|---|
| 5 | Scoping | Mentee takes a small feature (for example idempotency keys for comments, see debt item 9); writes a one-page design with alternatives and failure modes | Design reviewed by mentor and one peer |
| 6 | Implementation | Pair on the tricky part (concurrency); mentee writes the race test using the `CountDownLatch` pattern from `TicketApiTest` | Race test passes reliably |
| 7 | Operability | Add a metric and a log line; write a runbook paragraph; draw the rollout and rollback plan | Metric visible on `/actuator/prometheus` |
| 8 | Delivery | Ship behind a config switch; mentee presents the change in the team demo | Feature merged and demoed |

**Days 61 to 90: Independence and influence**

| Week | Focus | Activities | Evidence |
|---|---|---|---|
| 9 | Incident readiness | Run a tabletop exercise on one runbook scenario (for example Redis outage): mentee acts as incident commander | Timeline and decisions written |
| 10 | Debugging depth | Profile with JFR or a thread dump on a load run; interpret a flame graph | Written findings, one improvement proposed |
| 11 | Communication | Mentee writes a trade-off summary for a non-engineer on a real decision (for example gap-free ticket numbers versus tenant throughput) | Summary accepted by a product partner |
| 12 | Review and next goals | Retrospective: strengths, gaps, next 90 days; collect feedback from peers; update the growth plan | Updated plan; sponsor identified |

Success measures at day 90: the mentee owns a small area, reviews others' work usefully, has run or shadowed an incident drill, and can explain trade-offs in writing. Failure signals: mentee waiting for instructions, avoiding review, or hiding blockers; adjust the plan rather than the person.

### Mentoring at scale

Written guides (such as the runbooks and this document), brown-bags, pairing rotations, a reading list, and rotating "design review" and "incident commander" roles so more people practise.

### Common mistakes

Doing the mentee's work for them, giving only praise or only criticism, no agenda, unclear goals, and mentoring only those who resemble you.

### Interview Q&A

**Q1. How do you mentor a mid-level engineer to senior?**
Set goals together, give end-to-end ownership of a small feature including rollout and metrics, review designs early, teach decision-making and communication, give honest feedback, and make their work visible to sponsors. Check progress every few weeks against concrete evidence.

**Q2. The mentee is stuck. What do you do?**
Ask guiding questions first (what have you tried, what do you expect, what do the logs say), narrow the problem together, and only then show. The aim is a repeatable method, not the answer.

**Q3. How do you give difficult feedback?**
Privately, specifically, soon, with an example, the impact, and a request. Ask for their perspective first; agree on a next step and follow up.

**Q4. How do you know mentoring is working?**
The mentee makes better decisions without you, requests less review on routine work, mentors others and takes on larger scope. Track against the written goals.

## N.9 Code review guidelines

### Principles

1. Review the change, not the person. Assume good intent and be specific.
2. The goal is code health and shared understanding, not perfection. Approve when the change improves the codebase and any remaining issues are minor.
3. Be fast: respond within one working day; slow review is a tax on the whole team.
4. Automate what a tool can check (format, lint, typecheck), so humans review design, correctness and risk.
5. Keep pull requests small and single-purpose.
6. Label comments: blocking, suggestion, question, nit.

### What to look for, in order

1. Does it do what it claims, including edge cases (null, empty, large, concurrent)?
2. Security: authentication and authorisation, tenant scoping, input validation, secrets, logging of sensitive data, injection.
3. Data: migrations (locks, backward compatibility), indexes, transactions, consistency.
4. Failure modes: timeouts, retries, idempotency, partial failure, degraded behaviour.
5. Observability: metrics, logs with correlation id, alerts, runbook impact.
6. Tests: is the behaviour proven, does the test fail without the change, is it deterministic?
7. Design and readability: names, structure, complexity, duplication, consistency with ADRs.
8. Performance: N+1, unbounded queries, hot paths, caching correctness.
9. Docs and rollout: contract updates, flags, rollback.

### The reviewer's checklist for this codebase

- No controller parameter named tenant; no entity returned from a controller (both enforced by `ArchitectureRulesTest`).
- Every new repository method takes the tenant or goes through the filter; no `findById` without a tenant check; no native SQL or `JdbcTemplate` without a reviewed reason.
- New endpoint: `@PreAuthorize`, a row in the role matrix test, a cross-tenant 404 test, error contract.
- New write path: idempotency story, optimistic locking if editable, audit event, outbox event if other services care.
- New cache: key through `CacheService.fullKey`, TTL, eviction on write, behaviour when Redis is down.
- New async consumer: idempotent on event id, bounded retries, dead-letter path, metric.
- New query: bounded page size, sort whitelist, index with tenant first, N+1 guard.
- Logs: no bodies, tokens, PII; correlation id present.
- AI or external call: outside transactions, bounded timeout and retries, rate limit, fails closed, output validated.

### A sample review of real code: `TicketNumberAllocator`

The class (`core-service/src/main/java/com/example/cx/core/tickets/TicketNumberAllocator.java`) allocates the next per-tenant ticket number with an atomic `UPDATE ... next_number = next_number + 1` in its own tiny transaction before the business transaction. The following is how a careful reviewer would comment. It is a practice exercise on real code; it is not a claim that the class is defective. Where I call something a risk, I have read the code but not executed an experiment for that specific point.

**Summary comment.**
Thanks for the measured write-up and the Javadoc explaining history, consequences and the calling constraint; that context makes review quick. The approach is sound: it removes the row lock from the long create transaction (the measured same-tenant p99 went from about 0.5 to 1 s to about 80 ms), and uniqueness is protected by both the serialised UPDATE and the `(tenant_id, number)` unique constraint. Comments below, labelled.

1. **[blocking? discuss] The "no open transaction" contract is unenforced.**
The Javadoc says the allocator must be called while the caller holds no database transaction, otherwise a second pooled connection per request is needed and the pool could deadlock under load. Today `TicketCommandService` is the only caller, but the safety rests on documentation. Suggestion: fail fast with a guard such as `if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(...)` at the top of `next`, and add a test that calling inside a transaction fails. This turns a load-time deadlock into an immediate, obvious error in tests. (Alternative: use `Propagation.NOT_SUPPORTED` semantics through `TransactionTemplate` configuration; the guard is simpler and clearer.)

2. **[question] Numbers burned on failed creates: is the observability there?**
The gap behaviour is a documented trade-off and `aFailedCreateBurnsANumberButNumbersStayUniqueAndIncreasing` asserts it. Do we want a counter (for example `cx_ticket_numbers_burned_total`) incremented when a create fails after allocation? It would tell us how often gaps occur in production and detect a bug (an exception path burning a number on every request). Cheap to add, valuable during incidents.

3. **[suggestion] Error handling when allocation fails.**
After three attempts the method throws `IllegalStateException`, which will surface as a 500 (assuming the global handler maps unknown exceptions that way). That is acceptable for an unexpected state, but include the tenant id in the message (ids only, no PII) and consider logging at ERROR once with the correlation id (MDC) so the failure is findable. Also confirm the exception cannot leak internals in the response body; `PlatformEndpointsTest` covers the error schema in general, but not this path.

4. **[question] First-ticket race and the retry loop.**
When `increment` returns 0, the code saves a new counter row and increments again. If two requests race on the first ticket, one hits `DataIntegrityViolationException` and loops. Is there a test for two parallel first creates in a fresh tenant? `TenancyTest` runs 10 parallel creates and `TicketNumberAllocatorTest` has 3 tests, but a test that does exactly "new tenant, N parallel creates" would pin this path. If it exists, please name it in the description. The `catch` is also easy to misread: the comment helps, but a short test name that says what it covers would help more.

5. **[nit] Naming and readability.**
`currentNext` returns the counter after the increment; the subtraction `after - 1` is correct but subtle. A name such as `nextNumberAfterIncrement` or a one-line comment next to the subtraction would prevent a future off-by-one edit.

6. **[question] Database portability.**
The code is portable across H2 and PostgreSQL by design (the SQL is in a repository). On PostgreSQL the first-ticket path relies on a unique violation inside a transaction that then needs to be retried in a new transaction; this is handled because each attempt uses `tx.execute` with a fresh transaction. Has this been run on PostgreSQL? The measurements were on H2 only, and the Docker IT does not cover it. Suggest adding the first-ticket race to the Testcontainers suite.

7. **[praise]**
The Javadoc records the measurement, the trade-off and the calling constraint; the test names state rules; the change is small and revertible. That is exactly the standard for a contentious concurrency change.

**Decision.** Approve once item 1 is addressed (guard plus test) and the answers to 4 and 6 are in the description; items 2, 3 and 5 can be follow-ups. This review demonstrates the ordering: correctness and safety first, then failure modes and observability, then tests, then naming.

### Common mistakes

Nitpicking style while missing a missing tenant check, rubber-stamping large diffs, reviewing only the diff without the surrounding code, unclear blocking status, and tone that makes people defensive.

### Interview Q&A

**Q1. What do you look for first in a pull request?**
Correctness and security impact before style: does it change authorisation, tenant scoping, data access or money-like invariants? Then failure modes and tests.

**Q2. How do you keep reviews kind and effective?**
Comment on code, not people; explain why; offer alternatives; separate blocking from optional; praise good work; automate style; and keep turnaround short.

**Q3. A review is stuck in disagreement. What next?**
Time-box a conversation (voice beats comments), reduce to principles and evidence, involve a third reviewer or the tech lead for a tie-break, record the decision.

**Q4. What do you do when a senior colleague repeatedly merges large risky changes late on Fridays?**
Private, specific feedback with impact, ask their perspective, then agree team-wide working agreements (size limits, no risky merges before weekends, flags for risky work). Make it a team rule so no one is singled out, and escalate only if it persists.

## N.10 Incident ownership

### What it is and why it exists

Ownership means being accountable for a service's behaviour in production: detecting problems, restoring service, learning, and preventing recurrence. A senior engineer owns incidents from the first page to the last action item, even when other people do the typing.

### Responsibilities before, during and after

Before: runbooks (ten exist here), alerts that map to symptoms, dashboards, safe deploys, rollback plans, access and drills, an on-call handbook, known limits documented.

During: take or hand off the IC role explicitly; mitigate first; communicate on a cadence; keep a timeline; protect the team (rotate, rest); involve security early for data exposure.

After: blameless postmortem, tracked actions, update runbooks and tests, share learnings, measure whether recurrence dropped.

### Applying it to this platform

- Mean time to detect depends on alerts, which do not exist yet: the ownership gap is listed in the debt register (item 7).
- Mean time to mitigate depends on safe switches: `cx.ai.provider` back to mock, flags, readiness probes, rollback; others are missing (no replay endpoint, no tenant kill switch).
- Learning loop: each runbook's "Prevent" section is an action list. For example, after Runbook 2 the actions are alerting on pending connections, role-level statement and lock timeouts, and PgBouncer.

### The "my change caused the incident" conversation

When the VP asks what happened: state facts and impact, what you did to restore service, the contributing factors (not just your commit), what you are changing, and when you will report back. Do not speculate, do not blame, do not hide. Own the outcome and the follow-up.

### Common mistakes

Waiting to be asked, heroics without communication, fixing symptoms only, skipping the postmortem when "it was quick", and treating on-call as a punishment rather than a feedback loop to improve the system.

### Interview Q&A

**Q1. What does owning an incident mean to you?**
Making sure users are restored quickly, stakeholders are informed, the cause is understood, and the system is measurably safer afterwards. It is accountability for the whole loop, not for typing the fix.

**Q2. Your own change caused a production incident and the VP wants an explanation. What do you say?**
Concise facts: what broke, who was affected, when it started and ended, what restored service. Then contributing factors including process gaps (missing test, no canary), the actions with owners and dates, and when you will follow up. No excuses, no blame; and I would tell them before they have to ask.

**Q3. How do you reduce on-call burden?**
Fix the top three noisy alerts each week, write runbooks for repeat pages, automate remediation, improve defaults and limits, and review alert quality in a recurring meeting.

**Q4. How do you decide when to wake someone up?**
If user impact crosses the SLO burn threshold, or the incident exceeds my ability or authority. Waking someone early is cheaper than a long outage.

## N.11 Communicating trade-offs to non-engineers

### What it is and why it matters

Most engineering decisions are trade-offs among time, cost, risk and quality, and the people who must decide are often not engineers. Clear communication converts technical complexity into decisions the business can make.

### A method

1. Start with the decision needed and the deadline.
2. State the options (two or three), each with outcome, cost, risk, and what it forecloses.
3. Give a recommendation and the reason.
4. Use analogies and concrete numbers; avoid jargon and acronyms.
5. State what you do not know and how you will find out.
6. Confirm understanding and write the decision down.

### Worked example: gap-free ticket numbers versus speed

Audience: a product manager.

"Today every new ticket takes the next number for its customer. Our load tests showed that when many agents of the same customer create tickets at once, they queue up behind each other, and a few requests waited several seconds. We can fix it by handing out numbers in a separate quick step. The cost: if a ticket creation fails after it got its number, that number is skipped, so a customer might see ticket 41 and then 43. Option A keeps numbers gap-free but keeps the slow queueing for busy customers. Option B removes the queueing but allows rare gaps. I recommend B, because the slowness is visible to every busy customer while gaps only matter if customers or auditors require gap-free numbers. Do any customers need that? If so, we can build a separate gap-free invoice-style number later. What I do not know: how often creation fails after numbering in production; I will add a counter to measure that." (Measured basis: same-tenant p99 477 to 1 033 ms before, 81 to 84 ms after, on a laptop with H2; production will differ.)

### Worked example: why we cannot yet promise an uptime figure

"We have not defined or measured an availability target. We have error and latency metrics, but no alerts or dashboards running in production-like conditions yet, and the infrastructure tests have not been executed. I recommend we first spend about three weeks running those tests and enabling alerts, then commit to a target based on data. Promising 99.9 percent today would be a guess."

### Tips

Use ranges instead of false precision; separate facts from opinions; show the user impact; avoid fear and avoid minimising; prefer visuals; write the decision and the revisit date.

### Common mistakes

Burying the decision in detail, jargon, presenting only one option, hiding risks until late, and over-promising.

### Interview Q&A

**Q1. How do you explain an architectural trade-off to a non-technical executive?**
Use a decision-oriented summary: options, business impact, cost, risk, recommendation, and a clear ask. Use an everyday analogy: gap-free numbering versus speed is like handing out queue tickets at a bakery counter: a single clerk calling every number in order is fair but slow; a ticket dispenser is fast, and if someone leaves, their number is skipped.

**Q2. The roadmap slips. How do you communicate it?**
Early, with facts: what changed, impact, options (cut scope, move date, add capacity), a recommendation and the new forecast with confidence. Never wait until the deadline.

**Q3. How do you say no to a request?**
Say what the request needs, why we cannot do it now (capacity, risk), what we can do instead, and when we can revisit. A "no" with options is a decision, not a refusal.

**Q4. How do you present risk without causing panic?**
State likelihood and impact, mitigations in place, residual risk and the decision you need.

## N.12 Estimating

### What it is and why it exists

Estimation predicts effort or duration to support planning and trade-offs. It is a forecast under uncertainty, not a commitment, and the goal is a useful range and a shared understanding, not precision.

### Techniques

| Technique | How | Good for |
|---|---|---|
| Decompose and sum | Break into tasks of 1 to 3 days, sum, add buffer | Well-understood work |
| Three-point (PERT) | (optimistic + 4 x likely + pessimistic) / 6 | Uncertain tasks |
| Reference-class | Compare with similar past work | Quick sanity checks |
| T-shirt sizes | S, M, L, XL for relative size | Roadmaps |
| Story points | Relative complexity, velocity-calibrated | Sprint planning |
| Spikes | Time-boxed research to reduce uncertainty | High unknowns |
| Probabilistic forecast | Monte Carlo from historical throughput | Mature teams |

### Worked example: "add idempotency keys to comment creation"

Tasks (illustrative days): design and ADR 1; migration for key table or reuse existing idempotency table 1; service logic plus tenant scope 2; API contract and error codes 0.5; unit and API tests including a race test 2; frontend key reuse per form mount (as ticket create does) 1; metrics, runbook update 0.5; review and rollout 1. Sum 9 days likely. Optimistic 6, pessimistic 15 (unknown: interaction with the outbox, PostgreSQL-only SQL). PERT = (6 + 4 x 9 + 15) / 6 = 9.5 days, call it 2 weeks including review latency and meetings. Risks: the first PostgreSQL run may reveal migration issues (the docker tests have not run); mitigate with a spike in week one. Present as "about 2 weeks, range 1.5 to 3, confidence medium; main risk: untested PostgreSQL path."

### Practices

Estimate as a team; include testing, review, rollout, documentation and on-call impact; separate effort from calendar time (meetings, interruptions); track estimates versus actuals and learn; re-estimate when facts change; communicate ranges and confidence; say "I do not know yet, give me two days to spike."

### Evidence from this project

The tail-latency work shows estimation hazards: the fix was small (one allocator class) but the evidence work (differential experiments, re-measuring, documenting trade-offs, adding tests) was most of the effort. Estimates that include only coding systematically undercount.

### Common mistakes

Single-number estimates, estimating only the happy path, anchoring on the first guess, pressure-driven optimism, ignoring integration and rollout, treating estimates as promises, and no buffer for unknowns.

### Interview Q&A

**Q1. How do you estimate a project with many unknowns?**
Run a time-boxed spike to remove the biggest unknowns, then estimate in ranges with stated assumptions and risks. Re-estimate at milestones.

**Q2. Your manager wants a firm date. What do you give?**
A date range with a confidence level and the assumptions behind it, plus the scope that could be cut to protect the date. Offer to update weekly as facts arrive.

**Q3. Why do estimates often miss?**
Planning fallacy, unplanned work, hidden dependencies, integration and rollout costs, and optimism. Counter with reference classes, buffers, and tracking actuals.

**Q4. Story points or hours?**
Points for relative sizing and velocity trends within a stable team; hours or days for task-level planning and for cross-team commitments. Neither is a performance measure.

## N.13 Hiring and leveling expectations for IC3

### What interviewers look for

IC3 interview loops usually assess technical depth, system design, coding, collaboration and leadership behaviours. Signals at this level are consistency across loops, depth beyond buzzwords, and evidence of impact.

| Area | What good looks like | How this project helps you show it |
|---|---|---|
| Coding | Clean, tested solutions; sensible complexity; edge cases | `dsa-lab` (53 tests): LRU cache, token bucket, heap, trie; plus production use of the token bucket in the gateway and login limiter |
| System design | Requirements and capacity first, trade-offs, failure modes, data model, scaling path | `docs/HLD.md` with an illustrative capacity method (labelled as invented numbers), outbox, tenancy, caching |
| Domain depth | Explains internals, not just usage | Hibernate filter versus RLS, JWT revocation, optimistic locking, at-least-once and idempotency |
| Quality and ops | Tests as strategy, observability, incident handling | Parts L and M |
| Leadership | Design reviews, mentoring, conflict resolution, communication of trade-offs | This part |
| Honesty and judgement | States limits and uncertainty | Honest-scope statements across the repository |

### Behavioural interview answers (structure)

Use a concise STAR+ structure: Situation, Task, Action, Result, and then what you learned or would change. Prepare about eight stories reusable across themes: a technical decision with trade-offs, an incident you led, a disagreement, mentoring, an ambiguous project, a failure, influencing without authority, a process improvement.

Example outline built from this project (be ready to stay truthful about scope, since it is a self-built project): Situation, load tests showed ticket creation tail latency up to 12.9 s at 20 concurrent clients on one tenant while the median stayed under 50 ms. Task, find and remove the cause without breaking uniqueness. Action, suspected a per-tenant counter row lock; ran a differential experiment spreading clients over three tenants; confirmed; moved allocation to a tiny separate transaction; added tests; documented the gap trade-off. Result, p99 at 10 clients fell from 477 to 1 033 ms to 81 to 84 ms (H2, laptop). Learning, confirmed by experiment rather than a lock trace, and PostgreSQL remains unmeasured.

### Red flags interviewers notice

Overclaiming ("we handled millions of users" from a demo), inability to explain trade-offs, blaming others, no testing or operations experience, and rehearsed answers that collapse under follow-ups. The honest-limits habit this repository practises is a strength at this level.

### What to prepare

1. Two system designs you can whiteboard fluently (ticketing with SLA escalation and a notification pipeline).
2. A one-page summary of this project: architecture, three tough problems, honest gaps.
3. Eight behavioural stories.
4. Depth on three technologies you claim, including failure modes.
5. Questions to ask: team ownership, on-call, release process, how decisions are made.

### Interview Q&A

**Q1. Why should we hire you as a senior engineer rather than a mid-level one?**
Because I can take an ambiguous problem to a production-ready outcome, make and document trade-offs, raise the team's quality through review and mentoring, and I am explicit about risk. I would point to a concrete example with measurable results and what I learned from the part that did not go well.

**Q2. Tell me about a time you disagreed with a technical decision.**
Choose a real one: the disagreement, the evidence you gathered, how you communicated, what was decided, how you committed, and what happened next. Show respect and that the outcome mattered more than winning.

**Q3. What would you do in your first 90 days?**
Learn the system and the people (read designs, ADRs, incidents), ship a small change end to end, find the top reliability and delivery pain points with data, propose one improvement with a measurable outcome, and build relationships with product, QA and operations.

**Q4. What are your weaknesses as a senior engineer?**
Pick a true, bounded one with a mitigation: for example, "I tend to dive into code before clarifying scope; I now write a short problem statement and confirm it first."

## N.14 Verification status summary for Part N

| Item | Status |
|---|---|
| ADR practice | Real: 20 ADRs in `ARCHITECTURE-DECISIONS.md` |
| Technical debt register | Real items traced to the repository; consolidation is new in this document |
| Sample code review | Real code (`TicketNumberAllocator`), review is an exercise; points marked as questions are not claims of defects |
| Roadmap, prioritisation scores, estimates, 90-day plan | Illustrative examples, not data |
| Mentoring, collaboration, communication | Conceptual; no team exists for this project |
| Hiring and leveling | General industry expectations, not a company policy |
