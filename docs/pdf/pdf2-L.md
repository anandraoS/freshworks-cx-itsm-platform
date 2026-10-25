# Part L. Testing and CI/CD

This part explains how a multi-tenant CX and ITSM platform is proven correct before it reaches production, and how it travels from a developer laptop to users. It is written against the real repository, so every claim about "what runs" is tied to a file, a count and an honest execution status.

> Honesty rules for this part. Numbers come from `docs/core-service-validation.md` (executed 2026-10-17, GraalVM JDK 21.0.7, Maven 3.9.12, no Docker) and from the module READMEs. The 7 core-service tests tagged `docker` (Testcontainers), the notification-service RabbitMQ integration test, k6, the Grafana and Prometheus stack have NOT been executed in the authoring environment unless a sentence says otherwise. The Playwright suite (17 tests) and the Angular smoke have since been run locally (see L.17). No CI pipeline ran: the workflow in `.github/workflows/ci.yml` is a sample, labelled as not executed. This repository is a self-built learning project, not Freshworks code.

## L.1 The testing strategy at a glance

### What it is and why it exists

A testing strategy is the deliberate choice of which kinds of automated checks exist, what each one is responsible for, and how much of the total effort each receives. The reason to have a strategy rather than "write tests" is economics. A test that runs in two milliseconds and fails for one reason is cheap to write, cheap to run and cheap to debug. A browser test that boots three services and fails for twenty reasons is the opposite. A healthy suite therefore looks like a pyramid: many cheap, fast, narrow tests at the bottom, fewer slow, broad tests at the top.

![Figure: Test pyramid for the platform, with executed and not-executed layers labelled](docs/diagrams/png/pdf2L-test-pyramid.png)

### The layers in this project

| Layer | Tool | Location | Count | Executed? |
|---|---|---|---:|---|
| Unit (pure logic) | JUnit 5, AssertJ, Mockito | `core-service/src/test/.../sla`, `tickets`, `workflow`, `ai`; `dsa-lab/src/test` | part of 179 core, 53 dsa-lab | Yes (recorded whole-backend run 2026-10-17) |
| Spring slice and API tests | Spring Boot Test, MockMvc, H2 in PostgreSQL mode | `core-service/src/test/.../*ApiTest`, `security`, `outbox`, `cache` | most of 179 core | Yes |
| Architecture rules | JUnit tests that scan source and classes | `ArchitectureRulesTest`, `AiArchitectureTest` | 4 + 3 | Yes |
| Service tests with stub servers | JDK `HttpServer` stubs, `@SpringBootTest` random port | `gateway/src/test` | 14 | Yes (whole-backend run 2026-10-17) |
| Notification tests | JUnit, Mockito | `notification-service/src/test` | 46 | Yes (whole-backend run 2026-10-17) |
| Docker integration | Testcontainers | `core-service/.../docker/*DockerIT`, `docstore/MongoDocumentStoreDockerIT`, `notification-service/.../NotificationRabbitDockerIT` | 7 in core (+1 file in notification) | NOT EXECUTED |
| Frontend unit and component | Vitest, React Testing Library, jsdom | `frontend/src/test` (10 test files) | 86 tests in 10 files | Yes: 86 of 86 passed (2026-10-17); the Angular twin has 83 Karma specs, 83 of 83 passed |
| End to end | Playwright (Chromium) | `e2e/tests` (11 spec files) | 17 | Yes: 17 run, 17 passed with the fault-injected second stack (16 passed + 1 skipped without it); see `docs/e2e-validation.md`. The Angular UI has a 25-step real-backend smoke (25 of 25) |
| Smoke over real HTTP | Node script | `core-service/scripts/smoke-test.mjs` | 57 checks | Yes: 57 of 57 passed |
| Load | `perf/load-test.mjs`, k6 | `perf/` | scenarios | Node script executed; k6 NOT EXECUTED |

The headline number worth remembering is: `Tests run: 179, Failures: 0, Errors: 0, Skipped: 0` for `core-service`, after the 108 original tests grew by 48 for the knowledge base and AI modules, 15 for the gap fixes and 8 for the defects found by the documentation review. The 7 Docker-tagged tests are not part of those 179.

### Where the strategy is weak, honestly

- There is no coverage tool (no JaCoCo) configured, so "how much is tested" is answered by test names and behaviour tables, not by a percentage.
- Everything that depends on real PostgreSQL, Redis or RabbitMQ is tested on substitutes (H2, Caffeine, an in-process relay). The substitutes run fast but can hide dialect and driver differences. Defect 4 in the validation record (H2 null ordering silently dropping `nulls first`) shows the substitute can even disagree with itself across configurations.
- The Playwright suite has been run to green here (17 run, 17 passed with the fault-injected second stack; the degraded-AI spec skips without it), but only locally and only once; it is not wired into CI.

### Interview Q&A

**Q1. How would you describe the testing strategy of a multi-tenant SaaS API to a new hire in two minutes?**
Start from risk. For a multi-tenant system the worst bug is cross-tenant data exposure, so the strategy has a dedicated layer for it (tenant isolation tests that run through the real security chain), then correctness of business rules (state machine, SLA clock, workflow validation) as fast unit tests, then contract and API behaviour through MockMvc, then the infrastructure behaviours that substitutes cannot prove (RLS, broker confirms) as Testcontainers tests, and finally a thin layer of browser tests for the journeys that matter. In this repo the first four are executed on every build; the last two are tagged and excluded by default so a laptop without Docker can still run `mvn test` in a reasonable time.

**Q2. Why is the pyramid a heuristic and not a law?**
The shape reflects cost and feedback speed, not purity. A service whose logic lives mostly in SQL may deserve a "trophy" with more integration tests. Here, many of the most valuable tests (for example `TicketApiTest`) are integration-level because the behaviour they protect (idempotency, optimistic locking, error schema) only exists when the controller, security, JPA and database cooperate. The principle that survives is: push each assertion to the lowest layer that can still observe the behaviour.

**Q3. You inherit a suite that takes 40 minutes. What do you do first?**
Measure before cutting. Get per-test timings from surefire reports, find the Spring context count (each distinct configuration creates a new context and an H2 database), and find tests that sleep. Then split by tag so pull requests run the fast tier while the slow tier runs on merge and nightly. This project already does the split in the right direction: `excludedGroups=docker` in `core-service/pom.xml` keeps container tests out of the default build.

**Q4. What would you refuse to accept as proof that the suite is good?**
A coverage percentage on its own. Coverage says a line executed, not that a wrong value would be caught. Better evidence is mutation testing on the critical packages, a golden set that fails when behaviour drifts, and a history of bugs the suite actually caught. The validation record lists eight such catches, including the recommender bug the golden set found on its first run.

## L.2 Unit testing with JUnit 5 and AssertJ

### What it is and why it exists

A unit test exercises one class or function with its collaborators replaced or absent, and asserts on its output. It exists to give a failure that points at one place within seconds. JUnit 5 is the Java test platform (engine, lifecycle, `@Test`, `@Tag`, parameterised tests). AssertJ provides fluent, readable assertions such as `assertThat(x).containsExactlyInAnyOrder(200, 409)`.

### How it works internally

JUnit 5 discovers classes through the Jupiter engine, instantiates the test class per test method by default (so instance fields are fresh), runs lifecycle callbacks, and reports through the platform launcher that Maven Surefire hooks into. Tags let you select or exclude groups at the build level; that is the mechanism behind the docker exclusion.

### Why selected

JUnit 5 and AssertJ are the Spring Boot default test stack (`spring-boot-starter-test`), need no extra setup, and AssertJ failure messages show actual and expected values, which shortens debugging.

### How THIS PROJECT uses it

The densest unit tests protect pure logic where wrong answers are expensive:

