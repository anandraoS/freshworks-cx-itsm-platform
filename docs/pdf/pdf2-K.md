# Part K. Security

## K.0 How to read this part

Labels used throughout:

- **Project-implemented behaviour**: the code exists in the repository, the file is named, and where stated a test ran. The test suite recorded in `docs/core-service-validation.md` is 179 core-service tests with 0 failures (Docker-tagged tests excluded), plus a smoke test over HTTP.
- **Conceptual learning example**: a general technique or snippet that is NOT in the repository.
- **Gap**: a control that is not implemented. Gaps are listed deliberately; an honest gap list is worth more in an interview than a long list of claimed controls.

Boundaries of the evidence. The source of truth for the posture is `docs/security-hardening.md`. It states that **no penetration test, dependency audit, container scan or threat-modelling workshop was performed**, that the docker profile was **never executed** (no Docker on the authoring machine), and that PostgreSQL row-level security (RLS) was **defined but never run**. This part repeats those facts wherever a control depends on them.

> **Interview tip.** Security answers are judged by their honesty about residual risk. Structure each answer as: threat, control, evidence (test or code), gap, next step.

## K.1 The shape of the security design

### K.1.1 Layers

| Layer | Controls | Where |
|---|---|---|
| Edge (gateway, port 8080) | Bearer presence and HS256 signature check, token bucket per subject, correlation id validation, routing allow-list `/api/v1/**`, upstream timeouts, actuator on a separate port | `gateway/.../AuthFilter`, `RateLimitFilter`, `ProxyFilter` |
| Core authentication | JWT verification with issuer and clock skew, `jti` revocation, BCrypt, login limiter, generic failure message | `JwtService`, `JwtAuthFilter`, `TokenRevocationService`, `AuthService`, `LoginRateLimiter` |
| Authorization | Role hierarchy, `@PreAuthorize`, object-level checks in services | `SecurityConfig`, controllers, `TicketService.loadVisible` |
| Tenant isolation | Token-derived tenant, Hibernate filter, explicit predicates, composite FKs, optional RLS | Part I |
| Input and output handling | Bean Validation on DTO records, sort whitelist, page cap, DTO-only responses, no stack traces | DTOs, `PageParams`, `GlobalExceptionHandler` |
| Data and audit | Append-only audit, redaction of credential-like values | `AuditService`, `Redaction` |
| AI safety | Sanitiser, validation, human approval | Part J |
| Browser | Token in memory plus `sessionStorage`, React output escaping, security headers on nginx | `frontend/src/auth/tokenStore.ts` |

The gateway is a **cheap first line**, not the authority. Its own comment says core-service "still performs full validation (revocation by jti, roles, tenant checks) and remains the authority".

### K.1.2 Four concerns, once more

Authentication proves who. Tenant resolution decides which organisation. Authorization decides what that user may do. Data isolation prevents queries from reaching other tenants' rows. Part I.1 has the full table; in this part, K.2 covers the first, K.3 the third, K.6 the intersection of the third and fourth, and Part I the second and fourth.

### K.1.3 Request path (Project-implemented behaviour)

1. Gateway: public routes are login, register-organization, OpenAPI pages and health. Everything else under `/api/v1/` needs `Authorization: Bearer <jwt>`; signature and expiry are checked; the subject becomes the rate-limit key; over limit gives 429 with `Retry-After`.
2. Core `CorrelationIdFilter`: validates `X-Correlation-Id` against `^[A-Za-z0-9._-]{1,64}$`, else generates a UUID; puts it in the MDC; logs method, path, status and duration only; clears the tenant and MDC in `finally`.
3. Core `JwtAuthFilter`: verifies, checks revocation, builds authorities, binds the tenant. A bad token is **not** an error at this point; the request simply stays unauthenticated and the authentication entry point returns 401 `UNAUTHENTICATED` with the contract error body.
4. Authorization: route rule `anyRequest().authenticated()`, then `@PreAuthorize` on the method.
5. Service: object-level checks and tenant-scoped queries.
6. Response: a DTO record, an error body without stack trace (`server.error.include-message: never`, `include-stacktrace: never`).

## K.2 Authentication

### K.2.1 What it is

Authentication establishes that the caller is a known user. Here it is a two-step flow: **password login** that issues a token, then **bearer token verification** on every request.

### K.2.2 Why this approach

A single-page application and API clients need a simple credential for each request. A signed, self-contained JWT lets any node verify a request without a session store. The price is revocation and role freshness (K.2.8).

### K.2.3 How login works internally (Project-implemented behaviour)

`AuthService.login`:

1. Normalise slug and email (trim, lower case).
2. `limiter.check("ip:" + clientIp, "acct:" + slug + ":" + email)`: a token bucket per key; both keys must have a token; over limit throws `RateLimitedException` (429).
3. Look up the organisation by slug; if found, look up the user by `(tenantId, email)` inside `TenantContext.callAs`.
4. `encoder.matches(password, user == null ? dummyHash : user.getPasswordHash())`. When the tenant or user does not exist, the password is verified against a pre-computed **dummy BCrypt hash**, so the response time does not reveal whether the account exists.
5. If the user is missing, inactive or the password is wrong: increment `cx.auth.login{result=failure}`, write a `LOGIN_FAILED` audit row (only when the tenant exists), and throw `UNAUTHENTICATED` with the single message **"Invalid credentials"**.
6. On success: increment the success counter, update `last_login`, write `LOGIN_SUCCESS`, and issue a token.

The client IP for rate limiting comes from the servlet or the gateway's handling; the gateway replaces `X-Forwarded-For` instead of trusting it. `AuthService.firstForwarded` accepts only a first entry of at most 45 characters.

### K.2.4 JWT details

`JwtService`:

| Item | Value |
|---|---|
| Algorithm | HS256 (HMAC-SHA-256) with a shared secret of at least 32 bytes (startup fails otherwise) |
| Claims | `sub` (user id), `tid` (tenant id), `roles` (sorted list), `email`, `jti` (random UUID), `iss`, `iat`, `exp` |
| Lifetime | 60 minutes (`cx.security.jwt.ttl-minutes`) |
| Verification | `Jwts.parser().verifyWith(key).requireIssuer(issuer).clockSkewSeconds(5).parseSignedClaims(...)`; then roles non-empty, `jti` present, UUID parse of `sub` and `tid`; any parse problem becomes a `JwtException` |
| Rejected | tampered signature, expired, wrong issuer, `alg=none` (unsigned), malformed claims |

The parser is configured with `verifyWith(key)` and `parseSignedClaims`, which **refuses unsigned tokens**; that is the library-level defence against the classic `alg=none` and algorithm-confusion attacks. The test `AuthApiTest.forgedExpiredAndUnsignedTokensAreRejected` exercises forged, expired and unsigned tokens, and `tokenClaimsFollowTheContract` checks the claim shape.

### K.2.5 BCrypt

`SecurityConfig.passwordEncoder()` returns `new BCryptPasswordEncoder(10)`.

- **What it is**: an adaptive password hashing function with a per-hash random salt and a cost factor. Cost 10 means 2^10 rounds of the key schedule.
- **Why**: it is deliberately slow, which limits offline guessing if the hash table is stolen.
- **Limits**: BCrypt truncates input at 72 bytes, so the DTO validation caps passwords at 72 characters (`@Size(min = 8, max = 72)`). Cost 10 is acceptable for a demo; current guidance often suggests 12 or more depending on hardware, or Argon2id.
- **Cost**: roughly tens of milliseconds per verification, which is also why the login limiter matters: BCrypt is a CPU-based denial-of-service target.
- **Test**: `AuthApiTest.passwordsAreStoredAsBcrypt`.

### K.2.6 Revocation (logout)

JWTs are stateless, so a stolen token is valid until `exp` unless the server remembers it was revoked.

`TokenRevocationService`:

