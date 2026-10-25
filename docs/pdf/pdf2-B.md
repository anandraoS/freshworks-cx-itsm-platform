# Part B. Spring and Spring Boot

This part explains the Spring concepts that the platform uses, in the order a request and an application experience them: the container, bean creation, auto-configuration, configuration, the web layer, errors, transactions, security, persistence, background jobs, observability, startup and tests. It uses the same template as Part A (what, why, how, why here, configuration, project use, implemented versus not, extensions, performance, security, mistakes, debugging, alternatives, interview questions) and the same two labels: "Project-implemented behaviour" for what the repository does, and "Conceptual learning example" for standalone programs written for this guide.

Execution note, stated once. The Spring examples below were compiled and run with the GraalVM JDK 21.0.7 against Spring Framework 6.1.14, Spring Boot 3.3.5 (only the `spring-boot` jar for the property binder), Spring Security 6.3.4, Spring Data JPA 3.3.5, Hibernate 6.5.3 and Hibernate Validator 8.0.1 jars from the local Maven cache. They are small programs, not the platform. They show the framework mechanism; the platform's own behaviour is described from its source. No Docker was available, so nothing involving PostgreSQL, Redis, RabbitMQ or MongoDB was executed, and the docker profile configuration is described but unverified at run time.

The versions that matter: the root `pom.xml` uses `spring-boot-starter-parent` 3.3.5 (Spring Framework 6.1.x, Spring Security 6.3.x, Hibernate 6.5.x, Jackson 2.17, Tomcat 10.1, Jakarta EE 10 namespaces `jakarta.*`).

![Figure: end-to-end request lifecycle through filters, controller, service and database](docs/diagrams/png/18-request-lifecycle.png)

## B1. Inversion of control and dependency injection

### What it is and why it exists

Inversion of control (IoC) means that the framework, not your code, creates objects and wires them together. Dependency injection (DI) is the technique: a class declares what it needs, and the container supplies it. It exists so that classes depend on abstractions, can be tested with replacements, and have their object graph and lifecycle managed in one place.

### How it works internally

The container is an `ApplicationContext` backed by a `DefaultListableBeanFactory`. Classes annotated with stereotypes (`@Component`, `@Service`, `@RestController`, `@Configuration`) are found by classpath scanning starting at the package of the `@SpringBootApplication` class; `@Bean` methods in `@Configuration` classes register further definitions. Each becomes a `BeanDefinition` (class, scope, constructor arguments, dependencies). Instantiation resolves dependencies by type; if several candidates exist, `@Primary`, `@Qualifier`, the parameter name, or conditions decide. With one constructor, `@Autowired` is implicit. `@Configuration` classes are subclassed with CGLIB so that calling one `@Bean` method from another returns the singleton instead of creating a second instance. Circular constructor dependencies fail at startup (`BeanCurrentlyInCreationException`), and Spring Boot 2.6+ forbids circular references by default.

### How THIS PROJECT uses it

> Project-implemented behaviour: constructor injection throughout, selection of implementations by profile or property, optional collaborators by `ObjectProvider`, and a few explicit `@Bean` definitions where a framework default must be replaced.

| Mechanism | Example in the code |
|---|---|
| Constructor injection (implicit `@Autowired`) | `OutboxRelay`, `TicketService` (15 collaborators), `AiAssistService` |
| Two constructors, one marked `@Autowired` | `TokenRevocationService` and `LoginRateLimiter`: public production constructor, package-private constructor with a clock or `Ticker` for tests |
| Interface plus conditional implementations | `CacheService` (`@Profile("!docker")` / `@Profile("docker")`), `EventPublisher`, `AiProvider` (`@ConditionalOnProperty`), `DocumentStore` |
| `ObjectProvider<T>` for optional or zero-to-many beans | `LocalEventPublisher(ObjectProvider<EventListener>)` iterates every listener bean, and works when there are none (the notification module is only present when embedded) |
| `@Bean` replacement of a Boot default | `TenancyConfig.transactionManager` returns `TenantAwareJpaTransactionManager`; `SecurityConfig` defines `PasswordEncoder` (BCrypt strength 10), `RoleHierarchy`, `MethodSecurityExpressionHandler`, `SecurityFilterChain`, `CorsConfigurationSource` |
| `static` `@Bean` methods | `roleHierarchy()` and `methodSecurityExpressionHandler()` are static so they are created early, before the configuration class, which method security needs |
| Object not managed by Spring | `new JwtAuthFilter(jwt, revocations)` inside `filterChain(...)`: because it is not a bean, Boot does not also register it as a servlet filter, which would run it twice |
| `@Value` for simple values | `AiFaultInjector`, `HttpAiProvider` read `cx.ai.*` keys with defaults |
| `@ConfigurationPropertiesScan` | registers `CxProperties` as a typed bean (see B4) |

Why the project prefers constructor injection: dependencies are final and visible, classes are testable with plain `new`, and `ArchitectureRulesTest` can reason about classes by their constructors. Where a test needs to replace a collaborator, a `@TestConfiguration` (as in `OutboxTest.Capture`) supplies a bean.

Not implemented: XML configuration, `@Lazy` injection, `@DependsOn`, `@Lookup` methods, custom `@Qualifier` annotations, `BeanFactoryPostProcessor` extensions, `ImportSelector`, `@Import` of third-party configuration. The `UserDetailsServiceAutoConfiguration` is excluded on purpose in `@SpringBootApplication(exclude=...)`: Boot would otherwise create an in-memory user with a generated password in the logs, and authentication here is JWT only.

### Conceptual learning example (compiled and executed): `LifecycleDemo.java`

The profile-selected bean pair mirrors `CaffeineCacheService` and `RedisCacheService`:

```java
@Bean @Profile("!docker") CacheService caffeine() { return () -> "Caffeine (in-process)"; }
@Bean @Profile("docker")  CacheService redis()    { return () -> "Redis (remote)"; }
// ctx.getEnvironment().setActiveProfiles("local" or "docker"); ctx.refresh();
```

Real output of the two runs:

```text
profile=local -> CacheService bean: Caffeine (in-process)
profile=docker -> CacheService bean: Redis (remote)
```

The same program shows a prototype-scoped bean injected into a singleton:

```text
prototype injected directly: ids 1 and 1; via ObjectProvider: 2, 3
```

Injecting a prototype directly freezes one instance at singleton creation. `ObjectProvider<Proto>.getObject()` asks the container each time, which is the correct way to get a fresh instance.

### Performance, security, mistakes, debugging

Performance: bean creation happens once at startup; large object graphs cost startup time, not request time. Security: wire security-critical objects explicitly (as `SecurityConfig` does) rather than relying on scanning order, and never let a default credential bean exist (the excluded user-details auto-configuration). Common mistakes: field injection hiding a dozen dependencies (a design smell this project avoids), circular dependencies solved with `@Lazy` instead of redesign, injecting a prototype or request-scoped bean into a singleton, two beans of the same type without a qualifier (`NoUniqueBeanDefinitionException`), and a component outside the scanned package (`NoSuchBeanDefinitionException`). Debugging: the error message lists the required type and the candidates; run with `--debug` for the condition evaluation report; expose `/actuator/beans` and `/actuator/conditions` in a non-production profile only; log `ctx.getBeanDefinitionNames()` when in doubt.

### Alternatives and trade-offs

Plain constructor wiring in `main` (no container) is simplest for tiny programs. Guice and Dagger offer lighter DI (Dagger resolves at compile time); Quarkus and Micronaut move DI to build time for faster startup. Spring's cost is reflection and startup time; its benefit is the ecosystem (transactions, security, data, web, observability) and the conditions model.

### Interview questions

Question: What is the difference between IoC and DI?
Answer: IoC is the principle that the framework controls object creation and the program flow of calling your code; DI is one technique to realise it, where dependencies are passed in (constructor, setter, field) rather than created or looked up by the class itself. Service locators are another IoC technique, but they hide dependencies.

Question: Why constructor injection over field injection?
Answer: Dependencies become explicit and final, the class cannot exist half-initialised, tests do not need reflection or a container, and circular dependencies surface at startup. With a single constructor `@Autowired` is optional. Field injection also hides how many collaborators a class has; `TicketService` with fifteen shows why that visibility is useful.

Question: How does Spring pick between two beans of the same type?
Answer: First by type; then `@Primary`, then `@Qualifier` or the injection point's parameter name matching the bean name. This project avoids ambiguity differently: conditions guarantee that only one `CacheService` (or `EventPublisher`) bean exists in any profile.

Question: What is the effect of prototype scope and how do you use it from a singleton?
Answer: A prototype bean is created per request to the container, but injecting it into a singleton happens once, so the singleton holds one instance (the executed example printed ids 1 and 1). Use `ObjectProvider`, `Provider`, or a scoped proxy to get a new instance each time.

Question: Why does `LocalEventPublisher` take `ObjectProvider<EventListener>` rather than `List<EventListener>`?
Answer: With a list, an empty set of beans would fail injection unless the parameter is optional; `ObjectProvider` handles zero, one or many, resolves lazily at call time, and `orderedStream()` returns listeners in `@Order` order. The notification module that contributes the listener is only on the application when `cx.notifications.embedded` is true.

## B2. Bean lifecycle, scopes and proxies

### What it is and why it exists

A bean has a lifecycle that the container controls: instantiation, population, initialisation, use, and destruction. Hooks let code run at defined points: `@PostConstruct`, `InitializingBean`, `BeanPostProcessor`, `@PreDestroy`. Scopes (singleton, prototype, request, session) define how many instances exist. The lifecycle exists so that infrastructure features (AOP proxies, `@Transactional`, `@Async`, `@Validated`) can be applied transparently by wrapping beans after they are initialised.

![Figure: Spring bean lifecycle from definition to destruction](docs/diagrams/png/pdf2A-spring-bean-lifecycle.png)

### How it works internally

For a singleton the order is: instantiate through the constructor; inject dependencies; `Aware` callbacks (`BeanNameAware`, `ApplicationContextAware`); `BeanPostProcessor.postProcessBeforeInitialization` (the annotation processor that calls `@PostConstruct` is one of them); `afterPropertiesSet` and custom init methods; `postProcessAfterInitialization` (this is where `AbstractAutoProxyCreator` replaces the bean with a proxy when an advisor matches, for example for `@Transactional`); the bean is stored in the singleton cache; once all singletons exist, `SmartInitializingSingleton.afterSingletonsInstantiated` runs. On context close, `@PreDestroy` and `DisposableBean.destroy` run. Singletons are created eagerly at startup unless `@Lazy`, so configuration errors appear at boot, not at the first request.

Proxies: if the bean implements an interface, Spring may create a JDK dynamic proxy; Spring Boot sets `spring.aop.proxy-target-class=true` by default, so a CGLIB subclass proxy is used even for interface-based beans (this is why the repository proxy in the JPA demo is a JDK proxy, since Spring Data builds it itself, but a service bean is `...$$SpringCGLIB$$0`). A proxy intercepts calls from outside; it cannot intercept calls a bean makes to itself, cannot override `final` or `private` methods, and CGLIB needs a non-final class.

### How THIS PROJECT uses it

> Project-implemented behaviour: lifecycle hooks are used sparingly, and always for validation or cleanup.

| Hook | Where | Purpose |
|---|---|---|
| `@PostConstruct` | `StartupGuards.check` (docker profile) | Refuse to start with the development JWT secret |
| Constructor validation | `JwtService` (secret at least 32 bytes), `AiFaultInjector` (fault mode must be NONE in docker) | Fail fast before the application accepts traffic |
| `@PreDestroy` | `AiAssistService.shutdown` | `pool.shutdownNow()` for the AI worker threads |
| `ApplicationRunner` (`@Order(100)`) | `DemoDataSeeder` | Seed demo tenants after the context is fully started (only when `cx.seed.enabled=true`) |
| `BeanPostProcessor` side effects | `@Transactional`, `@PreAuthorize`, `@Scheduled`, `@Validated` | Proxies and scheduling registrations are applied by Spring's own post-processors; the project writes none |
| Graceful shutdown | `server.shutdown: graceful` | In-flight requests are allowed to finish before the context closes |

All services are singletons and stateless apart from final collaborators and deliberate in-memory state (rate limiters, breaker, caches), which is why concurrent request threads can share them safely. Request-scoped state travels in `ThreadLocal`s (`TenantContext`, `SecurityContextHolder`, MDC) rather than request-scoped beans.

Not implemented: custom scopes, `@RefreshScope` (the platform has no Spring Cloud Config), `SmartLifecycle` beans, `@Lazy` beans, context hierarchies. A `SmartLifecycle` would be the correct tool if the outbox relay needed to drain on shutdown before the database pool closes: `stop()` could wait for in-flight deliveries.

### Conceptual learning example (compiled and executed): `LifecycleDemo.java` (lifecycle part)

Real output for one singleton that implements several callbacks:

```text
--- lifecycle of one singleton bean (profile=local)
  1. constructor (dependency injected: true)
  2. setter/field injection
  2b. BeanNameAware.setBeanName(lifecycleBean)
  3. custom BeanPostProcessor.before
  4. @PostConstruct
  5. InitializingBean.afterPropertiesSet
  6. custom BeanPostProcessor.after (the AOP auto-proxy creator is a BPP that acts at this step)
  7. SmartInitializingSingleton (all singletons exist now)
...
--- closing context
  8. @PreDestroy (on context close)
  9. DisposableBean.destroy
```

Observe that a user-defined post-processor's "before" ran before `@PostConstruct`: Spring's own annotation post-processors are re-registered at the end of the list, so the custom one runs first. Do not rely on the relative order of different post-processors; it is an implementation detail.

### Performance, security, mistakes, debugging

Performance: eager singletons make the first request fast and startup slower; lazy initialisation reverses that. Proxy creation adds a small per-call overhead (one method interception) that is negligible next to a database call. Security: a `@PostConstruct` guard is a safe way to enforce production invariants because it blocks startup (the project does this for secrets). Mistakes: expecting `@PostConstruct` to see injected collaborators in a constructor (it does not; use constructor injection), calling an `@Transactional` method on `this`, annotating a `private` or `final` method, doing long work in `@PostConstruct` (it blocks startup), and relying on `finalize`. Debugging: log the bean class (`getClass()`), check `AopUtils.isAopProxy(bean)` and `AopUtils.isCglibProxy(bean)`, and use `ctx.getBean(Service.class)` in a scratch test to see whether you hold the proxy or the target.

### Alternatives and trade-offs

`@Bean(initMethod, destroyMethod)` keeps lifecycle code out of annotations for third-party classes. `SmartLifecycle` gives phased start and stop. `ApplicationReadyEvent` listeners run when the application is ready to serve, after runners. Compile-time AOP (AspectJ weaving) removes the self-invocation limitation at the cost of build complexity.

### Interview questions

Question: Describe the lifecycle of a singleton bean.
Answer: Definition registered; instantiation by constructor; dependency injection; Aware callbacks; post-processors before initialisation (including `@PostConstruct`); `afterPropertiesSet` and init method; post-processors after initialisation (proxies are created here); the bean is ready; on shutdown `@PreDestroy` and `destroy`. The executed example printed this exact order for a bean that implements each hook.

