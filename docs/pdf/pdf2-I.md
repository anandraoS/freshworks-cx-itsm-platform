# Part I. Multi-tenant SaaS architecture

## I.0 How to read this part

Every sentence in this part is one of two kinds, and the heading or a label tells you which.

- **Project-implemented behaviour** means the code exists in this repository, you can open the file named, and (where stated) an automated test ran against it.
- **Conceptual learning example** means a general technique, a snippet or a scenario that is NOT in the repository. It is there so you can explain the idea in an interview and so you can compare it with what the project did.

Three honesty rules apply to the whole part and you should be able to state them without notes.

1. The platform is a **shared database, shared schema, `tenant_id` discriminator** design. It is the cheapest model to operate, and it is also the model where one forgotten predicate leaks data, which is why the project stacks several independent defences.
2. **PostgreSQL row-level security (RLS) is defined in SQL for the `docker` profile only and has never been executed.** The author's machine has no Docker. The local profile runs on H2 in PostgreSQL mode, which has no RLS. Everything the tests prove about tenant isolation is proved by the application-layer controls and the composite foreign keys, not by RLS.
3. Several multi-tenant topics are **not implemented**: per-tenant quotas, tenant suspension, tenant data export, tenant deletion, per-tenant encryption keys, regional cells. They are discussed here because an interviewer will ask, and the honest answer is "designed on paper, not built".

> **Interview tip.** The strongest answer to "how do you isolate tenants?" names the layers in order (token, context, filter, explicit predicate, constraint, database policy), says which are executed and which are not, and then says what you would test next. Candidates who claim "RLS protects us" without having run it lose credibility quickly.

## I.1 Four different questions: authentication, tenant resolution, authorization, data isolation

Most multi-tenant bugs come from blending these four ideas. Keep them separate in your head and in your answers.

| Concern | The question it answers | Project mechanism | Where | Fails how |
|---|---|---|---|---|
| Authentication | Who are you, and can you prove it? | Email + password checked with BCrypt at login, then a signed HS256 JWT on every later request | `AuthService.login`, `JwtService.verify`, `JwtAuthFilter` | Forged or expired token accepted; weak password check |
| Tenant resolution | Which customer organisation is this request acting for? | Login: slug in the request body selects whose credentials to check (server-side lookup). Every later request: the `tid` claim of the verified token | `AuthService.login`, `JwtAuthFilter`, `TenantContext` | Tenant id taken from a header, path or body; thread-local not cleared |
| Authorization | Is this user allowed to do this action on this kind of object? | Role hierarchy ADMIN > MANAGER > AGENT, CUSTOMER outside it; `@PreAuthorize`; object checks such as "customers see only their own tickets" | `SecurityConfig`, controllers, `TicketService.loadVisible` | Missing annotation; role check without object check |
| Data isolation | Can this query or write physically touch another tenant's rows? | Hibernate filter, explicit `tenant_id` predicates, composite foreign keys, optional RLS | `tenancy/*`, repositories, Flyway migrations | A query without a tenant predicate; native SQL; a cache key without a tenant |

Why they are different:

- A user can be **authenticated** (valid token) and still be refused by **authorization** (CUSTOMER calling an analytics endpoint gets 403).
- A user can be **authorized** for an action (AGENT may read tickets) and still be refused by **data isolation** (the ticket id belongs to another tenant, so the answer is 404).
- **Tenant resolution** is the bridge: it turns an authenticated identity into the single tenant id that every later layer uses. If resolution is wrong, authorization and isolation both operate on the wrong tenant and look correct.

### I.1.1 Walk through one request (Project-implemented behaviour)

The request is `GET /api/v1/tickets/{id}` with `Authorization: Bearer <jwt>`.

1. The gateway (a separate WebFlux service) checks signature and expiry, applies its rate limit and forwards the request. It never decides tenant.
2. In core-service, `CorrelationIdFilter` runs first and will clear the tenant thread-local at the very end.
3. `JwtAuthFilter` verifies the token with `JwtService.verify` (signature, issuer, expiry with 5 seconds of clock skew, claims shape), asks `TokenRevocationService.isRevoked(jti)`, builds a Spring `Authentication` with `ROLE_AGENT` style authorities and calls `TenantContext.set(u.tenantId())`. That `set` call is the tenant resolution step.
4. `SecurityFilterChain` requires an authenticated principal for every route except login, register-organization, health, Prometheus and the OpenAPI pages.
5. The controller carries `@PreAuthorize`; Spring evaluates the role against the hierarchy. This is authorization.
6. The service opens a transaction. `TenantAwareJpaTransactionManager.createEntityManagerForTransaction` enables the Hibernate `tenantFilter` with the bound tenant. In the `docker` profile `doBegin` also sets the PostgreSQL session variable.
7. The repository call `findByIdAndTenantId(id, tenant)` adds an explicit predicate. If the id belongs to another tenant the result is empty and the service throws a not-found error, which becomes HTTP 404.

If you can narrate those seven steps and name the class at each step, you can answer most questions in this part.

## I.2 Tenant resolution

### I.2.1 What it is

Tenant resolution is the act of deciding which tenant a unit of work belongs to, and carrying that decision to every layer that needs it. A "unit of work" is a web request, a scheduled job iteration or a message handler.

### I.2.2 Why it exists

Without a single, trusted tenant id, every service method would need the caller to pass one in, and some caller would eventually pass the wrong one, or pass one that the client invented. Resolution centralises the decision so there is exactly one place to audit.

### I.2.3 How it works internally

There are four common resolution sources. The first three come from the request and each has a trust problem.

| Source | Example | Trust problem | Used here? |
|---|---|---|---|
| Subdomain | `acme.example.com` | Needs wildcard DNS and TLS; must still be matched to the token | No |
| Header | `X-Tenant-Id: ...` | Client controls it; must be checked against the token, which makes it redundant | No (ignored if sent) |
| Path or query | `/tenants/{id}/tickets` | Client controls it; classic IDOR source | No (an architecture test forbids it) |
| Signed token claim | `tid` inside the JWT | Cannot be changed without the signing key | **Yes** |

The project chose the token claim. The mechanics:

- `JwtService.issue` writes `sub` (user id), `tid` (tenant id), `roles`, `email`, `jti`, `iss`, `iat`, `exp` and signs with HS256.
- `JwtAuthFilter` is the only request-time code that calls `TenantContext.set`. It does so after the signature is verified.
- `TenantContext` is a `ThreadLocal<UUID>` plus an SLF4J MDC entry `tenantId` so every log line carries it (`logging.pattern.console` includes `tenant=%X{tenantId:-}`).
- The filter and `CorrelationIdFilter` both clear the context in a `finally` block. A thread-pool thread that is reused for the next request therefore starts with no tenant.

There is one legitimate place where a client names a tenant: the login request contains a `tenantSlug`. `AuthService.login` looks the organisation up by slug on the server and uses the slug only to choose **whose credentials to verify**. The tenant id that goes into the issued token is the organisation's own id from the database, never a value the client sent.

### I.2.4 Why this design was selected

ADR-004 records the reasoning: the cheapest multi-tenant mistake is trusting a tenant id from the client. A signed claim makes the trust decision once, at token verification, and all later code reads from a thread-local that nobody else may write. The project also lets the build enforce the rule (I.4.6).

### I.2.5 How it is configured

There is no tenancy-specific switch for resolution; it is code. The related settings are:

| Property | Default | Meaning |
|---|---|---|
| `cx.security.jwt.secret` (env `CX_JWT_SECRET`) | dummy dev value | Signing key; the `docker` profile refuses the dummy |
| `cx.security.jwt.ttl-minutes` | 60 | Token lifetime, so also the longest time a deactivated user or tenant keeps working |
| `cx.tenancy.pg-rls` | false | Turns on the PostgreSQL session variable even outside the `docker` profile |

### I.2.6 How THIS PROJECT uses it (Project-implemented behaviour)

- Request path: `JwtAuthFilter` binds from `tid`.
- Login and registration: `AuthService` binds with `TenantContext.callAs` / `runAs` after it has looked up the organisation.
- System jobs: `SlaMonitorJob` iterates tenants and binds each one itself. `TicketCommandService` and `DemoDataSeeder` are also on the allow-list.
- `TenantProvisioner` binds the tenant id it just generated when creating a new organisation.
- The allow-list lives in `ArchitectureRulesTest.MAY_BIND_TENANT`.

### I.2.7 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Tenant from the signed `tid` claim only | Subdomain or custom-domain resolution |
| Client-supplied tenant header or parameter is ignored (`TenantIsolationTest.clientSuppliedTenantIdentifiersAreIgnored`) | Users who belong to several tenants (one login per tenant) |
| Thread-local cleared in `finally` | Propagation to `@Async`, virtual-thread hops or reactive code (the core is blocking MVC) |
| MDC tenant id in every log line | Tenant status check at token verification (suspended tenants keep working until `exp`) |

### I.2.8 Additional features worth knowing

- `TenantContext.callAs` restores the previous binding in a `finally`, so nested calls do not leave the wrong tenant behind.
- `TenantContext.require()` throws `IllegalStateException` when no tenant is bound. A missing tenant is a programming error, not a 400.
- Audit rows and outbox events call `TenantContext.require()`, so they cannot be written without a tenant.