- `revoke(jti, expiry)` inserts a row in `revoked_token` (shared by all nodes through the database) and updates two Caffeine caches.
- `isRevoked(jti)` checks a **positive cache** (10 minutes, up to 50 000 entries) first, then a **negative cache** (known-not-revoked, default 3 seconds, `cx.security.revocation-negative-cache-seconds`, 0 disables), then the database.
- A scheduled purge deletes expired rows hourly.

Trade-off, stated in the class comment: on the node that handled the logout the token is rejected immediately; on **other nodes** a token cached as "not revoked" may still work for up to the negative TTL (3 seconds by default). Setting the property to 0 gives strict behaviour at the price of one query per request. The test `AuthApiTest.loginMeLogoutRevokesTheToken` shows logout works, and `TokenRevocationCacheTest` covers the cache logic.

### K.2.7 Why this design was selected

ADR-005: stateless HS256 with a database-backed revocation list. It is simple, works across nodes without a session store, and gives real logout. The ADR also lists alternatives: opaque tokens looked up server-side (instant revocation, a lookup per request), asymmetric JWT (public key at the gateway), and an external identity provider.

### K.2.8 Implemented vs NOT implemented

| Implemented | Not implemented (gap) |
|---|---|
| Password login with BCrypt, generic failure, dummy-hash timing equalisation | Multi-factor authentication |
| Signed JWT with issuer, expiry, skew, claim validation | SSO, SAML or OIDC |
| `jti` revocation list shared by nodes | Refresh tokens and rotation; short access token plus refresh |
| Login and registration limiter per IP and per account | Account lockout, breached-password check, password policy beyond 8 to 72 characters |
| Startup guard against the dev secret (docker profile) | Asymmetric signing (RS256 or EdDSA) and key rotation with `kid` |
| Audit of login success and failure | Alerting on failed-login bursts |
| `/auth/me` reads the user from the database | Immediate effect of user deactivation on an already issued token |

Two consequences worth stating clearly:

1. **Deactivated users keep working until the token expires** (up to 60 minutes), because `JwtAuthFilter` trusts the token's claims and does not re-read the user. `AuthService.me()` does check `isActive`, but ordinary endpoints do not.
2. **Role changes take effect at the next login**, because authorities are built from the token's `roles` claim.

### K.2.9 Performance

BCrypt dominates login time; JWT verification is an HMAC (microseconds); the revocation negative cache removes a per-request query. The number of caches (positive, negative) is bounded.

### K.2.10 Security considerations

- The HS256 secret is shared by gateway, core and notification-service. Anyone with it can mint a token for any tenant and role. This is the largest single trust concentration in the design.
- Dummy-hash verification equalises time but not perfectly ("constant-ish").
- Login failures are audited with the email supplied by the caller; it is stored as given after normalisation, which is acceptable here because it is the tenant's own audit, but log injection must be considered (JSON serialisation and truncation are applied; emails are validated by DTO).

### K.2.11 Common mistakes

- Accepting `alg` from the token header.
- Different messages for "no such user" and "wrong password".
- Long-lived tokens with no revocation.
- Putting the password hash or other sensitive data in the token.
- Skipping the issuer or audience check, which allows tokens from another system with the same key.

### K.2.12 Debugging

| Symptom | Check |
|---|---|
| 401 on every call | Header format `Bearer <token>`; token expiry; clock skew beyond 5 seconds; secret mismatch between gateway and core; revoked `jti` |
| 429 on login | Limiter keys per IP and account; local profile is looser (100 capacity), docker profile is 10 capacity with 5 per minute refill |
| Gateway 401 but core would accept | Gateway checks the signature and expiry only; check the shared secret value |
| Role change not visible | Re-login; authorities come from the token |

### K.2.13 Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Stateless HS256 plus revocation list (chosen) | Simple, nodes verify locally | Shared secret, revocation lag, role staleness |
| RS256 or EdDSA | Verifiers hold only a public key | Key management, rotation |
| Opaque tokens | Instant revocation, small tokens | Lookup per request |
| Server sessions with cookies | Mature, revocable | State, CSRF handling |
| External IdP (OIDC) | MFA, SSO, lifecycle | Integration, dependency |

### K.2.14 Runnable example (Project-implemented behaviour)

With the local profile running (core on port 8081; use the seed tenant slug `acme-retail` and an email from the run guide; take the demo password from `RUN-AND-TEST-GUIDE.md` rather than copying it into scripts):

```bash
read -r -s -p "demo password: " DEMO_PASSWORD; echo
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"tenantSlug\":\"acme-retail\",\"email\":\"agent1@acme.test\",\"password\":\"$DEMO_PASSWORD\"}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# Inspect the claims (header.payload.signature; payload is base64url JSON)
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo

# Use it, then log out and prove the token is dead
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/auth/me
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/auth/logout
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/auth/me   # expect 401
```

### K.2.15 Conceptual learning example: why `alg=none` is dangerous

NOT in the repository. A naive library trusts the token header. An attacker sets `{"alg":"none"}`, removes the signature and the library skips verification. Defence: the verifier is configured with the expected algorithm and key (as `verifyWith(key)` does), and unsigned tokens are rejected.

### K.2.16 Interview Q&A

**Q1. Walk through a login and say what is protected at each step.**
Normalise input; rate limit by IP and by account; find the organisation and user; verify BCrypt against the stored hash or a dummy hash so timing does not reveal existence; one generic error; audit; issue an HS256 token with `sub`, `tid`, roles, `jti`, expiry; never return the hash. The honest gaps: no MFA, no lockout beyond rate limiting.

**Q2. How does logout work with stateless JWTs?**
The `jti` goes into a database table shared by all nodes; every request checks it through a positive cache and a short negative cache. The negative cache means other nodes may accept the token for up to 3 seconds; that can be set to zero for strict behaviour at the cost of a query per request.

**Q3. Why BCrypt cost 10, and what would you change?**
It is a reasonable demo default. For production I would raise the cost to match hardware (aim for a few hundred milliseconds budget per verification at login only), or use Argon2id, and put an IdP in front for MFA. I would also cap password length at 72 bytes for BCrypt, which the DTO does.

**Q4. What is the weakness of a shared HS256 secret?**
Every service that can verify can also forge. A compromise of any of the three services (gateway, core, notification) lets an attacker mint tokens for any tenant. Asymmetric signing with a private key only in the issuer fixes this; rotation with a key id (`kid`) is the operational companion.

**Q5. A user is fired. How fast are they locked out?**
Not immediately: deactivation does not invalidate the issued token, so access can last up to 60 minutes. Mitigations: a per-user token version checked on each request, a revocation by user id, shorter access tokens plus refresh tokens, or opaque tokens.

## K.3 Authorization

### K.3.1 What it is

Authorization decides whether an authenticated user may perform an action. There are two kinds: **function-level** (can this role call this endpoint?) and **object-level** (can this user touch this specific object?).

### K.3.2 Role model (Project-implemented behaviour)

Roles: `ADMIN`, `MANAGER`, `AGENT`, `CUSTOMER`. `SecurityConfig` defines the hierarchy:

```java
RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_MANAGER\nROLE_MANAGER > ROLE_AGENT");
```

`CUSTOMER` is outside the hierarchy on purpose: it is a **restriction**, not a privilege level. `AuthUser.isStaff()` is true for any user holding a role above customer.

A `MethodSecurityExpressionHandler` bean is configured with the hierarchy so that `@PreAuthorize("hasRole('AGENT')")` also allows MANAGER and ADMIN. Without that bean the annotation would not honour the hierarchy; this is a classic mistake.

### K.3.3 Method security in the code

`@EnableMethodSecurity` activates `@PreAuthorize`. Examples found in the code base:

| Controller | Rule |
|---|---|
| `AiController` (class level) | `hasRole('AGENT')` |
| `KbController` (authoring methods) | `hasRole('AGENT')` |
| `AnalyticsController` | `hasRole('MANAGER')` |
| `SlaPolicyController` | MANAGER to read, ADMIN to change |
| `WorkflowController` | MANAGER to read or validate, ADMIN to activate |
| `CategoryController` (changes) | `hasRole('ADMIN')` |
| `AuditController` | `hasRole('ADMIN')` |
| `UserController` | AGENT to list, ADMIN to create and update |
| `TenantSettingsController` | `hasRole('ADMIN')` |
| `TicketController` | most routes any authenticated user (customers allowed with object rules), staff-only routes `hasRole('AGENT')` |

