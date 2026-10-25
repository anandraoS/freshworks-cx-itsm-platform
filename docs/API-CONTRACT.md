# API Contract (source of truth for backend + frontend)

Base: gateway `http://localhost:8080`, routes `/api/v1/**` -> core-service (`:8081`). Notification service `:8082` (internal; exposed via `/api/v1/notifications/**`).
All JSON. Header `X-Correlation-Id` accepted/returned. Auth: `Authorization: Bearer <JWT>`. JWT claims: `sub`=userId, `tid`=tenantId, `roles`=[...], `email`, `jti`. Tenant is ALWAYS derived from the JWT, never from a client-supplied id (switching tenant = re-login).
Roles: `CUSTOMER`, `AGENT`, `MANAGER`, `ADMIN`. CUSTOMER sees only own tickets + public comments + public articles; AGENT works tickets/KB/AI; MANAGER adds analytics + workflow read; ADMIN adds users/roles/settings/workflow-write/audit.

## Error schema (all non-2xx)
`{ "timestamp":"ISO", "status":409, "error":"CONFLICT", "code":"TICKET_VERSION_CONFLICT", "message":"...", "path":"/api/v1/...", "correlationId":"...", "fieldErrors":[{"field":"title","message":"must not be blank"}] }`
Codes: VALIDATION_FAILED(400), UNAUTHENTICATED(401), FORBIDDEN(403), NOT_FOUND(404; also used for cross-tenant ids), TICKET_VERSION_CONFLICT(409), DUPLICATE_SUBMISSION(409), INVALID_TRANSITION(422), RATE_LIMITED(429), plus additive framework-level codes CONFLICT(409), METHOD_NOT_ALLOWED(405) and UNSUPPORTED_MEDIA_TYPE(415), AI_UNAVAILABLE(503), INTERNAL_ERROR(500).

## Pagination
Query `page` (0-based), `size` (1..100, default 20), `sort=field,dir`. Response `{ "content":[...], "page":0, "size":20, "totalElements":123, "totalPages":7 }`.

## Auth
- `POST /api/v1/auth/login` `{tenantSlug,email,password}` -> `{accessToken,expiresInSeconds,user:{id,email,displayName,roles,tenant:{id,slug,name}}}`
- `GET /api/v1/auth/me` -> `user` object. `POST /api/v1/auth/logout` -> 204 (revokes token jti). `POST /api/v1/auth/register-organization` `{organizationName,slug,adminEmail,adminDisplayName,password}` -> 201 login response.
- Seed logins (password for all: `Passw0rd!demo`): tenants `acme-retail` (admin@acme.test, manager@acme.test, agent1@acme.test, agent2@acme.test, customer1@acme.test), `globex-it` (admin@globex.test, agent1@globex.test, customer1@globex.test), `initech-health` (admin@initech.test, agent1@initech.test).

## Users / Admin
- `GET /users` (AGENT+: for assignment), `POST /users` (ADMIN) `{email,displayName,roles[],password}`, `PUT /users/{id}` (ADMIN) `{displayName,roles[],active}`
- `GET /tenant/settings` (ADMIN), `PUT /tenant/settings` `{name,timezone,businessHoursStart,businessHoursEnd,businessDays[],defaultLanguage,integrations:{emailProvider,slackWebhookUrl,aiProvider}}`
- `GET /audit-events?page&size&entityType&actorId` (ADMIN) -> `{id,at,actorId,actorEmail,action,entityType,entityId,details}`