### I.2.9 Performance implications

Reading a thread-local is effectively free. The costs are indirect: resolution happens after JWT verification (one HMAC per request) and one revocation check. The revocation check is the expensive part, which is why `TokenRevocationService` has a 3-second "known not revoked" cache; the trade-off is described in Part K.

### I.2.10 Security considerations

- The secret is symmetric and shared by gateway, core and notification-service. Anyone who learns it can mint a token for any tenant. Production should use asymmetric keys.
- A token lives for 60 minutes. Disabling a user or a tenant does not stop existing tokens (a known gap, see I.8).
- Thread-bound context must never be carried across threads by hand. If a future feature uses `@Async`, copy the tenant explicitly and bind it with `callAs`.

### I.2.11 Common mistakes

- Reading `X-Tenant-Id` "just for convenience" in one controller.
- Setting the tenant in a filter but forgetting to clear it, so a pooled thread serves the next request with the previous tenant.
- Using the tenant from the URL for authorization and the tenant from the token for queries, so the two disagree.
- Resolving the tenant after the cache or database has already been touched.

### I.2.12 Debugging

1. Log lines carry `tenant=` and `user=`. Search for the correlation id of the failing request (`X-Correlation-Id` header) and check that the tenant is the one you expected.
2. Decode the token (header and payload are base64url, the signature is what matters) and compare `tid` with the organisation id in the `organization` table.
3. If a query returns zero rows unexpectedly, check whether a transaction was started with no tenant bound; the Hibernate filter and the RLS variable are only applied when a tenant is bound.
4. `IllegalStateException: No tenant bound to the current thread` means a code path ran outside a request or job that bound a tenant.

### I.2.13 Alternatives and trade-offs

| Option | Strength | Weakness |
|---|---|---|
| Signed token claim (chosen) | One trust decision; works for SPA and API clients | Switching tenant means re-login; revocation by tenant is not instant |
| Subdomain | Good user experience, simple per-tenant branding | DNS, wildcard TLS, and still needs authentication |
| Opaque token looked up server-side | Instant revocation, per-tenant kill switch | A lookup (database or cache) on every request |
| Header validated against token | Convenient for internal tools | Redundant; the validation is the whole security |

### I.2.14 Conceptual learning example: tenant propagation to a background thread

This is NOT in the repository. It shows what you must do the day someone adds `@Async`.

```java
// Conceptual learning example
UUID tenant = TenantContext.require();
executor.submit(() -> TenantContext.runAs(tenant, () -> doWork()));
```

The key point is that the tenant id is captured on the submitting thread and bound explicitly on the worker thread, inside a scope that restores the previous value.

### I.2.15 Interview Q&A

**Q1. Where does the tenant id come from, and how do you stop a client from lying about it?**
It comes from the `tid` claim of a signature-verified JWT. `JwtAuthFilter` is the only request-time code that writes the thread-local. A forged header or query parameter is never read, and `TenantIsolationTest.clientSuppliedTenantIdentifiersAreIgnored` demonstrates it. The build also fails if a controller parameter name contains "tenant" or if a class outside an allow-list calls `TenantContext.set`, `callAs` or `runAs` (`ArchitectureRulesTest`). The honest limit: if the HMAC secret leaks, an attacker can sign any `tid`.

**Q2. Login contains a tenant slug from the client. Is that not client-supplied tenant input?**
It is, and it is the one allowed exception. The slug selects which organisation's credentials to check. The server looks the organisation up, verifies the password for a user inside that organisation, and puts the organisation's own id into the token. A wrong slug fails with the same generic "Invalid credentials" message as a wrong password, and a dummy BCrypt hash is verified when the tenant or user does not exist so timing does not reveal which part was wrong.

**Q3. What happens to the tenant when a request finishes?**
`JwtAuthFilter` and `CorrelationIdFilter` clear the thread-local and the MDC entry in `finally`. Servlet threads are pooled, so failing to clear would leak one request's tenant into the next. Part of the design is that "no tenant bound" is safe: the RLS function returns NULL and matches no rows, and `TenantContext.require()` throws.

**Q4. How would you support a user who belongs to two tenants?**
The current design needs one login per tenant, because the token carries one `tid`. Options: a tenant switcher that calls an endpoint which verifies membership and issues a new token for the other tenant; or an identity provider that issues tokens per tenant. Both keep the property that the tenant in the token is decided by the server.

**Q5. A tenant is suspended for non-payment. How fast does it take effect?**
Not instantly. There is no tenant status check in the token path, so existing tokens work until they expire (up to 60 minutes) unless individually revoked. The fix is a tenant status check (cached for a few seconds) in `JwtAuthFilter`, or a tenant-level token version in the claims.

## I.3 Isolation strategies

### I.3.1 What it is

An isolation strategy is how tenant data is physically separated. There are four families, and real products often mix them.

![Figure: Four tenant isolation models; the project uses the first](docs/diagrams/png/19-isolation-models.png)

### I.3.2 Why it exists

Isolation is a trade between three forces: safety (how hard is it to leak), cost (how much does each tenant add to the bill and to operations) and flexibility (can you move, restore or tune one tenant). No model wins all three.

### I.3.3 How each model works

**Shared schema with a discriminator.** One set of tables; every row has `tenant_id`. Queries must filter by it. Indexes lead with it. Migrations run once.

**Schema per tenant.** One database, many schemas (`acme.ticket`, `globex.ticket`). The connection sets `search_path` per request. Migrations run once per schema.

**Database per tenant.** A separate database (or server) for each tenant. A routing data source picks the connection from the tenant id.

**Hybrid.** Small tenants share; large or regulated tenants get dedicated resources. A tenant directory maps tenant id to location.

### I.3.4 Comparison table

| Dimension | Shared schema + `tenant_id` | Schema per tenant | Database per tenant | Hybrid |
|---|---|---|---|---|
| Isolation strength | Logical only; depends on predicates and constraints | Stronger; wrong table name needed to leak | Strongest; separate credentials, backups, tuning | As strong as the tier the tenant is on |
| Cost per tenant | Lowest (a few rows) | Medium (catalog entries, migrations) | Highest (pool, backups, monitoring) | Mixed |
| Migrations | One run | N runs; partial failure states | N runs; orchestration needed | Both problems |
| Connection pooling | One pool | One pool with `search_path` switching (hazards) | One pool per tenant or a routing layer | Complex |
| Noisy neighbour | Highest risk (shared indexes, pool, cache) | Shared server resources | Contained per database | Contained for big tenants |
| Per-tenant backup and restore | Hard (needs export tooling) | Moderate (`pg_dump -n`) | Easy | Easy for dedicated tenants |
| Cross-tenant analytics for the operator | Easy | Harder | Hardest | Mixed |
| Leak risk from one missing predicate | **High** | Low | Very low | Depends on tier |
| Onboarding time | Milliseconds (insert rows) | Seconds to minutes | Minutes (provision) | Mixed |
| Typical scale | Thousands to millions of small tenants | Hundreds to low thousands | Tens to hundreds of large tenants | Large platforms |

### I.3.5 Why the project chose shared schema (Project-implemented behaviour)

ADR-002 in `ARCHITECTURE-DECISIONS.md` gives the reasoning. Restated:

- The product is a CX plus ITSM help desk. Tenants are expected to be small to medium, so per-database cost would dominate.
- One schema means one Flyway history (`db/migration`, with `db/migration-pg` added for PostgreSQL) and one set of indexes to tune.
- Cross-tenant operator views (the SLA job iterates all tenants) are a simple query.
- Because the model has the highest leak risk, the project invests in layers instead of trusting any single one.

The costs are stated in the same ADR: noisy neighbours, indexes must lead with `tenant_id`, native SQL bypasses the Hibernate filter, and tenant data cannot be moved or restored independently without tooling.

### I.3.6 How it is configured

There is nothing to configure per tenant. The configuration is structural: every tenant-owned table has `tenant_id uuid not null`, indexes begin with `tenant_id` (`V15__ticket_indexes.sql`), and entities extend `TenantOwned`.

### I.3.7 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Shared schema with `tenant_id` on every tenant table | Schema-per-tenant or database-per-tenant routing |
| Composite unique keys `(tenant_id, id)` so children can reference parents safely | A tenant directory or a way to move one tenant to its own database |
| Per-tenant ticket numbering (`unique (tenant_id, number)`, `ticket_counter`) | Per-tenant backup or restore tooling |
| Per-tenant reference data created at registration (`TenantProvisioner`) | Regional cells or data residency |

### I.3.8 Additional features

- **Tenant-scoped numbering.** `ticket_counter` is one row per tenant. `TenancyTest.ticketNumbersAreUniqueAndGapFreeUnderConcurrency` proves unique, gap-free numbers per tenant under concurrency.
- **Operator cross-tenant reads.** `OrganizationRepository.findAllByOrderBySlugAsc` is deliberately not tenant-filtered because `organization` is the tenant registry.

### I.3.9 Performance implications