### K.3.4 Object-level rules (Project-implemented)

`TicketService.loadVisible(id)`:

```java
Ticket t = tickets.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Ticket"));
if (!me.isStaff() && !t.getRequesterId().equals(me.userId())) throw ApiException.notFound("Ticket");
```

A customer sees only their own tickets and PUBLIC comments, and may only reopen. `RoleMatrixTest` covers these: `endpointAccessByRole` (endpoint by role matrix), `customersSeeOnlyTheirOwnTicketsAndPublicComments`, `customerMayOnlyReopen`, `lastAdminCannotBeRemoved`.

### K.3.5 Why selected

Declarative annotations keep the policy next to the endpoint where it can be reviewed. The hierarchy avoids repeating "ADMIN or MANAGER or AGENT". Object rules live in the service because annotations cannot express "is this the requester?" cleanly.

### K.3.6 Configuration

No runtime configuration; roles are stored on the user (`app_user.roles`) and changed by ADMIN through `UserController`. The "last admin cannot be removed" rule prevents lock-out.

### K.3.7 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Role hierarchy and method security | Fine-grained permissions (per-feature scopes) |
| Endpoint-by-role test matrix | Policy engine (for example ABAC with attributes) |
| Customer object scoping | Field-level permission model beyond DTO choices |
| ADMIN-only role changes | Approval flow for privilege escalation; audit review UI |
| Last-admin protection | Role changes effective without re-login |

### K.3.8 Performance

Annotation checks are cheap. Object-level checks cost one tenant-scoped query that the service needs anyway.

### K.3.9 Security considerations

- A missing annotation is the failure mode. Mitigation in this project: the route default is `authenticated()` (not `permitAll`), and `RoleMatrixTest` is an endpoint list. A new endpoint must be added to the matrix; there is no automatic scan that every controller method has an annotation or a deliberate absence.
- Function-level checks without object-level checks lead to IDOR (K.6).
- Roles in the token are stale until re-login.

### K.3.10 Common mistakes

- Using `hasAuthority('ADMIN')` where authorities are prefixed `ROLE_`.
- Checking roles in the UI only.
- Forgetting the hierarchy bean.
- Returning 403 for foreign objects (leaks existence) instead of 404.
- Putting authorization logic in the controller and forgetting the same service method from a job or event handler.

### K.3.11 Debugging

| Symptom | Check |
|---|---|
| 403 for a manager on an agent endpoint | Hierarchy bean missing or misnamed; `ROLE_` prefix |
| 403 body shape | `ErrorCode.FORBIDDEN` from `accessDeniedHandler` |
| 404 for an existing ticket | Wrong tenant in the token, or customer not the requester |
| Role changed but still denied | Re-login (token claim) |

### K.3.12 Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| RBAC with hierarchy (chosen) | Easy to explain and test | Coarse |
| Permission-based (scopes) | Fine-grained | Larger management surface |
| ABAC or policy engine (OPA, Cedar) | Expressive | Operational complexity |
| ACL per object | Precise sharing | Storage and checks per object |

### K.3.13 Conceptual learning example: a permission check helper

NOT in the repository.

```java
// Conceptual learning example
@PreAuthorize("@perm.canEditTicket(#id)")
public TicketDto update(@PathVariable UUID id, @RequestBody UpdateTicket r) { ... }
```

`@perm` is a bean that loads the ticket with the tenant predicate and applies rules (assignee, group, status). The risk is a second query and divergent rules; the project keeps the rules inside the service.

### K.3.14 Interview Q&A

**Q1. Explain RBAC in your project and how the hierarchy is wired.**
Four roles; ADMIN includes MANAGER which includes AGENT; CUSTOMER separate. A `RoleHierarchy` bean is plugged into a `MethodSecurityExpressionHandler` so `hasRole('AGENT')` accepts higher roles. Endpoints are annotated; services add object rules (customers see only their own tickets). `RoleMatrixTest` checks the matrix.

**Q2. Why is CUSTOMER outside the hierarchy?**
It is a restriction. If CUSTOMER were the lowest level of the hierarchy, then AGENT would inherit customer permissions and the design would have to subtract privileges. Treating it separately keeps staff checks simple (`isStaff()`).

**Q3. What is the difference between authentication failures and authorization failures in your API?**
Missing or invalid token: 401 `UNAUTHENTICATED`. Valid token without the needed role: 403 `FORBIDDEN`. Valid token and role but foreign or missing object: 404 `NOT_FOUND`.

**Q4. How do you stop a developer from forgetting `@PreAuthorize`?**
Default-deny at the route level (`anyRequest().authenticated()`), the role matrix test, and code review. A stronger step is an architecture test that fails if a controller method has neither an annotation nor an allow-list entry. Not built.

**Q5. How would you evolve to permission-based authorization?**
Introduce permission strings in the token or a permission service, annotate with `hasAuthority('ticket:assign')`, map roles to permissions in configuration, and keep the same object-level checks.

## K.4 Secure sessions and token storage

### K.4.1 What the browser does (Project-implemented behaviour)

`frontend/src/auth/tokenStore.ts`:

- Primary copy of the session (`accessToken`, `expiresAt`, `user`) in a module variable.
- A mirror in `sessionStorage` under key `cx.session.v1` so a page reload keeps the session; cleared when the tab closes.
- No `localStorage` (not shared across tabs, not persisted).
- Expired sessions are dropped on read.
- On 401 the session is cleared; on logout the client calls `POST /auth/logout` (server-side revocation) and clears the cache (tenant-first query keys).
- The code base avoids `dangerouslySetInnerHTML`; React escapes output.

### K.4.2 The documented trade-off

Any script running in the page origin can read `sessionStorage`, so a successful cross-site scripting (XSS) attack could steal the token. HttpOnly, SameSite cookies avoid that, but require CSRF defences and backend support that the API contract does not define (contract: bearer header). The file's own comment lists the mitigations: short-lived token, no `localStorage`, no raw HTML rendering, server-side revocation, tenant derived from the JWT.

### K.4.3 CSRF and CORS

- **CSRF disabled deliberately** (`csrf(AbstractHttpConfigurer::disable)`). CSRF exploits browsers attaching **cookies** automatically. This API is stateless (`SessionCreationPolicy.STATELESS`), uses no cookies, and requires an `Authorization` header that browsers never attach on their own, so there is nothing for CSRF to ride on. If the project ever moved to cookie sessions, CSRF protection would have to be turned on.
- **CORS** allow-list from `cx.cors.allowed-origins` (default `http://localhost:5173`), allowed methods `GET, POST, PUT, DELETE, OPTIONS`, allowed headers `Authorization, Content-Type, Idempotency-Key, X-Correlation-Id`, `allowCredentials(false)`, no wildcard. `PlatformEndpointsTest` tests allowed and denied origins.
- HTTP Basic, form login and the default generated user (`UserDetailsServiceAutoConfiguration`) are disabled or excluded.
- nginx image: `X-Content-Type-Options`, `X-Frame-Options DENY`, `Referrer-Policy no-referrer`. **No CSP and no HSTS** (gap).

### K.4.4 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Memory plus `sessionStorage`; short 60-minute token; server logout | HttpOnly cookie plus CSRF; refresh token rotation |
| Stateless API, CSRF off with reasoning | Content-Security-Policy; Trusted Types |
| CORS allow-list without credentials | Subresource integrity; dependency audit of the frontend |
| Basic security headers on the frontend server | HSTS and TLS termination (no TLS in the repository) |

### K.4.5 Performance, mistakes, debugging

- Performance: none material.
- Mistakes: storing tokens in `localStorage` for convenience; disabling CSRF while using cookies; `Access-Control-Allow-Origin: *` with credentials; logging the Authorization header.
- Debugging: if the browser blocks a request, read the console for the CORS error and compare the origin with `cx.cors.allowed-origins`; remember that a preflight `OPTIONS` is permitted by the filter chain.