- `BusinessCalendarTest` (9 tests): business-minute arithmetic including weekend roll-over, outside hours, multi-day spans, daylight-saving spring and fall, pause shift and round trip.
- `SlaCalculatorTest` (12): ON_TRACK, AT_RISK, BREACHED for resolution and first response, PAUSED, MET, late-resolved, and escalation thresholds being monotonic, plus four business-minute cases: a HIGH ticket created Friday 15:30 is at 18.75 percent (ON_TRACK) on Sunday 21:30 (`closedHoursDoNotConsumeBudget_fridayEveningTicketIsNotAtRiskOnSunday`), thresholds are crossed by business minutes only, paused business time is excluded, and first-response percent uses business minutes too.
- `AnalyticsTimeZoneTest` (2): the dashboard trend's day buckets use the tenant's time zone (Pacific/Kiritimati example) and an invalid stored zone falls back to UTC. `ErrorContractTest` (2): 415 and 405 come back in the contract error schema.
- `TicketStateMachineTest` (4): the default transition table equals the API contract, 12 allowed pairs and 37 rejected pairs, plus custom tables.
- `WorkflowValidatorTest` (6): a valid model passes; unknown field, operator or action is rejected; SpEL, EL or script-like values are rejected; unknown parameters and size limits are enforced.
- `LoginRateLimiterTest` (3) and `TokenBucketLimiterTest` in the gateway: burst, refill, key independence, bounded memory under 5 000 rotating keys.
- `AiSanitizerTest` (6): redaction of e-mail, three phone formats, card numbers, bearer tokens, JWTs and key=value secrets; ordinary numbers and dates kept; instruction-like sentences dropped.
- The 53 tests in `dsa-lab` (LRU cache, heap, graph, trie, ring buffer, intervals, sliding window, scheduling, sorting and search, hashing, BST) are pure algorithm tests with no Spring.

An example of the state machine contract check, summarised from the record: the test enumerates every pair of statuses, so a new status added without a decision about its transitions fails the build rather than silently becoming "all transitions rejected" or "all allowed".

### Implemented versus not implemented

- Implemented: pure-logic tests for time arithmetic, SLA states, validators, limiters, sanitiser, state machine.
- Not implemented: property-based testing (jqwik) for the business calendar, which is the best candidate; mutation testing (PIT); a coverage gate.

### Performance

Pure unit tests run in milliseconds. The core suite as a whole is dominated by Spring context startup and the H2 schema migration, not by assertions.

### Security

Unit tests of security logic matter disproportionately: `AiSanitizerTest` encodes the redaction rules, `LoginRateLimiterTest` encodes brute-force limits. Use only synthetic data. The test password in `AbstractApiTest` (`PASSWORD`) is labelled a synthetic test password, and the Postgres IT uses an ephemeral role with a throw-away value inside a container.

### Common mistakes

- Asserting on implementation (private call order) rather than behaviour, so every refactor breaks tests.
- Using `LocalDateTime.now()` inside logic under test so results depend on the clock. The business calendar tests pass explicit instants and zones instead.
- One giant test with many assertions, which hides the second failure.
- Testing getters and framework behaviour.

### Debugging a failing unit test

Run the single class with `mvn -pl core-service -am test -Dtest=SlaCalculatorTest`. Read the AssertJ diff first. If it is time-related, check the `-Duser.timezone=UTC` argument that surefire sets in `core-service/pom.xml`; time tests that pass on a developer machine and fail in CI nearly always hide a timezone assumption.

### Alternatives and trade-offs

JUnit 4 (legacy), TestNG (strong data providers, less Spring default), Spock (Groovy, expressive but another language), Kotest. JUnit 5 plus AssertJ wins on ecosystem fit.

### Interview Q&A

**Q1. What makes a unit test a good one?**
It is fast, deterministic, independent, and fails for exactly one reason that the name reveals. Names such as `aFailedCreateBurnsANumberButNumbersStayUniqueAndIncreasing` document a rule, not a method.

**Q2. Why does `BusinessCalendarTest` cover daylight saving time?**
Because a business-minute calculation in a tenant timezone crosses a 23-hour or 25-hour day twice a year. Adding 480 minutes naively to a wall-clock time can land an hour off. The test fixes the behaviour for the spring and fall transitions so the SLA due date is right for customers in those zones.

**Q3. How do you test code that depends on randomness or time?**
Inject the source (a `Clock`, a seeded generator or an explicit instant). The mock AI provider is deterministic by design and `MockRuleBasedAiProviderTest` runs the same input 20 times and requires identical output.

**Q4. When is a parameterised test better than several tests?**
When the cases are the same behaviour on different data, such as operator semantics (EQ, NE, GT, LT, IN, CONTAINS) in the workflow validator. Use separate tests when the cases tell different stories.

## L.3 Mockito and test doubles

### What it is and why it exists

Mockito creates test doubles: mocks (record and verify interactions), stubs (return canned answers) and spies (wrap a real object). They exist so a unit can be tested without its slow or non-deterministic neighbours (network, database, clock).

### How it works internally

Mockito generates a subclass or inline proxy at runtime (inline mock maker with ByteBuddy by default in current versions), intercepts method calls, matches them against stubbings registered with `when(...).thenReturn(...)`, and records invocations for `verify(...)`.

### Why selected

It is the Spring Boot default and the de facto standard. Interaction verification is expressive for failure simulation.

### How THIS PROJECT uses it