Question: When is a Spring AOP proxy created and what are its limits?
Answer: In `postProcessAfterInitialization`, if any advisor's pointcut matches the bean (for instance a method annotated with `@Transactional`). The container then exposes the proxy under the bean name. Limits: only external calls through the proxy are intercepted, so self-invocation bypasses advice; `final` classes and methods cannot be overridden by CGLIB; `private` methods are not intercepted; and `this` inside the target is the target, not the proxy.

Question: Singleton scope and thread safety?
Answer: A singleton is shared by all threads, so it must be stateless or protect its state. Here services hold only final collaborators; mutable shared state lives in explicitly thread-safe structures (`AtomicInteger`, `ConcurrentHashMap`, Caffeine) and per-request data lives in `ThreadLocal`s that are cleared in `finally`.

Question: How would you run code once the application is fully started?
Answer: An `ApplicationRunner` or `CommandLineRunner` (as `DemoDataSeeder` does, ordered with `@Order`), an `ApplicationReadyEvent` listener, or `SmartInitializingSingleton` if you need to run after all singletons exist but before the web server accepts traffic.

## B3. Auto-configuration and conditions

### What it is and why it exists

Spring Boot auto-configuration inspects the classpath, existing beans and properties, and registers sensible default beans: add `spring-boot-starter-data-jpa` and a `DataSource`, `EntityManagerFactory` and `JpaTransactionManager` appear; add a Flyway dependency and migrations run at startup. It exists to replace pages of configuration with conventions, while staying overridable.

### How it works internally

`@SpringBootApplication` combines `@SpringBootConfiguration`, `@ComponentScan` and `@EnableAutoConfiguration`. The last imports a selector that reads the file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` from every jar on the classpath. Each listed class is a `@AutoConfiguration` guarded by conditions: `@ConditionalOnClass` (a library is present), `@ConditionalOnMissingBean` (back off if the application defined its own), `@ConditionalOnProperty` (a switch), `@ConditionalOnWebApplication`, `@Profile`. Auto-configurations are processed after user configuration, which is why a user-defined `@Bean` wins. The `--debug` flag prints a report of positive matches, negative matches and exclusions. Properties bind through `@EnableConfigurationProperties` classes such as `JpaProperties`.

### How THIS PROJECT uses it

> Project-implemented behaviour: the project consumes Boot auto-configuration, overrides it in three places, switches parts of it off per profile, and ships one auto-configuration of its own.

| Item | Detail |
|---|---|
| Excluded for the whole app | `UserDetailsServiceAutoConfiguration` (no default user) in `@SpringBootApplication(exclude=...)` |
| Excluded in the `local` profile | `RabbitAutoConfiguration`, `RedisAutoConfiguration`, `RedisRepositoriesAutoConfiguration`, `MongoAutoConfiguration`, `MongoDataAutoConfiguration`, `MongoRepositoriesAutoConfiguration` via `spring.autoconfigure.exclude` in `application-local.yml`, because those client libraries are on the classpath for the docker profile but no server exists locally |
| Health indicators disabled locally | `management.health.rabbit/redis/mongo.enabled=false` so `/actuator/health` does not report DOWN for services that are intentionally absent |
| Overridden defaults | `JpaTransactionManager` replaced by `TenantAwareJpaTransactionManager` (a `@Bean` named `transactionManager` makes Boot's `@ConditionalOnMissingBean` back off); `OpenAPI` bean; `CorsConfigurationSource`; `SecurityFilterChain` |
| Project auto-configuration | `notification-service` registers `NotificationAutoConfiguration` in `META-INF/spring/...AutoConfiguration.imports`, annotated `@AutoConfiguration(after = DataSourceAutoConfiguration.class)` and `@ConditionalOnProperty(cx.notifications.embedded=true)`; it imports `NotificationConfiguration` |
| Conditional beans | `@ConditionalOnProperty` on `HttpAiProvider`, `MockRuleBasedAiProvider`, the three `DocumentStore` implementations, `DemoDataSeeder`, and the embedded notification pieces; `@ConditionalOnClass(RabbitTemplate)` and `@ConditionalOnWebApplication(SERVLET)` inside the notification module |

The embedded notification module is the best example of Boot's extension model. In the local runtime there is no RabbitMQ and no separate notification process, so the notification-service jar is on the core-service class path as a library, its auto-configuration activates because `cx.notifications.embedded` is `true` in `application-local.yml`, and `LocalEventPublisher` hands outbox events to its `EventListener` in the same JVM. In the docker profile the property is `false`, the module stays inert, and RabbitMQ carries the events to a separate container.

Not implemented: custom starters with `spring-configuration-metadata.json`, `@ConditionalOnBean` ordering tricks, `@ImportRuntimeHints` for native images, `AutoConfigurationImportFilter`.

### Conceptual learning example

> Conceptual learning example (not executed as a program; explained from the source and from the Boot documentation): how to see the decisions made. Start the application with `--debug` or set `debug=true`; the log prints "CONDITIONS EVALUATION REPORT" with sections Positive matches, Negative matches, Exclusions, and Unconditional classes. For this project you would look for `RabbitAutoConfiguration` under Exclusions in the local profile and `NotificationAutoConfiguration` under Positive matches because of the property. I did not start the full application in this environment, so the report itself is not reproduced here.

The property binding half of the mechanism (B4) was executed.

### Performance, security, mistakes, debugging

Performance: every auto-configuration on the class path is evaluated at startup; excluding irrelevant ones shortens it, which is one reason for the local excludes. Security: auto-configuration can create insecure defaults (generated passwords, exposed actuator endpoints, open CORS); the project excludes the default user, sets `management.endpoints.web.exposure.include` to an explicit list, and gives the gateway's actuator its own management port. Mistakes: leaving a client library on the classpath without a server and getting connection errors or health DOWN (cured here with excludes), defining a bean with the wrong name or type so the Boot default does not back off, relying on a default that changes between Boot versions, and treating `@ConditionalOnProperty` string values loosely (`havingValue` is exact; `matchIfMissing` decides the default). Debugging: `--debug`, `/actuator/conditions`, and reading the `@AutoConfiguration` source of the feature.

### Alternatives and trade-offs

Explicit `@Configuration` for everything is more transparent and slower to write; Boot's conventions save code but hide decisions. `spring.main.lazy-initialization=true` trades startup time for first-request latency.

### Interview questions

Question: How does Spring Boot decide which beans to create automatically?
Answer: `@EnableAutoConfiguration` loads the candidate classes listed in each jar's `AutoConfiguration.imports` file, and each candidate evaluates conditions: class presence, missing bean, property values, web application type, profile. Matching ones register their beans after the application's own configuration, and `@ConditionalOnMissingBean` makes them back off when the application defines the same kind of bean.

Question: How can you disable one auto-configuration?
Answer: `@SpringBootApplication(exclude = X.class)` or `spring.autoconfigure.exclude=` in properties. The project does both: the first for `UserDetailsServiceAutoConfiguration`, the second for Rabbit, Redis and Mongo in `application-local.yml`.

Question: How did you make the notification module run inside core-service locally and as a separate service in Docker?
Answer: As a library with its own auto-configuration guarded by `cx.notifications.embedded`. Locally it is true: the module's beans load into the core-service context and receive events through the in-JVM `EventListener` contract. In Docker it is false in core-service, and the same jar's `main` class runs as its own Spring Boot application consuming from RabbitMQ.

Question: What does `@ConditionalOnProperty(name="cx.ai.provider", havingValue="mock", matchIfMissing=true)` do?
Answer: The mock provider bean is registered when the property equals `mock` or is not set. Setting `cx.ai.provider=http` selects `HttpAiProvider` instead; because the two conditions are mutually exclusive, injection of `AiProvider` always finds exactly one bean.

## B4. Configuration properties and profiles (local versus docker)

### What it is and why it exists

Externalised configuration lets one build run in many environments. Spring's `Environment` merges property sources in a defined precedence; profiles activate groups of beans and property files. `@ConfigurationProperties` binds a prefix of properties to a typed object with validation, so configuration is checked at startup and not discovered at the first use.

### How it works internally

Boot's property source order, highest first: command-line arguments, `SPRING_APPLICATION_JSON`, OS environment variables, `application-{profile}.yml` outside then inside the jar, `application.yml` outside then inside the jar, then defaults in code. Relaxed binding maps `cx.security.login-rate.capacity`, `cx.security.loginRate.capacity` and the environment variable `CX_SECURITY_LOGINRATE_CAPACITY` (or `CX_SECURITY_LOGIN_RATE_CAPACITY`) to the same property. Profile-specific files override the base file for keys they define. Placeholders `${NAME:default}` resolve against the environment at binding time. Profiles are activated by `spring.profiles.active`, `SPRING_PROFILES_ACTIVE`, or `--spring.profiles.active`. Constructor binding on records binds each parameter, and `@DefaultValue` supplies values for missing keys, including nested records.

### How THIS PROJECT uses it

> Project-implemented behaviour: one typed record `CxProperties` (prefix `cx`) holds all custom settings, with defaults in code; `application.yml` sets the default profile; two profile files define the runtime differences.

| Aspect | `local` (default) | `docker` |
|---|---|---|
| How selected | `spring.profiles.active: local` in `application.yml`; override with `SPRING_PROFILES_ACTIVE=docker` | Environment variable in the container |
| Database | H2 in memory, PostgreSQL compatibility mode, `DATABASE_TO_LOWER=TRUE` | PostgreSQL `jdbc:postgresql://postgres:5432/cx`, user `cx_app`, password from `CX_DB_PASSWORD` (no default) |
| Migrations | `classpath:db/migration` | `db/migration` plus `db/migration-pg` (partial and GIN indexes, row-level security, append-only audit trigger) |
| Cache | `CaffeineCacheService` (`@Profile("!docker")`) | `RedisCacheService`, host from `CX_REDIS_HOST`, timeouts 500 ms |
| Events | `LocalEventPublisher` plus embedded notifications (`cx.notifications.embedded=true`) | `RabbitEventPublisher` with publisher confirms and mandatory returns; embedded false |
| Document store | `jpa` (table `document_store`) | `mongo`, URI from `CX_MONGO_URI` (no default because it carries credentials) |
| JWT secret | Clearly dummy development value | `${CX_JWT_SECRET}` with no default; `StartupGuards` refuses the dev value |
| Seeding | `cx.seed.enabled: true` | `${CX_SEED_ENABLED:true}` |
| Tenancy hardening | Hibernate filter only | `cx.tenancy.pg-rls: true`, transaction manager also sets `app.tenant_id` for PostgreSQL row-level security |
| Login rate limit | 100 burst, 60 per minute (demo friendly) | Defaults 10 burst, 5 per minute |
| Auto-configuration | Rabbit, Redis, Mongo excluded; their health checks disabled | All active |

The typed record: `CxProperties` nests `Security` (with `Jwt`, `LoginRate`, `trustForwardedFor`, `revocationNegativeCacheSeconds`), `Cors`, `Seed`, `Outbox`, `Sla` and `Cache`, each parameter carrying `@DefaultValue`. `@ConfigurationPropertiesScan` on the application class registers it. A few settings are read with `@Value` (`AiSettings` reads `cx.ai.*` through constructor `@Value` parameters, and `TenancyConfig` reads `cx.tenancy.pg-rls`); those are the older style and are not part of the record, a small inconsistency a refactoring could remove by adding nested records. Secrets are not committed: `env.example` holds clearly dummy `CHANGE_ME_...` placeholders for local use, and `.env` is meant to be git-ignored.

Why this design: environment-specific facts (hosts, credentials) come only from the environment; behaviour switches (cache kind, event transport) come from profiles; tunables (batch sizes, TTLs) come from the typed record with defaults so that a missing key never leaves the application unconfigured. The docker profile deliberately has no defaults for secrets so a missing variable fails startup instead of running insecurely.

Not implemented: `@Validated` constraints on `CxProperties` (for example `@Min` on batch size), `@ConfigurationProperties` metadata generation, Spring Cloud Config or Vault integration, configuration reload at run time, profile groups (`spring.profiles.group`), and `@Profile` on configuration for the test profile (tests use `@ActiveProfiles({"local","test"})` and `application-test.yml`).

### Conceptual learning example (compiled and executed): `BinderDemo.java`

Spring Boot's `Binder` is what `@ConfigurationProperties` uses. The program declares records shaped like `CxProperties` (trimmed) and binds three kinds of source:

```java
record Security(@DefaultValue Jwt jwt, @DefaultValue LoginRate loginRate) {}
record LoginRate(@DefaultValue("10") int capacity, @DefaultValue("5") int refillPerMinute) {}
// source 1: yaml-style keys cx.security.login-rate.capacity=100 and cx.outbox.relay-enabled=false
// source 2: camelCase keys cx.security.loginRate.refillPerMinute=7
// source 3: environment variables CX_SECURITY_JWT_SECRET and CX_OUTBOX_BATCH_SIZE
```

Real output:

```text
kebab-case keys    : Cx[security=Security[jwt=Jwt[secret=dev-only-secret, ttlMinutes=60, issuer=cx-core-service], loginRate=LoginRate[capacity=100, refillPerMinute=5]], outbox=Outbox[batchSize=50, relayEnabled=false]]
camelCase keys     : Cx[security=Security[jwt=Jwt[secret=dev-only-secret, ttlMinutes=60, issuer=cx-core-service], loginRate=LoginRate[capacity=10, refillPerMinute=7]], outbox=Outbox[batchSize=50, relayEnabled=true]]
environment vars   : from-environment-variable / batch 5
bad value fails fast at startup: ConversionFailedException
```

Reading it: one overridden key changes only that field and every other field keeps its `@DefaultValue`; kebab-case and camelCase are equivalent; environment variable names bind by relaxed rules; and a value that cannot be converted fails at binding time (inside the application it surfaces as `BindException` while the context starts), not later. A first attempt of this program also showed that, when nothing at all is bound under the prefix, a direct `Binder.bind` returns an unbound result rather than an object built from defaults; the Boot `@ConfigurationProperties` machinery for constructor-bound records handles that case by creating the instance when the annotation is processed, which is why the project can rely on defaults. I did not execute that Boot path here.

### Performance, security, mistakes, debugging

Performance: binding is a startup cost. Security: the single most important profile rule is "no default for a secret in the production-like profile"; the project also keeps the demo seed password documented as synthetic and BCrypt-hashed at seed time, and keeps JWT secrets at least 32 bytes. Environment variables are visible to anyone who can read `/proc` or `docker inspect`; for stronger isolation use mounted secret files or a secret manager. Mistakes: committing secrets in `application.yml`; assuming a profile file replaces rather than overlays the base file; a typo in a property key that silently falls back to the default (typed records with validation reduce this); forgetting that a list in a profile file replaces, not merges with, the list in the base file; and enabling several profiles that each define the same bean. Debugging: `/actuator/env` and `/actuator/configprops` (not exposed here, deliberately; the exposure list is `health,info,prometheus,metrics`), `--debug`, logging `env.getActiveProfiles()` at startup, and `spring.config.import` problems show up as `ConfigDataLocationNotFoundException`.

### Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Profile files plus environment (used) | Simple, standard, no extra service | Secrets in environment variables; no run-time refresh |
| Spring Cloud Config / Consul | Central, refreshable | Extra infrastructure, bootstrap complexity |
| Kubernetes ConfigMaps and Secrets | Platform-native, mount as files | Platform dependency |
| Vault | Dynamic secrets, audit | Operational overhead |

### Interview questions

Question: In what order does Spring Boot resolve configuration sources?
Answer: Highest precedence first: command-line arguments, system properties and environment variables (environment variables above files), profile-specific files, then `application.yml`, then code defaults. Later keys do not merge lists; they replace them. This lets Docker supply `CX_JWT_SECRET` without touching the file, and tests override with `@TestPropertySource`.

Question: Why use `@ConfigurationProperties` rather than many `@Value` annotations?
Answer: One typed, documented, constructor-bound object with defaults and optional validation, rather than strings scattered through the code; relaxed binding; IDE metadata; and the whole group is visible in one place. `@Value` remains fine for a single isolated setting, as in `AiFaultInjector`.

Question: How does the application behave differently under the `docker` profile?
Answer: PostgreSQL with extra migrations and row-level security, Redis cache, RabbitMQ event publishing, MongoDB document store, no default secrets, the startup guard on the JWT secret, and the notification module not embedded. All of it is selected by profile and properties, and no business class changes.

Question: How do you stop a misconfigured production deploy from running with the dev secret?
Answer: Make the production profile have no default and add a guard that fails fast: `application-docker.yml` uses `${CX_JWT_SECRET}` without a default, and `StartupGuards` throws if the value equals the development secret or starts with `dev-only`. `JwtService` additionally rejects secrets shorter than 32 bytes.

Question: Where would you add validation to the typed configuration?
Answer: Annotate `CxProperties` with `@Validated` and add jakarta constraints on record components (for example `@Min(1)` on `batchSize`); Boot then fails at startup with a precise message instead of misbehaving later.
## B5. REST controllers and request validation

### What it is and why it exists

Spring MVC maps HTTP requests to controller methods. `@RestController` combines `@Controller` and `@ResponseBody`, so return values are serialised (JSON through Jackson) instead of resolved as view names. Bean Validation (Jakarta Validation, implemented by Hibernate Validator) lets you declare constraints on the request model so that invalid input is rejected before business code runs.

### How it works internally