### K.4.6 Alternatives and trade-offs

| Storage | XSS exposure | CSRF exposure | Notes |
|---|---|---|---|
| Memory only | Lowest persistence | None | Lost on reload |
| `sessionStorage` (chosen) | Readable by XSS | None | Per tab |
| `localStorage` | Readable by XSS | None | Persists, shared across tabs |
| HttpOnly SameSite cookie | Not readable by script | Needs CSRF defence | Needs backend support |
| Backend-for-frontend pattern | Tokens never in browser | Cookie to BFF | Extra service |

### K.4.7 Interview Q&A

**Q1. Where do you store the token and why?**
In memory with a `sessionStorage` mirror. It is a deliberate trade-off: scripts can read it, so XSS is the risk; HttpOnly cookies would remove that but require CSRF defences and backend contract changes. I mitigate with a short lifetime, React escaping, no raw HTML, server-side revocation and no `localStorage`.

**Q2. Why is CSRF disabled and is that safe?**
CSRF needs automatic credential attachment. Cookies are attached automatically; an `Authorization` header set by code is not. With a stateless API and no cookies, there is no ambient credential to abuse. It becomes unsafe the moment cookies carry authentication.

**Q3. What would you add for production browser security?**
A strict CSP, HSTS over TLS, dependency scanning, perhaps a BFF holding tokens, and Trusted Types for DOM sinks.

**Q4. What does `allowCredentials(false)` do?**
It stops browsers from sending cookies or HTTP auth on cross-origin requests, which is fine for a bearer-header API and removes a class of CORS misconfiguration.

## K.5 Input validation and output handling

### K.5.1 What it is

Validation ensures that input has the expected shape and size before it reaches business logic. Output handling ensures responses reveal only intended data.

### K.5.2 What the project does (Project-implemented behaviour)

| Control | Where | Example |
|---|---|---|
| Bean Validation on DTO records | `IdentityDtos`, ticket DTOs | login: `@NotBlank @Size(max = 40) tenantSlug`, `@Email @Size(max = 200) email`; registration slug `^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$`; password `@Size(min = 8, max = 72)` |
| Structured 400 body | `GlobalExceptionHandler` | `VALIDATION_FAILED` with `fieldErrors` |
| Pagination bounds | `PageParams` | `page >= 0`, `size` 1 to 100 (500 gives 400, tested in `TicketApiTest`) |
| Sort whitelist | `PageParams` | unknown sort field gives 400 |
| LIKE wildcard escaping | search code | `%` and `_` in user text are escaped |
| Idempotency-Key format | ticket creation | regex-validated header |
| Workflow definitions | `WorkflowValidator` | data not scripts; SpEL or EL markers rejected; size caps; 20 actions per run |
| Webhook URL | `IntegrationsDto` | pattern allowing empty or an https URL up to 280 characters (https only; stored, never fetched) |
| Correlation id | `CorrelationIdFilter` | strict regex; prevents log injection |
| Unknown JSON fields | Jackson `fail-on-unknown-properties: false` | ignored silently (documented as a minor gap) |
| Responses are DTOs | `ArchitectureRulesTest.controllersNeverExposeEntities` | no entities in controller signatures |
| Errors | `include-message: never`, `include-stacktrace: never` | no internals leak |
| SQL injection | Spring Data derived queries and parameter binding; Criteria API in audit search | no string-built SQL in repositories |
| Attachments | metadata only | size check constraint; no file bytes handled |

Test coverage: `AuthApiTest.registerOrganizationValidatesAndRejectsDuplicateSlug` (field errors on short passwords and missing names), `TicketApiTest` bounds, `WorkflowValidatorTest`, `PlatformEndpointsTest` (error schema).

### K.5.3 Why selected

Declarative constraints on records are close to the data shape, reviewed with the API, and applied before the controller body. Whitelisting beats blacklisting: allowed sort fields, allowed characters.

### K.5.4 Implemented vs NOT implemented

| Implemented | Not implemented (gap) |
|---|---|
| Field validation, whitelists, bounded pagination | Explicit request body size limit (framework default) |
| Error bodies without internals | Strict unknown-property rejection |
| Escaping of search wildcards | Content-type or file scanning for attachments (no uploads) |
| Rate limits on public endpoints | Maximum `page` depth; deep offsets are costly |

### K.5.5 Performance and security notes

- Validation is cheap; deep offset pagination is not (performance guide). A cap on `page` or keyset pagination would address both abuse and cost.
- Output: React escapes text; if rich text is ever introduced (KB articles with HTML), sanitise on the server with an allow-list and render with care.
- Do not validate only on the client.

### K.5.6 Common mistakes

- Validating format but not length.
- Trusting enum values from JSON without a binder (the project uses enums, which reject unknown values).
- Reflecting raw input into error messages (the project returns static messages and field names).
- Using `@Valid` on a DTO but forgetting it on nested objects or collections elements.

### K.5.7 Debugging

A 400 with `fieldErrors` lists the field and message; reproduce with the exact JSON. For a validation that "does not run", check that `@Valid` is on the `@RequestBody` parameter and that the DTO constraint is on the record component.

### K.5.8 Conceptual learning example: SQL injection vs parameter binding

NOT in the repository (the project avoids this).

```java
// BAD: string concatenation
em.createNativeQuery("select * from ticket where title like '%" + q + "%'");
// GOOD: bound parameter plus wildcard escaping
em.createQuery("select t from Ticket t where lower(t.title) like :q escape '\\'")
  .setParameter("q", "%" + escapeLike(q.toLowerCase()) + "%");
```

### K.5.9 Interview Q&A

**Q1. How do you validate input in a Spring Boot API?**
Constraint annotations on request DTO records with `@Valid`, a global handler that turns violations into a structured 400, whitelists for sort and filter fields, size caps on pages and text, regexes for identifiers, and server-side re-validation of anything security relevant. Validation errors return field names and static messages, never raw input.

**Q2. Why DTO records instead of entities in controllers?**
To avoid mass assignment (a client setting `tenantId` or `roles`), accidental exposure of fields, and lazy-loading problems. The architecture test enforces no entities in controller types.

**Q3. How do you prevent injection?**
Parameter binding everywhere, no concatenated SQL, escaping for `LIKE`, workflow rules that are data rather than expressions, strict patterns for ids and headers, and no logging of raw bodies. The mapped risk categories are SQL, log, expression and HTML injection.

**Q4. What validation gap would you fix first?**
A request body size limit and a maximum page depth.

## K.6 IDOR / BOLA prevention and the 404 convention

### K.6.1 What it is

An insecure direct object reference (IDOR), now usually called broken object level authorization (BOLA, OWASP API1), is when a caller supplies an object id and the server returns or changes it without checking that the caller may access that object.

### K.6.2 Why it is the top risk here

Ids are in every URL (`/tickets/{id}`). Anyone can try another id. In a multi-tenant system the worst case is cross-tenant access, which the design treats as a severity-one defect.

### K.6.3 How the project prevents it (Project-implemented behaviour)

1. **Tenant-scoped lookups**: `findByIdAndTenantId(id, tenant)` is the default finder; the tenant comes from the token (Part I).
2. **Requester scoping for customers**: `TicketService.loadVisible` also checks `requesterId` for non-staff users.
3. **404, not 403**, for both "does not exist" and "belongs to someone else", so existence is not revealed. The same ApiException (`notFound("Ticket")`) is thrown in both cases.
4. **Composite foreign keys** prevent cross-tenant references from being stored (Part I.4.4).
5. **Hibernate filter and RLS** as additional layers (Part I).
6. **Child objects re-checked**: comments, attachments and AI suggestions load the parent ticket through the tenant-scoped finder; `AiDecisionService.approve` loads both suggestion and ticket with tenant-scoped finders.
7. **Tests**: `TenantIsolationTest.tenantBCannotTouchTenantATickets` attempts read, update, transition, assign, comment and list across tenants; `usersAnalyticsAuditSettingsAndConfigAreTenantScoped`; `AiApiTest.agentPlusOnlyAndCrossTenantIs404`; `RoleMatrixTest.customersSeeOnlyTheirOwnTicketsAndPublicComments`.