## Tickets
Ticket: `{id,number,type:"SUPPORT"|"IT_SERVICE",title,description,status,priority:"LOW|MEDIUM|HIGH|URGENT",severity:"MINOR|MAJOR|CRITICAL",categoryId,categoryName,tags[],requester:{id,displayName},assignee:{id,displayName}|null,createdAt,updatedAt,firstResponseDueAt,resolutionDueAt,firstRespondedAt,resolvedAt,slaStatus:"ON_TRACK|AT_RISK|BREACHED|MET|PAUSED",escalationLevel,reopenCount,version,attachments:[{id,fileName,contentType,sizeBytes}]}`
Status: `NEW,OPEN,PENDING,ON_HOLD,RESOLVED,CLOSED,REOPENED`. Default transitions: NEW->OPEN; OPEN->PENDING|ON_HOLD|RESOLVED; PENDING->OPEN|RESOLVED; ON_HOLD->OPEN; RESOLVED->CLOSED|REOPENED; CLOSED->REOPENED; REOPENED->OPEN|RESOLVED (workflow-configurable).
- `GET /tickets?page&size&sort&q&status&priority&assigneeId&categoryId&type&slaStatus&mine=true`
- `POST /tickets` header `Idempotency-Key` (required) body `{type,title,description,priority,severity,categoryId,tags[],attachments[{fileName,contentType,sizeBytes}]}` -> 201 (same key replays original response)
- `GET /tickets/{id}`, `PUT /tickets/{id}` `{title,description,priority,severity,categoryId,tags[],version}` (409 on stale version)
- `POST /tickets/{id}/assign` `{assigneeId|null,version}`; `POST /tickets/{id}/transition` `{toStatus,reason,version}`
- `GET /tickets/{id}/comments`, `POST /tickets/{id}/comments` `{body,visibility:"PUBLIC"|"INTERNAL"}` (CUSTOMER cannot post/see INTERNAL). Comment: `{id,authorId,authorName,body,visibility,createdAt,aiGenerated}`
- `GET /tickets/{id}/activity` -> `[{id,at,actorId,actorName,type,details}]`
- `GET /ticket-categories`; `POST/PUT/DELETE /ticket-categories` (ADMIN) `{name,type,defaultPriority}` -> `{id,name,type,defaultPriority}`

## Workflow & SLA (read: MANAGER+, write: ADMIN)
- `GET /workflows` -> list of versions `{id,name,version,active,createdAt,rules,transitions}`; `GET /workflows/{id}`; `POST /workflows` `{name,rules:[{name,when:[{field,op,value}],then:[{action,params}]}],transitions:[{from,to}]}` creates a new **version** (validated constrained model; ops EQ,NE,GT,LT,IN,CONTAINS; actions ASSIGN_TO_ROLE_LEAST_LOADED,SET_PRIORITY,ESCALATE,NOTIFY,ADD_TAG); `POST /workflows/{id}/activate`; `POST /workflows/validate` -> `{valid,errors[]}`
- `GET /sla-policies`, `PUT /sla-policies/{id}` `{priority,firstResponseMinutes,resolutionMinutes,escalationAfterPercent[]}`; business hours come from tenant settings.

## Knowledge base
Article: `{id,title,slug,body,summary,categoryId,categoryName,visibility:"PUBLIC"|"INTERNAL",status:"DRAFT"|"PUBLISHED",version,author,createdAt,updatedAt,relatedTicketIds[]}`
- `GET /articles?page&size&q&categoryId&status&visibility`, `GET /articles/{id}`, `POST /articles`, `PUT /articles/{id}`, `POST /articles/{id}/publish`, `GET /articles/{id}/versions`, `POST /articles/{id}/link-ticket/{ticketId}`; `GET/POST /article-categories`.
- CUSTOMER sees PUBLISHED+PUBLIC only.

## AI assist (AGENT+)
- `POST /tickets/{id}/ai/analyze` -> `{suggestionId,provider:"MOCK_RULE_BASED",generated:true,summary,suggestedCategory:{id,name},suggestedPriority,confidence:0..1,explanation,recommendedArticles:[{id,title,score}],suggestedReply,status:"PENDING_REVIEW",degraded:false,fallbackMessage:null}`. On provider failure/timeout: HTTP 200 with `degraded:true`, `fallbackMessage`, no reply text.
- `GET /tickets/{id}/ai/suggestions`; `POST /ai/suggestions/{id}/approve` `{editedReply?,visibility:"PUBLIC"}` -> posts the (edited) reply as a comment, status APPROVED|EDITED_APPROVED; `POST /ai/suggestions/{id}/reject` `{reason}`. Nothing reaches a customer without approve. Audit for generation + decision.

## Analytics (MANAGER+)
`GET /analytics/dashboard?days=30` -> `{openTickets,ticketsByPriority:{LOW,MEDIUM,HIGH,URGENT},avgFirstResponseMinutes,avgResolutionMinutes,slaCompliancePercent,backlog,reopenedTickets,aiAcceptanceRatePercent,agentWorkload:[{agentId,name,openCount}],resolutionTrend:[{date,resolved,created}],tenantActivity:{ticketsCreatedLast7d,commentsLast7d,activeUsers}}` (Redis cache-aside, TTL 60s, tenant-keyed).

## Notifications (served by notification-service, routed by gateway)
`GET /notifications?unread=true&page&size` (own) -> `{id,type,title,body,read,createdAt,ticketId}`, `POST /notifications/{id}/read`, `GET /notifications/unread-count` -> `{count}`.

## Ops
`/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`; Swagger UI `/swagger-ui.html` on each service.