The most instructive use is the Redis-down simulation in `CacheServiceTest` (10 tests): a mocked `RedisTemplate` throws on read, write and evict. The test then proves four properties at once: the loader result is still returned, the circuit breaker opens, no further Redis calls are made while it is open, and evictions never throw. That is a failure that is very hard to cause with a real Redis in a unit-speed test. `NotificationProcessorTest` and `NotificationMessageHandlerTest` in notification-service use doubles for the channel sender and the repository to test redelivery and retry classification. The frontend uses `vi.mock` (Vitest's equivalent) to replace `ticketApi.create`, as in `ticketForm.test.tsx`.

The honest caveat is recorded in the docs: the Redis behaviour is "tested only with a mocked template and a docker IT that did not run". A mock proves your code reacts to the exception you imagined, not to the exception Lettuce really throws.

### Implemented versus not implemented

Implemented: mocked Redis template, mocked collaborators in the notification module, frontend module mocks. Not implemented: contract verification of the mocks against the real client (the Docker IT would do that).

### Performance, security

Mocks are as fast as plain objects. Security-wise, never put real credentials in stubbed return values; use obviously fake ones.

### Common mistakes

- Over-mocking: mocking value objects and your own domain classes, so the test only checks that mocks call mocks.
- Verifying every interaction (`verifyNoMoreInteractions` everywhere), which freezes the implementation.
- Stubbing what the real object would never return (null where the real contract says empty list).
- Mocking what you do not own without an integration test behind it.

### Debugging

`UnnecessaryStubbingException` means a stub was never used, which usually means the code path changed. `PotentialStubbingProblem` means arguments differ from the stub. Print `mockingDetails(mock).getInvocations()` when unsure what was called.

### Alternatives

Hand-written fakes (an in-memory implementation, like the project's in-memory `DocumentStore`, which is asserted against the same behavioural contract as the JPA store in `DocumentStoreTest`), WireMock for HTTP, MockK in Kotlin. The `DocumentStore` pair is a good illustration of a fake that is verified against the real implementation by one shared contract.

### Interview Q&A

**Q1. Mock, stub, spy, fake: what is the difference?**
A stub returns canned data, a mock also asserts that it was called a certain way, a spy wraps the real object and records, and a fake is a working lightweight implementation (in-memory store). Prefer fakes for stateful collaborators and mocks for failure injection.

**Q2. When would you not use Mockito?**
When the real collaborator is cheap and deterministic (use it), or when the behaviour you care about lives in the integration (use a slice or integration test). The state machine test uses the real table, not a mock.

**Q3. How do you stop mocks from lying?**
Pair each mock-based test of a boundary with at least one integration test of the real boundary, or run the same behavioural contract against the fake and the real implementation, as `DocumentStoreTest` and `MongoDocumentStoreDockerIT` do for the document store.

**Q4. How would you test the circuit breaker opening without real time passing?**
Inject the clock or use configurable open durations. `AiResilienceUnitTest` covers open, fail-fast, half-open single trial and close, and `AiBreakerAndProviderSelectionTest` shows it opening after 2 failures.

## L.4 Spring slice, MockMvc and integration tests

### What it is and why it exists

A slice test loads only part of the Spring context (for example `@WebMvcTest` for controllers). An integration test loads the whole application context against a real or embedded database and drives it through its public interface. MockMvc is Spring's in-process HTTP simulator: it runs the real filter chain and dispatcher servlet without opening a socket. These exist to test the behaviour that only appears when components cooperate: validation, security, serialisation, transactions and persistence.

### How it works internally

`@SpringBootTest` builds a full `ApplicationContext`, cached by configuration key so identical test classes share a context. `@AutoConfigureMockMvc` adds a `MockMvc` wired to the real `FilterChainProxy`, so JWT parsing, tenant binding and error mapping all execute. Because Spring caches contexts, tests with different properties or profiles each pay a context startup, which is the main cost of the suite.

### Why selected

Real value comes from running the real security chain. A controller test that bypasses security cannot prove "customer gets 403 on analytics".

### How THIS PROJECT uses it

All HTTP-level tests extend `core-service/src/test/java/com/example/cx/core/AbstractApiTest.java`:

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
public abstract class AbstractApiTest { ... }
```

Key design decisions visible in that class:

1. Each test creates its own tenant through the public `POST /api/v1/auth/register-organization` endpoint with a random slug. Tests are therefore independent of each other and of seed data, and they exercise a real endpoint on the way.
2. `application-test.yml` gives every Spring context its own H2 database (`jdbc:h2:mem:cxtest-${random.uuid};MODE=PostgreSQL;...`), disables seeding, the outbox relay, the outbox purge and the SLA job (no background schedulers racing the assertions), and raises the login limiter to 1000 so rate limiting does not fail unrelated tests.
3. `ddl-auto: validate` makes the context fail if an entity mapping drifts from the Flyway migrations. Schema drift is therefore a test failure, not a production surprise.
4. `generate_statistics: true` in the test profile feeds `NPlusOneGuardTest`, which asserts that the statement count for a 30-row page equals the count for a 2-row page and is at most 8.

Important tests built this way: `TicketApiTest` (8), `TenantIsolationTest` (4), `RoleMatrixTest` (4), `AuthApiTest` (6), `WorkflowApiTest` (8), `OutboxTest` (9), `KbApiTest` (7), `AiApiTest` (14), `PlatformEndpointsTest` (6), `SeedDataTest` (3).

### Implemented versus not implemented

Implemented: full-context MockMvc tests on H2 (PostgreSQL mode). Not implemented or not executed: the same tests on real PostgreSQL (the one run on PostgreSQL is the docker-tagged `PostgresRlsDockerIT`, not executed); `@WebMvcTest` and `@DataJpaTest` narrow slices (the project mostly uses full-context tests, which is simpler but slower).

### Performance

The cost is context creation. Reduce it by keeping the number of distinct property combinations small, sharing a base class (done) and avoiding `@DirtiesContext`. Using `H2 in-memory` and random database names prevents state leakage between contexts.

### Security

Tests prove authorization by status code: 401 for no or forged token, 403 for insufficient role, 404 (not 403) for another tenant's object so existence is not leaked. `AuthApiTest` includes forged, expired, `alg=none` and tampered tokens.

### Common mistakes

- Sharing mutable data across tests (static tenants) so order matters. The random tenant per test avoids this.
- Asserting on full JSON strings rather than fields.
- Letting background schedulers run in tests, which creates flakiness. Here they are switched off in `application-test.yml`.
- Double-encoding query strings in MockMvc: defect 8 in the validation record, where a test passed `q=quokka%20joiners` in the URL template and MockMvc encoded it again, so the test checked the wrong string. Use `.param(...)`.

### Debugging

Run one class, raise logging for the area, and use `andDo(print())` on the failing request. If a context fails to start, read the first "Caused by" (defects 1 and 2 in the record were found exactly this way: a constructor without `@Autowired`, and two `CorsConfigurationSource` beans).

### Alternatives

`@WebMvcTest` slices with `@MockBean` services (faster, narrower); REST Assured or `TestRestTemplate` against a random port (real sockets, closer to production, slower); WebTestClient for reactive apps (the gateway is WebFlux).

### Interview Q&A

**Q1. Why `@SpringBootTest` rather than slices for this project?**
Because the behaviours that matter are cross-cutting: tenant filter plus security plus transactions plus audit. A slice with a mocked service would pass while the real tenant binding was broken. The trade-off is slower startup, accepted because contexts are cached.

**Q2. How do you keep integration tests independent?**
Create unique data per test (random tenant slug), isolate the database per context, disable background jobs, and never rely on execution order.

**Q3. How would you test a flow that spans a transaction and an after-commit event?**
Assert on persisted outbox rows and on rollback behaviour. `OutboxTest` checks that `publish` needs a transaction, that a rollback leaves no event, and that a failed business operation leaves no event.

**Q4. What does `ddl-auto: validate` buy you in tests?**
A guarantee that entity classes and the Flyway-created schema agree. Without it, Hibernate would create the schema itself in tests and the real migration scripts would never be validated by the unit build.

## L.5 Contract testing

### What it is and why it exists

A contract test verifies that a producer and a consumer agree on an interface (request and response shape, status codes, error body) without needing both running together. It exists to catch breaking API changes before integration. Tools include Pact (consumer-driven) and Spring Cloud Contract (producer-driven stubs); a lighter version asserts directly against a written specification.

### How it works internally

Consumer-driven contracts record the consumer's expectations in a pact file, the provider replays them in its build, and a broker tracks which versions are compatible (`can-i-deploy`). Schema-based contracts validate real responses against an OpenAPI document.

### How THIS PROJECT does it

There is no Pact or Spring Cloud Contract. The contract is `docs/API-CONTRACT.md` (a single written document) and the project enforces it with several targeted tests:

- `TicketStateMachineTest` asserts the transition table equals the contract (12 allowed, 37 rejected pairs).
- `TicketApiTest` asserts the contract error schema (`code`, `status`, `correlationId`, field errors).
- `PlatformEndpointsTest` asserts the 404 and 400 error schema, and that the OpenAPI document and Swagger UI are served.
- `GatewayUpstreamDownTest` asserts the gateway produces a 503 `UPSTREAM_UNAVAILABLE` body with the same schema (`code`, `status`, `correlationId`, JSON content type) when the upstream is down, using a closed local port.
- The event contract: `OutboxTest` checks contract event types carry `tenantId`, a propagated correlation id and recipients, and `NotificationTemplatesTest` and `RecipientValidationTest` check the consumer side. The frontend client mapping is covered by `client.test.ts` (error mapping) and `schemas.test.ts` (Zod schemas, 12 cases).

The gap: the frontend types in `frontend/src/api/types.ts` are hand-written, not generated from the OpenAPI document, so a field rename in core is caught only if a test or the e2e suite touches it.

### Implemented versus not implemented

Implemented: contract-by-test for errors, transitions, event shape. Not implemented: consumer-driven contracts between frontend and core, OpenAPI diff in CI (for example `openapi-diff` to fail on breaking changes), generated client types.

### Performance and security

Contract tests are cheap. Security angle: a contract test that pins the error shape also pins that errors never leak stack traces, and `PlatformEndpointsTest` includes redaction and the log-injection-safe correlation id.

### Common mistakes

Treating generated documentation as the contract (it only mirrors the code), forgetting events (messages are contracts too), and versioning only the URL while changing field meaning.

### Debugging

When a frontend call fails after a backend change, compare the response body with `docs/API-CONTRACT.md`, then check the `X-Correlation-Id` in the logs.

### Alternatives and trade-offs

Pact: strongest for many independent consumers, costs a broker and process. OpenAPI diff plus generated clients: cheap, good for one frontend and one backend in one repository, which is this project's situation. Recommended next step here: generate TypeScript types from the springdoc OpenAPI output and fail CI on drift.

### Interview Q&A

**Q1. Contract tests or end-to-end tests to prevent integration breaks?**
Contract tests, because they fail fast and point at the field that changed. E2E tests are the safety net, not the primary guard.

**Q2. How do you evolve an event schema safely?**
Add optional fields only, version the schema (the outbox carries a `SCHEMA_VERSION`), keep consumers tolerant of unknown fields, and deprecate before removal.

**Q3. Why test the gateway error schema separately?**
Because the gateway writes its own error bodies (a separate JSON writer from core's `GlobalExceptionHandler`). The frontend must not have to branch on which layer failed.

**Q4. What would you adopt first: Pact or OpenAPI diff?**
OpenAPI diff plus generated types, since there is a single consumer. Introduce Pact only when independent teams or external consumers appear.

## L.6 Component tests (frontend) with Vitest and React Testing Library

### What it is and why it exists

Component tests render a UI component in a simulated DOM and interact with it as a user would (find by role and label, click, type), asserting on visible output. They exist to verify behaviour such as validation messages and loading states without a browser.

### How it works internally

Vitest runs tests under Vite with a `jsdom` environment (a JavaScript implementation of the DOM). React Testing Library renders into that DOM and offers queries that mirror accessibility semantics (`getByRole`, `getByLabelText`) so tests fail when accessibility breaks. `userEvent` simulates realistic event sequences.

### Why selected

Vitest shares the Vite config and is fast; Testing Library pushes tests towards user-visible behaviour rather than implementation details.

### How THIS PROJECT uses it

The 10 test files in `frontend/src/test/` (about 74 cases by file grep: `components` 13, `schemas` 12, `ticketList` 10, `client` 8, `aiPanel` 7, `auth` 7, `guards` 6, `ticketForm` 5, `errorBoundary` 3, `notifications` 3) cover:

- Form validation and duplicate-submit protection: `ticketForm.test.tsx` double-clicks the Create button against a never-resolving promise and asserts exactly one API call, a disabled button and `aria-busy="true"`.
- Route guards and role-based visibility (`guards.test.tsx`, `auth.test.tsx`).
- Filter-to-query-parameter mapping on the ticket list.
- The AI panel states: pending, degraded and approved (`aiPanel.test.tsx`).
- Optimistic update with rollback for notifications.
- The error boundary and the API client's error mapping.

Helpers are in `frontend/src/test/utils.tsx` (`renderApp`, `makeUser`) so each test renders the app with a fake auth user and a fresh query client. Execution status: the Vitest suite ran on 2026-10-17: 86 of 86 passed (`cd frontend && npm ci && npm test`).

### Implemented versus not implemented

Implemented: behavioural component tests with mocked endpoints. Not implemented: visual regression tests, accessibility audit tests (jest-axe), coverage thresholds, Mock Service Worker for network-level mocking (the tests mock the endpoint module instead).

### Performance and security

Vitest tests run in tens of milliseconds each. Security-relevant: the tests assert that role-gated routes do not render for the wrong role, but this is only a UX guard; the real enforcement is server-side, and `RoleMatrixTest` is the authority.

### Common mistakes

Querying by CSS class, snapshotting large trees, forgetting `await` on `findBy*`, sharing a query client between tests (cache bleed), and treating a mocked module as proof the real endpoint works.

### Debugging

Use `screen.debug()`, `screen.logTestingPlaygroundURL()`, and the `getByRole` error output, which lists available roles. For timing problems, prefer `waitFor` and `findBy*` over fixed sleeps.

### Alternatives

Jest (slower with Vite setups), Cypress component testing, Storybook interaction tests, Playwright component testing.

### Interview Q&A

**Q1. Why test the double-click on the submit button?**
It is the UI half of an end-to-end guarantee. The server protects against duplicates with `Idempotency-Key`; the client reuses one key per form mount and disables the button. Both layers are tested: `TicketApiTest` for four concurrent same-key requests yielding one ticket, and `ticketForm.test.tsx` for one request per double click.

**Q2. What should not be unit tested at the component level?**
Layout and pixel appearance, third-party library internals, and anything whose real risk is the server contract.

**Q3. How do you test optimistic updates?**
Mock the mutation to reject, assert the UI rolled back, and assert that the cache key used is tenant-first so another tenant's cache is not touched. `notifications.test.tsx` covers update and rollback.

**Q4. jsdom or a real browser?**
jsdom for speed on the bulk of tests; a real browser (Playwright) for focus, layout, navigation and real network behaviour.

## L.7 End-to-end tests with Playwright

### What it is and why it exists

An end-to-end test drives the real application through its user interface and asserts on user-visible results. It exists to prove that the deployed pieces (SPA, gateway, core, database) work together for critical journeys. Because it is the slowest and most fragile layer, it should cover few, high-value paths.

### How it works internally

Playwright controls a real browser over a protocol, auto-waits for elements to be actionable, isolates each test in a browser context (cookies and storage are separate), and records traces. The config in `e2e/playwright.config.ts` sets `timeout: 30_000`, `fullyParallel: false`, `workers: 1`, `retries: 0`, `trace: 'retain-on-failure'`, a Chromium project only, and a `webServer` that starts the Vite dev server (reusing one if running). The backend is not started by Playwright; it must already be running (gateway on 8080, core on 8081, profile `local`, seeded).

### Why selected

Auto-waiting removes most sleep-based flakiness, multiple browser contexts make multi-tenant tests easy, and traces make failures debuggable.

### How THIS PROJECT uses it

Eleven spec files, 17 tests in `e2e/tests/` (the original six: `login.spec.ts` (3), `create-ticket.spec.ts` (2), `assign-transition.spec.ts` (2), `customer-restrictions.spec.ts` (2), `tenant-isolation.spec.ts` (1), `ai-approve.spec.ts` (1); later additions: `ai-reject`, `ai-degraded`, `kb`, `notifications`, `dashboard-admin`), with shared helpers in `helpers.ts`. The tenant-isolation spec is the most instructive:

```ts
const a = await (await browser.newContext()).newPage();
await login(a, 'acme-retail', 'agent1@acme.test');
await createTicket(a, title);
const path = new URL(a.url()).pathname;
const b = await (await browser.newContext()).newPage();
await login(b, 'globex-it', 'agent1@globex.test');
await b.goto(path); // same id, other tenant: backend answers 404
await expect(b.getByText('Ticket not found')).toBeVisible();
```

Two independent browser contexts play two tenants. Execution status: the Playwright suite (17 tests in 11 spec files) was run on 2026-10-17: 17 run, 17 passed with the fault-injected second stack, 16 passed and 1 skipped without it (`docs/e2e-validation.md`). The seeded demo users are dummy credentials documented in `docs/API-CONTRACT.md`.

### Implemented versus not implemented

Implemented: authored journeys for login, create, assign, transition, role restrictions, tenant isolation and AI approval. Not implemented or not verified: any green run, cross-browser projects, retries, sharding, visual comparison, reporting beyond the `list` reporter, running in CI.

### Performance

Serial execution (`workers: 1`) is deliberate because tests share one seeded backend and state resets on restart; the cost is wall-clock time. To parallelise, give each test its own tenant (as the API tests do) rather than sharing seeded data.

### Security

E2E tests need credentials. Use seeded dummy accounts only; never put real tokens in specs or traces (Playwright traces can capture network headers, so treat the `test-results/` directory as sensitive and do not upload it from a pipeline that touches real environments).

### Common mistakes

Fixed `waitForTimeout`, selectors by CSS structure, shared mutable seed data, testing every permutation through the UI, and ignoring the first failure in a chain.

### Debugging

Run `npx playwright test --trace on`, open the trace viewer, compare the network panel's `X-Correlation-Id` with the server log line `corr=...`. If login fails everywhere, the backend is not running with seed data (troubleshooting table in `RUN-AND-TEST-GUIDE.md`).

### Alternatives

Cypress (great DX, single-browser history, in-browser architecture), Selenium (broadest legacy support), WebdriverIO, TestCafe. Playwright wins on multi-context, tracing and speed.

### Interview Q&A

**Q1. How many e2e tests should a project have?**
Few: the journeys whose failure would be a headline. Eleven for this platform is reasonable; more belongs in lower layers.

**Q2. How do you make e2e tests stable?**
Deterministic data (create it inside the test with unique names, as `E2E isolation ${Date.now()}`), auto-waiting assertions, role-based locators, isolated contexts, and no dependency on test order.

**Q3. How would you run these in CI?**
Build the jars, start core and gateway with a throwaway secret and seeded data, wait on `/actuator/health`, run Playwright, upload traces only on failure. The sample `ci.yml` does exactly this and was not executed.

**Q4. A test passes locally and fails in CI. How do you triage?**
Compare environment (timezone, locale, CPU count, startup order), download the trace, check for a race with seeded data, and check if the backend health wait was too short.

## L.8 Testcontainers

### What it is and why it exists

Testcontainers starts disposable Docker containers (PostgreSQL, RabbitMQ, Redis, MongoDB) from test code and wires the test to them. It exists because in-memory substitutes behave differently from real infrastructure, and "works on H2" is not evidence that it works on PostgreSQL.

### How it works internally

The JUnit extension (`@Testcontainers` with `@Container`) pulls the image, starts it, waits for a readiness strategy, exposes mapped random ports, and removes the container at the end (via the Ryuk reaper container). Tests read connection details from the container object.

### Why selected

Real engines in tests, no shared test database, reproducible versions pinned in code.

### How THIS PROJECT uses it

Four container test classes, all tagged `@Tag("docker")` and excluded by default (`excludedGroups=${excluded.groups}`, profile `docker-tests` clears the exclusion, `core-service/pom.xml`):

| Class | Tests | Container | What it proves |
|---|---:|---|---|
| `PostgresRlsDockerIT` | 2 | `postgres:16-alpine` | Flyway runs `db/migration` plus `db/migration-pg`; partial and GIN indexes exist; RLS isolates tenants per transaction (`SET LOCAL`); fail-closed without tenant; `audit_event` append-only trigger |
| `RabbitRedisDockerIT` | 2 | RabbitMQ, Redis | publisher confirms, persistent delivery, correlation header; Redis hit then a real outage |
| `MongoDocumentStoreDockerIT` | 3 | `mongo:7.0` | put/get/list tenant scoping, upsert, unique index, `$group`/`$avg` aggregation |
| `NotificationRabbitDockerIT` (notification-service) | not counted in the record | RabbitMQ | consumer, retry queue and dead-letter topology |

The Postgres IT shows good practice: it creates a non-superuser, `nobypassrls` role (`cx_app_test`) and runs the isolation assertions through that role, because RLS does not apply to superusers.

Execution status: **NOT EXECUTED**. The 7 core tests were compile-checked only by the test build. The first PostgreSQL run of the SQL migrations is therefore still outstanding, a fact the validation record states plainly.

To run on a Docker machine: `mvn -pl core-service -am -Pdocker-tests test`.

### Implemented versus not implemented

Implemented: the tests and the profile. Not implemented: container reuse, a shared base container class, CI job (only the sample), running the whole `@SpringBootTest` suite against Postgres (a `@ServiceConnection` setup would allow it).

### Performance

Container start costs seconds per class. Use static containers shared per class (done), consider the singleton container pattern across classes, and enable reuse locally.

### Security

Containers use throw-away credentials created in the test. Do not mount real data. In CI, pin image tags (they are pinned: `postgres:16-alpine`, `mongo:7.0`) and prefer images from a mirror.

### Common mistakes

Running RLS tests as the table owner or a superuser (policies silently do not apply), using `latest` tags, relying on fixed ports, not waiting for readiness, leaking containers in CI without Ryuk, and treating the existence of a Docker test as proof it passes.

### Debugging

Enable container log consumption (`withLogConsumer`), run with `-Dtestcontainers.reuse.enable=true` locally, inspect with `docker ps` while paused at a breakpoint, and check Docker Desktop resources on Windows.

### Alternatives

Embedded databases (H2, embedded Mongo; fast but dialect risk), a shared dev database (state pollution), docker compose test stacks (heavier), cloud ephemeral databases.

### Interview Q&A

**Q1. Why is H2 in PostgreSQL mode not enough?**
It accepts the SQL that Flyway portable scripts contain, but it does not implement RLS, partial and GIN indexes, `SET LOCAL`, triggers or real locking semantics. The project keeps PG-only SQL in a separate Flyway location (`db/migration-pg`, ADR-018) precisely so H2 can run the portable half and Testcontainers verifies the rest.

**Q2. Why must the RLS test use a non-owner role?**
The table owner and superusers bypass row-level security unless `FORCE ROW LEVEL SECURITY` is set for owners, and superusers always bypass. A test that passes as the owner proves nothing.

**Q3. How do you keep slow tests from blocking developers?**
Tags plus build profiles. Default build excludes them; CI runs them on every pull request, on a runner with Docker.

**Q4. What is your policy on a test that has never been run?**
Treat it as unverified. Document it as such (as the validation record does), and do not claim the behaviour is proven. Schedule its first run as an explicit task.

## L.9 Test data strategy and determinism

### What it is and why it exists

Test data strategy answers where the data for a test comes from and how it is cleaned up. Determinism means the same inputs always produce the same result: no hidden clocks, randomness, ordering or shared state. Non-determinism is the root of most flaky tests.

### Approaches compared

| Approach | Strength | Weakness |
|---|---|---|
| Shared seed data | Realistic, cheap | Tests couple to each other and to data volume |
| Per-test creation through the API | Independent, exercises endpoints | Slower setup |
| Builders and factories | Readable, minimal | Needs upkeep |
| Database snapshots / rollback per test | Fast cleanup | Hides transaction-commit bugs (outbox, after-commit) |
| Golden files | Detect behavioural drift | Updating blindly defeats the purpose |

### How THIS PROJECT does it

- API tests: a new tenant per test via the register endpoint (`AbstractApiTest.newTenant`), users via the admin API (`userToken`). No transactional rollback, so after-commit behaviour is really exercised.
- Isolated database per Spring context (random H2 name).
- Seeds: `SeedDataTest` (3 tests) checks that the demo seeder yields 3 tenants with 150, 100 and 60 tickets, status and priority spread, comments including INTERNAL ones, activity within 45 days and consistent SLA state. The seeder log in the smoke run showed `acme-retail ... 150 tickets in 1015 ms`. Seeds are for demos and e2e, not for unit tests.
- Determinism by design: the mock AI provider is deterministic (20 identical runs), and its golden set is 12 cases times 3 checks, 36 of 36 matching. The golden set caught a real defect on its first run (the stop-word "to" matched "Request access to finance shared drive" to "Connecting to the corporate VPN"), fixed in `KbSearchScorer`.
- Time: surefire forces `-Duser.timezone=UTC`; the calendar tests use explicit zones and instants.
- Randomness: tenant slugs are random on purpose (uniqueness), but assertions never depend on the random value.

### Implemented versus not implemented

Implemented: per-test tenants, per-context databases, golden set, UTC forcing. Not implemented: a fixed `Clock` bean used across the whole app (so full-flow tests can freeze time), fuzzing, data-builder classes for entities (tests use JSON maps).

### Performance

Creating a tenant per test costs a register call and a BCrypt hash (login is intentionally CPU heavy at cost 10). If tests become slow, reuse a tenant within a class only for read-only assertions.

### Security

Never copy production data into tests. Seed credentials are dummy values documented in the contract and must be changed or disabled outside a demo (`CX_SEED_ENABLED=false` appears in the production priority list in `docs/security-hardening.md`).

### Common mistakes

Depending on `ORDER BY` of an unordered query, comparing floating-point values exactly (the acceptance-rate test uses expected values 0, 50.0, 66.7, 50.0 which are rounded by design), using `System.currentTimeMillis()` in assertions, and leaving stale seeded rows that change counts.

### Debugging

When a test is order-dependent, run it alone and in reverse order (`-Dsurefire.runOrder=reversealphabetical`). Print the tenant slug in failure messages.

### Interview Q&A

**Q1. Rollback-per-test or committed data?**
For this system, committed data. The outbox relay, idempotency table and optimistic locking all behave differently under rollback-only tests (after-commit hooks never fire). Committed data with unique tenants gives isolation without that blind spot.

**Q2. What is a golden-set test and when does it backfire?**
A fixed set of inputs with expected outputs that catches behaviour drift. It backfires when expected values are regenerated automatically after every failure. Review diffs to the golden file like production code.

**Q3. How do you make time-based logic testable?**
Pass the clock in. The SLA calculator takes instants, and the business calendar handles zones, so the same tests pass in any timezone.

**Q4. How do you handle large seed volumes in tests?**
Do not. Use small, purpose-built data; use the seeder (150 tickets) only for dashboard and e2e flows.

## L.10 Concurrency tests

### What it is and why it exists

A concurrency test provokes races on purpose to prove that a guard (a lock, a unique constraint, an optimistic version) holds. It exists because correctness under parallel requests cannot be seen in single-threaded tests, and because production traffic is parallel.

### How it works internally

The standard recipe uses an `ExecutorService`, a `CountDownLatch` "starting gun" so threads begin at the same instant, and assertions on the set of outcomes (not on an interleaving). Because the actual interleaving is up to the scheduler, the assertion must hold for every legal interleaving.

### The parallel optimistic-lock test

From `core-service/src/test/.../tickets/TicketApiTest.java`:

```java
void parallelUpdatesWithSameVersionProduceExactlyOneConflict() throws Exception {
    ...
    long version = created.get("version").asLong();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    for (String token : List.of(agentA, agentB)) {
        results.add(pool.submit(() -> {
            ready.countDown();
            go.await();
            return doPut(token, "/api/v1/tickets/" + id, Map.of(..., "version", version)).getResponse().getStatus();
        }));
    }
    ready.await();
    go.countDown();
    ...
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
}
```

Reading it as a reviewer: two different agents hold the same version number. Whichever commit wins increments the version; the loser's `UPDATE ... WHERE version = ?` matches zero rows and is mapped to HTTP 409 `TICKET_VERSION_CONFLICT`. The assertion `containsExactlyInAnyOrder(200, 409)` is correct whichever thread wins, and excludes two outcomes that would be bugs (200 and 200 means lost update, 409 and 409 means over-locking). Latches make the race tight; `ready` ensures both threads are scheduled before `go` is released.

### Other concurrency tests in the project

| Test | Race | Expected outcome |
|---|---|---|
| `TicketApiTest.concurrentCreatesWithSameKeyYieldOneTicket` | 4 creates, same `Idempotency-Key` | all four return 201 (replay), one ticket exists |
| `TenancyTest` | 10 parallel creates | numbers 1..10 unique |
| `TicketNumberAllocatorTest` (3) | allocation under parallelism; gap on failed create | unique, increasing, gaps allowed |
| `OutboxTest` | 6 concurrent relay claimers | exactly one wins; an expired lease is re-claimed |
| `AiApiTest` | 4 concurrent approvals | one 200, three 409, one comment |
| `CacheServiceTest` | 12 threads, cold key | loader called once (single-flight) |
| `SlaJobTest` | repeated job runs | breach flagged once (idempotent) |

### Implemented versus not implemented

Implemented: race tests for every guard the design relies on (optimistic version, idempotency key, unique number, outbox lease, approval state, single-flight). Not implemented: multi-JVM races (two core instances against one PostgreSQL), because tests are single-JVM; deterministic scheduling tools (jcstress for memory-model level, or Awaitility-driven stress loops); chaos tests that kill a node mid-transaction.

### Performance

A concurrency test with a tight latch is fast. Looping a race 1 000 times to raise confidence is possible; run it in a nightly job, not in the pull-request build.

### Security

Races are also security bugs: a double approval posting two customer-visible AI comments, or a double spend of an idempotency key across tenants. `AiApiTest` ties the approval race to exactly one PUBLIC comment.

### Common mistakes

- Using `Thread.sleep` to "wait for the race". Use latches.
- Asserting a specific winner.
- Forgetting to shut down the pool (leaks threads between tests).
- A thread throwing an exception that nobody reads because `Future.get()` is never called. The tests call `get()` on each future.
- Believing a passing race test proves absence of races. It proves one stressed interleaving; the real proof is the design (a database constraint, a version column).

### Debugging

If a race test is flaky, the bug is real until proven otherwise: print the statuses, check isolation level and transaction boundaries, and check whether the guard is at the database (reliable) or only in application memory (breaks across nodes). A real instance of this lesson: the ticket-number lock was removed from the long create transaction because it serialised same-tenant creates (p99 of 477 to 1 033 ms before, 81 to 84 ms after at 10 clients; measured on H2 only), and `TicketNumberAllocatorTest` plus `TenancyTest` verify correctness after that change.

### Alternatives

jcstress (JVM memory model), property-based concurrency (jqwik), Lincheck (Kotlin and Java linearizability checks), Jepsen-style external fault tests for distributed behaviour.

### Interview Q&A

**Q1. Why `containsExactlyInAnyOrder(200, 409)`?**
Because winner order is nondeterministic. The invariant is "exactly one winner and one detected conflict". Any other multiset is a defect: two 200s is a lost update.

**Q2. Optimistic or pessimistic locking for ticket edits?**
Optimistic (ADR-009). Edits are rare relative to reads, conflicts are rare, a lock held across a human think time is unacceptable, and the 409 with "Reload latest" gives a clear user experience. Pessimistic locks fit short, contended read-modify-write sections.

**Q3. A race test fails once in 200 runs. What now?**
Treat it as a production bug. Reproduce with a loop, add logging of the interleaving, look for a check-then-act sequence without a database constraint, and fix the design (constraint, `UPDATE ... WHERE`), not the test.

**Q4. How would you test a distributed lock or an outbox lease across nodes?**
Run two relay instances against one PostgreSQL in a Testcontainers test, with claims done via `UPDATE ... WHERE status='PENDING' AND (lease_until < now())`. The single-JVM test here (`OutboxTest`, 6 claimers, one wins) validates the claim logic; the multi-instance test is not written.

## L.11 Tenant isolation tests

### What it is and why it exists

In a shared-database multi-tenant system, one missing predicate leaks another customer's data. Tenant isolation tests prove that identical requests from different tenants cannot see or change each other's data. Because this is the highest-severity bug class, the project tests it at four independent levels.

### The four levels

1. API behaviour: `TenantIsolationTest` (4 tests). Tenant B gets 404 (not 403) reading, updating, transitioning, assigning, commenting or listing tenant A tickets; also users, analytics (the cache is tenant-keyed), audit, settings, categories, workflows and SLA policies. Forged tenant headers and parameters are ignored, cross-tenant references are rejected, and credentials do not cross tenants.
2. Persistence: `TenancyTest` (5 tests). The Hibernate tenant filter limits even an unscoped query; a cross-tenant persist is blocked; persist without a tenant is refused; `TenantContext.require()` fails fast.
3. Structure: `ArchitectureRulesTest` (4 tests). Only allow-listed classes may bind a tenant, controllers take no tenant parameter, controllers never expose `@Entity`, the audit repository has no update or delete. This turns the review comment "never accept a tenant id from the client" into a build failure.
4. Database: `PostgresRlsDockerIT` (NOT EXECUTED): per-transaction `SET LOCAL`, fail-closed without a tenant, as a non-superuser role.

Plus feature-level isolation: `KbApiTest` (articles across tenants return 404 or 400), `AiApiTest` (cross-tenant analyze, approve, reject return 404), `CacheServiceTest` (tenant-prefixed keys), and the Playwright `tenant-isolation.spec.ts` (NOT EXECUTED).

### How the test pattern works

```text
tenantA = newTenant(); ticketA = create(tenantA)
tenantB = newTenant()
GET /tickets/{ticketA.id} as tenantB    -> 404 (not 403, never reveals existence)
PUT, transition, assign, comment        -> 404
GET /tickets as tenantB                  -> does not contain ticketA
```

### Implemented versus not implemented

Implemented: levels 1 to 3 executed; level 4 written but not executed. Not implemented: an automated rule that every new controller must have a cross-tenant test (a reviewer checklist item instead); fuzz-style tests that enumerate all endpoints from OpenAPI and replay them with another tenant's token; tests of Mongo document store isolation against a real Mongo (`MongoDocumentStoreDockerIT` not executed).

### Security and performance

The cost is negligible; the value is high. A residual risk: native SQL and `JdbcTemplate` bypass the Hibernate filter, so the troubleshooting runbook includes a grep for them.

### Common mistakes

Returning 403 (reveals existence), testing only reads and not writes, testing with the same tenant's token for both sides, forgetting caches and search indices, and not testing background jobs (the SLA job iterates all tenants).

### Debugging

If a test fails, the correlation id plus `tenant=` field in the log line (`ts=... corr=... tenant=... user=...`) shows which tenant context the request ran with. Check cache keys via `CacheService.fullKey`.

### Alternatives

Schema-per-tenant or database-per-tenant (isolation by construction, higher operational cost); RLS only; a generated cross-tenant test per endpoint from OpenAPI.

### Interview Q&A

**Q1. Why 404 and not 403 for another tenant's ticket?**
403 confirms the object exists. 404 gives an attacker nothing. The tests assert this deliberately.

**Q2. Which layer is the real protection?**
Defence in depth. The JWT-derived tenant plus the Hibernate filter is the primary application control; RLS is the database backstop (ADR-003) and is only as good as its verification, which here is unexecuted.

**Q3. How do you stop a future developer bypassing the filter?**
`ArchitectureRulesTest` for structural rules, code review checklist for native SQL, and an RLS-enabled production role.

**Q4. How would you scale isolation testing to 200 endpoints?**
Generate cases from the OpenAPI document: for every path with an id parameter, create the object as tenant A, call it as tenant B, expect 404.

## L.12 Flaky tests and how to handle them

### What it is and why it matters

A flaky test passes and fails on the same code. Flakiness erodes trust: people re-run until green, real failures are ignored, and the pipeline becomes slow. The cost is organisational, not just technical.

### Typical causes (with project-specific examples)

| Cause | Example here | Mitigation |
|---|---|---|
| Time and timezone | SLA and business-hour tests | explicit instants and zones, UTC forced in surefire |
| Background jobs racing assertions | outbox relay, SLA job | disabled in `application-test.yml` |
| Shared state | seeded tenants | tenant per test |
| Ordering | unordered SQL | explicit sort, `coalesce(slaCheckedAt, epoch)` rather than a dialect-specific null ordering |
| Dialect or config differences | H2 `DEFAULT_NULL_ORDERING=HIGH` dropped `nulls first` and the same ticket was re-scanned forever | portable SQL; defect 4 |
| Races in the test itself | threads without latches | latches, assert invariants |
| Real network and ports | Playwright and local stack | health wait, `--strictPort`, `retries` deliberately off to expose problems |
| Resource limits | CI machines with 2 cores | generous timeouts, not sleeps |

### Policy for handling

1. Detect: record failures per test over time (surefire XML in CI artifacts).
2. Quarantine quickly: tag the test, move it to a non-blocking job, create an owned ticket with a due date.
3. Fix root cause: do not add retries to hide a real race. In this project Playwright retries are zero so that a flaky e2e test is visible.
4. Delete if low value.
5. Prevent: ban `Thread.sleep` in tests, review new tests for time, order and shared state.

### Implemented versus not implemented

The project has controls that prevent flakiness (deterministic provider, isolated databases, disabled schedulers) but no flake tracking, no quarantine mechanism and no CI history, because no pipeline has run.

### Common mistakes

Auto-retry on failure for all tests, raising timeouts until it passes, and quarantining without an owner so the test rots.

### Debugging

Run the test 100 times (`-Dsurefire.rerunFailingTestsCount` is for detection, not a fix), run it alone, under CPU limits (`taskset` or Docker CPU quota), and in reverse order.

### Interview Q&A

**Q1. Should CI retry failed tests?**
Only as a measured tool: a retry that passes should still record the test as flaky and open a ticket. Silent retries erase the signal.

**Q2. A test fails 1 in 50. How do you decide whether it is a test bug or a product bug?**
If the failing outcome violates the stated invariant (two 200s on a version conflict), it is a product bug. If the assertion assumed an ordering or timing the system never promised, it is a test bug.

**Q3. What is the cheapest flake prevention?**
Controlling inputs: fixed clock, seeded randomness, isolated state and no sleeps.

**Q4. How do you keep the team honest about quarantines?**
Make the quarantine list visible, cap its size, and treat each item as an owned bug with an SLA.

## L.13 Code review practice (as a quality gate)

Part N gives a full review guideline and a sample review. Here the focus is how review fits the delivery pipeline.

### What it is and why it exists

Code review is a human check of a change before merge. It catches design problems, security gaps and knowledge silos that automated tests do not. It is also the main way standards spread through a team.

### How it works in a healthy flow

1. Author keeps the change small (aim for a few hundred changed lines at most) and writes a description: what, why, how tested, rollout and rollback.
2. Automation runs first (lint, typecheck, unit tests); reviewers should never be a human compiler.
3. A reviewer reads the tests first, then the design, then details, and classifies comments (blocking, suggestion, question, nit).
4. Merge only with green checks and at least one approval from someone who owns the area.

### How THIS PROJECT supports it

- Executable review rules: `ArchitectureRulesTest` and `AiArchitectureTest` encode what a reviewer would otherwise check by eye (tenant binding, no entities in controllers, audit append-only, the single `addComment` call site).
- Runbook greps for risky constructs (`JdbcTemplate`, `createNativeQuery`, bare `findById`).
- `ARCHITECTURE-DECISIONS.md` (20 ADRs) gives reviewers the reasons behind constraints.
- Not present: a pull request template, `CODEOWNERS`, branch protection settings (these live in the hosting platform, and there is no remote configured in the repository).

### Common mistakes

Reviewing style that a formatter could enforce, approving without running or reading tests, giant diffs, delayed reviews, and reviews that attack the author.

### Interview Q&A

**Q1. What do you check first in a pull request for this codebase?**
Does it touch tenant binding, authorization or data access? If so, is there a cross-tenant test and a role-matrix entry? Then migrations (backward compatibility), then failure modes and observability.

**Q2. How do you review a database migration?**
For lock duration on large tables, backward compatibility with the old application version running during deploy, index creation strategy and rollback story.

**Q3. How big should a pull request be?**
Small enough to review in under an hour with full attention, which usually means a single behavioural change plus its tests.

**Q4. How do you keep review fast?**
Agreed turnaround expectations, automation for style, rotating reviewers and splitting work.

## L.14 Build automation

### What it is and why it exists

Build automation turns source into a verified, deployable artifact reproducibly, with one command. It exists so "works on my machine" is replaced by "works in the pipeline".

### How THIS PROJECT builds

- Maven multi-module: root `pom.xml` and `pom-parts.xml` define versions; modules are `core-service`, `notification-service`, `gateway`, `dsa-lab`, plus `platform-events` (shared event contract). The notification module is a library in core when `cx.notifications.embedded=true`; the standalone executable is the `exec` classifier jar.
- Commands recorded in `RUN-AND-TEST-GUIDE.md` and `core-service/README.md`:

```bash
mvn -pl core-service -am test                 # 179 tests, docker-tagged excluded
mvn -pl core-service -am -DskipTests package  # runnable jar
mvn -pl core-service -am -Pdocker-tests test  # include Testcontainers tests
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
cd e2e && npm ci && npx playwright install chromium && npm test
```

- Frontend `npm run build` runs `tsc --noEmit && vite build`, so type errors fail the build; `npm run lint` uses `eslint src --max-warnings=0`.
- `scripts/run-local.sh` builds and starts core, gateway and the dev server. Its header states it was written but not executed end to end by its author.
- Containers: `infra/docker/*.Dockerfile`. The core image uses `eclipse-temurin:21-jre`, a non-root `app` user, `MaxRAMPercentage=75` and `ExitOnOutOfMemoryError` so a dead JVM is restarted by the orchestrator. Built images: never built (no Docker).
- Reproducibility facts: Maven dependency versions are managed by the parent; npm uses lockfiles with `npm ci`; the validation record pins the exact JDK (GraalVM 21.0.7) and Maven (3.9.12).

### Implemented versus not implemented

Implemented: build, test and package commands. Not implemented: a committed pipeline run, dependency vulnerability scan (`npm audit`, OWASP dependency-check or similar; `docs/security-hardening.md` states none was done), SBOM generation, image scanning, build cache strategy in CI, version stamping and release tagging.

### Performance, security, mistakes

Cache `~/.m2` and the npm cache. Pin plugin and action versions. Never put secrets in build arguments or images (the compose file requires secrets via `${VAR:?...}` and refuses to start otherwise). Common mistakes: `-DskipTests` in the release build, unpinned tags, builds depending on a developer's local state, and secrets baked into the frontend bundle (`VITE_API_BASE_URL` is baked in at build time, so it must never contain a secret).

### Debugging

Reproduce locally with the exact command from CI, compare JDK and Node versions, run `mvn -X` for dependency resolution and `mvn dependency:tree`.

### Alternatives

Gradle (faster incremental builds, flexible, steeper to author), Bazel (monorepo scale), Jenkins, GitLab CI, CircleCI, Azure DevOps.

### Interview Q&A

**Q1. What makes a build reproducible?**
Pinned toolchain and dependencies, no network-dependent versions (`latest`), lockfiles, clean checkouts, deterministic packaging.

**Q2. Why `npm ci` instead of `npm install` in CI?**
It installs exactly what the lockfile says and fails on drift.

**Q3. Where should the JWT secret for e2e in CI come from?**
A throwaway value generated for the job, never a production secret. The sample workflow uses an obviously fake throwaway string.

**Q4. How would you speed up a slow Maven build?**
Parallel module builds (`-T`), dependency caching, test tagging, and avoiding repeated context creation in tests.

## L.15 The CI pipeline

### What it is and why it exists

Continuous integration merges and verifies every change automatically so integration problems appear within minutes of the change, not weeks later.

![Figure: Proposed CI/CD pipeline stages from pull request to rollout](docs/diagrams/png/pdf2L-ci-pipeline.png)

### The proposed pipeline and its sample file

`.github/workflows/ci.yml` (**sample, never executed**, labelled so in its first lines) defines three jobs:

1. `backend`: Java 21 (Temurin), Maven cache, `mvn test` (excludes docker-tagged), then `mvn -Pdocker-tests ... test` for Testcontainers on a Docker-capable runner, and uploads surefire reports.
2. `frontend`: Node 20, `npm ci`, lint, typecheck, Vitest, build.
3. `e2e` (needs the other two): builds jars, starts core and gateway with a throwaway secret, waits for `/actuator/health`, installs Chromium and runs Playwright, uploads traces on failure.

Design choices, with reasons:

- `permissions: contents: read` follows least privilege.
- `concurrency` with `cancel-in-progress` avoids wasting runners on superseded pushes.
- `timeout-minutes` bounds hung jobs.
- Jobs run in parallel where independent (backend, frontend) and serially where dependent (e2e needs built artifacts).
- Artifacts are uploaded `if: always()` for test reports and `if: failure()` for traces.

Open items in the sample, flagged honestly: the gateway jar name was written by assumption and must be verified; image build, scan and push are left out because the Dockerfiles were never built; the workflow was never pushed, so no run exists.

### Pipeline hygiene

Fast feedback first (lint and unit in under about 10 minutes), expensive stages after. Required checks on the main branch. Secrets only in repository or environment secrets, never echoed. Pin third-party actions by commit SHA (supply chain). Use environment protection rules for deploy jobs.

### Security

Treat pull requests from forks as untrusted: no secrets, no `pull_request_target` with checkout of untrusted code. Scan dependencies and images in the pipeline. Sign artifacts if the organisation requires provenance.

### Common mistakes

A pipeline that is only a script runner nobody maintains, ignoring flaky jobs, secrets in logs, deploy from a developer laptop, and a pipeline slower than the review cycle.

### Debugging a red pipeline

Read the first failing step, download artifacts, reproduce with the same command and toolchain, check for environment differences (timezone, Docker availability, memory).

### Alternatives

GitLab CI (integrated registry and environments), Jenkins (flexible, high maintenance), Buildkite (self-hosted agents), Azure Pipelines, Tekton and Argo Workflows (Kubernetes-native).

### Interview Q&A

**Q1. What should block a merge?**
Lint, typecheck, unit and API tests, architecture rules, and a successful build. E2E and Testcontainers tests should block too if reliable; otherwise block on merge to main and alert.

**Q2. How would you make e2e not slow down every pull request?**
Run a smoke subset on pull requests and the full suite on merge and nightly, shard across runners, and give each test its own tenant to allow parallelism.

**Q3. How do you protect the pipeline itself?**
Least-privilege tokens, SHA-pinned actions, protected branches, reviewed workflow changes, ephemeral runners, no secrets for forks.

**Q4. How do you measure CI health?**
Lead time for changes, pipeline duration p50 and p90, flake rate, mean time to green after a break.

## L.16 Deployment strategies

### What they are and why they exist

A deployment strategy defines how a new version replaces the old one while limiting user impact and allowing fast rollback.

| Strategy | How it works | Strength | Weakness |
|---|---|---|---|
| Recreate | Stop old, start new | Simplest | Downtime |
| Rolling | Replace instances gradually | No extra capacity, default in orchestrators | Two versions live together; slow rollback |
| Blue/green | Run full new environment, switch traffic | Instant switch and rollback | Double capacity; database must support both versions |
| Canary | Send a small share of traffic to new version, watch SLIs, expand | Real-traffic validation, small blast radius | Needs good metrics and traffic routing |
| Shadow | Mirror traffic to new version, discard responses | Safe load validation | Side effects must be suppressed |
| Feature flags | Deploy code dark, enable by cohort | Decouples deploy from release; per-tenant rollout | Flag debt, test matrix growth |

### How these map to this platform (conceptual; nothing here is deployed)

- Statelessness: core and gateway are stateless (JWT, no server sessions), so rolling and blue/green are natural. Tenant caches are tenant-keyed and tolerant of loss.
- Two versions live together during rolling: therefore API changes must be backward compatible, event schemas are versioned (`SCHEMA_VERSION` in the outbox) and consumers are idempotent and tolerant.
- The gateway is the natural canary point: a route weight for a new core version, per-tenant routing by JWT `tid` claim (an internal pilot tenant first). The hand-written WebFlux gateway (ADR-015) has no weighted routing today; that would be new code or a load balancer feature.
- Health: `/actuator/health/readiness` and `liveness` exist (`PlatformEndpointsTest` covers them); readiness is the correct signal for traffic admission, and Compose healthchecks use them.
- Feature flags: not implemented. The closest real mechanisms are configuration switches: `cx.ai.provider` (mock by default, `http` to enable), `cx.notifications.embedded`, `cx.docstore`, and AI fault mode. A flag service with per-tenant targeting would be the next step, with the strict rule that a flag has an owner and an expiry date.
- Rollback: for a stateless service rollback is redeploying the previous image. The hard part is data (below).

### Migrations in deploys (expand and contract)

Flyway runs at startup; with several instances starting at once Flyway's lock serialises migrations. Safe procedure:

1. Expand: add new nullable column or new table in release N (old code ignores it).
2. Deploy code that writes both and reads old or new (release N+1).
3. Backfill in bounded batches.
4. Switch reads (release N+2), later contract: drop the old column in a separate release.

Dangerous migrations: adding an index on a large table without `CONCURRENTLY` (PostgreSQL), rewriting a column type, adding a not-null column with a default on older PostgreSQL versions, long locks on `ticket`. The repo's two Flyway locations help: portable SQL in `db/migration`, PostgreSQL-only SQL (RLS, GIN, trigram, triggers) in `db/migration-pg`. None of the PostgreSQL scripts has run, and `PostgresRlsDockerIT` is the first test that would apply them.

Because `ddl-auto: validate` is on, an application started against a database missing a migration fails at boot, which is fail-fast in rollouts (the new pod never becomes ready, old pods keep serving). Rolling back the code after a destructive migration is impossible, which is why contract steps are separate releases.

### Implemented versus not implemented

Implemented: stateless services, health probes, non-root images, backward-compatible event versioning, Flyway migrations, tested idempotent consumers. Not implemented: any deployment automation, Kubernetes manifests, weighted routing, feature flag service, automatic rollback, staging environment, release versioning.

### Performance and security considerations

Warm up caches and JIT before shifting full traffic (measured: a cold JVM first dashboard call took 119.5 ms vs a warm hit of 3.23 ms). Rotating `CX_JWT_SECRET` forces global re-login; plan it as a maintenance event. Never run migrations with a more privileged account than needed and keep the runtime role non-superuser and non-`BYPASSRLS`.

### Common mistakes

Deploying a migration and code that depend on each other in one step, no rollback test, flags never removed, canary without a decision metric, and ignoring in-flight messages from the old version.

### Debugging a bad rollout

Compare SLIs for old and new versions (add a `version` label), check readiness failures, check Flyway history table, check message backlog and DLQ.

### Alternatives

Platform features (Kubernetes rolling updates, Argo Rollouts, Flagger, Spinnaker, AWS CodeDeploy, LaunchDarkly or Unleash for flags), and trunk-based development with dark launches.

### Interview Q&A

**Q1. Blue/green or canary for this platform?**
Canary at the gateway by tenant: start with an internal tenant, then a small percentage of tenants, because tenant is a natural blast-radius boundary and tenant data is isolated. Blue/green is useful for the database-free parts (gateway, frontend) and for fast rollback.

**Q2. How do you ship a destructive schema change safely?**
Expand, migrate, switch, contract across separate releases; never drop in the same release that stops using the column.

**Q3. What can make a rollback unsafe?**
Irreversible migrations, events or messages written in a new format that old consumers cannot parse, and caches holding new-format values. Versioned events and tolerant consumers address the second; short TTLs the third.

**Q4. How do feature flags change testing?**
Test both states of every flag that is live; keep flags short-lived; make the flag default the safe behaviour, as the AI provider defaults to the mock.

## L.17 Verification status summary for Part L

| Claim | Evidence | Status |
|---|---|---|
| 179 core tests pass, docker excluded | `docs/core-service-validation.md` section 1 | Executed 2026-10-17 |
| 57 of 57 smoke checks pass | same, section 2 | Executed |
| Gateway 14, notification 46, dsa-lab 53 | whole-backend run, `docs/core-service-validation.md` | Executed 2026-10-17 (292 tests in total, 0 failures) |
| 7 core Docker ITs (PG RLS 2, Rabbit and Redis 2, Mongo 3) | validation record, section 1 | NOT EXECUTED, compile-checked |
| Vitest suite (86 tests, 10 files) and Angular Karma (83 specs) | `frontend/src/test`, `frontend-angular/src` | Executed 2026-10-17: 86/86 and 83/83 |
| Playwright (17 tests, 11 specs) and Angular smoke (25 steps) | `e2e/tests`, `frontend-angular/e2e/smoke.mjs` | Executed 2026-10-17: 17/17 (16 + 1 skipped without the fault stack) and 25/25 |
| `perf/load-test.mjs` results | `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 3 | Executed on H2, local profile |
| k6 scripts | `perf/README.md` | NOT EXECUTED, not syntax-checked by k6 |
| CI workflow | `.github/workflows/ci.yml` | Sample, NOT EXECUTED |
| Deployment strategies | this part | Conceptual |

Add to this table every time a run is recorded; a claim without a row is a claim without evidence.