### K.6.4 Gaps

- No automated fuzzing of every id-bearing route.
- Any new native SQL or `JdbcTemplate` call bypasses the Hibernate filter; RLS (the backstop) is not executed.
- Not every future endpoint is in the matrix until a developer adds it.
- The notification REST API keys by `tid` and `sub` and has its own tests, not covered in this part.

### K.6.5 Runnable example (Project-implemented behaviour)

Using two tenants from the seed data (take slugs and passwords from the run guide):

```bash
# 1. log in to tenant A and tenant B, get TOKEN_A and TOKEN_B (as in K.2.14)
# 2. list a ticket id from tenant A
TICKET_A=$(curl -s -H "Authorization: Bearer $TOKEN_A" "http://localhost:8081/api/v1/tickets?size=1" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
# 3. tenant B asks for tenant A's ticket: expect 404, not 403
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN_B" "http://localhost:8081/api/v1/tickets/$TICKET_A"
# 4. try to smuggle a tenant: still tenant B's view
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN_B" -H "X-Tenant-Id: <tenant A uuid>" "http://localhost:8081/api/v1/tickets/$TICKET_A"
```

### K.6.6 Why 404 and not 403

A 403 says "this exists but you may not see it". An attacker enumerating ids learns which ids are valid. A 404 is identical for both cases. The cost: legitimate users cannot tell a typo from a permission problem, which is acceptable for cross-tenant and customer-scope cases. Within a tenant, a 403 on function-level checks (role) is fine because the endpoint existence is not secret.

### K.6.7 Common mistakes

- `repo.findById(id)` followed by an `if (!tenant.equals(entity.getTenantId()))` check that is forgotten somewhere.
- Authorization in the controller and not in the service reused by other callers.
- Returning different timing for existing and non-existing foreign ids (minor).
- Using sequential integer ids, which make enumeration trivial. The project uses UUIDs for ids, but UUIDs are not a security control; ticket **numbers** are sequential per tenant and are shown to users, which is fine because lookups are tenant-scoped.

### K.6.8 Debugging

If a cross-tenant test fails, find which finder the service used (`findById` is the usual culprit), check the Hibernate SQL for `tenant_id`, and check that the token's `tid` is what you expect.

### K.6.9 Interview Q&A

**Q1. How do you prevent IDOR in a multi-tenant API?**
Never load by id alone: always id plus tenant (and owner when the role is restrictive), return 404 for foreign objects, back it with composite foreign keys, a filter and ideally RLS, and test each new endpoint from a second tenant.

**Q2. Are UUIDs enough?**
No. They make guessing harder, but ids leak in logs, URLs and screenshots. Authorization must not depend on unguessability.

**Q3. Why 404 instead of 403?**
To avoid confirming existence. I use 403 for role failures and 404 for object-level failures.

**Q4. How do you test BOLA systematically?**
Two tenants and a client for each role; for every id-bearing endpoint call it with the other tenant's token and expect 404; add negative tests for customer-versus-staff. A generated test from the OpenAPI spec could cover all routes; not built.

**Q5. What is function-level versus object-level authorization?**
Function-level: may this role call this endpoint? Object-level: may this user touch this particular record? Both are required; many breaches are object-level.

## K.7 OWASP API Security Top 10 (2023) mapping

![Figure: OWASP API Top 10 mapped to the project. Strong controls, partial controls, and trust assumptions.](docs/diagrams/png/21-owasp-api-mapping.png)

The table below is derived from `docs/security-hardening.md` section 3 and re-checked against the code read for this guide. "Evidence" names tests that ran; "gaps" are honest.

| # | Risk | Implemented controls | Evidence | Gaps |
|---|---|---|---|---|
| API1 | Broken object level authorization | Tenant-scoped finders; customer requester scoping; 404 for foreign objects; composite FKs; optional RLS | `TenantIsolationTest`, `RoleMatrixTest`, `AiApiTest` | No fuzzing of every id route; native SQL bypasses the filter; RLS not executed |
| API2 | Broken authentication | HS256 verification with issuer and skew; revocation; BCrypt; rate-limited login; generic error; dummy-hash timing; dev-secret startup guard | `AuthApiTest`, `LoginRateLimiterTest`, `RateLimitApiTest`, `TokenRevocationCacheTest` | No MFA, SSO, refresh tokens, lockout, password policy; shared symmetric secret; deactivated users keep tokens up to 60 minutes |
| API3 | Broken object property level authorization | DTO records only; no entities in controllers; customers get PUBLIC comments and filtered activity; roles editable by ADMIN only | `ArchitectureRulesTest`, `RoleMatrixTest` | Unknown JSON fields ignored; webhook URL returned to ADMIN |
| API4 | Unrestricted resource consumption | Page size cap 100; sort whitelist; gateway token bucket; login and registration limiter; AI per-tenant limit, bounded pool, deadline; workflow caps; dashboard fold cap 50 000; idempotency key purge; Hikari bounds | `RateLimitApiTest`, `TicketApiTest`, `WorkflowValidatorTest` | No body size limit configured; per-instance limits; no per-tenant quotas; unbounded `page` depth; unindexed LIKE search in the portable path |
| API5 | Broken function level authorization | `@PreAuthorize` per endpoint; hierarchy; method security handler | `RoleMatrixTest`, smoke test (customer to analytics 403, agent to audit 403) | Actuator Prometheus and OpenAPI intentionally unauthenticated |
| API6 | Unrestricted access to sensitive business flows | Idempotent ticket creation; AI replies need human approval; registration rate limited and audited; SLA and first response server-computed | `TicketApiTest`, `AiApiTest` | Open self-service tenant registration, no CAPTCHA or email verification; no per-user ticket quota; comment POST not idempotent |
| API7 | Server-side request forgery | No user-supplied URL is fetched; webhook URL validated https and stored, never called; AI provider URL from server config, https only, no redirects | Code review | If webhooks are ever called: allow-list, private-range blocking, egress filtering; HTTP AI provider never executed |
| API8 | Security misconfiguration | No default Spring user; CSRF, basic and form login disabled deliberately; CORS allow-list; stateless; `StartupGuards`; non-root containers; localhost-bound compose ports; actuator metrics authenticated; no env or heapdump endpoints | `PlatformEndpointsTest` | Seed data on by default in docker profile; health, Prometheus and Swagger open; no CSP; floating image tags; docker profile never executed |
| API9 | Improper inventory management | Versioned `/api/v1`; single contract file; generated OpenAPI; gateway routing allow-list with JSON 404 | `GatewayRoutingTest`, `PlatformEndpointsTest` | OpenAPI/Swagger public; no deprecation policy; contract drift only caught on covered routes |
| API10 | Unsafe consumption of APIs | AI output validated against candidates and cleaned; provider cannot call back; broker messages parsed defensively with DLQ for poison | `NotificationMessageHandlerTest`, AI tests | **The broker is trusted**: consumers believe `tenantId` and `recipientUserIds`; no per-service broker users; RabbitMQ topology never run |

### K.7.1 How to talk about each risk (summary sentences)

- **API1**: "Every lookup is id plus tenant, plus owner for customers, 404 on mismatch, tested across two tenants."
- **API2**: "Signed tokens with revocation and BCrypt, rate limited and audited; missing MFA, refresh tokens and an IdP."
- **API3**: "DTO records, no entity binding, customers see a filtered view."
- **API4**: "Bounded by construction in pagination and AI; missing per-tenant quotas and shared limiters."
- **API5**: "Annotation per endpoint, default deny, matrix test."
- **API6**: "Idempotency and human approval protect the flows; open registration is the abuse path."
- **API7**: "The server never fetches user-supplied URLs; it stays that way only by discipline."
- **API8**: "Good defaults, known open items: seed data, Swagger, CSP."
- **API9**: "One versioned surface, but docs are public."
- **API10**: "We validate AI output, but trust the broker."