- Every index should begin with `tenant_id`, otherwise a query for one tenant scans rows of all tenants. The performance guide in the repository shows query-plan evidence for this.
- One large tenant makes shared indexes bigger and vacuum slower for everyone.
- The shared Hikari pool, the shared cache and the shared AI worker pool are the contended resources (I.6).

### I.3.10 Security considerations

- The safety of this model is the sum of its layers. Remove the explicit predicate, the Hibernate filter still protects JPQL; remove both, only RLS (not executed here) would catch it.
- Native SQL and `JdbcTemplate` do not pass through the Hibernate filter. The project's rule is "no native SQL outside reviewed repositories", which is a convention, not an enforced check.

### I.3.11 Common mistakes

- Putting `tenant_id` on the table but not in the index, so isolation is correct but performance collapses.
- Using a surrogate id only for foreign keys, so a child row can point at another tenant's parent.
- Letting one tenant's id leak into a global unique constraint (for example a unique email across all tenants) when the business rule is "unique within a tenant".
- Treating tenant tables and global tables the same in migrations; `organization` and `revoked_token` are intentionally outside RLS.

### I.3.12 Debugging

- **Slow query for one tenant:** `EXPLAIN` the query and look for a sequential scan or an index that does not start with `tenant_id`.
- **Row visible to the wrong tenant (in a test):** check whether the query is native, a `JdbcTemplate` call, or an `em.find`. Hibernate filters do not apply to `em.find`/`getById`.
- **Constraint violation on insert:** a composite foreign key rejecting a cross-tenant reference is the database doing its job; look for a bug that mixed ids from two tenants.

### I.3.13 Alternatives and trade-offs

See the comparison table. A pragmatic path used by many SaaS companies: start with shared schema, keep `tenant_id` everywhere so export is possible, and promote the largest or most regulated tenants to dedicated databases later. The project's design keeps that option open but does not build the routing.

### I.3.14 Conceptual learning example: schema per tenant routing

NOT in the repository.

```java
// Conceptual learning example: switch search_path per transaction
jdbc.execute("set local search_path to tenant_" + safeSlug + ", public");
```

`safeSlug` must come from the server's tenant registry, never from user input, otherwise this is SQL injection. Pooled connections must have the path reset (`set local` helps for the same reason as in RLS). Migrations must iterate every schema and handle half-migrated states.

### I.3.15 Interview Q&A

**Q1. Why did you not use database-per-tenant?**
Cost and operations. Each database needs a pool, migrations, backups and monitoring; for hundreds of small help-desk tenants that is wasteful, and onboarding would take minutes instead of milliseconds. The price of the choice is that isolation is logical, so I stacked layers (filter, explicit predicates, composite foreign keys, optional RLS, tests) and documented the noisy-neighbour risk. If a tenant needed contractual isolation I would promote it to a dedicated database using the retained `tenant_id` for export.

**Q2. What is the main failure mode of a shared schema and how do you test for it?**
A query that forgets the tenant predicate. Tests: `TenancyTest.hibernateTenantFilterRestrictsEvenUnscopedQueries` shows the filter catches an unscoped JPQL query; `TenantIsolationTest` performs cross-tenant reads, updates, transitions, assignments and comments over HTTP and expects 404. Gap: native SQL is not covered by those, and no automated fuzzing of every id-bearing route exists.

**Q3. Compare schema-per-tenant and shared schema for migrations.**
Shared schema: one migration run, so a failure is all-or-nothing. Schema per tenant: N runs; some tenants can be on the new version and some on the old, application code must tolerate both, and long loops block deployments. That operational cost is the usual reason teams avoid it at large tenant counts.

**Q4. When would a hybrid model be justified?**
When a few tenants are very large (noisy neighbours), regulated (data residency, customer-managed keys) or contractually isolated. You introduce a tenant directory that maps tenant id to a data source, and keep the application code identical. The project does not have the directory; `tenant_id` on every row is what would make the migration feasible.

**Q5. What is "composite tenant-safe foreign key" and why do it?**
Instead of `ticket.requester_id -> app_user.id`, the foreign key is `(tenant_id, requester_id) -> app_user (tenant_id, id)`, which requires a unique constraint on `(tenant_id, id)` in the parent. The database then refuses a ticket in tenant A that references a user in tenant B, even if application code has a bug. Details in I.4.4.

## I.4 Layered isolation in the project

This section is the technical core. The project applies isolation in six layers. They are independent: each one catches a different class of mistake.

| Layer | Mechanism | Catches | Executed in tests? |
|---|---|---|---|
| 1 | Verified token, tenant bound from `tid` | Client lying about tenant | Yes |
| 2 | Hibernate `tenantFilter` per transaction | A JPQL or Criteria query that forgot the tenant | Yes (`TenancyTest`) |
| 3 | Explicit `tenant_id` predicates in repositories | `em.find`, `getById`, any path the filter does not cover; also makes intent visible | Yes (`TenantIsolationTest`) |
| 4 | Composite tenant foreign keys | A row that references another tenant's parent | Yes (H2 enforces them) |
| 5 | `@PrePersist` guard | A write for a tenant other than the bound one | Yes (`TenancyTest.crossTenantWritesAreBlockedAtPersist`) |
| 6 | PostgreSQL RLS with `SET LOCAL` | Native SQL, forgotten predicates, future developers | **No. Defined only; never executed.** |

### I.4.1 The Hibernate filter

**What it is.** A Hibernate filter is a named, parameterised SQL fragment that Hibernate appends to queries on entities that declare it. `TenantOwned` declares `@FilterDef(name = "tenantFilter", parameters = tenantId)` and its condition constant is `tenant_id = :tenantId`. Entities such as `AppUser`, `AuditEvent`, `AiSuggestion` and `DocumentEntity` add `@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)`.

**Why it exists.** Developers forget predicates. A filter makes the safe behaviour the default for JPQL and Criteria queries.