The Tomcat servlet container passes each request through the filter chain to the `DispatcherServlet`. A `HandlerMapping` (`RequestMappingHandlerMapping`) selects a handler method from the `@RequestMapping` family; a `HandlerAdapter` resolves each argument with `HandlerMethodArgumentResolver`s (`@PathVariable`, `@RequestParam`, `@RequestHeader`, `@RequestBody` via `HttpMessageConverter`s) and invokes the method; return values go through `HandlerMethodReturnValueHandler`s and message converters. With `@Valid` on a `@RequestBody` parameter, the resolver validates the deserialised object and throws `MethodArgumentNotValidException` if any constraint fails, before the method body executes. Constraint violations on `@RequestParam` or `@PathVariable` need `@Validated` on the class and surface as `ConstraintViolationException` (handled by the project's advice). Type conversion failures raise `MethodArgumentTypeMismatchException`; unreadable JSON raises `HttpMessageNotReadableException`. Exceptions are then offered to `HandlerExceptionResolver`s, among them `@ExceptionHandler` methods in `@ControllerAdvice` beans (B6).

### How THIS PROJECT uses it

> Project-implemented behaviour: thin controllers; DTO records with constraints; entities never in signatures; role checks by annotation; list endpoints bounded by `PageParams`.

| Practice | Evidence |
|---|---|
| Controllers only translate HTTP to a service call | `TicketController.update` is one line: `return tickets.update(id, r)` |
| Body validated with `@Valid @RequestBody` | `TicketController.create/update/assign/transition/addComment`, `AuthController.login/register` |
| Constraints live on records | `CreateTicketRequest(@NotNull type, @NotBlank @Size(max=200) title, @NotBlank @Size(max=8000) description, @NotNull priority, @NotNull severity, @Size(max=20) List<@Pattern(...) String> tags, @Size(max=10) List<@Valid AttachmentRequest> attachments)` |
| Optimistic-lock token required in the body | `UpdateTicketRequest`, `AssignRequest`, `TransitionRequest` each have `@NotNull Long version` |
| Passwords length-limited to BCrypt's input limit | `@Size(min=8, max=72)` on `RegisterOrganizationRequest.password` and `CreateUserRequest.password` (BCrypt ignores bytes beyond 72) |
| Secrets never printed | `LoginRequest`, `RegisterOrganizationRequest`, `CreateUserRequest` override `toString()` to mask the password; `LoginResponse` masks the token |
| Custom header contract | `POST /api/v1/tickets` takes `Idempotency-Key` (`@RequestHeader(required=false)`, validated by regex in `TicketCommandService`, reply header `Idempotent-Replay: true` on replay) |
| Enum parameters give automatic 400 on bad values | `@RequestParam TicketStatus status`; unknown value becomes `MethodArgumentTypeMismatchException` handled as `VALIDATION_FAILED` |
| Bounded paging and sorting | `PageParams.of` limits size to 1..100 and allows only whitelisted sort fields |
| Role gates | `@PreAuthorize("hasRole('AGENT')")` etc. on 26 controller methods or classes |
| No client-supplied tenant | Enforced by `ArchitectureRulesTest.controllersNeverAcceptATenantIdFromTheClient` |
| API documentation | springdoc OpenAPI at `/v3/api-docs` and `/swagger-ui.html`, configured by `OpenApiConfig` with a bearer scheme |

Controller conventions follow the API contract in `docs/API-CONTRACT.md`: `/api/v1` prefix, JSON, 201 on creation, 404 for cross-tenant ids, 409 for version conflicts, 422 for invalid state transitions, 429 with `Retry-After`.

Not implemented: `@Validated` method-level validation on controllers (so `ConstraintViolationException` is handled but not currently triggered by the listed controllers), custom `ConstraintValidator`s, validation groups, content negotiation beyond JSON, HATEOAS, async controller methods (`Callable`, `DeferredResult`), server-sent events, file upload endpoints (attachments store metadata only: file name, content type, size at most 25 MB as declared), API versioning beyond the path prefix. `spring.jackson.deserialization.fail-on-unknown-properties` is `false`, so unknown JSON fields are ignored rather than rejected, which is lenient and favours forward compatibility; a stricter service would set it to `true`.

### Conceptual learning example (compiled and executed): `MvcDemo.java`

A standalone `MockMvc` with the real Spring MVC machinery (no Spring Boot, no embedded server): a controller with `@Valid @RequestBody`, a `@RestControllerAdvice` with the same handler set as `GlobalExceptionHandler` (trimmed), Hibernate Validator through `LocalValidatorFactoryBean`, and a correlation-id filter. Seven requests were sent.

```java
record CreateTicketRequest(@NotBlank @Size(max = 20) String title, @NotNull Priority priority) {}
@PostMapping ResponseEntity<Map<String,Object>> create(@Valid @RequestBody CreateTicketRequest r) { ... 201 ... }
```

Real output (the random correlation ids were replaced by a placeholder by the program):

```text
1) valid create
  -> 201 {"priority":"HIGH","title":"Printer jam"}  | X-Correlation-Id present: true
2) invalid body
  -> 400 {"status":400,"error":"BAD_REQUEST","code":"VALIDATION_FAILED","message":"Validation failed","path":"/api/v1/tickets","correlationId":"<id>","fieldErrors":[{"field":"priority","message":"must not be null"},{"field":"title","message":"must not be blank"}]}
3) unknown enum value
  -> 400 {"status":400,"error":"BAD_REQUEST","code":"VALIDATION_FAILED","message":"Malformed request","path":"/api/v1/tickets","correlationId":"<id>"}
4) path variable is not a UUID
  -> 400 {... "message":"Malformed request","path":"/api/v1/tickets/not-a-uuid" ...}
5) business exception
  -> 404 {"status":404,"error":"NOT_FOUND","code":"NOT_FOUND","message":"Ticket not found", ...}
6) wrong HTTP method
  -> 405 {"status":405,"error":"METHOD_NOT_ALLOWED","code":"METHOD_NOT_ALLOWED","message":"Method not allowed", ...}
    (server log only) IllegalStateException: jdbc:postgresql://secret-host/cx password=hunter2
7) unexpected exception (message contains a fake secret)
  -> 500 {"status":500,"error":"INTERNAL_SERVER_ERROR","code":"INTERNAL_ERROR","message":"Unexpected error", ...}
```

Case 7 is the security point: the exception message (a deliberately fake connection string) appears only in the server log line, while the client receives the fixed text "Unexpected error". Case 3 shows that a bad enum value and bad JSON share one generic message so the API does not become an oracle about the model.

A second executed program, `ValidationDemo.java`, validated a record with the same kinds of constraints (including container-element constraints on list elements and nested `@Valid`) with Hibernate Validator directly:

```text
{"field":"attachments[0].fileName","message":"must not be blank"}
{"field":"attachments[0].sizeBytes","message":"must be greater than or equal to 0"}
{"field":"priority","message":"must not be null"}
{"field":"tags","message":"size must be between 0 and 2"}
{"field":"tags[1].<list element>","message":"1-10 chars: letters, digits, hyphen"}
{"field":"title","message":"must not be blank"}
```

The property path `tags[1].<list element>` is what appears as `field` in the project's `fieldErrors` for a bad tag; clients should treat it as an opaque path.

### Performance, security, mistakes, debugging

Performance: Jackson (de)serialisation and validation are cheap compared with the database; bound request sizes (Tomcat's `max-http-form-post-size` and `spring.servlet.multipart.*` where relevant) and list sizes (the DTOs cap tags at 20 and attachments at 10). Security: validation is the first defence (length caps are also denial-of-service limits), never trust path or header values, never bind request bodies onto entities (mass assignment), keep error messages generic, mask secrets in `toString`, and apply authorisation on the server regardless of what the UI hides. Mistakes: missing `@Valid` (constraints silently ignored), `@NotNull` on a primitive, `@Size` on a possibly null field without `@NotNull`, returning entities (lazy loading errors and data leaks), putting business logic in controllers, catching exceptions in controllers instead of using advice, and trusting `Content-Type` blindly. Debugging: enable `logging.level.org.springframework.web=DEBUG` temporarily to see handler selection and resolved exceptions, use the Swagger UI for manual calls, and check the `X-Correlation-Id` response header against log lines.

### Alternatives and trade-offs

Spring WebFlux (reactive controllers) suits very high concurrency with non-blocking I/O but not blocking JDBC. JAX-RS (Jersey) is a standards-based alternative. GraphQL or gRPC fit different client needs. Programmatic validation (calling a validator in the service) allows rules that need data access, which annotations cannot express; the project does both: annotations for shape, service code for rules such as "assignee must be an active staff user of this tenant".

### Interview questions

Question: What happens between an HTTP request arriving and your controller method running?
Answer: The servlet filter chain (correlation id, security) runs; the `DispatcherServlet` finds the handler through the handler mapping; the adapter resolves arguments, deserialising the JSON body with Jackson and running Bean Validation when `@Valid` is present; then the method runs. Exceptions go to the resolvers, and the advice produces the error body. The return value is serialised by a message converter.

Question: Difference between `@Valid` and `@Validated`?
Answer: `@Valid` (Jakarta) triggers validation and cascades into nested objects. `@Validated` (Spring) additionally supports validation groups and, on a class, enables method-level validation of parameters such as `@RequestParam` through a proxy. A failed `@Valid @RequestBody` gives `MethodArgumentNotValidException`; a failed method-level constraint gives `ConstraintViolationException`; the project's advice handles both.

Question: Why use DTO records instead of entities in controllers?
Answer: Decoupling the API from the schema, preventing mass assignment and accidental exposure of fields, avoiding lazy-loading problems during serialisation, and making validation explicit. The project proves it with a reflection test that fails the build if any controller method signature mentions an `@Entity`.

Question: How do you protect against duplicate submissions on `POST`?
Answer: An `Idempotency-Key` header scoped to tenant and user. The server stores the key with a hash of the request in the same transaction as the ticket; the same key and body replays the original response, a different body returns 409 `DUPLICATE_SUBMISSION`, and two concurrent requests race on a unique constraint where the loser rolls back and replays the winner.

Question: How is pagination protected?
Answer: `PageParams.of` caps the size at 100, rejects negative pages, and accepts only whitelisted sort fields with an explicit direction, adding `id` as a tie-breaker for a stable order. Unbounded queries are impossible by construction.

## B6. Global exception handling and the project's error schema

### What it is and why it exists

`@RestControllerAdvice` classes contain `@ExceptionHandler` methods that apply to all controllers, so error responses are produced in one place with one shape. Without it, Spring Boot's default `BasicErrorController` produces its own JSON, leaks internals if configured carelessly, and differs from handler to handler.

### How it works internally

`ExceptionHandlerExceptionResolver` looks for an `@ExceptionHandler` whose declared exception type best matches the thrown exception (nearest superclass wins), first in the controller, then in advice beans in order. Handler arguments (`HttpServletRequest`, the exception) are resolved like controller arguments, and the return value is written by message converters. Exceptions that occur outside the dispatcher, in servlet filters including the Spring Security chain, never reach `@ExceptionHandler`; they need filter-level handling (entry point and access-denied handler, or a try/catch in the filter). Spring 6 supports RFC 9457 Problem Details; this project turns it off (`spring.mvc.problemdetails.enabled: false`) and defines its own schema.

### How THIS PROJECT uses it

> Project-implemented behaviour: one error schema, written from two places that share one builder.

The schema is the record `ErrorResponse(timestamp, status, error, code, message, path, correlationId, fieldErrors)` with `@JsonInclude(NON_NULL)`; `FieldErrorDto(field, message)`; and the `ErrorCode` enum that couples a machine code, an HTTP status and a status text:

| ErrorCode | HTTP | Typical cause |
|---|---|---|
| `VALIDATION_FAILED` | 400 | `@Valid` failure, malformed JSON, bad enum or UUID, missing header, bad page or sort |
| `UNAUTHENTICATED` | 401 | No or invalid token, bad credentials |
| `FORBIDDEN` | 403 | Role check failed; customer attempting a staff action |
| `NOT_FOUND` | 404 | Unknown id or an id of another tenant; unknown URL |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP verb |
| `TICKET_VERSION_CONFLICT`, `DUPLICATE_SUBMISSION`, `CONFLICT` | 409 | Optimistic lock failure; idempotency key reused with a different body; unique constraint |
| `INVALID_TRANSITION` | 422 | State machine or workflow rejects the status change |
| `RATE_LIMITED` | 429 | Login or AI limiter (header `Retry-After`) |
| `AI_UNAVAILABLE` | 503 | Defined in the enum but not thrown anywhere in the current code: AI failures degrade to HTTP 200 with `degraded: true` instead |
| `INTERNAL_ERROR` | 500 | Anything unexpected; generic message |

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps: `ApiException`, `RateLimitedException` (adds `Retry-After`), `MethodArgumentNotValidException` and `ConstraintViolationException` (field errors), `HttpMessageNotReadableException` and `MethodArgumentTypeMismatchException` ("Malformed request"), `MissingRequestHeaderException` (field error naming the header), `AccessDeniedException`, `AuthenticationException`, `ObjectOptimisticLockingFailureException` (409 `TICKET_VERSION_CONFLICT`), `DataIntegrityViolationException` (409 `CONFLICT`, generic text, details only in the server log), `HttpRequestMethodNotSupportedException`, `NoResourceFoundException`, and the catch-all `Exception` (log with stack trace, answer "Unexpected error").

`ErrorWriter` builds the same record for non-MVC contexts: the Spring Security entry point and access-denied handler in `SecurityConfig` call `ErrorWriter.write(mapper, res, ErrorCode.UNAUTHENTICATED, "Authentication required", uri)`, because a `401` raised by the filter chain never reaches the advice. The gateway has its own `ErrorResponses` and `JsonErrorWebExceptionHandler` that emit the same shape, and the notification service has `ApiError`, `ErrorWriter` and `NotificationExceptionHandler`: three services, one contract. `server.error.include-message` and `include-stacktrace` are `never`, so even the servlet container's fallback error page leaks nothing. `CorrelationIdFilter` puts the id in the MDC and the `X-Correlation-Id` response header, and the id is included in the error body.

Not implemented: localisation of messages, `ProblemDetail` responses, an error catalogue endpoint, per-field error codes (only messages), retry hints other than `Retry-After`, and a handler for `HttpMediaTypeNotAcceptableException`. The project's advice has no handler for that one, and a catch-all `@ExceptionHandler(Exception.class)` takes precedence over Spring's default resolvers. In an executed MVC demo with a catch-all handler, a `POST` with `Content-Type: text/plain` returned HTTP 500 instead of 415, and the real application had the same defect. It is now fixed: `GlobalExceptionHandler.mediaType` maps `HttpMediaTypeNotSupportedException` to 415 `UNSUPPORTED_MEDIA_TYPE` (new `ErrorCode`) in the contract error schema, and `ErrorContractTest.unsupportedMediaTypeIs415WithContractSchema` proves it through MockMvc (the 405 mapping is covered by `unsupportedMethodIs405WithContractSchema`). `HttpMediaTypeNotAcceptableException` (406) still has no explicit handler.

### Conceptual learning example

> Conceptual learning example (compiled and executed): the output of `MvcDemo.java` in B5 is this mechanism in miniature, including the handler order and the rule that the catch-all must never echo the exception message. `SecurityDemo.java` in B8 shows the filter-chain half (401 and 403 written by the entry point and access-denied handler) and the method-security half (403 from `@PreAuthorize` caught by the advice because it is thrown inside the dispatcher).

### Performance, security, mistakes, debugging

Performance: handlers are cheap; logging stack traces for every 4xx would be expensive, so only the catch-all logs with a stack trace and `DataIntegrityViolationException` logs one warning line. Security: never include exception messages, SQL, class names or stack traces in responses; use the same shape for 401 and 403 regardless of which check failed; treat 404 as the answer for cross-tenant ids; keep correlation ids in logs so that support can find details. Mistakes: swallowing exceptions in the controller, a catch-all handler that returns `e.getMessage()`, handling exceptions in filters with MVC advice (it will not fire), the wrong order of overlapping handlers (the most specific type wins, not the first declared), returning 200 with an error body, and inconsistent shapes between services. Debugging: reproduce with the correlation id; check whether the failure happened before the dispatcher (filter chain, Tomcat) or inside it; use `logging.level.org.springframework.web.servlet.mvc.method.annotation=DEBUG`.

### Alternatives and trade-offs

RFC 9457 Problem Details is a standard media type (`application/problem+json`) with tooling support; the custom schema adds `code`, `correlationId` and `fieldErrors` that the front end relies on, at the cost of non-standard fields (a Problem Details extension could carry them). Handling errors in a servlet filter catches everything but loses MVC context. `ResponseStatusException` is convenient for tiny services but scatters status decisions.

### Interview questions

Question: How do you design a consistent error response for a REST API?
Answer: One schema (timestamp, HTTP status, short error text, stable machine code, safe message, request path, correlation id, optional field errors), produced in one advice and one writer; stable codes that clients can switch on; no internals; and the same shape from every service and from the gateway. This platform's contract is documented in `docs/API-CONTRACT.md` and exercised by API tests such as `TicketApiTest` and `AuthApiTest`.

Question: Why is there both a `@RestControllerAdvice` and an `ErrorWriter` used in `SecurityConfig`?
Answer: Exceptions thrown inside controllers reach the advice. Authentication failures in the security filter chain happen before the dispatcher, so the advice never sees them; the `AuthenticationEntryPoint` and `AccessDeniedHandler` write the response directly with the same builder so that clients see one shape.

Question: What is the difference between 401 and 403, and how does the project return them?
Answer: 401 means the caller is not authenticated (missing, invalid or expired token); 403 means authenticated but not permitted (role check, or a customer attempting a staff action). The project returns generic messages for both and uses 404 for other tenants' resources so existence is not revealed.

Question: How should concurrent modification be reported?
Answer: As 409 with a stable code. A stale `version` produces `TICKET_VERSION_CONFLICT` either from the explicit `checkVersion` comparison in `TicketService` or from `ObjectOptimisticLockingFailureException` when Hibernate's version check fails at flush; both map to the same error so the client can reload and retry.

Question: Would you expose `e.getMessage()` for `DataIntegrityViolationException`?
Answer: No. It contains constraint and table names. The advice logs the most specific cause message on the server and answers "The request conflicts with existing data".

## B7. Transactions and proxies

### What it is and why it exists

A transaction groups database operations into an atomic, consistent, isolated and durable unit. Spring's declarative transaction management (`@Transactional`) applies that to methods through AOP proxies, so business code does not call `begin`, `commit` or `rollback`. It exists to keep transaction handling out of business logic and to make the technology (JDBC, JPA) replaceable behind `PlatformTransactionManager`.

![Figure: how a call through the CGLIB proxy starts, commits or rolls back a transaction, and why self-invocation bypasses it](docs/diagrams/png/pdf2A-transactional-proxy-flow.png)

### How it works internally

`@EnableTransactionManagement` (auto-applied by Boot) registers an advisor whose pointcut matches methods annotated with `@Transactional`. The auto-proxy creator wraps matching beans in a proxy at bean creation (B2). A call from another bean goes to the proxy; the `TransactionInterceptor` asks the `PlatformTransactionManager` to get a transaction according to the propagation setting, invokes the target, and then commits on normal return or rolls back on an exception that matches the rollback rule. By default only `RuntimeException` and `Error` cause rollback; checked exceptions commit unless `rollbackFor` says otherwise. The transaction is bound to the thread through `TransactionSynchronizationManager` (a `ThreadLocal` holder for the connection or `EntityManager`), so every repository call on that thread joins it. Hibernate flushes pending changes at commit (and before queries when needed), which is when dirty-checked updates and constraint violations surface.

Propagation values: `REQUIRED` (default: join or create), `REQUIRES_NEW` (suspend and start a new one), `MANDATORY` (must already exist), `SUPPORTS`, `NOT_SUPPORTED`, `NEVER`, `NESTED` (savepoint, JDBC only). `readOnly = true` is a hint: Hibernate skips dirty checking and flush, and some drivers route to replicas. Isolation levels default to the database's (PostgreSQL: READ COMMITTED).

### How THIS PROJECT uses it

> Project-implemented behaviour: service-level `@Transactional` for use cases, `readOnly` for queries, `TransactionTemplate` where the transaction boundary must be exact, `MANDATORY` for the outbox, and a custom transaction manager that binds the tenant.

| Pattern | Where | Reason |
|---|---|---|
| `@Transactional` on every mutating use case, `@Transactional(readOnly = true)` on queries | `TicketService`, `KbService`, `WorkflowService`, `UserService`, `TenantSettingsService` | One transaction per request; entity changes flushed at commit |
| `Propagation.MANDATORY` | `OutboxService.publish` | An event cannot be written outside the business transaction: either the change and the event commit together, or neither does |
| `Propagation.REQUIRED` explicit | `AuditService.record`, `TicketActivityService.record`, `JpaDocumentStore.put` | Participate in the caller's transaction so audit rows roll back with the business change |
| `TransactionTemplate` | `TicketCommandService`, `AiAssistService` (`readTx`, `writeTx`), `OutboxRelay`, `TicketNumberAllocator`, `AuthService`, `AnalyticsService` | Exact boundaries where a slow call must happen outside a transaction |
| No `@Transactional` on a hot read | `TokenRevocationService.isRevoked` | Comment: a cache hit must not open a transaction or borrow a connection |
| After-commit hook | `TxHooks.afterCommit` used for cache eviction (`SlaService.update`) | Evict only after the data is durable, so a concurrent reader cannot re-cache stale data from before the commit |
| Custom manager | `TenantAwareJpaTransactionManager extends JpaTransactionManager` | On every transaction: enable the Hibernate tenant filter on the `Session`; in docker, `select set_config('app.tenant_id', ?, true)` (transaction-local) for row-level security |
| Atomic UPDATE instead of long lock | `TicketNumberAllocator` | Allocates the ticket number in its own tiny transaction before the business transaction |
| Outside any transaction | `OutboxRelay` publish call; `AiAssistService.callProvider` | A slow broker or AI provider must never hold a database connection |

Why the project chooses `TransactionTemplate` in several places: `@Transactional` demarcates a whole method, but flows such as login (BCrypt hashing takes about 100 ms by design), AI analysis (network call) and relay (broker call) must not hold a connection for their entire duration. The template makes the boundary visible in code, and it sidesteps the self-invocation limitation.

Idempotent ticket creation shows how these combine: `TicketCommandService.create` (no annotation) allocates the number, then inside `tx.execute` calls `tickets.create(request, number)` (annotated `@Transactional`, so it joins the template's transaction because it is invoked through the injected proxy of a different bean) and inserts the idempotency-key row in the same transaction. If two requests race on the key's unique constraint, the loser rolls back as a whole and replays the winner's response.

Not implemented: `REQUIRES_NEW` in production code, `NESTED` savepoints, explicit isolation levels, `@TransactionalEventListener`, distributed (XA) transactions, `timeout` attributes, and Spring Retry on optimistic lock failures (the client retries after a 409). The outbox replaces the need for distributed transactions between the database and RabbitMQ (see `docs/diagrams/11-transactional-outbox.png`).

### Conceptual learning example (compiled and executed): `TxDemo.java`

A logging `PlatformTransactionManager` prints what a real manager would do, so the proxy behaviour is visible without a database. Methods use the same annotations as the project's services. Real output:

```text
bean class: TxDemo$Service$$SpringCGLIB$$0 | isCglibProxy=true
1) self-invocation of a REQUIRES_NEW method (watch: only ONE tx begins)
    [tx#1 BEGIN  propagation=0 readOnly=false]
  outer() running
    innerRequiresNew() body
    [tx#1 COMMIT]
2) checked exception, default rules
    [tx#2 BEGIN  propagation=0 readOnly=false]
    [tx#2 COMMIT]
    -> RuntimeException: checked
3) checked exception with rollbackFor=Exception.class
    [tx#3 BEGIN  propagation=0 readOnly=false]
    [tx#3 ROLLBACK]
    -> RuntimeException: checked
4) runtime exception
    [tx#4 BEGIN  propagation=0 readOnly=false]
    [tx#4 ROLLBACK]
    -> IllegalStateException: runtime
5) MANDATORY without an active tx (mirrors OutboxService.publish)
    -> IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'
6) MANDATORY joins the caller's tx; afterCommit hook
    [tx#5 BEGIN  propagation=0 readOnly=false]
    outbox.publish(ticket.created) inside tx
    [tx#5 COMMIT]
    afterCommit hook runs (cache eviction goes here, see TxHooks)
```

How to read it, one case at a time. Case 1: `outer()` called `this.innerRequiresNew()`, annotated `REQUIRES_NEW`, yet only one transaction began, because a self-call bypasses the proxy. Case 2: a checked exception committed the transaction (the wrapper reported the exception only afterwards). Case 3: `rollbackFor = Exception.class` fixed it. Case 4: a runtime exception rolled back. Case 5: `MANDATORY` with no transaction throws `IllegalTransactionStateException`; this is the guard in `OutboxService.publish`, which makes "event written outside a transaction" impossible. Case 6: `MANDATORY` joined the caller's transaction (it went through the proxy of another bean), and the after-commit hook fired after the commit line, which is the ordering `TxHooks.afterCommit` relies on.

### Pitfalls checklist

| Pitfall | Why | Fix |
|---|---|---|
| Self-invocation | Call on `this` bypasses the proxy | Move the method to another bean, inject a self proxy, or use `TransactionTemplate` |
| `private` or `final` method annotated | CGLIB cannot override | Make it public and non-final in a proxied class |
| Checked exception | Default rule commits | `rollbackFor = Exception.class` or throw unchecked |
| Exception swallowed inside the method | The interceptor never sees it | Rethrow or call `setRollbackOnly` |
| `@Transactional` on a non-Spring object | No proxy | Make it a bean |
| Long transaction around remote calls | Connection held, pool exhaustion | Keep remote calls outside, as the relay and AI service do |
| Lazy loading outside a transaction | `LazyInitializationException` | Fetch what you need inside; DTO mapping inside the service (the project sets `open-in-view: false`) |
| Different thread | Transaction is thread-bound | Start a transaction in the new thread |
| Cache eviction inside the transaction | Concurrent reader can repopulate with old data | Evict after commit (`TxHooks`) |
| `readOnly=true` then writing | Flush disabled for Hibernate | Do not write in read-only transactions |

### Performance, security, debugging

Performance: keep transactions short, do reads first and writes last, batch with `hibernate.jdbc.batch_size` (50 here), avoid per-row transactions in loops (the outbox relay uses several small transactions per event intentionally to keep the broker call outside). Security: tenant isolation is bound at transaction start (filter plus RLS variable); `set_config(..., true)` is transaction-local, so a pooled connection cannot carry one tenant's id into another transaction. Debugging: set `logging.level.org.springframework.transaction.interceptor=TRACE` (and `org.springframework.orm.jpa=DEBUG`) to log each transaction begin, commit and rollback; check `TransactionSynchronizationManager.isActualTransactionActive()` in a debugger; log SQL temporarily with `logging.level.org.hibernate.SQL=DEBUG` (the project keeps it at WARN).

### Alternatives and trade-offs

Programmatic `TransactionTemplate` is explicit and fast to reason about but more verbose. AspectJ weaving removes proxy limits. Saga and outbox patterns replace distributed transactions across services (the platform chose the outbox). Optimistic locking (`@Version`, used) avoids long locks at the cost of retries; pessimistic locking (`SELECT ... FOR UPDATE`, avoided on the ticket-number path) guarantees order at the cost of contention.

### Interview questions

Question: Why does `@Transactional` not work when a method calls another method of the same class?
Answer: The annotation is implemented by a proxy that wraps the bean. External callers hold the proxy and are intercepted; a call through `this` goes straight to the target, so no advice runs. The executed example shows a `REQUIRES_NEW` method starting no new transaction. Solutions: put the method in another bean, use `TransactionTemplate`, or use AspectJ weaving.

Question: What is the default rollback rule and how do you change it?
Answer: Rollback on `RuntimeException` and `Error`, commit on checked exceptions. Change with `rollbackFor` and `noRollbackFor`, or by throwing unchecked exceptions (this project throws unchecked `ApiException`).

Question: Explain `Propagation.MANDATORY` and where the platform uses it.
Answer: The method must run inside an existing transaction or Spring throws `IllegalTransactionStateException`. `OutboxService.publish` uses it so that an event row can only be written in the same transaction as the business change, which is the transactional outbox guarantee.

Question: Why does the relay publish outside a transaction?
Answer: A transaction holds a pooled connection and possibly locks. A broker call can be slow or hang; keeping it inside would exhaust the pool and block other work. The relay claims a row with a short transaction (compare-and-set with a lease), publishes outside, then marks the row sent in another short transaction; the lease lets another relay retry if this one crashes, giving at-least-once delivery.

Question: How would you evict a cache safely when data changes?
Answer: After the transaction commits. If you evict before commit, another request may read the old row and re-populate the cache before the new data is visible. `TxHooks.afterCommit` registers a `TransactionSynchronization` that runs `cache.evict` after a successful commit, and runs immediately if no transaction is active.

Question: What does `readOnly = true` do?
Answer: It is a hint. With Hibernate the session is set to manual flush so dirty checking is skipped (less CPU, no accidental writes), the JDBC connection may be marked read-only, and some routing data sources send it to a replica. It does not prevent writes at the database unless the driver enforces read-only.

## B8. Spring Security: the filter chain and JWT

### What it is and why it exists

Spring Security is a chain of servlet filters that authenticates the caller and authorises requests, plus method-level checks. JSON Web Tokens (JWT, RFC 7519) are signed tokens carrying claims; a stateless API verifies the signature on each request instead of keeping a server session.

![Figure: security filter chain, tenant binding and error responses](docs/diagrams/png/pdf2A-security-filter-chain.png)

### How it works internally

Spring Boot registers a `DelegatingFilterProxy` for the bean `springSecurityFilterChain`, a `FilterChainProxy` that selects the first `SecurityFilterChain` whose request matcher matches and runs its filters in a fixed order (executed list below). The `SecurityContextHolder` stores the `Authentication` in a `ThreadLocal`; with `SessionCreationPolicy.STATELESS` nothing is stored in an HTTP session, so every request must authenticate itself. `AuthorizationFilter` applies the rules from `authorizeHttpRequests`; `ExceptionTranslationFilter` converts `AuthenticationException` into the authentication entry point (401) and `AccessDeniedException` into the access-denied handler (403). Method security (`@EnableMethodSecurity`) wraps beans in a proxy that evaluates `@PreAuthorize` expressions before the call and throws `AccessDeniedException`. A `RoleHierarchy` makes `ROLE_ADMIN` imply `ROLE_MANAGER` implies `ROLE_AGENT`.

A JWT has three base64url parts: header (`alg`, `typ`), payload (claims) and signature. HS256 signs `header.payload` with HMAC-SHA-256 and a shared secret. The payload is encoded, not encrypted: anyone can read it. Verification must check the signature with a fixed expected algorithm, the issuer, the expiry (with small clock skew), and reject `alg: none`.

### How THIS PROJECT uses it

> Project-implemented behaviour (`core-service/.../security`): stateless JWT authentication with server-side revocation, role hierarchy, method security, and tenant binding from the verified token only.

| Concern | Implementation |
|---|---|
| Token issue | `JwtService.issue`: HS256, claims `sub` (user id), `tid` (tenant id), `email`, `roles`, `jti` (random UUID), `iss` (`cx-core-service`), `iat`, `exp` (default 60 minutes) |
| Token verification | `JwtService.verify`: `Jwts.parser().verifyWith(key).requireIssuer(issuer).clockSkewSeconds(5)`; rejects missing roles or `jti`; malformed claims become `JwtException` |
| Secret handling | At least 32 bytes else the application will not start; docker profile has no default and refuses the dev value (`StartupGuards`) |
| Filter | `JwtAuthFilter extends OncePerRequestFilter`, added before `UsernamePasswordAuthenticationFilter`; extracts `Bearer` token; on success sets `SecurityContext`, `TenantContext` (from `tid`) and MDC `userId`; on any `JwtException` it logs at debug and continues unauthenticated; always clears all three in `finally` |
| Revocation | `TokenRevocationService`: table `revoked_token` (shared across nodes), positive Caffeine cache (10 minutes), negative cache (3 seconds by default, `cx.security.revocation-negative-cache-seconds`, 0 for strict); logout stores the `jti`; an hourly job purges expired rows |
| Authorisation rules | Public: `OPTIONS` on any path, `/api/v1/auth/login`, `/api/v1/auth/register-organization`, `/actuator/health` and its sub-paths, `/actuator/info`, `/actuator/prometheus`, OpenAPI and Swagger UI paths; everything else authenticated |
| Stateless, no CSRF | `csrf(disable)` with a comment explaining why: the API authenticates with an `Authorization` header that browsers never attach automatically; `httpBasic` and `formLogin` disabled |
| Passwords | `BCryptPasswordEncoder(10)`; login verifies against a dummy hash when the tenant or user is unknown so timing does not reveal which was wrong |
| Brute-force defence | `LoginRateLimiter` token buckets keyed by client IP and by account (`acct:slug:email`); 429 with `Retry-After` |
| Roles | `ADMIN > MANAGER > AGENT`; `CUSTOMER` outside the hierarchy on purpose (a restriction, not a privilege level) |
| CORS | `CorsConfigurationSource`: allowed origins from `cx.cors.allowed-origins` (default `http://localhost:5173`), methods GET, POST, PUT, DELETE, OPTIONS, headers Authorization, Content-Type, Idempotency-Key, X-Correlation-Id, credentials false, max age 3600 |
| 401 and 403 bodies | Entry point and access-denied handler call `ErrorWriter` (B6) |
| Edge | The gateway independently verifies the JWT (`AuthFilter`, same secret property) and applies a per-user or IP token bucket before proxying |

Outermost filter detail: `CorrelationIdFilter` is a `@Component` with `@Order(Ordered.HIGHEST_PRECEDENCE)`, so Boot runs it before the Spring Security chain (whose registration order is -100). It validates an inbound `X-Correlation-Id` against `^[A-Za-z0-9._-]{1,64}$`, sets the MDC, and in `finally` logs method, path, status and duration (never headers, bodies or query strings) and clears the tenant and MDC.

Known limits and honest notes: HS256 means every service that verifies tokens holds the signing secret (the gateway and notification service read the same secret property); asymmetric signing (RS256 or EdDSA with a JWKS endpoint) would limit the blast radius of a compromised verifier. There is no refresh token: sessions end after the TTL or at logout. `/actuator/prometheus` is public in `SecurityConfig`, so it must never be reachable from untrusted networks (the gateway moves its own actuator to management port 8090). Tokens carry roles at issue time, so a role change takes effect at the next login unless the token is revoked.

Not implemented: OAuth2 or OIDC login, refresh tokens, multi-factor authentication, account lockout beyond rate limiting, password-reset flow, `@PostAuthorize`, ACLs, method security on services (only on controllers), asymmetric JWT keys, JWE (encrypted tokens), `jti` revocation by user ("log out everywhere"), and security headers customisation beyond Spring Security's defaults (the `HeaderWriterFilter` adds `X-Content-Type-Options` and cache-control headers by default).

### Conceptual learning examples (compiled and executed): `JwtDemo.java` and `SecurityDemo.java`

`JwtDemo.java` uses the same library (JJWT 0.12.6) and the same verification settings as `JwtService`, with a throwaway demo secret:

```text
token has 3 dot-separated parts: 3
payload (base64url, NOT encrypted): {"iss":"cx-core-service","sub":"user-1","tid":"tenant-A","roles":["AGENT"],}
valid token         : OK tid=tenant-A roles=[AGENT]
wrong signing key   : REJECTED SignatureException
wrong issuer        : REJECTED IncorrectClaimException
expired 1 minute ago: REJECTED ExpiredJwtException
expired 2 s ago     : OK tid=tenant-A roles=[AGENT]  (inside the 5 s skew)
tampered payload    : REJECTED SignatureException
alg=none token      : REJECTED UnsupportedJwtException
short secret refused: WeakKeyException
```

(The program strips `iat`, `exp` and `jti` from the printed payload only for readability.) The forged payload claimed `ADMIN` in another tenant, and failed because the signature no longer matches; the `alg=none` token was rejected because the parser was given a key and refuses unsigned tokens.

`SecurityDemo.java` builds a real `SecurityFilterChain` with the same settings as `SecurityConfig` (stateless, CSRF disabled, `permitAll` for login, JSON entry point and access-denied handler, role hierarchy, method security, custom JWT filter before `UsernamePasswordAuthenticationFilter`) and drives it with `MockMvc` plus `springSecurity()`:

```text
filter order: DisableEncodeUrlFilter > WebAsyncManagerIntegrationFilter > SecurityContextHolderFilter > HeaderWriterFilter > LogoutFilter > JwtFilter > RequestCacheAwareFilter > SecurityContextHolderAwareRequestFilter > AnonymousAuthenticationFilter > SessionManagementFilter > ExceptionTranslationFilter > AuthorizationFilter >
no token -> GET /tickets                         -> 401 {"code":"UNAUTHENTICATED","message":"Authentication required"}
garbage token -> GET /tickets                    -> 401 {"code":"UNAUTHENTICATED","message":"Authentication required"}
wrong-key token -> GET /tickets                  -> 401 {"code":"UNAUTHENTICATED","message":"Authentication required"}
expired token -> GET /tickets                    -> 401 {"code":"UNAUTHENTICATED","message":"Authentication required"}
AGENT token -> GET /tickets                      -> 200 {"who":"u1@tenant-A"}
AGENT token -> POST /tickets (no CSRF token)     -> 200 {"created":"yes"}
AGENT token -> GET /admin-only                   -> 403 {"code":"FORBIDDEN","status":403,"message":"Access denied"}
MANAGER token -> GET /agent-only (hierarchy)     -> 200 {"ok":"agent area"}
CUSTOMER token -> GET /agent-only                -> 403 {"code":"FORBIDDEN","status":403,"message":"Access denied"}
ADMIN token -> GET /admin-only                   -> 200 {"ok":"admin area"}
no token -> POST /auth/login (permitAll)         -> 200 {"accessToken":"issued-here"}
```

Reading it. The JWT filter sits after `LogoutFilter` and before the anonymous and authorisation filters. All four bad-token cases end as 401 because an invalid token leaves the request anonymous and `anyRequest().authenticated()` rejects it: the filter itself never answers. The `POST` without a CSRF token succeeded because CSRF is disabled for a stateless header-authenticated API. The AGENT versus admin-only case returned the 403 body from the controller advice (method security throws inside the dispatcher), while a rule-based 403 from `authorizeHttpRequests` would use the access-denied handler: both produce the same shape. A `MANAGER` reached an `AGENT` endpoint through the role hierarchy, while a `CUSTOMER` did not.

### Performance, security considerations, mistakes, debugging

Performance: HS256 verification is microseconds; BCrypt cost 10 is about 50 to 100 ms by design, which is why login is rate limited and not run inside a database transaction. The revocation check is the per-request database cost, mitigated by the two caches. Security: keep access tokens short-lived; never put secrets in the payload; verify issuer and expiry; fix the algorithm; protect the signing key; clear thread-bound context in `finally`; log rejected tokens at debug without the token; return identical responses for unknown tenant, unknown user and wrong password; scope CORS to known origins; do not trust `X-Forwarded-For` unless configured (`cx.security.trust-forwarded-for` defaults to false). Mistakes: disabling CSRF on a cookie-session application, `permitAll` ordering mistakes (first match wins), `hasRole('ROLE_X')` versus `hasAuthority`, storing tokens in `localStorage` where XSS can read them (a front-end decision to review separately), forgetting to clear `SecurityContextHolder` in a custom filter, using a weak or default secret, and accepting `alg` from the token header. Debugging: `logging.level.org.springframework.security=DEBUG` prints the filter chain and decisions for each request; decode the token payload (it is only base64url) to inspect claims; confirm the clock and the issuer; check the revocation table when a valid token is refused.

### Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Stateless JWT with revocation list (used) | No session store for authentication; scalable; revocation possible | Per-request revocation lookup (cached); shared secret across verifiers |
| Server sessions (cookie plus Redis) | Instant revocation, small tokens | Session store, CSRF protection required |
| OAuth2 resource server with an identity provider | Standard flows, SSO, key rotation via JWKS | Extra infrastructure and integration |
| Opaque tokens with introspection | Easy revocation | Network call per request unless cached |
| RS256/EdDSA JWT | Verifiers hold only the public key | Key management, slightly more CPU |

### Interview questions

Question: Walk me through authentication of a request in this platform.
Answer: The correlation filter sets the log context; CORS is handled; `JwtAuthFilter` reads the `Authorization: Bearer` header, verifies the HS256 signature, issuer and expiry, checks that the `jti` is not revoked (cache first, then the shared table), then sets the `Authentication` (roles become `ROLE_` authorities), the `TenantContext` from the `tid` claim and the log `userId`. `AuthorizationFilter` applies the URL rules, `@PreAuthorize` applies role checks (with the hierarchy), the controller calls the service, and in `finally` the filter clears the tenant, MDC and security context. The tenant id is never read from the request.

Question: Why is CSRF disabled and is that safe?
Answer: CSRF attacks rely on the browser attaching credentials automatically (cookies). This API uses an `Authorization` header that scripts must set explicitly, and no session cookie, so a forged cross-site request carries no credentials. It would not be safe if a cookie-based session or cookie-stored token were ever introduced.

Question: How do you log a user out when JWTs are stateless?
Answer: Record the token's `jti` in a revocation store until its natural expiry, and check it on each request. The platform keeps the table in the database so all nodes agree, with a long-lived positive cache and a very short negative cache to avoid a query per request; the negative TTL (3 seconds by default) is the documented worst-case delay for other nodes, configurable to zero for strict behaviour. Expired rows are purged hourly.

Question: HS256 versus RS256?
Answer: HS256 uses one shared secret for signing and verifying, so every verifier can also mint tokens. RS256 and EdDSA use a private key for signing and a public key for verification, so verifying services (gateway, notification) cannot forge tokens, and keys can be rotated via a JWKS endpoint. The platform uses HS256 for simplicity and documents the trade-off.

Question: What stops a user changing the tenant id to see another tenant's tickets?
Answer: The tenant comes only from the signed `tid` claim; no controller accepts a tenant parameter (an architecture test enforces it); every repository query has an explicit tenant predicate; a Hibernate filter and, in the docker profile, PostgreSQL row-level security enforce the same predicate; and cross-tenant ids answer 404. Forging a token requires the signing secret, as the tampered-payload run above shows.

Question: What is the difference between `hasRole('AGENT')` and `hasAuthority('ROLE_AGENT')`?
Answer: `hasRole` adds the `ROLE_` prefix automatically, so they are equivalent here. The filter creates authorities `ROLE_AGENT`, and `hasRole('AGENT')` matches through the role hierarchy, so managers and admins also pass.
## B9. Spring Data JPA and Hibernate integration

### What it is and why it exists

JPA (Jakarta Persistence) is the standard object-relational mapping API; Hibernate is the implementation Spring Boot uses. Spring Data JPA generates repository implementations from interfaces, so common queries need no code. Together they map entities to tables, track changes automatically within a transaction, and translate method names or JPQL into SQL.

### How it works internally

An `EntityManagerFactory` (one per application) builds `EntityManager`s; a transaction-bound `EntityManager` is the persistence context, a first-level cache that keeps one instance per entity id and remembers each entity's loaded state. At flush, Hibernate compares current state with the snapshot (dirty checking) and issues INSERT, UPDATE or DELETE statements in a defined order; flush happens at commit and before queries that could be affected. Lazy associations are replaced by proxies that load on first access and fail with `LazyInitializationException` after the session closes. Spring Data creates a JDK proxy for each repository interface at startup, parses derived query names and validates `@Query` strings against the entity model, so typos fail at startup (executed example below). `@Version` adds optimistic locking: `UPDATE ... WHERE id=? AND version=?` and an exception when zero rows match. A new-versus-existing decision in `save()` uses the version attribute or the id: a null wrapper `Long` version means new (`persist`), otherwise `merge` is used (an extra SELECT first).

### How THIS PROJECT uses it

> Project-implemented behaviour: schema owned by Flyway; entities with assigned UUIDs and a `Long` version; tenant isolation layered into mapping, repositories and the transaction manager; read paths shaped to avoid N+1; bulk updates for background jobs.

| Topic | Implementation |
|---|---|
| Schema | Flyway migrations V1 to V15 and V20 to V22 under `db/migration` (portable), plus `db/migration-pg` V1001 to V1011 (partial and GIN indexes, row-level security, append-only audit trigger, full-text) in docker; `spring.jpa.hibernate.ddl-auto: none`; the test profile uses `validate`, so an entity that drifts from the migrations fails the context |
| Entity base | `TenantOwned` (`@MappedSuperclass`): `tenant_id` not updatable, `@FilterDef("tenantFilter")`, `@PrePersist` verification of the bound tenant |
| Tenant filter | Entities carry `@Filter(name = TenantOwned.FILTER, condition = "tenant_id = :tenantId")`; `TenantAwareJpaTransactionManager.createEntityManagerForTransaction` enables the filter on every transaction's `Session` from `TenantContext` |
| Explicit tenant predicates | Repositories declare `findByIdAndTenantId`, `findByTenantId...`, and every `@Query` has `where t.tenantId = :t`, because filters do not apply to `em.find` (see executed example) |
| Identity | Application-assigned `UUID` ids (`Ticket.id`), not `@GeneratedValue`; `@Version private Long version` (wrapper) so `save` of a new entity uses `persist` |
| Concurrency | `@Version` plus the explicit `checkVersion` comparison for a clean 409; `@DynamicUpdate` on `Ticket` so only changed columns are written |
| Relations | Plain id columns instead of `@ManyToOne` for users and categories (composite foreign keys keep them in-tenant in the database); display names are resolved in batch by `TicketMapper` (one `IN` query each for users, categories, attachments) |
| Fetch tuning | `hibernate.default_batch_fetch_size: 32`, `hibernate.jdbc.batch_size: 50`, `open-in-view: false` |
| Queries | Derived queries, `@Query` JPQL, constructor projections (`select new TicketAgg(...)`), `JpaSpecificationExecutor` with `TicketSearch.build(...)` for dynamic filters, `Pageable` everywhere a list can grow |
| Bulk updates | `@Modifying` JPQL: `OutboxRepository.claim` (compare-and-set claim), `TicketRepository.updateSlaState` (does not bump the version, so background SLA checks never cause user-facing 409s) |
| Append-only audit | `AuditEventRepository` deliberately does not extend `JpaRepository`; `ArchitectureRulesTest.auditRepositoryIsAppendOnly` fails if delete methods appear |
| Verification | `NPlusOneGuardTest` counts SQL statements with Hibernate statistics (`generate_statistics: true` in test) and asserts a 30-row page costs no more statements than a 2-row page (at most 8) |

Local and docker differ in the database only: H2 in PostgreSQL mode with `DATABASE_TO_LOWER=TRUE` and `DEFAULT_NULL_ORDERING=HIGH` locally, PostgreSQL with RLS in Docker. One query comment records a consequence: `slaScanBatch` uses `coalesce(t.slaCheckedAt, :epoch)` instead of `NULLS FIRST` because null ordering differs between databases and dialect settings.

Not implemented: second-level cache, Envers auditing (an explicit `AuditService` is used), `@EntityGraph`, `@BatchSize` annotations (the global setting is used), Spring Data projections by interface, `@Query(nativeQuery=true)`, custom repository fragments, auditing annotations (`@CreatedDate`), `@Lock` pessimistic locking, multiple data sources, database read replicas.

### Conceptual learning example (compiled and executed): `JpaDemo.java`

Spring Data JPA 3.3.5 with Hibernate 6.5.3 on H2 in memory (Hibernate generates the demo tables; the project uses Flyway). Two entities: `Ticket` (tenant id, title, status, `@Version Long`, lazy `@ManyToOne Agent`) with the same `@Filter` as `TenantOwned`, and a repository with a derived query and two `@Query` methods. Real output:

```text
repository bean class: $Proxy77 (a JDK dynamic proxy created from the interface)
derived query findByTenantIdAndStatus(A, NEW): 3 rows
dirty checking: status changed without save(); entity updates executed = 1
stale write rejected: ObjectOptimisticLockingFailureException (the project maps this to HTTP 409 TICKET_VERSION_CONFLICT)
N+1: lazy agent read per ticket       -> SQL statements = 6 (1 list + 5 agents)
join fetch                            -> SQL statements = 1
tenant filter enabled for A: JPQL sees 3 rows of 5; em.find() of a tenant-B id returns it = true
```

What each line proves. Dirty checking: the transaction changed `status` and never called `save`, yet one UPDATE was executed, so `repo.save(t)` after loading is usually redundant (the project calls `saveAndFlush` where it needs the flush to happen before later steps). Optimistic locking: the stale detached copy was rejected. N+1: reading a lazy association inside a loop issued one statement per row (1 plus 5); `join fetch` collapsed it to one. The last line is the reason `TenantOwned` documents that "filters do NOT apply to `em.find()`/`getById()`": with the tenant-A filter enabled, JPQL saw only 3 of 5 rows, but `em.find` returned a tenant-B entity. That is why every repository method has an explicit tenant predicate and why PostgreSQL row-level security exists as a third layer.

Three real pitfalls that appeared while writing this example, each worth remembering. First, an entity with an assigned id and a primitive `long` version made `save()` choose `merge` instead of `persist`, which tried to merge a transient association and produced a foreign key violation; the wrapper `Long` version used by the project makes `save()` pick `persist`. Second, nested classes get the entity name `JpaDemo$Ticket`, so the JPQL `from Ticket` failed validation at startup until `@Entity(name = "Ticket")` was added (Spring Data validates queries at startup, which is good). Third, reading `t.agent.name` through the lazy proxy returned `null` because proxy objects do not populate fields: always use getters on entities that might be proxies.

### Performance

Fetch what the use case needs in as few statements as possible; use projections for read models; page everything; batch inserts (`jdbc.batch_size`) require not using identity generation, which the project avoids with assigned UUIDs; use `readOnly = true` for queries; index for the predicates (composite indexes lead with `tenant_id`, per `docs/diagrams/12-database-erd.mmd` and the index migrations V15 and V1001); avoid `findAll()` on growing tables; never loop with `findById`. Bulk JPQL updates bypass the persistence context and the version, which is intentional for background jobs but means stale entities in the same session must be refreshed.

### Security

Parameterised queries everywhere (JPQL parameters, derived queries) prevent SQL injection; never concatenate user input into JPQL or `@Query` strings; sort fields are whitelisted in `PageParams` because a `sort` parameter is otherwise an injection and information-disclosure vector. Tenant isolation is defence in depth: explicit predicates, Hibernate filter, `@PrePersist` guard, and PostgreSQL RLS driven by a transaction-local setting. Database users should have least privilege (the docker profile uses `cx_app`, and the audit table is append-only through a trigger and the repository shape). Do not log SQL with parameters in production.

### Common mistakes and debugging

Mistakes: open-in-view hiding lazy loading in controllers (disabled here); entities in controller responses; `equals`/`hashCode` on mutable or generated fields; `CascadeType.ALL` on large graphs; bidirectional associations without a helper; loading entire collections to count them; `@Transactional` missing so lazy loads fail; `IN` lists with thousands of ids; N+1 introduced by a mapper that touches a lazy association. Debugging: enable `hibernate.generate_statistics` and read counts (as `NPlusOneGuardTest` does); set `logging.level.org.hibernate.SQL=DEBUG` and `org.hibernate.orm.jdbc.bind=TRACE` temporarily; `EXPLAIN (ANALYZE)` in PostgreSQL; `perf/sql/H2Explain.java` for local plans; check Hikari metrics (`hikaricp_connections_active`, `pending`) for pool exhaustion.

### Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| JPA/Hibernate (used) | Productivity, dirty checking, portability | Hidden SQL, N+1, session semantics |
| Spring JDBC / `JdbcTemplate` | Full control, predictable SQL | Manual mapping (the notification module uses `JdbcRecipientDirectory` and its own store) |
| jOOQ | Type-safe SQL | Code generation, licence for some databases |
| Spring Data JDBC | Simple aggregate model, no lazy loading | Fewer features |
| MyBatis | SQL in XML, flexible | Manual mapping |

### Interview questions

Question: What is the persistence context and what does flush do?
Answer: The persistence context is the first-level cache bound to an `EntityManager` (here, to the transaction): one managed instance per id, with a snapshot of loaded state. Flush synchronises pending changes with the database by running INSERT/UPDATE/DELETE statements; it happens at commit, before queries that might see pending changes, or when requested. Dirty checking means modifying a managed entity needs no explicit `save`, as the executed example showed.

Question: Explain the N+1 problem and how this project avoids it.
Answer: Loading N parents and then triggering one lazy load per parent issues 1+N statements. Avoid by fetching joins, batch fetching, or by not mapping associations and resolving references in batch. This platform stores plain ids and `TicketMapper` resolves users, categories and attachments with one `IN` query each; `default_batch_fetch_size: 32` covers any remaining lazy cases, and a test asserts that a 30-row list costs the same number of statements as a 2-row list.

Question: How does optimistic locking work and why use it for tickets?
Answer: A `@Version` column is included in the UPDATE's WHERE clause and incremented on success; zero updated rows means another transaction changed the row, and Hibernate throws `ObjectOptimisticLockingFailureException`. Tickets are edited by many agents; optimistic locking avoids holding locks while a person reads and edits. The API requires `version` on update, assign and transition, rejects mismatches with 409, and the client reloads and retries.

Question: Why does `save()` sometimes issue a SELECT before INSERT?
Answer: When the entity has an assigned id and Spring Data cannot tell it is new, it calls `merge`, which loads the row first. The project uses a nullable `Long` version, so a new entity (version null) goes through `persist` without the extra SELECT. Implementing `Persistable` is the alternative.

Question: Do Hibernate filters protect `findById`?
Answer: No. Filters apply to queries (HQL, Criteria, collection loading) but not to direct loads by identifier (`em.find`, `getReference`). Hence the explicit `findByIdAndTenantId` repository methods and row-level security as a backstop, demonstrated by the last line of the executed output.

Question: Why is Flyway used instead of `ddl-auto=update`?
Answer: `update` guesses schema changes, cannot express data migrations, indexes or database-specific features, and is unsafe in production. Versioned migrations are reviewed, repeatable and testable, and the test profile's `validate` mode proves entity and schema agree.

## B10. Scheduling: the SLA job, the outbox relay and housekeeping

### What it is and why it exists

`@Scheduled` methods run periodically on a `TaskScheduler`. `@EnableScheduling` (present on `CoreServiceApplication`) registers a post-processor that finds the annotations after the context starts. Schedules are `fixedDelay` (wait after the previous run finishes), `fixedRate` (start at fixed intervals), or `cron`. Background jobs here implement polling consumers (the outbox) and periodic evaluation (the SLA clock).

### How it works internally

`ScheduledAnnotationBeanPostProcessor` collects annotated methods per bean and registers them with a `ScheduledTaskRegistrar` when the context refresh event fires. Boot's `TaskSchedulingAutoConfiguration` provides a `ThreadPoolTaskScheduler` bean named `taskScheduler` whose default pool size is 1 (`spring.task.scheduling.pool.size`). All scheduled methods share those threads. An exception thrown from a scheduled method is logged and suppressed for repeating tasks, so the schedule continues. `initialDelay` postpones the first run. Placeholders such as `fixedDelayString = "${cx.outbox.poll-ms:1000}"` are resolved from the environment. Each application node runs its own scheduled tasks; there is no cluster coordination unless you add it (ShedLock, database lock, Quartz).

### How THIS PROJECT uses it

> Project-implemented behaviour: five scheduled jobs on the default single scheduler thread, each with a bounded, idempotent run and metrics.

| Job | Schedule | What it does | Safety properties |
|---|---|---|---|
| `OutboxRelay.poll` | fixedDelay `cx.outbox.poll-ms` (1000), initialDelay 2000 | Reads up to `batch-size` (50) due ids, claims each with a compare-and-set UPDATE and a lease (`lock-seconds` 60), publishes through `EventPublisher` outside any transaction, marks SENT; failures retry with backoff, then FAILED after `max-attempts` (8) | Exactly one relay wins a claim; a crashed relay's rows are re-claimed after the lease; at-least-once delivery and consumer de-duplication on `eventId`; guard `relay-enabled` |
| `SlaMonitorJob.scheduled` | fixedDelay `cx.sla.job-interval-ms` (60000), same initial delay | Pages through tenants (100 per page), binds each tenant, calls `SlaService.scanCurrentTenant(200)` | Bounded batch; least recently checked first (`coalesce(sla_checked_at, epoch)`); idempotent (escalation level only increases, `breach_notified` flips once); one tenant's failure is logged and counted, others continue; guard `job-enabled`; Javadoc notes ShedLock as the multi-node improvement |
| `OutboxPurgeJob` | fixedDelay `purge-interval-ms` (1 hour) | Deletes SENT rows older than `retention-days` (7) in batches of 500, at most 20 batches per run | Bounded; FAILED rows are kept for investigation |
| `TokenRevocationService.purgeExpired` | fixedDelay 1 hour | Deletes expired revoked-token rows | Cheap, idempotent |
| `TicketCommandService.purgeOldKeys` | fixedDelay 1 hour, initialDelay 2 minutes | Deletes idempotency keys older than 24 hours, per tenant | Tenant-bound so it also works under PostgreSQL RLS |

The SLA flow is in `docs/diagrams/08-sla-evaluation.mmd`; the outbox flow in `docs/diagrams/11-transactional-outbox.mmd`. Jobs read their intervals from `CxProperties`, so tests set `cx.outbox.relay-enabled=false`, `purge-enabled=false` and `cx.sla.job-enabled=false` in `application-test.yml` and call `relayOnce()` or `runOnce()` directly for deterministic tests.

Why `fixedDelay`: the next run starts only after the previous one finishes, so runs of the same job never overlap in one node. Why a lease and compare-and-set instead of `SELECT ... FOR UPDATE SKIP LOCKED`: it works on both H2 and PostgreSQL and survives a relay crash; the repository comment says the compare-and-set claim is the portable equivalent.

Important behaviour to know and to be able to explain: because the project defines no `TaskScheduler`, the five jobs share one thread. A long `SlaMonitorJob` run (many tenants) delays the relay's next poll, and so delays notifications, until it finishes. The executed example below reproduces exactly that effect, and the fix is one property: `spring.task.scheduling.pool.size: 3` (or a dedicated scheduler for the relay). I did not change the repository because the request was documentation only.

Not implemented: `cron` expressions, `fixedRate`, `@Async`, `SchedulingConfigurer`, Quartz, ShedLock or any leader election, dynamic rescheduling, a job-status endpoint, and graceful draining of a running job at shutdown beyond Spring's scheduler shutdown (a `SmartLifecycle` could add it).

### Conceptual learning example (compiled and executed): `SchedDemo.java`

Two jobs, one fast (every 300 ms, standing for the relay) and one slow (sleeps 1,200 ms once, standing for a long SLA scan), run on a `ThreadPoolTaskScheduler` of size 1 and then size 2. Real output (the first run's offsets include one-time Spring and class-loading warm-up; read the sequence, not the absolute numbers):

```text
=== pool size 1 (the Spring Boot default for spring.task.scheduling.pool.size)
 2807 ms [sched-1] relay tick
 3043 ms [sched-1] slow job starts (sleeps 1200 ms)
 4299 ms [sched-1] slow job done
 4299 ms [sched-1] relay tick
 4663 ms [sched-1] relay tick
=== pool size 2
  196 ms [sched-2] relay tick
  473 ms [sched-1] slow job starts (sleeps 1200 ms)
  521 ms [sched-2] relay tick
  843 ms [sched-2] relay tick
 1152 ms [sched-2] relay tick
 1469 ms [sched-2] relay tick
 1770 ms [sched-1] slow job done
 1770 ms [sched-1] relay tick
 2138 ms [sched-1] relay tick
```

With one thread there were no relay ticks for the whole 1.2 seconds the slow job ran (about four ticks were due); with two threads the relay kept ticking on the other thread. It is the same shape as the platform: the relay is the latency-sensitive job.

### Performance, security, mistakes, debugging

Performance: bound every run; prefer many short runs over one long one; give latency-sensitive jobs their own thread; add jitter to hourly jobs on many nodes. Security: system jobs have no user, so they must bind the tenant themselves (`TenantContext.callAs`) and be allow-listed (the architecture test lists `SlaMonitorJob` and `TicketCommandService`); they must never accept tenant input; job failures must not leak data into logs (they log `e.toString()` only). Mistakes: using `fixedRate` with long jobs (overlap on multi-thread schedulers), assuming a job runs once in a cluster, unbounded loops over all rows, swallowing exceptions without metrics, and long transactions around remote calls. Debugging: the scheduler threads are named `scheduling-1` by default (Boot) so a thread dump shows a stuck job; metrics `cx.sla.job.runs`, `cx.sla.job.errors`, `cx.outbox.published`, `cx.outbox.retried`, `cx.outbox.failed` show progress; the `outbox_event` table shows PENDING, PROCESSING and FAILED rows with `last_error`; temporarily raise `logging.level.com.example.cx.core.outbox` to DEBUG.

### Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| `@Scheduled` (used) | Trivial, no dependency | Per-node, no persistence, no coordination |
| `@Scheduled` plus ShedLock | One node runs a job at a time | Lock table, lock tuning |
| Quartz | Persistent, clustered, misfire handling | Heavier, database schema |
| Message queue with delayed messages | Event-driven, no polling | Broker dependency; the platform already uses RabbitMQ for notifications |
| Kubernetes CronJob | Platform-native, isolated | Per-job container start, not for 1-second polling |
| Change data capture (Debezium) | No polling of the outbox | Additional infrastructure |

### Interview questions

Question: `fixedDelay` versus `fixedRate`?
Answer: `fixedDelay` waits a fixed time after the previous execution completes, so runs never overlap and slow runs push the schedule back; `fixedRate` starts executions at fixed intervals measured from start times, which can overlap on a multi-threaded scheduler. The platform uses `fixedDelay` for all polling and housekeeping.

Question: What happens if a scheduled method throws?
Answer: Spring logs the exception and, for repeating tasks, continues with the next scheduled run. The platform still catches `RuntimeException` in `OutboxRelay.poll` and per tenant in `SlaMonitorJob` so that the failure is logged with context and counted.

Question: How do you ensure a job runs once in a cluster?
Answer: Make the job idempotent first (the SLA scan is), then add coordination if redundant work matters: ShedLock or another lock, a leader election, or a single scheduler instance. The outbox relay is safe on every node because claiming a row is a compare-and-set that exactly one node wins, with a lease for crash recovery.

Question: Why is the relay's publish call outside a transaction and how is delivery made reliable?
Answer: To avoid holding a database connection while talking to a broker. Reliability comes from the outbox row: it is written in the business transaction, retried with exponential backoff, and delivered at least once; consumers de-duplicate on `eventId`. After eight failed attempts the row becomes FAILED and is kept.

Question: How would you improve the scheduling setup for production?
Answer: Set `spring.task.scheduling.pool.size` above one (or give the relay its own scheduler), add a distributed lock for the SLA job to avoid redundant scans on several nodes, export last-success timestamps as gauges and alert on staleness, and drain jobs gracefully on shutdown.

## B11. Actuator and Micrometer

### What it is and why it exists

Spring Boot Actuator exposes operational endpoints (health, info, metrics, and many more). Micrometer is the metrics facade underneath: instrument code once with counters, timers and gauges and export to Prometheus, Datadog and others. They exist so that running systems can be observed and automatically supervised (probes, dashboards, alerts).

### How it works internally

Actuator endpoints are enabled and exposed separately; over HTTP only the ids listed in `management.endpoints.web.exposure.include` are reachable. Health aggregates `HealthIndicator`s (database, disk, Redis, RabbitMQ, Mongo when their auto-configurations are active); with `management.endpoint.health.probes.enabled=true` it adds `/actuator/health/liveness` and `/actuator/health/readiness` groups driven by the application availability state. Micrometer's `MeterRegistry` holds meters identified by name plus tags; Boot auto-binds JVM, system, Tomcat, Hikari, HTTP server and logback metrics, and each registry implementation (Prometheus here, through `micrometer-registry-prometheus`) renders the same meters in its format at `/actuator/prometheus`. Percentiles and histograms are optional per timer. Each unique tag combination is a separate time series.

### How THIS PROJECT uses it

> Project-implemented behaviour: configured in `application.yml` and consumed by `infra/prometheus` and `infra/grafana`.

| Item | Setting |
|---|---|
| Exposed endpoints | `health,info,prometheus,metrics` (no `env`, `beans`, `heapdump`, `threaddump`, `loggers`) |
| Health | `show-details: never`; probes enabled; locally Rabbit, Redis and Mongo indicators are disabled so the app reports UP without them |
| Common tag | `management.metrics.tags.application: core-service` (gateway and notification service use their own names) |
| Security | `/actuator/health` and its sub-paths, `/actuator/info`, `/actuator/prometheus` are `permitAll` in `SecurityConfig`; `/actuator/metrics` requires authentication; the gateway puts its actuator on management port 8090 |
| Prometheus | `infra/prometheus/prometheus.yml` scrapes `core-service:8081`, `notification-service:8082` and `gateway:8090` every 15 seconds at `/actuator/prometheus` |
| Dashboards | `infra/grafana/dashboards/cx-platform-overview.json` uses `http_server_requests_seconds_*` (rate, p95, slowest URIs), `hikaricp_connections_*`, `cx_cache_total` by cache and result, `jvm_memory_used_bytes`, and RabbitMQ queue depth |
| Logging | The console pattern is a key=value line with `corr`, `tenant`, `user` taken from the MDC; request bodies and headers are never logged |

Custom meters in the code (the names appear in Prometheus with dots turned into underscores): `cx.cache{cache,result}` and `cx.cache.fallback{op}`; `cx.outbox.enqueued|published|retried|failed|purged{type}`; `cx.sla.job.runs` and `cx.sla.job.errors`; `cx.ai.analyze{result}`, `cx.ai.meta.failed`; `cx.auth.login{result}`; `cx.tickets.created`; `cx.workflow.fallback`; and in the notification service `cx.notifications.events{result}`. Tag values come from small closed sets (event types, cache names, result words), which keeps cardinality bounded. The observability design is drawn in `docs/diagrams/16-observability.mmd`.

Not implemented: distributed tracing (`micrometer-tracing` and OpenTelemetry export), `@Timed` or `@Observed` annotations, custom `HealthIndicator`s (for example "outbox backlog" or "oldest PENDING age"), timers or histograms for business operations (only counters), gauges for queue depth, alert rules, Actuator on a separate management port for core-service, exemplars, JFR streaming. A useful first addition: a gauge `cx.outbox.pending` from `OutboxRepository.countByStatus` (that query already exists), and an alert when the oldest pending event is older than a few minutes.

### Conceptual learning example (compiled and executed): `MetricsDemo.java`

```java
meters.config().commonTags("application", "core-service");
meters.counter("cx.cache", "cache", "analytics", "result", "hit").increment();   // 5 hits and 2 misses
Timer t = Timer.builder("cx.demo.latency").tag("op", "analyze").publishPercentiles(0.5, 0.95).register(meters);
```

Real output:

```text
hit count   = 5.0
miss count  = 2.0
hit ratio   = 0.7142857142857143
timer count = 5, total ms = 500.0, max ms = 400.0
gauge       = 4.0
meter ids   : [cx.cache[tag(application=core-service), tag(cache=analytics), tag(result=hit)], cx.cache[... result=miss)], cx.demo.latency.percentile[... phi=0.5)], cx.demo.latency.percentile[... phi=0.95)], cx.demo.latency[...], cx.demo.queue.size[...], cx.outbox.published[... type=ticket.created)]]
unbounded tag -> 1000 distinct time series from one metric name
```

The last line is the cardinality warning: a tag with an unbounded value (a ticket id) creates one series per value and can overwhelm Prometheus. The platform's tag values are all from closed sets.

### Performance, security, mistakes, debugging

Performance: meters are cheap; avoid creating a meter lookup with new tag combinations in a hot loop (the code calls `meters.counter(name, tags...)` per event, which does a registry lookup each time; caching meters in fields is a micro-optimisation not needed at this volume). Security: actuator is an attack surface; expose the minimum, keep detail hidden, require authentication or network isolation for anything beyond health, and never expose `heapdump`, `env`, `threaddump` publicly. Mistakes: high-cardinality tags (user id, URL with ids; Boot templates the URI tag to avoid this), exposing all endpoints with `*`, health checks that call slow dependencies and time out probes, and using liveness for dependency health (a database outage should make readiness fail, not liveness, so the platform does not restart healthy processes). Debugging: `curl /actuator/health`, `/actuator/metrics/http.server.requests`, `/actuator/prometheus` for raw output; compare `cx_outbox_published_total` growth with `cx_outbox_enqueued_total`; check the Grafana panels for pool saturation (`hikaricp_connections_pending`).

### Alternatives and trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Micrometer plus Prometheus (used) | Vendor neutral, pull model, cheap | Cardinality discipline; no traces |
| OpenTelemetry SDK and collector | Traces, metrics and logs in one standard | More moving parts |
| APM agents (Datadog, New Relic) | Deep automatic instrumentation | Cost, lock-in, agent overhead |
| Log-based metrics | No code changes | Expensive, slow, imprecise |

### Interview questions

Question: Difference between liveness and readiness probes?
Answer: Liveness says the process is alive and should not be restarted; readiness says it can serve traffic now. A database outage should turn readiness off (stop receiving traffic) but not liveness. Boot provides `/actuator/health/liveness` and `/readiness` when probes are enabled, as configured here.

Question: What is metric cardinality and why does it matter?
Answer: The number of distinct tag combinations per metric name. Each combination is a time series stored and queried separately, so unbounded tags (ids, emails) can exhaust memory in the monitoring system. The platform tags only with closed sets such as event type and cache name; the executed demo shows 1,000 series from a per-id tag.

Question: How would you find out why notifications are delayed?
Answer: Look at `cx_outbox_enqueued_total` versus `cx_outbox_published_total` and `cx_outbox_retried_total`, the count of PENDING and FAILED rows, the `last_error` column, relay logs with correlation ids, the scheduler thread state in a thread dump (the shared single scheduler thread may be busy with the SLA job), then RabbitMQ queue depth on the Grafana panel.

Question: Why is `/actuator/prometheus` public in the security rules and is that acceptable?
Answer: It lets Prometheus scrape without credentials, which is acceptable only when the port is reachable only from the monitoring network. The alternative is a separate management port (as the gateway does) or basic authentication on the scrape job. The risk is information disclosure (internal endpoint names, tenant-agnostic counters), not tenant data.

## B12. The application startup sequence

### What it is and why it exists

`SpringApplication.run` is a fixed sequence that prepares the environment, builds and refreshes the application context, starts the web server, runs startup runners and finally publishes readiness. Knowing the order explains why configuration errors stop the application early, when tenants can first be served, and when scheduled jobs begin.

### The sequence, mapped to this project

1. `CoreServiceApplication.main` sets the JVM default time zone to UTC (timestamps and logs are consistent; the dashboard's day buckets and business-hours arithmetic use the tenant's own zone, not this default), then calls `SpringApplication.run`.
2. Boot creates the run listeners, publishes `ApplicationStartingEvent`, and prepares the `Environment`: command-line arguments, environment variables, then `application.yml`; `spring.profiles.active: local` in that file activates the `local` profile unless `SPRING_PROFILES_ACTIVE` overrides it; then `application-local.yml` or `application-docker.yml` is layered on top.
3. The banner prints; the application context (servlet web, annotation-config) is created.
4. Context refresh begins. Bean-factory post-processors run: `ConfigurationClassPostProcessor` scans `com.example.cx.core`, processes `@Configuration` and `@Bean` methods, imports auto-configurations (conditions evaluate against the profile, properties and classpath), and registers the typed properties bean through `@ConfigurationPropertiesScan`.
5. Bean post-processors are registered (annotation processing, auto-proxy creator for `@Transactional`, `@PreAuthorize`, `@Scheduled`).
6. The web server (Tomcat) is created but does not accept requests yet.
7. Singletons are instantiated eagerly in dependency order. The `DataSource` (Hikari) is created; Flyway migrates the schema before the `EntityManagerFactory` is built (Boot makes the factory depend on the Flyway bean); `TenantAwareJpaTransactionManager` replaces the default manager; repositories are created (query strings are validated here); services, controllers, filters and the `SecurityFilterChain` are built. Constructor-time guards fire: `JwtService` rejects short secrets; `StartupGuards.check` (docker profile) rejects the dev secret; `AiFaultInjector` rejects a fault mode in docker; a missing `CX_DB_PASSWORD` or `CX_JWT_SECRET` placeholder fails the binding. Any failure here stops the application with a clear message before a single request is served.
8. `SmartInitializingSingleton` callbacks run; the `ScheduledAnnotationBeanPostProcessor` registers the scheduled methods when the refresh completes.
9. `finishRefresh`: lifecycle beans start; Tomcat starts accepting connections; `ContextRefreshedEvent` is published; scheduled tasks begin counting their initial delays (relay 2 s, SLA 60 s, hourly jobs 1 to 2 minutes).
10. `ApplicationStartedEvent`; then `ApplicationRunner`s and `CommandLineRunner`s in order: `DemoDataSeeder` (`@Order(100)`) seeds the demo tenants, users, tickets and KB articles when `cx.seed.enabled` is true (default true in `local`).
11. `ApplicationReadyEvent`; availability state becomes `ACCEPTING_TRAFFIC`, so `/actuator/health/readiness` reports UP.
12. Shutdown (`server.shutdown: graceful`): the web server stops accepting new requests and waits for in-flight ones; the context closes, `@PreDestroy` hooks run (`AiAssistService.shutdown`), the connection pool closes.

A point worth stating in an interview: between steps 9 and 10 the HTTP port is already open, and the demo seeder is still running, so a request in that window may see partial demo data. Readiness (step 11) is the correct signal for a load balancer. For production use the seeder is disabled (`CX_SEED_ENABLED=false`).

### Conceptual learning example

> Conceptual learning example (executed pieces only): the lifecycle ordering inside the refresh (steps 4 to 8) was executed in `LifecycleDemo.java` (B2), the property layering (steps 2 and 4) in `BinderDemo.java` (B4), and the scheduler start (step 9) in `SchedDemo.java` (B10). The full startup of the real application was not run here (the Maven build and a database were not part of this documentation task), so the numbered sequence above is derived from the source and from Spring Boot's documented behaviour, not from a captured log.

### Performance, security, mistakes, debugging

Performance: startup time is dominated by class loading, component scanning, Hibernate metadata, Flyway and the first connection; see A8 for options. Security: fail fast on missing secrets, which this project does at three levels (property placeholder without default, guard, key-length check). Mistakes: long work in `@PostConstruct` or runners (blocks readiness), depending on bean creation order (declare dependencies instead), scheduled jobs that start before data is ready, different behaviour between "worked locally" and "container" because of profile defaults (local default profile is `local`; the container must set `docker`). Debugging: `--debug` for conditions; `logging.level.org.springframework.boot=DEBUG`; `spring.main.log-startup-info`; the startup actuator (`BufferingApplicationStartup`) to time each phase; read the first `Caused by:` in a startup failure's chain, because the final message is usually a wrapper (`BeanCreationException`).

### Alternatives and trade-offs

Lazy initialisation shortens startup but delays failures to first use. AOT and native images shift work to build time. Running Flyway as a separate deployment step (init container) decouples schema changes from application start and avoids several nodes racing to migrate (Flyway locks, but explicit control is cleaner at scale).

### Interview questions

Question: Describe what happens when a Spring Boot application starts.
Answer: Prepare environment and profiles; create the context; refresh it: process configuration and auto-configuration, register post-processors, create the web server, instantiate singletons (database, migrations, JPA, services, security chain), start lifecycle beans and open the port, publish refreshed and started events, run runners, then publish ready. Failures in bean creation stop the process before it serves traffic.

Question: When do `@Scheduled` methods first run?
Answer: After the context refresh completes, once the post-processor has registered them; `initialDelay` then postpones the first execution (2 seconds for the relay, one interval for the SLA job). They do not run during bean creation.

Question: How can an application fail fast on bad configuration?
Answer: Typed properties binding with conversion errors, placeholders without defaults (`${CX_JWT_SECRET}`), `@PostConstruct` or constructor checks that throw, and `@Validated` property classes. This platform uses the first three.

Question: What is the difference between `ApplicationRunner` and `ApplicationReadyEvent`?
Answer: Runners run after the context is refreshed and before the ready event; the application is not yet marked ready while they run. `ApplicationReadyEvent` fires after all runners complete. Use a runner for startup tasks that must complete before readiness (seeding), and the ready event for work that should start only once the application is fully available.

Question: Why must Flyway complete before the `EntityManagerFactory`?
Answer: Hibernate validates (in tests) or relies on (always) the schema; if migrations had not run the first query would fail. Boot makes `EntityManagerFactory` depend on Flyway automatically when both are present.

## B13. Testing: slices, full-context tests and unit tests

### What it is and why it exists

Spring Boot Test provides test annotations that start parts of the application: `@SpringBootTest` loads the whole context; slice annotations (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, `@RestClientTest`) load only the beans for one layer with matching auto-configuration; plain JUnit tests need no Spring at all. Choosing the smallest sufficient scope keeps feedback fast; using full-context tests for security-critical flows proves the real wiring.

### How it works internally

The Spring TestContext framework builds and caches `ApplicationContext`s keyed by configuration (classes, active profiles, property overrides, mocked beans); tests with the same key share a context, which is why many tests are cheap and why each distinct `@TestPropertySource` creates another context. `@MockBean` (or `@MockitoBean` in newer versions) replaces a bean with a Mockito mock and also changes the cache key. `MockMvc` drives the `DispatcherServlet` without a network. Slices apply `@TypeExcludeFilter`s so that only controllers (for web), or only entities and repositories (for data), are scanned, and apply only the relevant auto-configurations.

### How THIS PROJECT tests

> Project-implemented behaviour: three kinds of tests, chosen by what must be proven. The Maven suite was run for the 2026-10-17 validation; surefire reports found in `core-service/target/surefire-reports` at the time of writing list 179 tests with 0 failures and 0 errors in the full core-service run recorded in `docs/core-service-validation.md` (2026-10-17; whole backend 292 tests, 0 failures).

| Kind | Examples | What it proves |
|---|---|---|
| Pure unit tests (no Spring) | `BusinessCalendarTest`, `SlaCalculatorTest`, `TicketStateMachineTest`, `WorkflowValidatorTest`, `LoginRateLimiterTest`, `AiSanitizerTest`, `MockRuleBasedAiProviderTest`, gateway `TokenBucketLimiterTest`, notification `RetryPolicyTest`, and the `dsa-lab` tests (README: 53) | Algorithms and rules, fast, deterministic (injectable clocks and tickers) |
| Full-context HTTP tests | `AbstractApiTest` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles({"local","test"})`) and subclasses such as `TicketApiTest`, `AuthApiTest`, `RoleMatrixTest`, `TenantIsolationTest`, `WorkflowApiTest`, `KbApiTest`, `AiApiTest`, `OutboxTest` | The real security chain, validation, advice, transactions, JPA, Flyway and H2 together; each test registers its own random tenant through the public API, so tests are independent without cleanup |
| Rule and guard tests | `ArchitectureRulesTest`, `AiArchitectureTest`, `NPlusOneGuardTest` | Conventions enforced by reflection and Hibernate statistics: tenant binding allow-list, no tenant parameter in controllers, no entities in controller signatures, append-only audit repository, constant statement count for list endpoints |
| Docker-dependent integration tests | `PostgresRlsDockerIT`, `RabbitRedisDockerIT`, `MongoDocumentStoreDockerIT`, `NotificationRabbitDockerIT` | Real PostgreSQL RLS, RabbitMQ and Redis, MongoDB through Testcontainers; tagged `docker` and excluded by default (`excluded.groups=docker`; the `docker-tests` Maven profile includes them). Not executed in this environment because Docker was unavailable |

Test configuration (`application-test.yml`): an isolated H2 database per Spring context (`jdbc:h2:mem:cxtest-${random.uuid}`), `ddl-auto: validate`, `generate_statistics: true`, seeding off, outbox relay and purge off, SLA job off, high login-rate limits, quiet logging. Property overrides use `@TestPropertySource` (for example `cx.ai.timeout-ms=300`, `cx.outbox.max-attempts=3`), and `@TestConfiguration` with `@Import` adds a capturing publisher in `OutboxTest`. Time-dependent code is tested by calling the job method directly (`relayOnce`, `runOnce`) rather than waiting.

Where slices are not used: the project does not use `@WebMvcTest`, `@DataJpaTest` or `@MockBean`. This is a trade-off, not an omission: because the security chain, tenant binding, validation and transactions interact, full-context tests with `MockMvc` cover the integration at the cost of more startup time per distinct configuration (about as many contexts as distinct `@TestPropertySource` sets). A pyramid with more slice tests would run faster but would miss wiring errors that the full tests catch.

How a slice test would look here (not executed, shown for learning):

```java
@WebMvcTest(TicketController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class TicketControllerSliceTest {
    @Autowired MockMvc mvc;
    @MockBean TicketService tickets;            // collaborators replaced by mocks
    @MockBean TicketCommandService commands;

    @Test void invalidBodyIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/tickets").header("Idempotency-Key", "k1")
                .contentType(APPLICATION_JSON).content("{\"title\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

### Conceptual learning examples (compiled and executed)

`MvcDemo.java`, `SecurityDemo.java` and `JpaDemo.java` are essentially hand-built slices: `MockMvc` standalone with a controller and advice (web slice), `MockMvc` with the real Spring Security chain (security slice), and Spring Data JPA with H2 (data slice), each starting in well under a few seconds without Spring Boot's test support. Their outputs are in B5, B8 and B9. They demonstrate the point of slices: test one layer with only the beans it needs.

### Performance, security, mistakes, debugging

Performance: reuse contexts (same configuration), avoid `@DirtiesContext` unless needed, avoid per-test property overrides that fragment the cache, run docker tests in a separate stage. Security: test the negative paths (401, 403, cross-tenant 404) as carefully as the positive ones; `RoleMatrixTest` and `TenantIsolationTest` do; never use real secrets or real data in tests (the project's test password is synthetic: `Test-Passw0rd!-123`). Mistakes: tests that depend on execution order or shared data (the random tenant per test avoids this), sleeping instead of triggering the job directly, mocking the class under test, asserting only status codes, and treating H2 as identical to PostgreSQL (the docker-tagged tests exist precisely because RLS and partial indexes do not exist in H2). Debugging: `logging.level.org.springframework.test.context.cache=DEBUG` shows context cache hits and misses; run one test with `-Dtest=ClassName#method`; check `@ActiveProfiles` when a bean is missing.

### Alternatives and trade-offs

| Approach | Benefit | Cost |
|---|---|---|
| Full-context `MockMvc` (used) | Real wiring, security and transactions | Slower per context |
| Slice tests | Fast, focused | Miss cross-layer wiring; need mocks |
| Testcontainers (used for docker ITs) | Real databases and brokers | Needs Docker, slower |
| Contract tests (Pact, Spring Cloud Contract) | Protects consumer-provider agreements across services | Extra tooling; the platform relies on `API-CONTRACT.md` and tests |
| End-to-end browser tests | Real user flows | Slow, flaky; the repo has an `e2e` folder for that layer |

### Interview questions

Question: Explain the difference between `@SpringBootTest` and `@WebMvcTest`.
Answer: `@SpringBootTest` starts the whole application context (optionally a real server); `@WebMvcTest` starts only the web layer (controllers, advice, converters, filters) with MVC auto-configuration and expects you to provide mocks for services. Use the slice when testing request mapping and validation in isolation, and the full test when behaviour depends on several layers, as in this platform's security and tenancy tests.

Question: How do you keep tests independent in a multi-tenant system?
Answer: Each test creates its own tenant through the public registration endpoint with a random slug, so data never collides and no cleanup is needed; contexts are isolated by a random H2 database name; background jobs are disabled and invoked directly.

Question: How would you test that a cache stampede does not call the loader many times?
Answer: Start N threads that call `getOrLoad` on the same key with a loader that counts invocations and sleeps briefly, released together by a `CountDownLatch`; assert the count is 1 and all threads got the same value. `CacheServiceTest.singleFlightPreventsAStampede` does exactly this, next to tests for tenant-prefixed keys, TTL expiry, failures not being cached, a loader that uses the cache itself, and the Redis-down fallback.

Question: Why are Docker-dependent tests excluded by default?
Answer: CI or a developer laptop may not have Docker, and the tests are slower. They are tagged `docker` and excluded through a Maven property; the `docker-tests` profile includes them. They cover what H2 cannot: PostgreSQL row-level security, RabbitMQ and Redis behaviour, MongoDB aggregation.

Question: What is an architecture test and why have one?
Answer: A test that fails when a design rule is broken, for example "only these classes may bind the tenant" or "controllers never expose entities". The rules are cheap to check by scanning source or reflecting over classes, and they protect security properties that code review can miss as the team grows.

## B14. Troubleshooting exercises for Part B

| No. | Symptom | Hint | Answer |
|---|---|---|---|
| 1 | An `@Transactional(REQUIRES_NEW)` method does not start a new transaction | Who calls it? | Self-invocation bypasses the proxy (executed in B7); move it to another bean or use `TransactionTemplate` |
| 2 | A method throws a checked exception but the data was saved | Default rollback rule | Checked exceptions commit; add `rollbackFor` or throw unchecked |
| 3 | After enabling virtual threads or an executor, tickets are created under the wrong tenant or with "No tenant bound" | Where is the tenant stored? | `ThreadLocal` does not cross threads; capture the id and rebind with `TenantContext.callAs` inside the task |
| 4 | `NoSuchBeanDefinitionException` for `CacheService` only when running a test | Profiles | A profile-conditional bean was not active; check `@ActiveProfiles` and `@Profile("!docker")` |
| 5 | Application starts locally but the container fails with a placeholder error | Profile defaults | The container needs `SPRING_PROFILES_ACTIVE=docker` and all required variables; the docker profile has no defaults for secrets |
| 6 | Notifications are delayed by about a minute at the top of every minute | Scheduler threads | One scheduler thread shared by the relay and the SLA job; raise `spring.task.scheduling.pool.size` (executed in B10) |
| 7 | List endpoint gets slower as page size grows, many identical SELECTs in the log | N+1 | Lazy association read in a loop; use batch lookup or `join fetch`; `NPlusOneGuardTest` shows how to assert it |
| 8 | A 401 response has the right shape but a 403 from `@PreAuthorize` has a different shape | Where is the exception thrown? | Method security throws inside the dispatcher (advice); URL rules fail in the filter chain (access-denied handler); keep both writing the same schema |
| 9 | Unsupported `Content-Type` yielded HTTP 500 (FIXED) | Catch-all handler | A catch-all `@ExceptionHandler(Exception.class)` beats Spring's default 415 mapping; fixed with an explicit handler and `ErrorContractTest` |
| 10 | Every request after logout still works on another node for a few seconds | Revocation caches | Negative cache TTL (default 3 s); set `cx.security.revocation-negative-cache-seconds=0` for strict behaviour at the cost of a query per request |
| 11 | Hibernate says `LazyInitializationException` in a mapper | Session scope | The entity is used outside a transaction; map inside the `@Transactional` service method (open-in-view is off) |
| 12 | `save()` issues a SELECT before INSERT and sometimes violates a foreign key | New-entity detection | Assigned id with a primitive version makes Spring Data call `merge`; use a nullable `Long` version or `Persistable` (reproduced in B9) |