### K.7.2 Implemented vs NOT implemented, plainly

Strongly implemented and tested: API1, API3 (partly), API5. Implemented with documented gaps: API2, API4, API6, API8, API9. Trust assumption not yet closed: API10. Not exercised: API7 (no code path to attack).

### K.7.3 Common mistakes when presenting this table

- Claiming "OWASP compliant". The mapping is a self-assessment, not a test result.
- Leaving out the "not run" items (docker profile, RLS, Redis, RabbitMQ).
- Treating rate limiting as authentication.

### K.7.4 Interview Q&A

**Q1. Which OWASP API risk is most relevant to a multi-tenant SaaS and how did you address it?**
API1, BOLA. Tenant from the token, id-plus-tenant lookups, 404 for foreign objects, composite foreign keys, a filter and optional RLS, and cross-tenant tests. The gaps are native SQL, un-run RLS, and no fuzzing.

**Q2. What is API10 and where is your exposure?**
Unsafe consumption of other APIs. Two inputs: the AI provider (we validate every field) and the message broker (we trust it). The notification consumer believes the tenant and recipients in events; I would add per-service broker credentials, publisher identity and recipient validation.

**Q3. What is API6?**
Abuse of legitimate business flows, like mass account creation. Our open registration is rate limited per IP but has no CAPTCHA, email verification or approval.

**Q4. How do you keep this mapping honest over time?**
Tie each claim to a test or a file, record what was not run, and review on every feature. A CI job with dependency scanning, SAST and API fuzzing would replace self-assessment with measurements.

## K.8 Secrets management

### K.8.1 What it is

Secrets are credentials: signing keys, database passwords, API keys, broker passwords. Secrets management is how they are stored, injected, rotated and kept out of code, logs and images.

### K.8.2 What the project does (Project-implemented behaviour)

| Secret | Local profile | Docker profile |
|---|---|---|
| JWT signing secret | dummy `dev-only-NOT-A-REAL-SECRET-change-me-...` in `application.yml` (clearly marked) | `CX_JWT_SECRET` required; `StartupGuards` refuses the dev default and anything starting with `dev-only` |
| Database | H2 in memory, user `sa`, empty password | PostgreSQL, `CX_DB_PASSWORD` required |
| AI key | empty by default (`AI_API_KEY`) | environment variable; never logged |
| Broker, Redis, Mongo | not used locally | compose `${VAR:?...}` fails fast if missing |
| Example file | `env.example` contains `CHANGE_ME_dummy_*` placeholders | `.env` should be git-ignored |
| Notification service | embedded in core it uses core's secret; its standalone docker config requires the same env names without fallback credentials | validated syntactically only |

Mechanisms: environment variables and Spring placeholders (`${CX_JWT_SECRET:...}`), fail-fast validation (`JwtService` rejects secrets under 32 bytes; `StartupGuards` in the docker profile), no secret in the frontend bundle, request bodies never logged, `Redaction` masks credential-looking values in audit details, and the AI sanitiser redacts secrets before text leaves the service.

### K.8.3 Gaps

- The local profile still carries a dummy secret in a committed file, which is acceptable only because it is clearly dummy and the docker profile refuses it.
- No secret manager integration, rotation procedure or key ids.
- Seed data is **on by default in the docker profile** (`CX_SEED_ENABLED:true`) and creates accounts with a published demo password. This is the single most important item to switch off before any real deployment.
- `organization.slack_webhook_url` is stored in plaintext (https-validated, never used).
- Mongo root credentials in compose; the compose block does not pass `CX_MONGO_URI` today.
- No secret scanning in CI.

### K.8.4 Conceptual learning example: loading a secret safely

NOT in the repository.

```java
// Conceptual learning example
@Value("${cx.security.jwt.secret}") String secret;
@PostConstruct void check() {
    if (secret.length() < 32 || secret.startsWith("dev-only")) throw new IllegalStateException("secret not set");
}
```

Better: inject from a secret manager at runtime (cloud secret store, Kubernetes secret mounted as a file or env), rotate with overlap using `kid`, and make the application read it through an abstraction so rotation does not require redeploying code.

### K.8.5 Common mistakes

- Committing `.env` files or screenshots with keys.
- Putting secrets in Docker image layers or build args.
- Logging configuration objects that contain secrets.
- Reusing one secret across environments.
- Using the same value for signing and for something else.

### K.8.6 Debugging

- Startup fails in the docker profile with "cx.security.jwt.secret ... must be set to a real secret": the guard is working; set `CX_JWT_SECRET`.
- 401 across services after a deploy: the gateway, core and notification-service must share the same secret value.

### K.8.7 Rules for this repository and for you

Do not introduce secrets into code, configuration, tests, logs or examples. Use placeholders (`$VAR`, `<set in secret manager>`) and read demo credentials from the documented seed docs at run time rather than copying them into scripts.

### K.8.8 Interview Q&A

**Q1. How do you manage secrets in this project and what would you change for production?**
Environment variables with fail-fast checks and a guard against the dev default. For production: a secret manager, rotation with key ids, asymmetric signing so only the issuer holds a private key, seed disabled, encrypted fields for integration secrets, secret scanning in CI.

**Q2. Why is the dummy JWT secret in a committed file acceptable, and when is it not?**
Because it is obviously dummy, the local profile is documented as unsafe to expose, and the docker profile refuses it. It would not be acceptable if the same value could reach an internet-facing deployment, which is why the guard exists.

**Q3. How do you rotate a signing key without logging everyone out?**
Support two keys at once: sign with the new key id, verify with both until the old tokens have expired, then retire the old key. Needs `kid` and a key set; not implemented.

## K.9 Audit trails

### K.9.1 What it is

An audit trail is a durable, queryable record of security-relevant and business-relevant actions: who did what to which object when, from which request.

### K.9.2 How it works (Project-implemented behaviour)

`AuditService` records `AuditEvent` rows: tenant id, occurred-at, actor id and email, action, entity type and id, correlation id (from the MDC), and details (redacted, truncated to 3900 characters). It **joins the caller's transaction**, so the audit row commits atomically with the change it describes (or both roll back).

Actions seen in the code: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `TENANT_REGISTERED`, ticket events, AI actions (`AI_SUGGESTION_GENERATED`, `AI_ANALYSIS_DEGRADED`, `AI_SUGGESTION_APPROVED`, `AI_SUGGESTION_EDITED_APPROVED`, `AI_SUGGESTION_REJECTED`) and others.

Protection:

- The repository exposes no update or delete and is not a CRUD repository (`ArchitectureRulesTest.auditRepositoryIsAppendOnly`).
- A PostgreSQL trigger blocks `UPDATE` and `DELETE` (`V1003__pg_audit_append_only.sql`): **defined, not executed**.
- Reads go through a criteria query with the tenant predicate first; the endpoint is ADMIN-only.
- Tenant filter applies (`AuditEvent` has `@Filter`).

### K.9.3 Why selected

An in-database audit that commits with the change is simple and consistent. The correlation id lets you join audit rows to log lines and to outbox events for one request.

### K.9.4 Implemented vs NOT implemented

| Implemented | Not implemented |
|---|---|
| Append-only API, tenant-scoped, redacted, correlated | Immutable storage shipping (WORM, SIEM) |
| Login success and failure auditing | Tamper-evident hash chain |
| AI decision audit | Retention and archival policy |
| ADMIN-only read | Alerts on suspicious sequences; export; per-tenant audit download |

### K.9.5 Performance, security, mistakes, debugging

- Performance: one insert per audited action in the same transaction; the table grows without bound (no retention), so partitioning or archiving will be needed.
- Security: a database administrator can still alter rows; shipping to immutable storage is the usual answer. Audit text must never contain secrets or ticket content (redaction, truncation, and the habit of logging ids).
- Mistakes: auditing in a separate transaction so failures vanish; auditing the request body; allowing deletion for "cleanup".
- Debugging: filter by `entityType` and `actorId` with the audit endpoint; take the `correlationId` from an error response header and find the log lines with the same id.