**How it works internally.** Filters are enabled on a Hibernate `Session`. `TenantAwareJpaTransactionManager` extends `JpaTransactionManager` and overrides `createEntityManagerForTransaction`: after Spring creates the entity manager for the transaction, the code unwraps the Hibernate `Session` and calls `enableFilter("tenantFilter").setParameter("tenantId", t)` if a tenant is bound. This is the single choke point for web requests, scheduled jobs and listeners, because they all use this transaction manager (`TenancyConfig` replaces Boot's default manager bean).

**Why selected.** A transaction manager hook is lower risk than a request interceptor, because it also covers code that runs outside a web request, and it cannot be bypassed by forgetting an annotation.

**Configuration.** None for the filter. `TenancyConfig` builds the manager and decides whether to also set the RLS variable (`cx.tenancy.pg-rls` or the `docker` profile).

**Known limits (be ready to state them).**

1. Filters do **not** apply to `em.find()` or `getReferenceById()`. That is why repositories use `findByIdAndTenantId`.
2. Filters do not apply to native SQL or `JdbcTemplate`.
3. The filter parameter is set when the entity manager is created. If code changes the bound tenant after the transaction has started, the filter still holds the old value. The project avoids nesting a different tenant inside an open transaction, but it is a trap for future code.
4. If no tenant is bound when the transaction starts, no filter is enabled. Login and system jobs rely on explicit predicates in that situation.

**Debugging.** Turn on `org.hibernate.SQL` at DEBUG in a throwaway run and verify that `tenant_id = ?` appears in the SQL. The default is WARN, and the key=value log pattern prints SQL if you turn it on.

### I.4.2 Explicit tenant predicates

`TicketRepository` is a good example. Every method names the tenant: `findByIdAndTenantId`, `countByTenantIdAndStatusNotIn`, and `@Query` methods with `where t.tenantId = :t`. The class comment says "Every query carries an explicit tenant predicate (in addition to the Hibernate tenant filter and PG RLS)". `TicketSearch` adds `tenantId` first in its specification.

Why duplicate what the filter does? Because the filter has the gaps above, and because an explicit predicate documents intent for the reader and for the query planner (the index is `tenant_id` first).

**Implemented vs not.** Implemented for ticket, user, audit, category, KB, AI and document repositories. Not enforced by a tool: there is no static check that every repository method contains a tenant predicate. A reviewer or a future ArchUnit rule has to do that.

### I.4.3 The `@PrePersist` guard

`TenantOwned.assignOrVerifyTenant` runs before an insert. If the entity has no tenant it takes the bound one; if there is no bound tenant it throws; if the entity's tenant differs from the bound tenant it throws "Cross-tenant write blocked". Tests: `TenancyTest.crossTenantWritesAreBlockedAtPersist` and `persistWithoutAnyTenantIsRefused`.

Limit: bulk `@Modifying` updates bypass entity callbacks, so they rely on the explicit predicate (for example `repo.decide(id, tenant, ...)` in the AI module includes the tenant).

### I.4.4 Composite tenant foreign keys

**What.** A foreign key that includes `tenant_id`.

**Why.** An id is a UUID and unique globally, so a plain foreign key would happily link tenant A's ticket to tenant B's user if a bug supplied that id. The composite key makes the database check the tenant too.

**How.** The parent declares a unique constraint on `(tenant_id, id)` (for ticket: `uq_ticket_tenant_id`). Children declare, for example in `V7__ticket.sql`:

```sql
constraint fk_ticket_requester foreign key (tenant_id, requester_id) references app_user (tenant_id, id),
constraint fk_ticket_assignee  foreign key (tenant_id, assignee_id)  references app_user (tenant_id, id),
constraint fk_ticket_category  foreign key (tenant_id, category_id)  references ticket_category (tenant_id, id),
constraint uq_ticket_tenant_number unique (tenant_id, number),
constraint uq_ticket_tenant_id unique (tenant_id, id)
```

`V9__ticket_comment.sql` does the same for comments, and `V22__ai_suggestion.sql` for AI suggestions (`fk_ai_suggestion_ticket`, `fk_ai_suggestion_creator`).

**Why selected.** It needs no extra code and works on H2 and PostgreSQL alike, so it is executed by the test suite (unlike RLS).

**Cost.** Larger unique indexes; slightly more complex inserts; one more thing to remember when adding a table.

**Common mistake.** Adding the composite foreign key to the child but forgetting the `(tenant_id, id)` unique constraint on the parent, so the migration fails; or nullable child columns where `tenant_id` is not null but `requester_id` is null (a null column in a composite foreign key means the constraint is skipped under the default match rule, which is correct for optional references but worth knowing).

**Debugging.** A `DataIntegrityViolationException` on a foreign key at insert almost always means two tenants' ids were mixed. Log the ids (never the content) and find which one came from the wrong tenant.

### I.4.5 PostgreSQL row-level security (defined, NOT executed)

**What it is.** RLS lets the database attach a policy to a table so that every statement sees only rows where the policy is true.

**Why it exists.** It is the only layer that survives application mistakes such as native SQL or a forgotten predicate.

**How the project defines it (Project-implemented definition, never run).** `db/migration-pg/V1002__pg_row_level_security.sql`:

```sql
create or replace function cx_current_tenant() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

alter table ticket enable row level security;
alter table ticket force row level security;
create policy tenant_isolation on ticket
  using (tenant_id = cx_current_tenant()) with check (tenant_id = cx_current_tenant());
```

The real file loops over 11 tables; `V1011__pg_rls_kb_ai.sql` extends it to the KB, AI and document tables (16 in total according to `docs/security-hardening.md`). `TenantAwareJpaTransactionManager.doBegin` runs `select set_config('app.tenant_id', ?, true)`. The `true` argument is the "is local" flag, so it behaves like `SET LOCAL`: the value ends with the transaction and a pooled connection cannot carry it to the next borrower.

**Fail closed.** If no tenant is set, `current_setting(..., true)` returns NULL, the function returns NULL, and `tenant_id = NULL` is never true, so no rows are visible.

**Deliberately outside RLS.** `organization` (login must find the tenant before a tenant is known), `revoked_token` (no tenant column), `outbox_event` (the relay claims across tenants). These are protected by application logic only.

**Caveats.** RLS does not apply to superusers or roles with `BYPASSRLS`; `FORCE ROW LEVEL SECURITY` extends it to the table owner. Production should run Flyway as an owner role and the application as a non-owner role. The project's compose file uses a single database user that is the owner, which `FORCE` covers but is not best practice.

**NOT done.** `PostgresRlsDockerIT` (Testcontainers, tag `docker`) exists and describes what it would verify: migrations apply, RLS isolates per transaction, it fails closed without a tenant, and the audit trigger blocks updates. It was **never run** because Docker is not installed. Describe RLS as "a reviewed design", not a verified control.

**Performance.** One extra statement per transaction and a predicate the planner adds to every query. Not measured here.

**Alternatives.** Application-only filtering (what the `local` profile is); per-connection session variables (rejected, because a leaked variable on a pooled connection is the classic RLS bug); database-per-tenant.

### I.4.6 The architecture tests

`core-service/src/test/java/com/example/cx/core/ArchitectureRulesTest.java` is a cheap, source-level guard:

| Test | Rule it enforces |
|---|---|
| `tenantIsBoundOnlyByTheAllowedComponents` | Only 7 files may call `TenantContext.set/callAs/runAs` (the context itself, the JWT filter, auth service, provisioner, SLA job, ticket command service, demo seeder) |
| `controllersNeverAcceptATenantIdFromTheClient` | No controller method parameter has "tenant" in its name or request-param name |
| `controllersNeverExposeEntities` | No `@Entity` in controller return or parameter types |
| `auditRepositoryIsAppendOnly` | The audit repository has no delete or save-all methods and is not a CRUD repository |

What they do not prove: they scan source text and reflection; they do not prove every repository method filters by tenant, and they would not catch a new class that reads a tenant from a header into a local variable and passes it to a service by another name.

### I.4.7 Runnable example: prove the filter works (Project-implemented test pattern)

Run only the isolation tests from the repository root. The repository has no Maven wrapper (`RUN-AND-TEST-GUIDE.md` explains the Maven path); use your own JDK 21 and plain `mvn`, and set `JAVA_HOME` inline for the command only, never globally:

```bash
JAVA_HOME=C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7 mvn -q -pl core-service test -Dtest='TenancyTest,TenantIsolationTest,ArchitectureRulesTest'
```

Then do a controlled mutation to see the layers working:

1. In `TicketService` temporarily replace a `findByIdAndTenantId(id, tenant)` with `findById(id)`.
2. Run `TenantIsolationTest`. Because `findById` uses `em.find`, which the filter does not cover, the cross-tenant read test should fail. That is the reason the explicit predicate exists.
3. Revert the change.

### I.4.8 Conceptual learning example: a missing-predicate bug

```java
// Conceptual learning example: BAD
@Query("select t from Ticket t where t.number = :n")
Optional<Ticket> findByNumber(@Param("n") long n);
```

With the Hibernate filter enabled this JPQL is protected, because the filter appends `tenant_id = ?`. With a native query version it is not:

```java
// Conceptual learning example: BAD, filter does not apply
@Query(value = "select * from ticket where number = :n", nativeQuery = true)
Optional<Ticket> findByNumberNative(@Param("n") long n);
```

Fix: add `and tenant_id = :t`. Defence in depth: RLS would also catch it, if it were running.

### I.4.9 Implemented vs NOT implemented (summary for I.4)

| Implemented and executed | Defined but not executed | Not implemented |
|---|---|---|
| Token-derived tenant; Hibernate filter; explicit predicates; `@PrePersist`; composite FKs; architecture tests | RLS policies; `SET LOCAL` per transaction; audit trigger; `PostgresRlsDockerIT` | Static check that every repository method has a tenant predicate; ban on native SQL enforced by a tool; separate migration and runtime database roles |

### I.4.10 Interview Q&A

**Q1. Why both a Hibernate filter and explicit predicates?**
Because they cover different gaps. The filter makes JPQL and Criteria queries safe by default; it does not apply to `em.find`, `getById` or native SQL. Explicit finders such as `findByIdAndTenantId` cover the by-id path and make the tenant visible in the query, and they let the planner use a `tenant_id`-first index. The filter protects against forgetting; the predicate protects against the filter's blind spots.

**Q2. How would RLS help if the application already filters?**
It protects against the failure of the application filter itself: native SQL, a new developer, a bug that disables the filter. The database enforces `tenant_id = current_setting('app.tenant_id')`. I must be honest that in this project the RLS is defined and never executed, so I describe it as an unverified design. I would run `PostgresRlsDockerIT` in CI on a Docker host and run the application with a non-owner, non-`BYPASSRLS` role.

**Q3. Why `SET LOCAL` and not a plain `SET`?**
A plain `SET` lasts for the session. With a connection pool the session outlives the request, so the next borrower of that connection would inherit the previous tenant (or, with a bug, run with it set). `set_config(name, value, true)` is transaction-local: it disappears at commit or rollback, so a connection returned to the pool carries nothing.

**Q4. A developer adds a native query for a report. What stops a cross-tenant leak?**
Today only convention and code review; the Hibernate filter does not apply and RLS is not running. What I would add: an ArchUnit or source test that fails on `nativeQuery = true` or `JdbcTemplate` outside an allow-listed package; a mandatory cross-tenant test for each new repository; and RLS executed in CI.

**Q5. Why composite foreign keys when you already have a tenant filter?**
The filter protects reads through the ORM. A composite foreign key protects the data's integrity at write time, independently of the ORM. It stops a ticket from referencing another tenant's user or category even if a service method mixed two ids. It also works on H2, so the test suite exercises it.

## I.5 Tenant-scoped cache, events, jobs, analytics, audit

Isolation is not only the database. Every shared resource that stores or moves data needs a tenant dimension.

### I.5.1 Cache keys

**What and why.** A cache is a shared, in-memory (Caffeine) or remote (Redis) map. If two tenants can produce the same key, one of them reads the other's data. The fix is to make the tenant part of every key.

**How it works (Project-implemented behaviour).** `CacheService` is the interface. `CacheService.fullKey(tenantId, key)` returns `"t:" + tenantId + ":" + key` and throws `IllegalArgumentException` if `tenantId` is null. Callers pass the tenant explicitly. Implementations: `CaffeineCacheService` (profile `!docker`, bounded by `cx.cache.max-entries`, per-entry TTL, single-flight loading with a `CompletableFuture` per key so a hot expired key does not stampede the database) and `RedisCacheService` (`docker` profile, never executed). `evictByPrefix(tenantId, prefix)` drops all keys of one tenant with a given prefix.

Real usages: `AnalyticsService` (`analytics:dashboard:<days>`, evicted after an AI decision changes the acceptance KPI), `TenantSettingsService`, `CategoryService`, `SlaService`, `WorkflowService` and `KbService` (5-minute reference data, 60-second article pages). Values are DTOs or records, never entities, so no lazy proxies or session state are cached.

**Why selected.** Putting the tenant in the key by construction (the method signature takes `UUID tenantId`) means a caller cannot forget it. Prefix eviction lets one tenant's data be flushed without touching the others.

**Performance.** The key prefix costs a few bytes. Single-flight loading prevents a cache stampede per node. The cache is per-node in the local profile, so a write on one node does not evict another node's entry; Redis would fix that.

**Security.** A cache key without the tenant is a cross-tenant leak; there is no test that scans every cache call site, but `CacheServiceTest` covers the key contract.

**Common mistakes.** Caching by `ticketId` alone (ids are unique but authorization differs); caching an entity that holds a lazy association; caching per-user data under a tenant-only key (customer sees agent's data). The project's cached data is tenant-wide reference data or dashboard aggregates only, which is why a tenant key is sufficient.

**Debugging.** Metrics `cx.cache{cache=..., result=hit|miss}` show whether the cache is being used. If one tenant sees stale data, check the eviction call after the write (`TxHooks.afterCommit(() -> cache.evictByPrefix(...))` is used so eviction happens after commit, not before).

**Not implemented.** Per-tenant cache size quotas: one tenant with huge key variety can evict others' entries from the shared bounded cache.

### I.5.2 Events and the outbox

**What.** Domain events (for example ticket created, AI suggestion decided) are written to an `outbox_event` table in the same transaction as the business change, and a relay publishes them to RabbitMQ (or, in the local profile, to an in-process publisher).

**Tenant dimension.** `OutboxService` calls `TenantContext.require()` and puts the tenant into both the `DomainEvent` (`tenantId` field) and the row (`row.setTenantId`). Consumers (notification-service) require `eventId`, `tenantId` and `type`; an unparseable or incomplete message goes to the dead-letter queue.

**Why it matters.** An event with no tenant would be an orphan; an event with the wrong tenant would deliver a notification to the wrong organisation.

**Honest gap (from the security notes).** The broker is trusted. Notification-service believes the `tenantId` and `recipientUserIds` in the event and does not verify the recipients belong to that tenant. Anyone who can publish to the exchange could create notifications for any tenant. Mitigation: per-service broker users with least-privilege permissions, publisher identity checks, and recipient validation in the consumer.

**Why `outbox_event` is not under RLS.** The relay claims due rows across all tenants. This is a case where a cross-tenant system component is legitimate, and the isolation is carried in the payload.

**Payload minimisation.** AI events carry ids and the decision only, never ticket text.

### I.5.3 Scheduled jobs

**What.** `SlaMonitorJob` runs every minute (`cx.sla.job-interval-ms`, 60000). It pages through organisations (100 per page) and for each tenant calls `TenantContext.callAs(tenantId, () -> sla.scanCurrentTenant(batch))`.

**Tenant dimension.** The job binds the tenant itself, one tenant at a time, so the Hibernate filter and the RLS variable are applied for each tenant's work. It never reads a tenant from a request.

**Fairness and fault isolation.** Work per tenant is bounded (`cx.sla.batch-per-tenant`, 200). `TicketRepository` orders by "least recently checked first" using `coalesce(slaCheckedAt, epoch)`, so tickets rotate fairly across runs. An exception in one tenant is caught, counted (`cx.sla.job.errors`) and logged with the tenant id, and the loop continues.

**Multi-node note.** Overlapping runs on several nodes are harmless because the scan is idempotent, but redundant; a leader lock (ShedLock) is the standard fix. Not implemented.

**Other jobs.** `OutboxPurgeJob` deletes delivered rows after 7 days in bounded batches (500 per batch, 20 batches per run, hourly) and keeps FAILED rows. `TokenRevocationService.purgeExpired` deletes expired revocations hourly.

### I.5.4 Analytics

`AnalyticsService` builds the dashboard from a bounded projection (`TicketRepository.aggregates`, predicate `tenantId = :t`, capped by `Pageable`, 50 000 rows maximum), folds it in memory, and caches it under the tenant key. Customers get 403 on analytics (`RoleMatrixTest`), and `TenantIsolationTest.usersAnalyticsAuditSettingsAndConfigAreTenantScoped` verifies tenant B sees only tenant B's numbers.

Performance caveat: the in-memory fold is bounded but not free; a very large tenant is cut off at the cap. A reporting replica or pre-aggregated tables would be the production answer. Not implemented.

### I.5.5 Audit trail

`AuditEvent` extends `TenantOwned` and carries the filter. `AuditService.record` calls `TenantContext.require()`, joins the caller's transaction (so the audit row commits atomically with the change), stores actor id and email, action, entity type and id, the correlation id from the MDC, and details that pass through `Redaction.redact` and are truncated to 3900 characters. `AuditService.search` always includes `cb.equal(root.get("tenantId"), tenant)` as the first predicate. The repository exposes no delete or update method (`ArchitectureRulesTest.auditRepositoryIsAppendOnly`), and a PostgreSQL trigger would block UPDATE and DELETE (`V1003__pg_audit_append_only.sql`, not executed). Audit read access is ADMIN-only per the role matrix tests (AGENT gets 403).

### I.5.6 Document store and KB

`DocumentEntity` carries the tenant filter; `DocumentStore.put(tenant, collection, id, doc)` takes the tenant explicitly. The Mongo implementation (docker profile) is application-enforced only: there is no RLS equivalent. KB articles, categories and article-ticket links extend `TenantOwned`; `KbService.publishedCandidates(tenant, 100)` takes the tenant as an argument.

### I.5.7 Implemented vs NOT implemented

| Resource | Implemented | Not implemented |
|---|---|---|
| Cache | `t:{tenantId}:` keys, prefix eviction, bounded size | Per-tenant cache quotas |
| Events | `tenantId` in every event and outbox row | Broker per-service permissions; consumer recipient validation |
| Jobs | Tenant bound per iteration, bounded batches, error containment, fair rotation | Leader lock; per-tenant job priority |
| Analytics | Tenant predicate, cap of 50 000 rows, tenant-keyed cache | Pre-aggregation, reporting replica |
| Audit | Tenant column, filter, append-only repository, redaction | Immutable storage shipping, retention policy |

### I.5.8 Common mistakes

- Using `tenantId` from a method argument that a caller computed rather than from `TenantContext`.
- Building a cache key from a user-controlled string that contains `:` to impersonate another prefix. Keys here are `t:{uuid}:{key}` with the tenant first, and the tenant is a UUID, so injection of a different tenant would require controlling the UUID part.
- Publishing an event before commit; the outbox exists to avoid exactly that.
- Logging tenant data in job error messages.

### I.5.9 Debugging

- A job seems to skip a tenant: check `cx.sla.job.errors` and search logs for "SLA scan failed for tenant".
- An event never arrives: query `outbox_event` for the tenant and look at `status` (`SENT`, `PROCESSING`, `FAILED`), `attempts` and `locked_until`.
- A dashboard shows stale numbers after an AI decision: confirm the after-commit eviction (`evictByPrefix(tenant, "analytics:")`) ran.

### I.5.10 Interview Q&A

**Q1. How do you make sure a cache cannot serve tenant A's data to tenant B?**
The `CacheService` API takes `UUID tenantId` as a required argument and builds `t:{tenantId}:{key}`, rejecting a null tenant. Cached values are tenant-wide DTOs, never per-user or entity data. Evictions are by tenant prefix. A gap: nothing automatically checks that every future caller passes the correct tenant; reviewers and tests do.

**Q2. A scheduled job needs to touch every tenant. How do you keep it tenant-safe?**
Bind the tenant per iteration with `TenantContext.callAs` so the filter and RLS variable apply, bound the work per tenant, contain failures per tenant, and rotate fairly. `SlaMonitorJob` does all four. The binding is on the allow-list in `ArchitectureRulesTest`, so adding another system job requires a deliberate change to that list.

**Q3. Why is `outbox_event` excluded from RLS?**
The relay is a system component that claims rows across all tenants. Applying a per-tenant policy would make it see nothing. The protection is that the tenant id is in the payload and consumers must validate it. The consequence, which I would document, is that the table is guarded by application logic only.

**Q4. What is the risk with the broker in a multi-tenant design?**
Messages carry a tenant id that the consumer trusts. If any service or attacker can publish to the exchange, it can create data for any tenant. Mitigate with separate broker users per service, vhost permissions, signed events or publisher identity, and recipient validation in the consumer. This project documents the gap and has not closed it.

**Q5. How does audit stay tenant-safe and tamper-resistant?**
Tenant-safe: the row has `tenant_id`, the search always filters on it, cross-tenant audit is tested. Tamper-resistant: the repository has no update or delete methods (tested), and a database trigger would block them on PostgreSQL (defined, not run). Neither stops a database administrator; shipping audit to immutable storage is the next step.

## I.6 Noisy neighbour controls

### I.6.1 What it is

A noisy neighbour is a tenant whose load degrades service for others because they share resources: CPU, database connections, indexes, cache, thread pools, provider budget.

### I.6.2 Why it exists as a concern here

The chosen isolation model shares everything. ADR-002 admits there is no per-tenant quota in the code. What exists are limits that are not tenant-aware but still bound the damage, plus one tenant-aware limit (AI).

### I.6.3 What the project does (Project-implemented behaviour)

| Control | Where | Scope | What it bounds |
|---|---|---|---|
| Gateway token bucket (100 burst, 20 per second) | `gateway/.../RateLimitFilter` | Per JWT subject (user), per instance; anonymous routes by socket peer | One user's request rate; `X-Forwarded-For` is not trusted |
| Login and registration limiter | `LoginRateLimiter` | Per IP and per account `acct:<slug>:<email>`; Caffeine-bounded to 10 000 keys; per node | Credential stuffing and registration spam |
| AI per-tenant limit | `AiRateLimiter` | Per tenant, 30 per minute, per node, fixed window | One tenant exhausting the provider budget |
| AI bounded pool | `AiAssistService` | 4 threads, 16 queued, abort policy | A slow provider cannot create unbounded backlog |
| Pagination cap | `PageParams` | `size` 1 to 100 | Large reads |
| Whitelisted sort | `PageParams` | Allowed fields only | Unindexed sorts |
| SLA job batch | `cx.sla.batch-per-tenant` | 200 tickets per tenant per run | Background CPU per tenant |
| Outbox relay batch and purge batch | `cx.outbox.*` | 50 per poll, 500 per purge batch | Background load |
| Dashboard fold cap | `AnalyticsService` | 50 000 rows | Memory per request |
| Workflow caps | `WorkflowValidator` | size caps, 20 actions per run | Rule-engine abuse |
| Hikari bounds | Spring config | Pool size | Concurrency to the database |

### I.6.4 How the AI per-tenant limit works internally

`AiRateLimiter` holds a Caffeine cache `UUID -> Window(startMs, AtomicInteger)` with `maximumSize(10_000)` and `expireAfterWrite(1 minute)`. `check(tenantId)` increments the counter and, if it exceeds `cx.ai.rate-limit-per-minute`, throws `RateLimitedException` with a retry-after value computed from the window start. The controller turns it into HTTP 429 `RATE_LIMITED` with `Retry-After`. `AiResilienceUnitTest.rateLimiterIsPerTenant` and `AiApiTest.analyzeIsRateLimitedPerTenant` verify that tenant A being limited does not affect tenant B.

This is a fixed window, so a tenant can burst 2x at a window boundary (30 at the end of one minute plus 30 at the start of the next). The setting is clamped to 1..10 000.

### I.6.5 Why selected

Simple, bounded in memory, no external store. The comment in the code states the limit is per node and "a global limit belongs in the gateway".

### I.6.6 How configured

```yaml
cx:
  ai:
    rate-limit-per-minute: 30   # per tenant, per node
  sla:
    batch-per-tenant: 200
  outbox:
    batch-size: 50
```

The login limiter is configured by `CX_LOGIN_RATE_CAPACITY` (default 10) and `CX_LOGIN_RATE_REFILL_PER_MIN` (default 5).

### I.6.7 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Per-user gateway limit; per-IP and per-account login limit; per-tenant AI limit; bounded batches and pools | Per-tenant API quota; per-tenant concurrency limit; per-tenant DB statement timeout; tenant-tier plans |
| 429 with `Retry-After` | A shared (Redis) limiter; limits are per instance, so N nodes multiply the allowance by N |
| Bounded memory in all limiters | Per-tenant cache or storage quotas; request body size limit configured explicitly (framework default) |

### I.6.8 Performance implications

Rate limiters add a map lookup per request. The bounded caches mean an attacker rotating keys cannot grow the heap. Because limits are per node, scaling out silently relaxes them, which is a surprise for operators.

### I.6.9 Security considerations

- Keying by user rather than tenant means a tenant with many users has many buckets. A tenant-level cap would be needed to bound a whole organisation.
- Trusting `X-Forwarded-For` would let a client rotate its address and evade a per-IP limit; the gateway replaces it with the socket peer address.
- Open registration (`register-organization`) is rate limited per IP only; a distributed attacker can still create many tenants.

### I.6.10 Common mistakes

- Rate limiting by IP only (corporate NAT puts a whole tenant behind one address).
- Limits with no `Retry-After`, causing clients to retry immediately.
- Unbounded maps keyed by attacker-controlled strings.
- Forgetting that background jobs and AI calls also consume shared resources.

### I.6.11 Debugging

- 429 in production: check the response header `Retry-After`, then metrics `gateway.rate.limited` and `cx.ai.analyze{result="rate_limited"}`.
- One tenant slow, others fine: check per-tenant request rate in logs (the `tenant=` field), and query plans for that tenant's largest queries.
- Do not rely on a high-cardinality metric label per tenant in Prometheus; use logs or top-N sampling.

### I.6.12 Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Per-tenant token bucket in Redis | Global, consistent, per-tenant | Redis dependency and latency per request |
| Bulkheads (separate thread pools per feature) | Contains slow features (AI already has its own pool) | More pools to size |
| Fair queuing for jobs | Prevents a big tenant starving small ones | More complex scheduler |
| Dedicated database for a heavy tenant | Hard isolation | Operations cost (hybrid model) |
| Statement timeouts and query governors | Stops runaway queries | Needs per-role setup in PostgreSQL |

### I.6.13 Conceptual learning example: a per-tenant limiter at the gateway

NOT in the repository.

```java
// Conceptual learning example
String key = "t:" + tenantIdFromVerifiedToken + ":rl";
Decision d = limiter.tryAcquire(key); // token bucket, shared store
if (!d.allowed()) return tooManyRequests(d.retryAfterSeconds());
```

The gateway already verifies the token and knows `tid`, so it is the natural place; the limiter must be shared across gateway instances, or the allowance multiplies.

### I.6.14 Interview Q&A

**Q1. How do you prevent one tenant from degrading the others in a shared schema?**
Layered limits: request rate at the edge, bounded pagination and sort, bounded background batches, a bounded AI pool with a per-tenant limit, and bounded caches. What is missing and what I would add next: a per-tenant quota (requests per minute and concurrent requests) at the gateway keyed by `tid`, statement timeouts, and moving the largest tenants to dedicated resources.

**Q2. Your rate limits are per node. Is that a problem?**
Yes, with N nodes the effective limit is up to N times the configured one, and a client that hits different nodes sees inconsistent counts. The documented fix is a shared limiter in Redis or at the edge. I kept per-node limits because they have no external dependency and they still bound memory and abuse locally.

**Q3. Why is the AI limit per tenant and the gateway limit per user?**
The AI limit protects a shared paid budget, so the tenant is the right unit. The gateway limit protects request handling, where a single misbehaving client is the typical problem. The gap is a tenant-level gateway limit.

**Q4. Fixed window or token bucket?**
The AI limiter uses a fixed window: easy and cheap, but it allows boundary bursts (up to twice the limit across two adjacent windows). The gateway and login limiters use token buckets, which smooth bursts. For a paid provider budget I would move to a token bucket or sliding window if the burst mattered.

**Q5. How would you detect a noisy neighbour you did not anticipate?**
Per-tenant request counts and database time from logs (tenant is in every line), slow-query logs grouped by tenant, queue depth, and connection pool wait time. Dashboards with high-cardinality tenant labels are costly, so aggregate top-N.

## I.7 Fault isolation

### I.7.1 What it is

Fault isolation means one failure (a tenant, a feature, a dependency) does not take down unrelated work.

### I.7.2 Why it exists

In a shared system a slow AI provider, a broken tenant record or a dead broker can otherwise cascade into every request.

### I.7.3 What the project does (Project-implemented behaviour)

| Fault | Isolation mechanism | Evidence |
|---|---|---|
| One tenant's SLA scan throws | Caught per tenant, counted, the loop continues | `SlaMonitorJob.scanTenant` |
| AI provider slow or failing | Timeout 3 s, at most 3 retries inside a 10 s deadline, circuit breaker, bounded pool; HTTP 200 degraded response | `AiApiTest`, `AiBreakerAndProviderSelectionTest` |
| AI provider overloaded | `RejectedExecutionException` becomes `overloaded`, degraded, no backlog | `AiAssistService.callProvider` |
| No DB connection held during the provider call | The call runs outside any transaction | Design comment in `AiAssistService` |
| Redis down | Cache degrades to the loader (cache is an optimisation) | `CacheService` contract; Redis path not executed |
| Broker down | Outbox keeps events; relay retries with exponential backoff (cap 5 minutes) up to 8 attempts, then `FAILED` | `OutboxRelay`, `OutboxTest` |
| Notification-service failing | Retry and DLQ topology; consumers de-duplicate on `eventId` | `NotificationMessageHandlerTest` |
| Metadata store failure for AI | Caught, counted `cx.ai.meta.failed`, user-visible result still returned | `AiAssistService.persist` |

### I.7.4 Not implemented

- Per-tenant circuit breakers (the AI breaker is per node and per provider, not per tenant).
- Bulkheads for the database (one shared Hikari pool).
- Cell-based architecture where a failure is contained to a slice of tenants.
- Chaos or failure-injection tests in production-like environments. A fault injector exists only as a test hook (`AiFaultInjector`, refused in the docker profile).

### I.7.5 Performance, security, mistakes, debugging

- A circuit breaker saves time when the provider is down (fail fast), but a threshold that is too low opens on normal noise.
- A failure message must never include another tenant's data; error bodies here use `include-message: never` and `include-stacktrace: never`.
- Common mistake: retrying non-idempotent calls. The AI provider call is idempotent in effect (it produces a proposal), so bounded retries are safe.
- Debugging: counters `cx.sla.job.errors`, `cx.ai.analyze{result}`, outbox status counts.

### I.7.6 Interview Q&A

**Q1. How is a failure in one tenant's job contained?**
`SlaMonitorJob.scanTenant` wraps each tenant in a try-catch, increments an error counter, logs the tenant id and returns 0, so the rest of the page of tenants still runs. Work is bounded per tenant so one tenant's backlog cannot monopolise a run.

**Q2. What would a cell-based architecture add?**
Tenants are partitioned into independent stacks (cells), each with its own database, cache and workers. A bad deploy or a noisy tenant affects only its cell. It costs a routing layer and operational overhead, and it is the natural step after the hybrid model.

**Q3. Why return HTTP 200 with `degraded:true` for AI failures instead of 503?**
The user can continue working manually; the AI feature is optional. A 5xx would show an error banner and trigger client retries that increase load on a failing provider. The rate limit is the one case that is an error (429) because retrying later is correct.

**Q4. What guarantees do you give for events when the broker is down?**
At-least-once delivery after recovery, because the event is committed with the business change and the relay retries; consumers must de-duplicate. No ordering guarantee across events is promised beyond what the relay's claim order gives.

**Q5. What is not isolated in this system?**
The database pool, the cache, the JVM heap and the CPU. A single heavy tenant can still slow others. That is the central trade-off of the shared model.

## I.8 Tenant onboarding

### I.8.1 What it is

Onboarding creates a new organisation, its first administrator and its default configuration.

### I.8.2 How it works (Project-implemented behaviour)

`POST /api/v1/auth/register-organization` is public and rate limited (`reg:<ip>` key). `AuthService.register`:

1. Normalises the slug (trim, lower case) and rejects an existing slug with 409. A race on the unique slug is caught as `DataIntegrityViolationException` and also becomes 409.
2. `TenantProvisioner.createOrganization` inserts the `organization` row with a server-generated UUID.
3. `TenantProvisioner.provisionDefaults(orgId)` binds the new tenant with `TenantContext.runAs` and creates the ticket counter, default categories (Billing, Product Question, Bug Report, Account Access, Incident, Service Request), SLA policies and a default ACTIVE workflow version 1.
4. `TenantProvisioner.createUser` creates the first user with role ADMIN and a BCrypt password hash.
5. An audit row `TENANT_REGISTERED` is written.
6. A token is issued so the admin is logged in immediately.

Tests: `AuthApiTest.registerOrganizationValidatesAndRejectsDuplicateSlug`, and `SeedDataTest` for the demo seeder.

### I.8.3 Why selected

Shared schema makes onboarding a handful of inserts, effectively instant. Putting defaults in code (`TenantProvisioner`) keeps one definition used by both registration and the demo seeder.

### I.8.4 Not implemented

- Email verification, CAPTCHA or manual approval (open registration is a spam and abuse path, noted under OWASP API6).
- Plans and billing, trial expiry.
- Tenant status (active, suspended, deleted) and a token-time check of it.
- Asynchronous provisioning; the work is synchronous and quick.
- Atomicity across the whole onboarding: organisation creation, defaults and user creation are separate transactions, so a failure in the middle can leave a partially provisioned tenant. A compensating clean-up job does not exist.

### I.8.5 Security considerations

- Slug is a public identifier; validate its format (the DTO has validation) to avoid confusing look-alikes.
- The demo seeder creates accounts with a published password. `cx.seed.enabled` is true in the docker profile by default, which is the most important item to switch off in production (`CX_SEED_ENABLED=false`).
- Never let registration accept a tenant id or a role from the client.

### I.8.6 Common mistakes and debugging

- Provisioning defaults without binding the tenant, so inserts fail the `@PrePersist` guard ("Cannot persist tenant-owned entity without a tenant").
- Forgetting the ticket counter row, so the first ticket fails.
- Debug: look for the `TENANT_REGISTERED` audit event and check that categories, SLA policies and the counter exist for the tenant id.

### I.8.7 Conceptual learning example: a safer onboarding flow

NOT in the repository: create the tenant in `PROVISIONING` state, create defaults, then flip to `ACTIVE` in one final statement; a janitor job deletes tenants stuck in `PROVISIONING` for more than an hour. Login checks status, which also gives a place to implement suspension.

### I.8.8 Interview Q&A

**Q1. Describe tenant onboarding in a shared-schema SaaS.**
Insert the tenant record, bind the tenant context for the new id, create defaults (counter, categories, policies, workflow), create the first admin, audit it and return a token. Isolation of the new tenant is automatic because every row carries its `tenant_id`.

**Q2. What can go wrong if registration is public?**
Spam tenants, resource consumption, enumeration of slugs, and abuse of free capacity. Controls: rate limit (present), CAPTCHA, email verification, approval or payment (absent).

**Q3. How would you make onboarding atomic?**
Run the whole sequence in one transaction, or use a status column and a reconciliation job as above. The project keeps separate transactions because `TenantProvisioner` binds the tenant itself and uses transaction templates per step; that is simple but not atomic.

**Q4. Where do you put per-tenant configuration?**
In tenant-scoped rows (business hours and time zone on `organization`, SLA policy rows, workflow rows) read through tenant-keyed caches. Settings changes evict the tenant's prefix.

## I.9 Tenant data export and deletion

### I.9.1 Status: NOT implemented

Be direct about this in an interview. `docs/HLD.md` lists "GDPR erasure workflow, backup/restore tooling" among the things that are out of scope. There is no export endpoint, no tenant deletion endpoint and no purge job per tenant. What exists is a data model (`tenant_id` on every row) that makes them feasible, and soft deactivation of users (`active=false`).

### I.9.2 Conceptual learning example: export

An async job per tenant:

1. An ADMIN requests an export; the request is audited and rate limited.
2. A job binds the tenant and streams tickets, comments, attachment metadata, KB and audit using keyset pagination (not offset) into an archive.
3. The archive is encrypted, stored with a time-limited download link, and the link is audited.
4. Large tenants are chunked so the job stays within memory and database limits.

### I.9.3 Conceptual learning example: deletion and erasure

Two different problems:

| Request | Meaning | Approach |
|---|---|---|
| Contract end | Remove the whole tenant | Export, grace period, then a batched per-tenant purge across PostgreSQL, document store, Redis (`evictByPrefix`), broker queues, search and object storage; produce a verification report |
| Single person erasure | Remove one person's data, keep the rest | Anonymise PII (name, email, attachments), keep ticket skeletons and audit trail; cascade to derived stores; document legal-retention exceptions |

```sql
-- Conceptual learning example: batched purge, repeated until 0 rows
delete from ticket_comment
 where tenant_id = :t and id in (
   select id from ticket_comment where tenant_id = :t limit 1000);
```

Order matters because of the composite foreign keys: delete children (comments, activities, AI suggestions, attachments) before tickets, tickets before users and categories, and the organisation last. The append-only audit table (trigger on PostgreSQL) needs a deliberate, privileged procedure for tenant deletion, which is itself an audited exception.

### I.9.4 Why it matters for design

- `ON DELETE CASCADE` on every foreign key would make deletion easy and accidental deletion catastrophic; explicit ordered purge is safer.
- Backups contain deleted data until they expire; crypto-shredding (per-tenant keys) is an advanced answer.
- Logs and caches are derived stores too. The project avoids putting ticket text in logs and audit, which makes erasure of those stores simpler.

### I.9.5 Interview Q&A

**Q1. Does this project support tenant export or deletion?**
No. The data model makes it feasible and I can describe the design (async per-tenant export with keyset pagination and encrypted download; ordered, batched purge across all stores with a verification report), but I did not build it, and I would say so.

**Q2. How is GDPR erasure different from tenant deletion?**
Erasure targets one data subject inside a tenant while the business records remain, so you anonymise rather than delete, keep referential integrity, and handle derived stores and retention exceptions. Tenant deletion removes everything for a customer at contract end.

**Q3. Why is audit deletion a special case?**
The audit table is deliberately append-only: no repository delete, and on PostgreSQL a trigger raising an exception. Removing a tenant's audit would need a privileged, separately audited procedure that disables the trigger for that operation. That friction is intentional.

## I.10 Testing tenant isolation (the project's tests)

### I.10.1 What the tests are

All tests below run in the standard build against H2 in PostgreSQL mode with the local profile (Caffeine cache, in-process events). `docs/core-service-validation.md` records a run of 179 core-service tests with 0 failures (Docker-tagged tests excluded); the Docker-tagged tests were not run.

| Test | What it proves | What it does not prove |
|---|---|---|
| `TenancyTest.hibernateTenantFilterRestrictsEvenUnscopedQueries` | An unscoped JPQL query only returns the bound tenant's rows | Native SQL, `em.find` |
| `TenancyTest.crossTenantWritesAreBlockedAtPersist` | `@PrePersist` refuses a write for another tenant | Bulk updates |
| `TenancyTest.persistWithoutAnyTenantIsRefused` | No tenant, no write | n/a |
| `TenancyTest.ticketNumbersAreUniqueAndGapFreeUnderConcurrency` | Per-tenant counters are correct under contention | Throughput |
| `TenantIsolationTest.tenantBCannotTouchTenantATickets` | Read, update, transition, assign, comment, list return 404 or empty for another tenant's ids | Routes not listed in the test |
| `TenantIsolationTest.clientSuppliedTenantIdentifiersAreIgnored` | Header and parameter tenant ids have no effect | Other injection points |
| `TenantIsolationTest.usersAnalyticsAuditSettingsAndConfigAreTenantScoped` | Users, analytics, audit, settings, categories, workflows, SLA are scoped | New endpoints added later |
| `TenantIsolationTest.credentialsOfOneTenantDoNotWorkInAnother` | Same email in two tenants does not cross-authenticate | Password reuse policies |
| `ArchitectureRulesTest` (four tests) | Tenant binding allow-list; no tenant parameters; no entities in controllers; append-only audit | Per-method predicates |
| `AiApiTest.agentPlusOnlyAndCrossTenantIs404` | AI analyze, list, approve and reject are tenant scoped | Provider-side isolation |
| `AiResilienceUnitTest.rateLimiterIsPerTenant` | AI limit is per tenant | Global limits |
| `RoleMatrixTest` | Endpoint-by-role matrix and customer visibility | Field-level rules beyond tested ones |

Docker-only tests that exist but were **never executed**: `PostgresRlsDockerIT`, `RabbitRedisDockerIT`, `MongoDocumentStoreDockerIT`.

### I.10.2 Why 404 and not 403

A 403 for a foreign id confirms the object exists. A 404 tells an attacker nothing. The service layer produces the same not-found result whether the id never existed or belongs to another tenant, because the lookup is `findByIdAndTenantId`.

### I.10.3 Runnable example: write a cross-tenant test for a new endpoint

Conceptual learning example, modelled on the project's style (not copy-pasted from it):

```java
@Test
void tenantBCannotReadTenantAsWidget() throws Exception {
    String tokenA = loginAs("acme", "admin@acme.test");
    String tokenB = loginAs("globex", "admin@globex.test");
    UUID widgetA = createWidget(tokenA);

    mvc.perform(get("/api/v1/widgets/{id}", widgetA).header("Authorization", "Bearer " + tokenB))
       .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/widgets").header("Authorization", "Bearer " + tokenB))
       .andExpect(jsonPath("$.content[?(@.id=='" + widgetA + "')]").isEmpty());
}
```

Checklist for each new tenant-owned entity: extends `TenantOwned`, has `@Filter`, has a migration with `tenant_id`, `(tenant_id, id)` unique and composite foreign keys, repository finders with tenant predicates, cache keys with the tenant, an audit call, and a cross-tenant test (read, update, delete, list, search).

### I.10.4 Gaps in testing

- No automated fuzzing of every id-bearing route.
- RLS and Redis key isolation never tested against real services.
- No test that scans every cache call site for the tenant argument.
- No concurrency test that two tenants' requests on the same pooled thread never mix tenants (the filter's `finally` clear is exercised indirectly).

### I.10.5 Interview Q&A

**Q1. How do you know isolation works?**
Layered evidence: a filter test for unscoped queries, an HTTP-level suite where tenant B attacks tenant A's ids across tickets, users, analytics, audit, settings and config, architecture tests for binding and controller parameters, and credential isolation. I also list what is unproven: native SQL, RLS, Redis keys, unlisted routes.

**Q2. What is the one test you would add first?**
A source-level test that fails on native queries and `JdbcTemplate` outside an allow-list, followed by running the RLS integration test in CI with a Docker host. Those address the biggest known hole.

**Q3. Why test over HTTP and not only at the repository?**
Because the isolation promise is a property of the whole stack: token to context to filter to query to response mapping. A repository test cannot catch a controller that reads a header or a service that skips the tenant argument.

## I.11 Consolidated status table

| Topic | Status | Evidence |
|---|---|---|
| Tenant from token claim | Implemented, tested | `JwtAuthFilter`, `TenantIsolationTest` |
| Hibernate filter | Implemented, tested | `TenancyTest` |
| Explicit tenant predicates | Implemented, convention-enforced | repositories |
| Composite FKs | Implemented | `V7`, `V9`, `V22` |
| Architecture test | Implemented | `ArchitectureRulesTest` |
| PostgreSQL RLS | Defined, **never executed** | `V1002`, `V1011`, `PostgresRlsDockerIT` |
| Tenant-keyed cache | Implemented (Caffeine executed; Redis not) | `CacheService` |
| Tenant in events | Implemented | `OutboxService` |
| Tenant jobs | Implemented | `SlaMonitorJob` |
| Per-tenant AI limit | Implemented, per node | `AiRateLimiter` |
| Per-tenant API quota | **Not implemented** | ADR-002 |
| Tenant suspension | **Not implemented** | ADR-004 |
| Export and deletion | **Not implemented** | HLD |
| Hybrid or dedicated databases | **Not implemented** | ADR-002 |

## I.12 Troubleshooting exercises

Work these without looking at the answers first.

**Exercise 1. A tenant sees a colleague's ticket in the wrong list.** Symptoms: user from tenant A sees one ticket titled with tenant B's customer name after a code change. Hints: which layers still apply to a new `@Query`? Was a cache involved? Answer: look for a new native query or a cache key built without the tenant; add the predicate or tenant argument, then add the cross-tenant test.

**Exercise 2. `IllegalStateException: Cross-tenant write blocked` in the SLA job log.** Hints: the job binds one tenant per iteration. Answer: a service method inside the scan created an entity with a tenant id taken from another object (for example a ticket loaded for tenant X, processed while tenant Y is bound). Fix the id source; the guard did its job.

**Exercise 3. After deploying, every query returns zero rows in the docker profile but works locally.** Hints: RLS is on only in docker; fail-closed behaviour. Answer: transactions started with no tenant bound (a job or a code path outside the tenant-aware transaction manager) set no `app.tenant_id`, so the policy matches nothing. Check that the code path binds the tenant before the transaction begins, and that the runtime role is not the one used for migrations.

**Exercise 4. A newly added endpoint returns 200 for tenant B with tenant A's id.** Answer: the service used `repo.findById(id)`; `findById` uses `em.find`, which the filter does not cover. Replace with `findByIdAndTenantId`.

**Exercise 5. Dashboard numbers for tenant A change after tenant B approves an AI suggestion.** Hints: cache. Answer: eviction used a prefix without the tenant, or the cache key omitted it. `evictByPrefix(tenantId, "analytics:")` takes the tenant argument for this reason.

**Exercise 6. Notification arrives for a user of another organisation.** Hints: the broker trust assumption. Answer: the event carried a recipient id from a different tenant (a bug or a forged message); the consumer did not validate membership. Add recipient validation and per-service broker permissions.

**Exercise 7. Logs show `tenant=` empty on some lines.** Answer: those lines were produced outside the request (startup, scheduler, or after the `finally` cleared the context). Confirm they contain no tenant data. If a tenant-bound job logs without it, it ran before `callAs`.

## I.13 Cross-cutting interview Q&A

**Q1. Explain the difference between authentication, tenant resolution, authorization and data isolation, using one request.** See I.1. In one sentence each: the token proves who; its `tid` claim says which tenant; `@PreAuthorize` and object checks say what the user may do; the filter, predicate, foreign keys and (optionally) RLS make sure the query cannot touch another tenant.

**Q2. If you had two weeks, what would you do to harden tenant isolation?** Run the Docker integration tests including RLS, switch the application to a non-owner database role, add a native-SQL ban test, add per-tenant quotas at the gateway, add tenant status to token verification, add per-service broker credentials, and add the cross-tenant test checklist to the pull request template.

**Q3. How would you migrate one big tenant to a dedicated database?** Keep `tenant_id` in every table; build an export that copies the tenant's rows in dependency order; put a tenant directory in front of the data source routing; run dual-write or a short maintenance window; flip the directory entry; verify counts; purge from the shared database after a grace period. Cache keys and events already carry the tenant, so they need no change.

**Q4. What breaks if two requests for different tenants share a thread?** The thread-local is cleared in `finally`, and the filter and context are per transaction, so nothing breaks in the blocking MVC model. It would break with `@Async`, virtual-thread hops or reactive code unless the tenant is propagated explicitly.

**Q5. Why not trust the database alone (RLS only)?** RLS fails closed but also fails opaque: a missing variable yields empty results that are hard to debug, and it needs a correct role setup. The application layers give clear errors and tests that run everywhere, including H2. Defence in depth uses both, and the project is honest that only the application layers have been executed.