### K.9.6 Interview Q&A

**Q1. What makes an audit trail trustworthy?**
Completeness (every sensitive action), integrity (append-only, ideally tamper-evident and stored elsewhere), attribution (actor and correlation id), confidentiality (no secrets) and retention. The project covers the first, third and fourth in the application layer, and the second only partially.

**Q2. Why write audit in the same transaction?**
So there is no change without an audit row and no audit row without the change. The exception is failure auditing, where the main transaction rolled back; login failures are recorded in their own transaction for that reason.

**Q3. What should not be in an audit row?**
Passwords, tokens, ticket body text, AI reply text, personal data beyond identifiers. The project redacts credential-like keys and truncates.

## K.10 Logging and log redaction

### K.10.1 What the project does (Project-implemented behaviour)

- Log pattern (`logging.pattern.console`): `ts=... level=... corr=%X{correlationId} tenant=%X{tenantId} user=%X{userId} logger=... msg="..."` with embedded quotes escaped.
- Access log (`http.access` logger) holds method, path, status and duration only: no headers, no bodies, no query strings.
- Correlation id from a validated header or a new UUID, returned in the response header `X-Correlation-Id`, so a user can quote it.
- `Redaction.redact` masks values of keys such as `password`, `secret`, `token`, `authorization`, `api_key`, `webhook_url` in audit details, with a regular expression `(?i)(password|passwd|secret|token|authorization|api[_-]?key|webhook[_-]?url)([\"'=: ]+)(Bearer )?[^,\"'\s}&]+` replacing the value with `***`.
- The AI service logs exception class names, not messages or payloads (`log.warn("AI provider attempt {} failed: {}", ..., getClass().getSimpleName())`), and the HTTP provider never logs the key.
- Hibernate SQL logging is at WARN so parameters are not printed.
- Error responses omit stack traces and messages.

### K.10.2 Limits

- `Redaction` is key-name based. A secret in free text with no recognisable key (for example "my pin is 1234") is not masked.
- Ticket text is never logged by design; if a developer adds it, nothing stops it. A log-scanning test or a structured-logging policy would help.
- Central log storage, access control and retention are not part of the repository.
- Log injection: the correlation id is validated; the user email in log lines is normalised by validation.

### K.10.3 Conceptual learning example: a redaction unit test

```java
// Conceptual learning example, style of the project
assertThat(Redaction.redact("{\"password\":\"hunter2\",\"user\":\"a\"}")).doesNotContain("hunter2");
assertThat(Redaction.redact("Authorization: Bearer abc.def.ghi")).doesNotContain("abc.def.ghi");
```

### K.10.4 Interview Q&A

**Q1. How do you avoid leaking secrets and personal data in logs?**
Do not log bodies or headers; log ids; structure the log with correlation id, tenant and user; mask credential-like keys at the audit boundary; keep Hibernate parameter logging off; scan for regressions; restrict access to log storage and set retention.

**Q2. Why a correlation id?**
To trace one request across the gateway, core, audit rows and outbox events and to give users a handle to quote in support tickets. It must be validated, or it becomes a log-injection vector.

**Q3. Is key-based redaction enough?**
No. It handles structured secrets, not free text. Defence in depth means not logging the data in the first place.

## K.11 Rate limiting and abuse controls

### K.11.1 What exists (Project-implemented behaviour)

| Layer | Limiter | Key | Algorithm | Scope |
|---|---|---|---|---|
| Gateway | `RateLimitFilter` plus `TokenBucketLimiter` | JWT subject, or socket peer address for anonymous routes | Token bucket (100 burst, 20 per second) | Per instance |
| Core login and registration | `LoginRateLimiter` | `ip:<addr>`, `acct:<slug>:<email>`, `reg:<ip>` | Token bucket, Caffeine-bounded to 10 000 keys, idle expiry 30 minutes | Per node; defaults 10 capacity and 5 refill per minute in the docker profile, looser locally |
| AI | `AiRateLimiter` | tenant id | Fixed window 30 per minute | Per node |
| Pagination | `PageParams` | n/a | Hard cap | Request |

Responses are HTTP 429 with the contract error body and a `Retry-After` header. The gateway does not trust `X-Forwarded-For` for limiting, so a client cannot rotate it to evade the limit. Tests: `GatewayRateLimitTest`, `LoginRateLimiterTest`, `RateLimitApiTest`, AI limiter tests.

### K.11.2 Why selected

Token buckets are simple, allow short bursts and give a natural `Retry-After`. Bounded maps stop memory exhaustion by key rotation.

### K.11.3 Gaps

Per-instance state (multiplies with node count); no per-tenant quota; no CAPTCHA; no distributed counter; anonymous gateway routes keyed by peer address, which on a shared NAT affects innocents and, behind a load balancer, would key all users by the balancer's address unless the deployment forwards the real client address in a trusted way.

### K.11.4 Interview Q&A

**Q1. Which algorithm would you choose for an API limit and why?**
Token bucket for bursts with a sustained rate; sliding window counter if you need smoother accuracy; fixed window is simplest but allows boundary bursts. The project uses token buckets at the edge and in login, and a fixed window for the paid AI feature.

**Q2. How do you rate-limit login without enabling denial of service against a user?**
Limit by IP and by account together, return 429 with a wait, avoid permanent lockout, and add step-up challenges (CAPTCHA, MFA) for suspicious patterns. The project limits both keys and does not lock accounts.

**Q3. What changes when you scale to ten nodes?**
Limits are per instance, so allowance becomes ten times larger; use a shared store or an edge limiter.

## K.12 Production hardening versus local demo

This is the table to quote when asked "is it production ready?". The answer is no, and this table says exactly why. It is a **separate table for the two postures** as required: the first columns describe what exists; the last describes what is needed.

### K.12.1 Posture comparison

| Topic | Local demo (profile `local`) | Docker profile as written (never executed) | Required before production |
|---|---|---|---|
| JWT secret | dummy dev value in `application.yml` | `CX_JWT_SECRET` required; guard refuses dev default | Secret manager; rotation; RS256 or EdDSA with `kid` |
| Database | H2 in memory, `sa` with empty password | PostgreSQL, `CX_DB_PASSWORD` required, user `cx_app` | Separate owner and runtime roles; no `BYPASSRLS`; TLS; least privilege; encrypted backups |
| Seed data | enabled; published demo password | **enabled by default as well** | `CX_SEED_ENABLED=false`; never seed demo tenants |
| Actuator and API docs | health, info, Prometheus, Swagger public in core; metrics authenticated | same | Management port or protection; disable or restrict Swagger |
| CORS | `http://localhost:5173` | configurable | Real SPA origin; no wildcard |
| TLS | none | none in repository | TLS termination; HSTS; TLS to DB, Redis, broker |
| Rate limiting | gateway bucket; looser login limiter locally | login limiter 10 and 5 per minute | Shared limiter; per-tenant quotas; CAPTCHA on registration |
| Authentication | password only, BCrypt 10 | same | MFA, SSO or OIDC, lockout, breached-password check, refresh tokens |
| Token lifetime | 60 minutes, no refresh | same | Short access token plus rotating refresh; user and tenant kill switches |
| Containers | not applicable | non-root, runtime-only images, localhost-bound ports, nginx headers | Image scanning, pinned digests, read-only root file system, CSP, SBOM |
| Logging | structured key=value, no bodies | same | Central, access-controlled, retained; PII review |
| Audit | append-only repository | plus DB trigger (not run) | Immutable shipping; retention |
| Row-level security | not available on H2 | policies on 16 tables (not run) | Run `PostgresRlsDockerIT` in CI; non-owner role |
| Data protection | BCrypt for passwords; rest plaintext | same | Encryption at rest; field encryption for integration secrets |
| Dependencies | Spring Boot 3.3.5 BOM, jjwt 0.12.6, springdoc 2.6.0 | same | Automated dependency and container scanning; update cadence |
| Mongo | not used (`cx.docstore: jpa`) | needs `CX_MONGO_URI`; root credentials in compose; compose block does not pass it | Least-privilege user, auth and TLS; per-tenant filter is application-only |
| Broker trust | in-process events | one shared broker user | Per-service users and vhost permissions; recipient validation |
| AI | mock provider only | same; fault injection refused | Evaluation, red team, vendor review, opt-in, budgets |

### K.12.2 Priority list (Project-documented, then expanded)

1. Set `CX_SEED_ENABLED=false`; protect or disable Swagger and Prometheus on public ports.
2. Execute the docker profile end to end on a Docker host: migrations, RLS tests, Redis and RabbitMQ integration tests, Mongo test; fix whatever breaks.
3. Replace shared-secret HS256 with an IdP or RS256; add refresh tokens, MFA or SSO, tenant suspension.
4. Close the broker trust gap (per-service credentials, recipient validation).
5. Add retention jobs (outbox, processed events, audit archival), request size limits, per-tenant quotas, CAPTCHA or approval on registration.
6. CI: dependency and container scanning, SAST, secret scanning, API fuzzing against a throwaway seeded environment.

### K.12.3 Secure-by-default decisions already taken

Fail closed on missing secrets (docker profile); no default Spring user; 404 over 403 for foreign objects; PUBLIC-only AI replies; append-only audit; per-transaction (`SET LOCAL`) tenant binding for RLS; redaction of credential-like values before audit storage and of PII-like values before AI calls; bounded memory in limiters and caches; graceful degradation instead of failure when optional dependencies fail.

### K.12.4 Interview Q&A

**Q1. Is this production-ready?**
No, and I can list why: the docker profile never ran, RLS never executed, seeding defaults on, shared symmetric secret, no MFA or refresh tokens, no TLS in the repo, no scanning in CI, broker trusted. What is solid is the application-level design (token-derived tenant, object-level checks, tested isolation, bounded failure) and the honest backlog.

**Q2. What do you fix in the first week?**
Seed off, docs and metrics protected, docker profile executed with RLS tests in CI, then asymmetric signing and refresh tokens, then per-tenant quotas.

**Q3. How do you separate migration and runtime database roles and why?**
The owner role runs Flyway (it needs DDL); the application role gets only DML on the tables and has no `BYPASSRLS`. That way RLS and least privilege apply to the application. The project's compose uses one owner-like user; `FORCE ROW LEVEL SECURITY` covers it but separation is better.

## K.13 Troubleshooting exercises

**Exercise 1. A request with a valid token returns 401 on core but 200 on the gateway.** Hints: gateway checks signature and expiry only; core also checks issuer and revocation. Answer: the `jti` was revoked by logout, or the issuer differs, or the token has malformed claims (roles missing).

**Exercise 2. A manager gets 403 on an agent-only endpoint after a refactor.** Hints: hierarchy bean. Answer: the `MethodSecurityExpressionHandler` bean lost its `RoleHierarchy` or the annotation uses `hasAuthority('AGENT')` instead of `hasRole`.

**Exercise 3. A customer can see another customer's ticket in the same tenant.** Answer: a new code path used `findByIdAndTenantId` without the requester check; route it through `loadVisible` and add a regression test in the style of `customersSeeOnlyTheirOwnTicketsAndPublicComments`.

**Exercise 4. After logout the token still works for a few seconds on another instance.** Answer: expected with the 3-second negative cache; set `cx.security.revocation-negative-cache-seconds=0` for strict revocation.

**Exercise 5. Startup fails in the docker profile with the JWT secret message.** Answer: the guard works; provide `CX_JWT_SECRET` (at least 32 bytes) through the environment or secret manager.

**Exercise 6. A security scan flags `/swagger-ui/**` and `/actuator/prometheus` as unauthenticated.** Answer: intentional for the demo; for production put them on the management port or protect them, as the hardening table says.

**Exercise 7. Log lines show `Authorization: Bearer ...` in an error message.** Answer: someone logged a request object or exception message with header content; remove it, add a test that exercises the path with a token and asserts the log output does not contain it, and rotate the exposed secret if it was real.

**Exercise 8. The AI endpoint returns 403 for a user who can log in.** Answer: customers get 403 by design; check the role claim in the token (roles come from the token, so a recently granted role needs a re-login).

**Exercise 9. Registration spam creates hundreds of tenants.** Answer: add email verification, CAPTCHA and approval; per-IP rate limiting alone is bypassed by distributed sources; add a janitor for unverified tenants.

## K.14 Consolidated status table

| Control | Status | Evidence |
|---|---|---|
| JWT verification, `alg=none` rejection | Implemented, tested | `AuthApiTest` |
| BCrypt hashing | Implemented, tested | `AuthApiTest.passwordsAreStoredAsBcrypt` |
| Revocation by `jti` | Implemented, tested | `AuthApiTest`, `TokenRevocationCacheTest` |
| Login limiter | Implemented, tested | `LoginRateLimiterTest`, `RateLimitApiTest` |
| RBAC and method security | Implemented, tested | `RoleMatrixTest` |
| Tenant isolation and 404 convention | Implemented, tested | `TenantIsolationTest`, `TenancyTest` |
| Input validation | Implemented, tested | `AuthApiTest`, `TicketApiTest` |
| Append-only audit | Implemented (repo level, tested); DB trigger not executed | `ArchitectureRulesTest` |
| Log and audit redaction | Implemented | `Redaction`, `PlatformEndpointsTest` |
| AI safety controls | Implemented, tested (mock only) | Part J |
| PostgreSQL RLS | Defined, **not executed** | `PostgresRlsDockerIT` never run |
| Docker profile, Redis, RabbitMQ, Mongo | Defined, **not executed** | docker-tagged tests never run |
| MFA, SSO, refresh tokens | **Not implemented** | ADR-005 |
| TLS, CSP, HSTS | **Not implemented** | hardening doc |
| Secret manager, rotation | **Not implemented** | hardening doc |
| Dependency and container scanning | **Not implemented** | hardening doc |
| Penetration test | **Not performed** | hardening doc |
| Broker authorization | **Not implemented** | hardening doc, API10 |

## K.15 Cross-cutting interview Q&A

**Q1. Describe the security architecture of the platform in two minutes.**
Gateway does cheap checks and rate limiting. Core verifies a signed token, checks revocation, binds the tenant from the token, applies role rules by annotation and object rules in services, scopes every query by tenant with layered defences, returns 404 for foreign objects, validates input with DTO constraints, redacts and audits sensitive actions, keeps the browser token short-lived with server revocation, and treats AI output as untrusted with a human approval gate. Then list the gaps: MFA, asymmetric keys, refresh tokens, TLS, scanning, RLS not run.

**Q2. What are the three biggest risks you would fix first?**
Shared symmetric signing key, seed data defaults, and un-run RLS and broker trust (isolation backstops). After that tenant quotas and registration abuse.

**Q3. How do you threat-model a new endpoint?**
Identify assets, actors and trust boundaries; check authentication, function-level and object-level authorization, tenant scoping, validation, rate limits, logging and audit, error leakage, and abuse of the business flow; write the cross-tenant and role tests first.

**Q4. Authentication, authorization, tenant resolution and data isolation: how do they differ?**
Authentication proves identity (password and token). Tenant resolution maps identity to an organisation (the signed `tid` claim). Authorization decides permitted actions (role and object rules). Data isolation ensures queries cannot cross tenant boundaries (filter, predicates, keys, RLS). A failure in one is not covered by success in another.

**Q5. How do you prove security claims?**
With tests that attack (forged tokens, cross-tenant ids, role matrix, rate limit), with architecture tests for conventions, with evidence recorded in a validation document, and by listing what was not run. Anything not executed is described as a design, not a control.

**Q6. What would you not claim?**
That the system is secure, OWASP compliant, penetration tested, or that RLS protects it. The claim is that the application layers are tested and the hardening backlog is explicit.
