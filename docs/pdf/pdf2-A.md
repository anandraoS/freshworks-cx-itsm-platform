# Part A. Java and core computer science

This part explains the Java and computer-science foundations that the platform relies on, using the real code of the repository as the running example. Every topic follows the same template so that you can revise quickly: what it is, why it exists, how it works internally, why it was selected here, how it is configured, how this project uses it, what is implemented and what is not, how it could be extended, performance, security, common mistakes, debugging, alternatives, and interview questions with answers.

Two labels are used throughout. A callout that starts with "Project-implemented behaviour" describes something you can find in the repository and verify by reading the cited file. A callout that starts with "Conceptual learning example" describes a standalone program written for this guide. Each such program says whether it was compiled and run. All runnable examples in Parts A and B were compiled and executed with the GraalVM JDK 21.0.7 on Windows; the Spring-based examples (Part B) ran against Spring Framework 6.1.14 and Spring Boot 3.3.5 jars from the local Maven cache, not inside the full application. Nothing that needs Docker (PostgreSQL, Redis, RabbitMQ, MongoDB) was executed, and this guide never claims otherwise.

> Project-implemented behaviour: the platform targets Java 21 (`java.version` 21 in `pom.xml`) and Spring Boot 3.3.5. Records, pattern matching for `instanceof`, switch expressions, text-friendly APIs such as `HexFormat`, and `Thread.ofVirtual` are all available. Virtual threads are available but not switched on in this project (see A5).

## A1. Object-oriented design and SOLID

### What it is and why it exists

Object-oriented design is the practice of organising code around types that hide their state and expose behaviour through small contracts. SOLID is a set of five guidelines that keep those contracts healthy as a codebase grows: Single responsibility, Open/closed, Liskov substitution, Interface segregation and Dependency inversion. They exist because the dominant cost of software is change. A design that lets you add a new AI provider, a new cache or a new message broker without editing the callers is cheaper to evolve than one in which every caller knows every implementation.

### How it works internally in Java

Java dispatches instance methods through a virtual method table: the JVM looks up the method for the runtime class of the receiver (`invokevirtual`), and for interfaces uses `invokeinterface` with an itable lookup. The JIT compiler removes most of the cost: when only one implementation of an interface has been loaded the call site is inlined (monomorphic), with two it uses a guarded inline (bimorphic), and only with many it falls back to a table lookup (megamorphic). This is why programming to interfaces is essentially free in practice, and why the number of implementations at a hot call site matters more than the interface itself.

Records (Java 16+) are final classes with a canonical constructor, accessors, `equals`, `hashCode` and `toString` generated from the component list. Their fields are final, so a record is shallowly immutable and safely published to other threads (see A6).

### Why it was selected here: the extension points

The code base deliberately marks its seams with the Javadoc phrase "EXTENSION POINT". Four of them are the best SOLID case studies in the repository.

| Interface | File | Implementations | Chosen by | Contract worth noticing |
|---|---|---|---|---|
| `AiProvider` | `core-service/.../ai/AiProvider.java` | `MockRuleBasedAiProvider`, `HttpAiProvider` | `@ConditionalOnProperty(cx.ai.provider)`, default `mock` | Pure: no DB, tenant or action access; input already sanitised |
| `CacheService` | `.../cache/CacheService.java` | `CaffeineCacheService`, `RedisCacheService` | `@Profile("!docker")` and `@Profile("docker")` | Tenant-prefixed key, single-flight loader, cache is optional |
| `EventPublisher` | `.../outbox/EventPublisher.java` | `LocalEventPublisher`, `RabbitEventPublisher` | `@Profile("!docker")` and `@Profile("docker")` | Called only by the relay; throw to trigger retry; at-least-once |
| `DocumentStore` | `.../docstore/DocumentStore.java` | `JpaDocumentStore`, `InMemoryDocumentStore`, `MongoDocumentStore` | `@ConditionalOnProperty(cx.docstore)` | Every method takes `tenantId`; list is ascending by key, bounded to 500 |

> Project-implemented behaviour: the choice between implementations is made by configuration only. `application.yml` sets `cx.ai.provider` to `mock` and `cx.docstore` to `jpa`; `application-docker.yml` overrides `cx.docstore` to `mongo` and activates the `docker` profile beans. No caller of `AiProvider`, `CacheService`, `EventPublisher` or `DocumentStore` mentions a concrete class.

### The five principles, mapped to the project

Single responsibility. The outbox is split into three classes that each change for exactly one reason. `OutboxService.publish` only writes a row and refuses to run outside a transaction (`Propagation.MANDATORY`). `OutboxRelay` only claims rows and calls a publisher. `EventPublisher` only knows a transport. If RabbitMQ is replaced by Kafka, only one class changes. By contrast `TicketService` is long and does coordinate many collaborators (SLA, workflow, audit, cache, events); this is an orchestration class, and it is the place where an honest review would suggest extracting an application-service boundary if it grows further.

Open/closed. Adding a third `AiProvider` means adding one class and one property value; `AiAssistService` is closed for modification. The workflow engine applies the same idea to data: `WorkflowValidator.ACTIONS` is a closed whitelist (`ASSIGN_TO_ROLE_LEAST_LOADED`, `SET_PRIORITY`, `ESCALATE`, `NOTIFY`, `ADD_TAG`), so extension requires code, which is a security decision, not an oversight.

Liskov substitution. Every `CacheService` implementation must honour the same promises: the key is tenant-prefixed through `CacheService.fullKey`, the loader runs at most once per key per node at a time, and a Redis outage degrades to loader-only behaviour instead of throwing. `RedisCacheService` swallows Redis failures, counts them in `cx.cache.fallback` and backs off for `redis-failure-backoff-seconds`. A substitute that threw on a Redis timeout would break callers that were written against the Caffeine implementation: that is a Liskov violation.

Interface segregation. `AiProvider` has two methods; `EventPublisher` has one. `TicketStateMachine` is a final class with only static members, so nothing can depend on more than it needs. `DocumentStore` is the widest interface at four operations and it is still cohesive.

Dependency inversion. High-level services receive their collaborators through constructors: `OutboxRelay(OutboxRepository, OutboxService, EventPublisher, CxProperties, MeterRegistry, PlatformTransactionManager)`. Nothing in the service layer calls `new` on an infrastructure class. The exception that proves the rule is deliberate: `TicketCommandService` constructs a `TransactionTemplate` itself from the injected `PlatformTransactionManager`, because a template is a cheap value object, not a service.

> Conceptual learning example (compiled and executed): `SolidDemo.java` recreates the `AiProvider` seam in about 40 lines, plus an interface-segregation split and a Liskov violation.

```java
interface AiProvider { String name(); String analyze(String text); }
static class AssistService {
    private final AiProvider provider;
    AssistService(AiProvider p) { this.provider = p; }          // DIP: constructor injection
    String suggest(String ticket) { return provider.name() + " -> " + provider.analyze(ticket); }
}
// Liskov violation: Square changes behaviour that Rect callers rely on
static class Rect { int w, h; void setW(int x) { w = x; } void setH(int x) { h = x; } int area() { return w * h; } }
static class Square extends Rect { @Override void setW(int x) { w = h = x; } @Override void setH(int x) { w = h = x; } }
```

Output of the real run:

```text
mock: MOCK_RULE_BASED -> Billing
http: HTTP -> remote-category-for:21
read through Reader role: v
LSP broken, a Rect user expects 10 but gets 4
```

### Implemented versus not implemented

| Feature | Status |
|---|---|
| Interface-based extension points with configuration-driven selection | Implemented (four seams above) |
| Constructor injection everywhere, no field injection in services | Implemented (a few `@Autowired` constructors for test seams, for example `TokenRevocationService`) |
| Immutable DTOs as records, entities never leave the service layer | Implemented and tested by `ArchitectureRulesTest.controllersNeverExposeEntities` |
| Sealed hierarchies for domain results | Not implemented (results such as `Outcome` are small private records with a boolean helper) |
| Abstract base entity | Implemented as `TenantOwned` (a `@MappedSuperclass` that enforces the tenant); this is inheritance used for a template, not for behaviour reuse across unrelated types |

How a sealed result would look if you wanted exhaustive handling of an AI outcome:

```java
sealed interface Outcome permits Ok, Degraded {}
record Ok(AiResult result) implements Outcome {}
record Degraded(String reason) implements Outcome {}
// switch (outcome) { case Ok o -> ...; case Degraded d -> ...; }   // compiler checks exhaustiveness
```

### Performance, security, common mistakes, debugging

Performance: interface dispatch is not a bottleneck; allocation and I/O are. Security: the biggest OOP security idea in the project is encapsulation of the tenant. `TenantContext` has a private constructor, a `ThreadLocal`, and mutators that an architecture test restricts to an allow-list of classes (`ArchitectureRulesTest.tenantIsBoundOnlyByTheAllowedComponents`). Making the dangerous operation hard to call is better than documenting that it is dangerous.

Common mistakes: inheritance for reuse (prefer composition), leaking a mutable collection from a getter (`ApiException` copies with `List.copyOf`; `BusinessCalendar` copies with `Set.copyOf`), a public constructor on a class that should be a singleton utility, and `equals` without `hashCode`. Debugging: when the wrong implementation is wired, print the bean class (`applicationContext.getBean(CacheService.class).getClass()`), look at `/actuator/beans` if exposed, and run the application with `--debug` to see the condition evaluation report that explains why each `@ConditionalOnProperty` matched.

### Alternatives and trade-offs

Strategy through interfaces and DI (used here) is explicit and type-checked. The alternatives are service-loader discovery (`java.util.ServiceLoader`, no Spring needed but less configurable), plugin systems with separate class loaders (powerful, heavy), and function types such as `Function<AiRequest, AiResult>` (lighter, but loses a name and documentation). The cost of the interface approach is more files; the benefit is a place to document the contract, which this project uses heavily.

### Interview questions

Question: Explain the Open/Closed principle with an example from a project you know.
Answer: Open for extension, closed for modification means new behaviour is added by writing new code, not by editing tested code. In this platform `AiAssistService` depends on `AiProvider`. To add a vendor, I write a class implementing `AiProvider` and select it with `cx.ai.provider`; `AiAssistService` and its tests do not change. The validation of results stays in the service, so a new provider cannot weaken the safety rules. The trade-off is that the interface must be designed carefully up front; here it is a pure function from a sanitised request to a result.

Question: Where would you say the Liskov Substitution Principle matters in this code?
Answer: In `CacheService` and `EventPublisher`. Callers assume a cache miss and a cache outage are both survivable and that the loader runs once per key per node. `RedisCacheService` keeps that promise by catching Redis exceptions, counting them and skipping Redis for a back-off window. `EventPublisher` implementations must throw to ask for a retry, because the relay uses that exception to schedule the next attempt; swallowing the exception would silently drop events, a substitution failure.

Question: Constructor injection versus field injection?
Answer: Constructor injection makes dependencies explicit and final, allows plain unit tests without a container, detects circular dependencies at startup, and avoids reflection into private fields. Field injection hides how many collaborators a class has. The project uses constructors and keeps a second package-private constructor for tests where a clock or ticker must be replaced (`TokenRevocationService`, `LoginRateLimiter`).

Question: Composition or inheritance?
Answer: Prefer composition because it does not couple a subclass to the internal evolution of the parent and allows swapping at runtime. The only inheritance in the domain model is `TenantOwned`, which is a template for a cross-cutting rule (the tenant filter and the `@PrePersist` check), and a rule like that must not be forgotten per entity.

Question: Why are the DTOs records and why does that matter for security?
Answer: Records are final and immutable, so a request object cannot be modified after validation, and mass-assignment of unexpected fields is impossible because only declared components exist. Because entities are never serialised directly, a new column cannot leak to the API by accident.

## A2. Collections and their internals

### What it is and why it exists

The Java Collections Framework provides standard data structures behind interfaces (`List`, `Set`, `Map`, `Queue`, `Deque`). Knowing the internals lets you predict complexity, memory use and failure modes, which is the content of most Java interviews and of most production performance bugs.

### How the main structures work

HashMap. An array of buckets (default capacity 16, load factor 0.75). The bucket index is `(h ^ (h >>> 16)) & (n - 1)` where `h` is `hashCode()`. Collisions form a linked list; since Java 8 a bucket with at least 8 entries is converted to a red-black tree when the table has at least 64 buckets (smaller tables resize instead), and the tree is untreeified at 6. Tree bins order by `compareTo` when the key is `Comparable`, otherwise by identity hash tie-breaking, which is why a constant `hashCode` on a non-Comparable key is catastrophic. When size exceeds `capacity * 0.75` the table doubles and each bin is split into a low and a high bin without rehashing every key. Lookup is O(1) expected and O(log n) worst case with trees. Iteration is fail-fast through `modCount`.

ConcurrentHashMap. Since Java 8 there is no segment lock. The table is an array of nodes; an empty bin is filled by a lock-free compare-and-set, and a non-empty bin is updated while synchronising on the bin's first node. Reads are lock-free (volatile reads of table slots). `size()` is computed from striped counters (`CounterCell`). Iterators are weakly consistent: they never throw `ConcurrentModificationException`. Keys and values cannot be null because null would be ambiguous in a concurrent `get`. Compound operations (`computeIfAbsent`, `merge`, `compute`) are atomic per key, but the function must be short and must not modify the map.

ArrayList. A resizable array: `add` is amortised O(1) (growth by about 1.5x), `get` is O(1), insertion in the middle is O(n) because of `System.arraycopy`. It is the right default; `LinkedList` is almost never faster in practice because of pointer chasing and per-node allocation.

PriorityQueue. A binary min-heap stored in an array. `offer` sifts up in O(log n), `poll` sifts down in O(log n), `peek` is O(1), `remove(Object)` is O(n). The iterator and `toString` show the array order, which is not sorted.

TreeMap. A red-black tree. `get`, `put`, `remove` are O(log n); the navigable operations (`floorKey`, `ceilingKey`, `headMap`, `tailMap`, `subMap`) are what you pay for. It requires a consistent comparator.

LinkedHashMap. A HashMap plus a doubly linked list through the entries; `accessOrder=true` plus `removeEldestEntry` yields an LRU cache in a few lines.

### How THIS PROJECT uses them

| Structure | Where | Why |
|---|---|---|
| `ConcurrentHashMap<String, CompletableFuture<Object>>` | `CaffeineCacheService.inflight`, `RedisCacheService.inflight` | Single-flight: the first thread to `putIfAbsent` loads, the others `join()` its future |
| `ConcurrentHashMap<String, Bucket>` | gateway `TokenBucketLimiter.buckets` | Per-key token buckets; `computeIfAbsent` creates, `removeIf` evicts idle full buckets |
| `TreeMap<String, Map<String,Object>>` | `InMemoryDocumentStore` | Ordered keys so a prefix list is `tailMap(prefix)` and zero-padded versions sort naturally |
| `EnumMap` / `EnumSet` | `TicketStateMachine.DEFAULT`, `WorkflowValidator.reachable` | Array-backed, no hashing, perfect for small enums |
| `ArrayDeque` | `WorkflowValidator.reachable` BFS queue | Faster than `LinkedList`, no per-node allocation |
| `HashMap<UUID, Long>` | `WorkflowEngine` least-loaded action | Open-ticket counts per candidate from one `IN` query |
| `LinkedHashMap` | `OutboxService.publish`, `TicketEvents.base` | Payload keys keep insertion order for stable JSON |
| `List.copyOf`, `Set.copyOf` | `ApiException`, `BusinessCalendar` | Defensive immutable copies |
| Caffeine `Cache` | `TokenRevocationService`, `AiRateLimiter`, `LoginRateLimiter`, `CaffeineCacheService` | Bounded, expiring maps; memory can never grow without limit |
| `PriorityQueue` | `dsa-lab` `Assigners.LeastLoaded`, `TicketPriorityQueue` | Educational; production assignment uses SQL counts |

> Project-implemented behaviour: `CaffeineCacheService.getOrLoad` checks the cache, then `inflight.putIfAbsent(full, mine)`. The winner calls the loader outside any map lock and completes the future; losers call `existing.join()`. If the winner's loader fails, losers fall back to calling the loader themselves. The comment in the source explains why Caffeine's own `compute` was not used: a loader that uses the cache again would hit a recursive-update error inside a map lock.

### Conceptual learning example (compiled and executed): `CollectionsDemo.java`

```java
static int spread(Object k, int n) { int h = k.hashCode(); return (h ^ (h >>> 16)) & (n - 1); }
...
LinkedHashMap<Integer, String> lru = new LinkedHashMap<>(16, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<Integer, String> e) { return size() > 2; }
};
lru.put(1, "a"); lru.put(2, "b"); lru.get(1); lru.put(3, "c");
```

Real output:

```text
alpha -> 7
beta -> 14
gamma -> 14
delta -> 8
4000 puts+gets, constant hashCode on a non-Comparable key (one bucket): 386 ms; good hash: 18 ms (timings illustrative)
mutated key lookup: null | key with original value: null | size 1
CME while removing inside for-each
removeIf result: [1, 3]
CHM modified while iterating, no CME, size now 5
CHM rejects null keys
CHM merge is atomic per key: a=11
PriorityQueue toString (heap array, NOT sorted): [1, 4, 2, 9, 8, 7]
poll order: 1 2 4 7 8 9
prefix 'v' range via tailMap: v001 v002 v010
floorKey(v005)=v002 ceilingKey(v005)=v010
List.of is immutable
LRU via LinkedHashMap(accessOrder) keys: [1, 3]
```

Reading the output: "beta" and "gamma" collide in bucket 14 and are then distinguished by `equals`. The mutated key is lost twice over: the entry sits in the bucket computed from the old hash, and the changed key no longer equals the stored one, so the entry is unreachable but still counted in `size`. The timing line is a single run on a developer laptop and is only meant to show the order of magnitude: one bucket turned 4000 lookups into a quadratic scan.

### Implemented versus not implemented, and extensions

Not implemented: no `ConcurrentSkipListMap`, no `CopyOnWriteArrayList`, no parallel collections, no primitive-specialised collections. All of them would be considered only after a measurement. If the in-memory document store ever needed concurrent sorted access without the explicit `synchronized (bucket)` blocks, `ConcurrentSkipListMap` would give lock-free ordered operations at the price of higher constant factors.

### Performance

Presize when you know the size (`new HashMap<>(expected / 0.75f + 1)`) to avoid resizes. Prefer `EnumMap` for enum keys. Avoid autoboxing in hot loops (`Map<Long, Long>` allocates). `ConcurrentHashMap.size()` is an estimate under concurrent updates. A cache without a bound is a memory leak with a nice name: every cache in this project has `maximumSize` (Caffeine) or a TTL (Redis).

### Security

Hash flooding: a client who controls keys can craft collisions. Java 8 tree bins limit the damage to O(log n) for Comparable keys such as `String`. The project also bounds attacker-controlled key sets: `LoginRateLimiter` caps tracked keys at `cx.security.login-rate.max-tracked-keys` (10,000) through Caffeine, and the gateway limiter evicts idle full buckets when `maxKeys` is exceeded.

### Common mistakes and debugging

Mutating a key after insertion; using `Arrays.asList` and then adding; removing during for-each; assuming `HashMap` iteration order; sharing a `HashMap` between threads (can lose entries or, in old JDKs, loop forever on resize); calling `computeIfAbsent` recursively on the same map. For debugging take a heap histogram (`jcmd <pid> GC.class_histogram`) to see which collection types dominate, and a thread dump if you suspect lock contention on a hot bucket.

### Alternatives and trade-offs

| Need | JDK | Alternative | Trade-off |
|---|---|---|---|
| Bounded cache with eviction | `LinkedHashMap` access order | Caffeine (used) | Caffeine adds W-TinyLFU admission, expiry and metrics, and is concurrent |
| Shared cache across nodes | none | Redis (used in docker profile) | Network hop, serialisation, outage handling |
| Concurrent sorted map | `ConcurrentSkipListMap` | database index | Skip list is in-memory only |
| Primitive keys at scale | `HashMap<Integer,..>` | fastutil, Eclipse Collections | Extra dependency, less familiar API |

### Interview questions

Question: How does HashMap work and what changed in Java 8?
Answer: Keys are hashed, the hash is spread with `h ^ (h >>> 16)` so high bits influence the index, and the index is `hash & (n - 1)` because the capacity is always a power of two. Entries in one bucket form a list; from Java 8 a bucket of 8 or more entries in a table of at least 64 slots becomes a red-black tree, so worst-case lookup is O(log n) instead of O(n). Resize doubles the table and splits each bin in order. Not thread safe: concurrent puts can lose updates.

Question: Why must `equals` and `hashCode` be consistent and keys effectively immutable?
Answer: The map finds the bucket with `hashCode` and then confirms with `equals`. If two equal objects had different hash codes they would land in different buckets and the map would behave as if they were different. If a key's fields change after insertion its hash changes and the entry becomes unreachable, as the executed example shows (lookup returns null while the size stays at 1).

Question: Compare `ConcurrentHashMap`, `Collections.synchronizedMap` and `Hashtable`.
Answer: The last two use one lock for every operation, so threads queue. `ConcurrentHashMap` does lock-free reads and locks only a single bin on a write, offers atomic `compute`, `merge` and `putIfAbsent`, and has weakly consistent iterators. It forbids nulls. For compound operations across keys it provides no atomicity; the project's single-flight code uses one atomic `putIfAbsent` per key for exactly that reason.

Question: When would you choose a PriorityQueue and what are its pitfalls?
Answer: For repeated "take the smallest/largest" with insertions: scheduling, top-K, merging sorted streams. Pitfalls: the iterator is not ordered, `remove(Object)` is O(n), and mutating an element's priority after insertion does not re-heapify. The dsa-lab `LeastLoaded.release` has this cost, noted in its Javadoc, and the production least-loaded assignment avoids the heap by asking the database for counts and taking a stream minimum over at most 100 candidates.

Question: Why did the project not use `Collections.synchronizedMap` or `computeIfAbsent` for the cache single-flight?
Answer: `computeIfAbsent` runs the loader inside the map's bin lock. A loader that queries the database blocks every other key hashing to that bin and, if it re-enters the same map, throws or deadlocks. Using a future per key moves the slow work outside the lock and still guarantees one loader per key.
## A3. Generics

### What it is and why it exists

Generics let a class or method be written once against a type parameter (`List<T>`, `Supplier<T>`) and checked by the compiler for each use. They exist to replace casts with compile-time guarantees: a `List<TicketDto>` cannot receive a `String`, and no `ClassCastException` appears at a distance from the mistake.

### How it works internally

Java implements generics by erasure. The compiler checks the types, inserts casts where needed, and then erases type arguments: at run time `ArrayList<String>` and `ArrayList<Integer>` are the same class. Consequences: no `new T()`, no `instanceof List<String>`, no generic arrays, and no overloads that differ only in type arguments. Generic information survives only in signatures stored in class metadata (fields, method return types, supertypes), which reflection can read through `getGenericSuperclass()`, `getGenericReturnType()` and so on.

Variance is expressed with wildcards. `List<? extends Number>` is a producer: you can read `Number`s but not add. `List<? super Integer>` is a consumer: you can add `Integer`s but only read `Object`. The mnemonic is PECS: Producer Extends, Consumer Super. Arrays are covariant and checked at run time (`ArrayStoreException`), while generics are invariant and checked at compile time; mixing raw types with generics causes heap pollution, where a wrong element is discovered later as a `ClassCastException`.

### How THIS PROJECT uses generics

| Use | Where | What it demonstrates |
|---|---|---|
| Super type token `TypeReference<T>` | `CacheService.getOrLoad(UUID, String, Duration, TypeReference<T>, Supplier<T>)` | Because of erasure the cache cannot know `List<SlaPolicyDto>` at run time; the caller passes `new TypeReference<List<SlaPolicyDto>>() {}` and Jackson reads the type from the anonymous subclass |
| Generic record `PageResponse<T>` | `common/PageResponse.java` | One response envelope for every list endpoint |
| Generic helper `TenantContext.callAs(UUID, Supplier<T>)` | `tenancy/TenantContext.java` | Run any computation with the tenant bound, restore the previous binding in `finally` |
| Generic class `Graph<N>`, `TopologicalSort.sort(Graph<N>)` | `dsa-lab` | Algorithms independent of node type |
| Spring Data `JpaRepository<OutboxEvent, UUID>` | every repository | Type-safe CRUD generated at startup from the entity and id types |
| Reflection over generic types | `ArchitectureRulesTest.typesIn(Type)` | Walks `ParameterizedType` arguments so `PageResponse<TicketDto>` and `List<Entity>` are both checked |

> Project-implemented behaviour: `ArchitectureRulesTest.controllersNeverExposeEntities` scans every `@RestController`, reads `getGenericReturnType()` and `getParameterizedType()` of public methods, and recursively inspects type arguments to prove that no JPA `@Entity` ever appears in a controller signature. It is a real use of the one place where Java keeps generic information.

### Conceptual learning example (compiled and executed): `StreamsGenericsDemo.java` (generics part)

```java
static abstract class TypeRef<T> {
    final Type type;
    TypeRef() { type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0]; }
}
System.out.println(new TypeRef<List<Map<String, Integer>>>() {}.type);
```

Real output (generics lines of the same program):

```text
erasure, ArrayList<String>.class == ArrayList<Integer>.class: true
heap pollution surfaces later as ClassCastException
TypeRef captured: java.util.List<java.util.Map<java.lang.String, java.lang.Integer>>
PECS producer sum=6.5
PECS consumer sink=[1, 2]
```

The anonymous subclass `{}` is the trick: it creates a real class whose superclass signature records `TypeRef<List<Map<String,Integer>>>`, and that signature is retained.

### Implemented versus not implemented, extensions

The project uses generics for infrastructure and keeps the domain model plain. Not used: bounded generic types with recursive bounds (`<T extends Comparable<T>>`) in production code (only in dsa-lab), generic methods with explicit type witnesses, and custom collectors. A natural extension is a typed event bus: `interface EventHandler<E extends DomainEvent>`; Spring can inject `List<EventHandler<?>>` and, using `ResolvableType`, route by the event's type argument.

### Performance, security, mistakes, debugging

Erasure means no runtime cost for generics themselves, but boxing for primitives does: `Supplier<Long>` allocates. Security: type-token deserialisation must never be driven by attacker-chosen type names; `CacheService` documents "values must be Jackson-serialisable (records/DTOs), never entities" and uses a compile-time type, not a type name read from Redis. Common mistakes: raw types, `List<Object>` where `List<?>` was meant, unchecked casts hidden under `@SuppressWarnings`, and returning `? extends` collections to callers who then cannot add. Debugging: compile with `-Xlint:unchecked,rawtypes`; the executed example printed "uses unchecked or unsafe operations" exactly because it deliberately pollutes a list.

### Alternatives and trade-offs

Instead of a type token you can pass a `Class<T>` (fails for `List<X>`), or a `Function<String, T>` deserialiser (flexible, more code). Kotlin has reified inline functions; Java does not. Project cache values are always records, so the token covers every case.

### Interview questions

Question: What is type erasure and what limitations does it cause?
Answer: The compiler removes type arguments after checking, replacing them with bounds (usually `Object`) and inserting casts. So generic type arguments are not available at run time: you cannot create `new T()`, create `T[]`, test `instanceof List<String>`, or overload on type arguments. Class metadata keeps generic signatures, so frameworks such as Jackson recover them through anonymous subclasses like `TypeReference`, which `CacheService` uses.

Question: Explain `? extends` versus `? super` with an example.
Answer: `sum(Collection<? extends Number>)` can read numbers from any collection of a subtype but cannot add, because the compiler does not know which subtype it is. `fill(List<? super Integer>)` can add integers to a `List<Number>` or `List<Object>` but can only read `Object`. Remember PECS: if the argument produces values for you use extends; if it consumes values you give it use super.

Question: Why does `List<Object>` not accept a `List<String>`?
Answer: Generics are invariant. If it were allowed you could add an `Integer` through the `List<Object>` view and corrupt the original list. Arrays are covariant and pay for it with `ArrayStoreException` at run time.

Question: What is heap pollution?
Answer: A variable of a parameterised type that references an object of another parameterisation, usually via a raw type or an unchecked cast. The executed example adds an `Integer` to a `List<String>` through a raw reference; the failure appears later, as a `ClassCastException` where the element is read.

## A4. Streams and functional style

### What it is and why it exists

A `Stream` is a lazy pipeline over a source: zero or more intermediate operations (`filter`, `map`, `sorted`, `distinct`) and one terminal operation (`collect`, `forEach`, `findFirst`, `anyMatch`). It exists to express data transformations declaratively and to allow parallel execution without hand-written loops. It is not a collection and it can be consumed only once.

### How it works internally

A pipeline is a chain of stages; nothing runs until the terminal operation starts pulling. The terminal operation drives a spliterator over the source and pushes each element through the fused stages, so `filter(...).findFirst()` touches as few elements as needed (short-circuiting). Stateful operations such as `sorted` and `distinct` must buffer. Parallel streams split the source with `trySplit` and run tasks on the common `ForkJoinPool`, which is shared by the whole JVM; a blocking task there starves unrelated work. `Collectors` are mutable reductions (supplier, accumulator, combiner, finisher). `Collectors.toMap` throws `IllegalStateException` on a duplicate key unless a merge function is supplied. `Optional` is for return values, not for fields or parameters.

### How THIS PROJECT uses streams

The code contains about seventy stream pipelines, none parallel. Representative ones:

| Pipeline | File | Purpose |
|---|---|---|
| `candidates.stream().min(comparingLong(load).thenComparing(AppUser::getId))` | `WorkflowEngine` (ASSIGN_TO_ROLE_LEAST_LOADED) | Least-loaded agent, ties broken by id for determinism |
| `ctx.articles().stream().collect(toMap(KbCandidate::id, a -> a, (a, b) -> a))` | `AiAssistService.persist` | Index candidate articles by id so provider-invented ids are dropped |
| `policies.findByTenantId(t).stream().map(toDto).sorted(comparing(p -> p.priority().ordinal())).toList()` | `SlaService.list` | Ordered policy list stored in the cache |
| `WorkflowValidator.asList(c.value()).stream().anyMatch(v -> eq(actual, v))` | `WorkflowEngine` condition `IN` | Short-circuit membership test |
| `e.getConstraintViolations().stream().map(v -> new FieldErrorDto(path, message)).toList()` | `GlobalExceptionHandler` | Convert validation violations to the error schema |

> Project-implemented behaviour: stream results are returned as `toList()` (unmodifiable) or collected into maps; stream code never touches shared mutable state, which is the property that would make a later `parallel()` safe. The project chose sequential streams everywhere because requests are already concurrent (one thread per request) and the data sets are bounded by `PageParams.MAX_SIZE` (100) or explicit limits such as `PageRequest.of(0, 100)`.

### Conceptual learning example (compiled and executed): `StreamsGenericsDemo.java` (streams part)

```java
System.out.println(ts.stream().collect(Collectors.groupingBy(Ticket::status, Collectors.counting())));
Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> 1));            // IllegalStateException
Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> 1, (x, y) -> x)); // merge function, as in AiAssistService
```

Real output:

```text
groupingBy count: {OPEN=3, RESOLVED=1}
avg minutes by priority: {HIGH=45.0, LOW=90.0}
partitioningBy: {false=[1], true=[2, 3, 4]}
toMap duplicate: Duplicate key a (attempted merging values 1 and 1)
toMap with a merge function: {a=1}
filter x; -> first=x (lazy: only one element was touched)
parallel forEach into an ArrayList: size=100000 (expected 100000)
parallel collect(toList) is safe: 100000
```

An honest note on the last two lines: the program adds 100,000 integers to an `ArrayList` from a parallel stream, which is a data race. In this run the size happened to come out correct. A data race is allowed to look fine; on other runs or machines it can throw `ArrayIndexOutOfBoundsException` or lose elements. Never conclude "it works" from one execution of racy code. `collect(toList())` is the safe form because each thread fills its own container and the combiner merges them.

### Implemented versus not implemented, extensions

Not implemented: parallel streams, custom `Collector`s, `Stream.iterate` pipelines over unbounded sources, `flatMap`-heavy transformations, `Gatherers` (a later JDK feature). If the analytics service ever needed to aggregate a large in-memory list, a custom `Collector` that produces the day buckets in one pass would replace several `groupingBy` passes. Today `AnalyticsService` reads a bounded projection (`TicketRepository.aggregates`, with a `Pageable` limit) and aggregates in memory.

### Performance

Streams cost allocation (lambda objects, spliterators, boxing). For small collections the difference from a loop is irrelevant; for hot paths over primitives use `IntStream`/`LongStream`. Use parallel streams only for CPU-bound work on large, splittable sources (`ArrayList`, arrays, ranges) after measuring; never for I/O inside the common pool. `sorted()` on a large stream is O(n log n) memory plus time. Prefer pushing filtering and aggregation into SQL; the N+1 guard test shows the project's attitude: the number of statements must not grow with the page size.

### Security

Streams do not change the trust model, but two habits matter: never build a query or a command from stream-mapped untrusted strings (the workflow engine compares values as text only, and `WorkflowValidator.looksLikeExpression` rejects `${`, `#{`, `T(`, `{{`, `<%`), and bound every collection derived from user input before streaming it.

### Common mistakes and debugging

Reusing a stream (`IllegalStateException: stream has already been operated upon or closed`), side effects in `map`/`peek`, assuming order in parallel `forEach`, `toMap` without a merge function on non-unique keys, `Optional.get()` without a check, autoboxing in `reduce`, and catching exceptions inside lambdas by wrapping everything in `RuntimeException`. For debugging, `peek` shows what flows through a stage (remove it afterwards), and a heap/CPU profile shows whether lambdas dominate allocation.

### Alternatives and trade-offs

A plain loop is clearer for complex control flow and early exits with several outputs. Reactive streams (Reactor, used by Spring WebFlux) add back-pressure and async composition but require a non-blocking stack end to end; this project is servlet-based (blocking JDBC), so reactive would give no benefit on the database path. Database-side aggregation replaces many stream pipelines for large data.

### Interview questions

Question: What is the difference between intermediate and terminal operations, and what does lazy mean?
Answer: Intermediate operations return a new stream and only describe work; the terminal operation starts the traversal. Elements flow one at a time through all stages, so `filter(...).findFirst()` stops after the first match. The executed example printed only "filter x" for a three-element stream.

Question: When should you use a parallel stream?
Answer: When the work is CPU-bound, the source splits cheaply, the per-element cost is high enough to beat fork/join overhead, the operations are stateless and non-interfering, and nothing blocks. The common pool is shared JVM-wide, so a blocking call can hurt unrelated parts of the application. In a web server where each request already has a thread, parallel streams usually add contention rather than speed.

Question: `map` versus `flatMap`?
Answer: `map` transforms one element to one element; `flatMap` transforms one element to a stream and flattens the results, for example turning a list of tickets into a stream of all their tags.

Question: Why did `AiAssistService` give `toMap` a merge function?
Answer: Candidate article ids come from the database and should be unique, but a defensive `(a, b) -> a` guarantees that a duplicate cannot raise `IllegalStateException` in the middle of persisting a suggestion. The map is then used to drop any article id a provider invents that is not in the candidate list.

## A5. Concurrency: threads, executors, locks, CompletableFuture, virtual threads

### What it is and why it exists

Concurrency is the ability to make progress on several tasks whose execution overlaps. A web application is concurrent by default: the servlet container runs each request on a pool thread, and background work (schedulers, message listeners) runs beside it. Correctness depends on how shared state is protected; performance depends on how long threads wait.

### How it works internally

Threads and executors. A platform thread maps one-to-one to an operating-system thread with its own stack (about 1 MB reserved by default). `ThreadPoolExecutor` has a core size, a maximum size, a work queue and a rejection policy. A new task goes to a new core thread if fewer than core are running; otherwise it is queued; only when the queue is full does it start threads up to the maximum; if that is also exhausted the rejection handler runs (`AbortPolicy` throws `RejectedExecutionException`). An unbounded queue therefore means the maximum size is never used and backlog grows without limit.

Atomicity and visibility. `synchronized` provides mutual exclusion and a happens-before edge between unlock and the next lock of the same monitor. `volatile` provides visibility and ordering for one variable but no atomicity for compound actions (`count++` is read, add, write). `java.util.concurrent.atomic` classes use compare-and-set (a hardware instruction) for lock-free atomic updates. `ReentrantLock` adds `tryLock` with timeout, interruptible waits and fairness options.

Futures. `Future.get(timeout)` waits with a bound; `cancel(true)` interrupts the worker, which only helps if the task responds to interruption. `CompletableFuture` composes asynchronous stages (`thenApply`, `thenCombine`, `exceptionally`, `orTimeout`) and can be completed manually, which is how the cache single-flight uses it.

Virtual threads (Java 21, JEP 444). A virtual thread is a lightweight thread scheduled by the JVM onto a small pool of carrier platform threads. When it blocks on I/O or a `java.util.concurrent` lock, it unmounts and the carrier is free. They make "one thread per request" scalable for blocking code. They do not speed up CPU-bound work. Pinning can occur inside `synchronized` blocks around blocking calls in JDK 21 (improved in later JDKs).

### How THIS PROJECT uses concurrency

> Project-implemented behaviour: the AI call path in `AiAssistService` is the project's main hand-written concurrency design, shown in the sequence diagram below.

![Figure: AI provider call path with rate limiter, circuit breaker and bounded worker pool](docs/diagrams/png/pdf2A-ai-call-concurrency.png)

The pool is `new ThreadPoolExecutor(4, 4, 0, MILLISECONDS, new ArrayBlockingQueue<>(16), threadFactory, new AbortPolicy())`, with daemon threads named `ai-provider-N`. Reasoning: a slow or hung provider must not consume Tomcat request threads without bound. Four workers plus a queue of sixteen bound the backlog; the twenty-first concurrent request is rejected, and the service converts `RejectedExecutionException` into a degraded HTTP 200 with a fallback message and reason `overloaded`. The caller waits with `f.get(min(timeoutMs, remaining), MILLISECONDS)`, and on `TimeoutException` calls `f.cancel(true)`. Retries are bounded by `cx.ai.max-retries` (hard cap 3) inside `cx.ai.total-deadline-ms` (10 s). Interrupts are respected: `InterruptedException` re-sets the thread's interrupt flag with `Thread.currentThread().interrupt()` and stops retrying.

Other concurrency constructs in the code base:

| Construct | Where | Purpose |
|---|---|---|
| `AtomicInteger`/`AtomicLong` with CAS | `AiCircuitBreaker` | Failure counter, open-until timestamp, and a trial token so exactly one request is allowed through in half-open state (`trialToken.compareAndSet(0, now)`) |
| `volatile` mode, `AtomicInteger.getAndUpdate` | `AiFaultInjector` | Test hook visible across threads, fail-first-N counter |
| `volatile long skipRedisUntilNanos` | `RedisCacheService` | Back-off flag read by many threads, written on failure |
| `synchronized` on a bucket | gateway `TokenBucketLimiter`, `LoginRateLimiter.Bucket` | Per-key lock around a tiny read-modify-write; the map itself is concurrent |
| `CompletableFuture` per key | `CaffeineCacheService`, `RedisCacheService` | Single-flight loading |
| `ThreadLocal` | `TenantContext` | Tenant bound to the request thread, cleared in `finally`; MDC does the same for log context |
| Database CAS | `OutboxRepository.claim` | `UPDATE ... WHERE id=? AND (status='PENDING' ...)` returns 1 for exactly one relay; `locked_until` is a lease |
| Optimistic locking | `Ticket` `@Version` | Stale write becomes `ObjectOptimisticLockingFailureException`, mapped to 409 `TICKET_VERSION_CONFLICT` |
| Atomic SQL update | `TicketNumberAllocator` (`next_number = next_number + 1`) | Unique numbers without holding a row lock for the whole business transaction |
| `@Scheduled` | `OutboxRelay`, `SlaMonitorJob`, purge jobs | Single scheduler thread by default (see below) |

Not implemented: `@Async`, custom `ExecutorService` beans, `ReentrantLock`, `StampedLock`, `Semaphore`, `CountDownLatch`, reactive code, and virtual threads (no `spring.threads.virtual.enabled`). The web server uses Tomcat's platform thread pool (default maximum 200 threads in Spring Boot).

> Project-implemented behaviour, and a finding worth knowing: the application does not define a `TaskScheduler`, so Spring Boot's default scheduler with pool size 1 runs every `@Scheduled` method: `OutboxRelay.poll` (1 s), `SlaMonitorJob.scheduled` (60 s), `OutboxPurgeJob` (1 h), `TokenRevocationService.purgeExpired` (1 h) and the idempotency purge in `TicketCommandService`. A long SLA scan therefore delays the relay. The executed Part B example (B10) reproduces the effect. Multi-node deployments also run each job on every node; the code documents that overlapping runs are idempotent and that a leader lock (for example ShedLock) would avoid redundant work.

### Conceptual learning examples (compiled and executed): `ConcurrencyDemo.java`

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
// 5 submissions of a 300 ms task: 1 runs, 1 queues, 3 are rejected
try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10_000; i++) vt.submit(() -> { Thread.sleep(500); return 1; });
}
```

Real output (run 1 of the counter race used 4 threads times 2,000,000 increments each):

```text
expected 8000000 | plain=7961377 volatile=7954213 atomic=8000000 synchronized=8000000
bounded pool: accepted=2 rejected=3
timeout then cancel(true): isCancelled=true
thenCombine: 42
fallback after CompletionException/boom
orTimeout -> TimeoutException
10,000 virtual threads each sleeping 500 ms finished in 966 ms (wall clock, illustrative)
isVirtual=true
next task on the same pooled thread sees: tenant-A
after remove(): null
tryLock(100ms) while another thread holds it: false
```

How to read it. The counter line is the textbook lesson: plain and volatile counters lose updates (a second run printed 7994797 and 7856861; an earlier run with only 100,000 increments per thread happened not to lose any, which is why concurrency bugs survive tests), while atomic and synchronized are exact. The bounded pool accepted two tasks and rejected three, matching the AI pool design. The virtual-thread line shows ten thousand concurrent sleepers completing in about the sleep time; with a fixed pool of 200 platform threads the same work would need about 25 seconds. The `ThreadLocal` lines show why `TenantContext.clear()` and `MDC.clear()` are in `finally` blocks: the tenant of one task is still bound for the next task on the same pooled thread, which would be a cross-tenant leak.

### Performance

Size pools from the workload: CPU-bound work needs about the number of cores; I/O-bound work needs more, bounded by the downstream capacity. Queue length is a latency decision: a long queue hides overload. Lock contention shows as threads in `BLOCKED` or `WAITING` states in a thread dump; reduce critical sections, shard locks (the per-key bucket), or use atomics. The ticket-number allocator is the project's measured example: holding a row lock for the whole transaction serialised creates per tenant and produced multi-second tails at concurrency 10 to 20; a sub-millisecond atomic UPDATE in its own transaction fixed it (documented in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`).

### Security

Concurrency bugs are security bugs when they cross tenant boundaries. Rules visible in the code: the tenant is bound only by allow-listed classes; every unit of work clears it in `finally`; scheduled jobs bind the tenant explicitly through `TenantContext.callAs`; PostgreSQL row-level security uses `set_config('app.tenant_id', ?, true)` which is transaction-local, so a pooled connection cannot leak one tenant's id to the next borrower. Resource exhaustion is also a threat: the AI pool is bounded, the login limiter bounds tracked keys, and the circuit breaker stops hammering a failing provider.

### Common mistakes and debugging techniques

Mistakes: unbounded queues and `Executors.newFixedThreadPool` with its unbounded `LinkedBlockingQueue`; swallowing `InterruptedException`; `synchronized` around I/O; check-then-act on a `ConcurrentHashMap` (`if (!map.containsKey(k)) map.put(k, v)`); forgetting to clear a `ThreadLocal`; double-checked locking without `volatile`; nested locks in inconsistent order (deadlock); calling `Future.get()` without a timeout. Debugging: `jcmd <pid> Thread.print` or `jstack` shows thread states and detects deadlocks ("Found one Java-level deadlock"); `jcmd <pid> Thread.dump_to_file -format=json file` includes virtual threads; Java Flight Recorder shows lock contention and thread parking; metrics for executor queue size and rejections make saturation visible (the project counts `cx.ai.analyze{result=degraded|rate_limited|ok}`).

### Alternatives and trade-offs

| Option | Strength | Cost |
|---|---|---|
| Bounded `ThreadPoolExecutor` (used) | Explicit capacity, simple, predictable | Needs sizing; blocking calls occupy workers |
| Virtual threads | Cheap blocking, simple code | Pinning in `synchronized` (JDK 21), unbounded concurrency still needs limits (semaphores) toward downstreams |
| Reactive (Reactor) | Back-pressure, few threads | Whole stack must be non-blocking; harder debugging |
| Resilience4j | Mature circuit breaker, bulkhead, retry | Extra dependency; the project wrote a deliberately tiny breaker ("circuit-breaker-lite") |
| Message queue | Durable decoupling | Operational complexity; used for notifications via the outbox, not for the synchronous AI call |

### Interview questions

Question: Explain the difference between `synchronized`, `volatile` and `AtomicInteger`.
Answer: `synchronized` gives mutual exclusion and visibility for a block; `volatile` gives visibility and ordering for a single variable but `x++` is still three steps; `AtomicInteger` performs read-modify-write atomically with CAS and no lock. The executed example shows plain and volatile counters losing between about 5,000 and 145,000 of 8,000,000 increments across two runs while atomic and synchronized were exact.

Question: How does a `ThreadPoolExecutor` decide between queueing and creating threads?
Answer: Below core size it starts a thread; at or above core size it offers the task to the queue; only if the queue refuses (bounded and full) does it add threads up to maximum; if that fails the rejection policy runs. That is why an unbounded queue effectively fixes the pool at core size, and why the AI pool uses a bounded `ArrayBlockingQueue` with `AbortPolicy`: overload turns into a fast, explainable degraded response.

Question: How does the circuit breaker in this project work and what concurrency problem does it solve in half-open state?
Answer: After N consecutive failures `openUntil` is set to now plus the open duration and calls fail fast. When the time passes, many threads may arrive at once; `trialToken.compareAndSet(0, now)` lets exactly one through. Success closes the circuit; failure with a trial token set re-opens it. The CAS is what keeps the "one trial request" promise without a lock.

Question: Virtual threads or a bounded pool for the AI calls?
Answer: Virtual threads make blocking calls cheap, but the AI provider is an external service with a cost and a rate limit, so concurrency toward it must still be bounded; with virtual threads you would add a `Semaphore`. The bounded pool already provides that bound and the overload rejection, so switching would mean re-implementing the bulkhead. Virtual threads would be attractive for the request path generally (`spring.threads.virtual.enabled=true`), after checking for `synchronized` blocks that wrap blocking I/O.

Question: How would you find a deadlock in production?
Answer: Take two or three thread dumps a few seconds apart (`jcmd <pid> Thread.print`). Look for threads `BLOCKED` on monitors owned by each other; the JVM prints the deadlock cycle. Then fix lock ordering, shrink critical sections or use `tryLock` with a timeout. Database deadlocks are separate: read the database's deadlock log and make transactions touch rows in a consistent order.

Question: What happens to the tenant context when a task is handed to another thread?
Answer: A `ThreadLocal` does not follow the task. Work submitted to an executor sees no tenant (or a stale one on a reused thread). The AI provider call is deliberately pure and receives only sanitised data, so it needs no tenant. Anything that does need it must capture the tenant id value and rebind it with `TenantContext.callAs` inside the task, and clear it afterwards.
## A6. The Java Memory Model

### What it is and why it exists

The Java Memory Model (JMM, JLS chapter 17) defines which writes a thread is guaranteed to see when it reads a variable that another thread wrote. Modern CPUs and compilers reorder instructions and keep values in registers and per-core caches, so without rules a program that looks correct in source order can misbehave. The JMM states those rules in terms of the happens-before relation: if action A happens-before action B, then A's effects are visible to B and A is ordered before B.

### How it works internally

The main happens-before edges are:

| Edge | Meaning |
|---|---|
| Program order | Within one thread, each action happens-before later actions in that thread |
| Monitor | Unlock of a monitor happens-before every later lock of the same monitor |
| Volatile | A write to a volatile variable happens-before every later read of it |
| Thread start and join | `Thread.start()` happens-before the started thread's actions; the thread's actions happen-before `join()` returns |
| Final fields | After a constructor finishes, other threads see correctly initialised final fields without synchronisation (provided `this` did not escape) |
| Concurrent library actions | `ConcurrentHashMap.put` before a later `get`; `Future.get`; `CompletableFuture.complete` before `join`; executor `submit` before task start |
| Class initialisation | Static initialisers run once, under an initialisation lock |

Without such an edge a read can return a stale value, and a program that races is allowed to behave in surprising ways. A non-volatile flag polled in a loop may be hoisted out of the loop by the JIT, so the loop never ends. Safe publication means making an object reachable to other threads through one of the edges: store it in a volatile or final field, put it in a concurrent collection, or hand it over inside a synchronised block.

### How THIS PROJECT relies on the JMM

> Project-implemented behaviour: every cross-thread flag in the code is either volatile, atomic or protected by a lock.

| Field | Why it is safe |
|---|---|
| `RedisCacheService.skipRedisUntilNanos` (`volatile long`) | Written by a failing thread, read by every request thread; a single variable, no compound action |
| `AiFaultInjector.mode` (`volatile`) | Set by tests or an operator, read by worker threads |
| `AiCircuitBreaker` counters (`AtomicInteger`, `AtomicLong`) | CAS provides atomicity and visibility together |
| `CxProperties` and the nested config records | Final fields in records, published by the Spring container after construction; safe to read from any thread |
| `JwtService.key` (`private final SecretKey`) | Final field, so visible after construction |
| `LoginRateLimiter.Bucket.tryConsume` (`synchronized`) | Read-modify-write of two fields under one monitor |
| `InMemoryDocumentStore` bucket maps | `synchronized (bucket)` around every access, and `ConcurrentHashMap.computeIfAbsent` to publish the bucket |
| `CaffeineCacheService` future per key | `putIfAbsent` and `complete`/`join` give the happens-before edge between the loading thread and the waiting threads |

Why a lock-free read of `skipRedisUntilNanos` is acceptable: the value is a deadline in `System.nanoTime()` units; reading a slightly old value only means one extra Redis attempt. That reasoning, "the staleness is harmless", is the correct justification for a `volatile` flag; "it is probably fine" is not.

Not used: `VarHandle`, `Unsafe`, release/acquire modes, `StampedLock` optimistic reads. They are for library authors; the project uses Caffeine and `java.util.concurrent` instead.

### Conceptual learning example (compiled and executed): `JmmDemo.java`

```java
static boolean stopPlain;
static volatile boolean stopVolatile;
// worker: while (!stopPlain) n++;   main: sleep(300); stopPlain = true; worker.join(1500);
// holder idiom: static class Holder { static final Holder INSTANCE = new Holder(); }
```

Real output with the default JIT (HotSpot tiered compilation):

```text
non-volatile flag: worker still running after the stop signal = true
volatile flag    : worker still running after the stop signal = false
before first use of Holder
Holder initialised
true
```

Run again with `-Xint` (interpreter only, no JIT) the same program printed `false` for both flags: the visibility problem appeared only once the JIT optimised the loop. This is why "it works on my machine" and "it works in the debugger" prove nothing about visibility. The last lines show the initialisation-on-demand holder: the class `Holder` is initialised only on first use, and the JVM guarantees that initialisation happens once and is visible to all threads, so no `volatile` or locking is needed for a lazy singleton.

### Implemented versus not implemented, extensions

Implemented: correct flags, immutable config, no double-checked locking. Not implemented: structured publication via `VarHandle`, or lock-free queues written by hand. Extension idea: if the circuit breaker needed a sliding window of failures, a `LongAdder` array or Resilience4j would be the right tools; hand-rolling a window with plain `int[]` and a flag would reintroduce exactly the bugs this section describes.

### Performance, security, mistakes, debugging

Performance: volatile reads are cheap on x86 and volatile writes cost a store barrier; `synchronized` is cheap when uncontended (biased and thin locks were reworked in recent JDKs) and expensive when contended; false sharing between adjacent hot fields can cost real throughput, which `LongAdder` and `@Contended` address. Security: visibility bugs are the root of check-then-act races such as a revoked token being used after revocation on the same node. The project closes that one explicitly: `TokenRevocationService.revoke` writes the positive cache first and invalidates the negative one, and the Javadoc states the cross-node bound (negative TTL, default 3 seconds, configurable to zero).

Common mistakes: relying on `sleep` for ordering, publishing `this` from a constructor, using a plain `boolean` stop flag, using `volatile` for compound updates, double-checked locking without `volatile`, and mutable static state. Debugging: use `-Xint` to see whether behaviour changes, add jcstress for concurrency litmus tests, run with a stress thread count, and read thread dumps; reproduction of data races is probabilistic, so design reviews matter more than testing.

### Alternatives and trade-offs

Immutability removes the whole class of problems and should be the first choice (records, `List.copyOf`). Thread confinement (`ThreadLocal`, one-writer designs) is next. Locks are simple and correct but can contend; atomics scale for single variables; message passing (queues, the outbox) avoids shared state entirely. The platform uses all four, in that order of preference.

### Interview questions

Question: What does `volatile` guarantee and what does it not?
Answer: It guarantees that a write is visible to subsequent reads of the same variable by other threads and that reads and writes are not reordered across it in ways that break happens-before. It does not make compound actions atomic: `count++` can still lose updates, as the executed counter race showed (volatile lost about 46,000 and 143,000 increments in two runs). Use atomics or locks for read-modify-write.

Question: What is the happens-before relationship?
Answer: A partial order on actions. If A happens-before B, A's writes are visible to B. The rules include program order, monitor unlock before lock, volatile write before read, thread start/join, final-field semantics and the guarantees of `java.util.concurrent`. If two conflicting accesses are not ordered by happens-before, the program has a data race and the JMM allows surprising results.

Question: Why is double-checked locking broken without `volatile`?
Answer: Another thread can see a non-null reference to an object whose fields are not yet written, because the reference assignment can be reordered before the constructor's writes. Declaring the field `volatile` forbids that reordering. The simpler alternatives are the holder idiom (shown above), an enum singleton, or letting the container manage singletons, which is what Spring does.

Question: Why are records and final fields safe to share?
Answer: The JMM gives final fields a freeze action at the end of the constructor: any thread that obtains a reference sees the constructed values, even if the reference was published through a data race, provided `this` did not escape during construction. That is why `CxProperties` and the DTOs can be read from many request threads without locks.

Question: A request thread sets the tenant in a `ThreadLocal`; a worker thread must read it. What is wrong?
Answer: `ThreadLocal` values are per thread and are not inherited by pool threads that already exist. Capture the value (an immutable `UUID`) and pass it into the task, then rebind and clear it inside the task, as `TenantContext.callAs` does. Never share the `ThreadLocal`'s holder object between threads.

## A7. Memory management and garbage collection

### What it is and why it exists

The JVM manages memory automatically. Objects are allocated on the heap, and a garbage collector (GC) reclaims objects that are no longer reachable from the GC roots (thread stacks, static fields, JNI handles). This removes whole classes of bugs (use-after-free, double free) at the cost of pauses, memory overhead and tuning knobs.

![Figure: JVM memory areas and the generational flow of objects](docs/diagrams/png/pdf2A-jvm-memory-and-gc.png)

### How it works internally

Memory areas. The heap holds objects. Each platform thread has a stack of frames (local variables, operand stack, return addresses); stacks are not garbage collected and overflow as `StackOverflowError`. Metaspace (native memory) holds class metadata and is bounded by `-XX:MaxMetaspaceSize` if set. The code cache holds JIT-compiled machine code. Direct buffers and native libraries use memory outside the heap, which is why a process can be killed by the operating system or the container runtime even when the Java heap looks fine.

Generations. Most objects die young (the weak generational hypothesis). Allocation happens in thread-local allocation buffers in Eden, which makes `new` a pointer bump. A young collection copies live objects from Eden and one survivor space into the other survivor space or promotes them to the old generation after reaching an age threshold. Old collections are rarer and more expensive.

Collectors in JDK 21:

| Collector | Flag | Design | Typical use |
|---|---|---|---|
| Serial | `-XX:+UseSerialGC` | Single-threaded, stop-the-world | Very small heaps, single CPU |
| Parallel | `-XX:+UseParallelGC` | Multi-threaded stop-the-world, throughput focus | Batch jobs |
| G1 | default on server-class machines | Heap split into regions; concurrent marking; mixed collections; pause goal `-XX:MaxGCPauseMillis` (200 ms default) | General services |
| ZGC | `-XX:+UseZGC` (generational in 21 with `-XX:+ZGenerational`) | Concurrent, sub-millisecond pauses, coloured pointers | Large heaps and tight latency |
| Shenandoah | `-XX:+UseShenandoahGC` (not in every distribution) | Concurrent compaction | Low-latency services |

G1 treats arrays of at least half a region size as humongous objects that are allocated directly in old regions; frequent humongous allocation causes fragmentation and extra collections (visible in the executed log below as "G1 Humongous Allocation").

Reachability and references. Strong references keep objects alive. `WeakReference` is cleared when the object is only weakly reachable (the basis of `WeakHashMap`, and of Caffeine's weak keys option). `SoftReference` is cleared under memory pressure. `PhantomReference` and `Cleaner` support post-mortem cleanup. Finalisers are deprecated for removal.

Leaks in a garbage-collected language mean unintended strong reachability: static collections that only grow, caches without bounds, listeners never removed, `ThreadLocal` values on pooled threads, and class loaders kept alive by a thread or a JDBC driver registration.

### How THIS PROJECT manages memory

> Project-implemented behaviour: what the repository actually configures.

| Item | Setting | Source |
|---|---|---|
| Container heap sizing | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | `infra/docker/core.Dockerfile`, gateway and notification Dockerfiles (not built here, no Docker) |
| Collector | JDK default (G1) | No collector flag anywhere; the performance guide records "default G1 GC, no -Xmx, no JVM tuning" for the local runs |
| Runtime image | `eclipse-temurin:21-jre` as a non-root `app` user | `core.Dockerfile` |
| Bounded caches | Caffeine `maximumSize` for tenant cache (`cx.cache.max-entries` 10,000), revocation caches (50,000), AI rate windows (10,000), login buckets (`max-tracked-keys` 10,000) | `CxProperties.Cache`, `TokenRevocationService`, `AiRateLimiter`, `LoginRateLimiter` |
| Bounded result sets | `PageParams.MAX_SIZE` 100; `Pageable` limits in analytics, SLA scan and KB candidates | `PageParams`, `TicketRepository` |
| Bounded in-flight state | AI pool queue 16, outbox batch 50, purge batches 500 | `AiAssistService`, `CxProperties.Outbox` |
| JVM metrics | `jvm.memory.used`, `jvm.gc.pause` exposed on `/actuator/prometheus`; a Grafana panel sums `jvm_memory_used_bytes{area="heap"}` | `infra/grafana/dashboards/cx-platform-overview.json` |

`-XX:+ExitOnOutOfMemoryError` is a common operational choice (inferred from the flag, the repository gives no written rationale): after an `OutOfMemoryError` the JVM state is unreliable, so the process exits and the container orchestrator restarts it instead of limping on. `MaxRAMPercentage=75` leaves room for metaspace, thread stacks and direct memory inside the container limit.

Not implemented: GC logging (`-Xlog:gc*`), heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`), `-XX:MaxMetaspaceSize`, native memory tracking, a GC choice. All are one-line additions to `JAVA_TOOL_OPTIONS`; the first two are what an operator wants before the first incident, with the caveat that a heap dump contains tenant data and must be treated as confidential.

Measured evidence in the repository: the performance guide reports `jvm_gc_pause_seconds_max` = 0.010 s and a total young-GC pause of 0.451 s over 101 pauses in the load test, and uses that to rule out GC as the cause of multi-second tail latency. That is the right use of GC metrics: eliminate hypotheses with data before tuning.

### Conceptual learning example (compiled and executed): `GcDemo.java`

The program prints the class loaders of a JDK class and an application class, shows that loading a class literal does not initialise it, counts young collections caused by allocating about 100 MB of short-lived arrays, clears a `WeakReference` after `System.gc()`, and finally exhausts a 64 MB heap. Run with `java -Xmx64m -Xlog:gc:stdout GcDemo`:

```text
[0.166s][info][gc] Using G1
String loader: null | GcDemo loader: AppClassLoader | its parent: PlatformClassLoader
have the class literal (loaded, not yet initialised)
[class init] Holder
collector: G1 Young Generation
collector: G1 Concurrent GC
collector: G1 Old Generation
[0.620s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 22M->2M(64M) 33.765ms
[0.634s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 34M->2M(64M) 4.472ms
collections triggered by ~100 MB of short-lived arrays: 4 (kept 4 arrays)
weak.get() != null while strongly reachable: true
[0.683s][info][gc] GC(4) Pause Full (System.gc()) 17M->2M(10M) 8.596ms
weak.get() != null after unreachable + System.gc() (a hint, not a guarantee): false
[0.797s][info][gc] GC(5) Pause Young (Concurrent Start) (G1 Humongous Allocation) 3M->3M(10M) 0.830ms
[0.825s][info][gc] GC(8) Pause Remark 50M->50M(64M) 1.239ms
[0.833s][info][gc] GC(10) Pause Full (G1 Compaction Pause) 50M->50M(64M) 6.124ms
caught: Java heap space
```

Reading the log: `22M->2M(64M)` means the heap occupied 22 MB before the pause, 2 MB after, with 64 MB committed; almost everything allocated was garbage, which is why young collections are cheap. The final lines show the signature of a real leak or an undersized heap: collections that free nothing (`50M->50M`), full compactions back to back, humongous allocations of 8 MB arrays, and then `OutOfMemoryError: Java heap space`. With `-XX:+UseSerialGC` the same program reported the collectors `Copy` and `MarkSweepCompact` and also caught the error. Pause times in this log are one laptop run and illustrative only.

### Performance

Allocation rate and live-set size drive GC cost, not heap size alone. Young pauses scale with live objects, not garbage. To reduce GC pressure: avoid large temporary collections, stream or page large results (the project caps page size at 100), reuse immutable objects, and avoid boxing in tight loops. Do not call `System.gc()` in production. For latency-sensitive services consider ZGC after measuring; for memory-constrained containers keep the heap well below the container limit.

### Security

Heap dumps and core files contain secrets (JWT signing key, tokens, tenant data); restrict where they are written and who can read them. `-XX:+ExitOnOutOfMemoryError` plus bounded caches limits denial of service through memory: the attacker-controlled maps (login keys, AI windows) are bounded, and the notification and AI payloads are length-capped (`AiSanitizer` bounds title to 200 and description to 4000 characters). Do not log objects with `toString` that include secrets; the project's `Redaction` helper masks details before they are stored in audit rows.

### Common mistakes and debugging techniques

Mistakes: unbounded static caches; `ThreadLocal` without `remove`; forgetting that the container limit is not the heap limit (metaspace, stacks, direct memory add up); setting `-Xmx` equal to the container limit; ignoring `OutOfMemoryError: unable to create native thread` (too many threads) or `Metaspace`. Techniques: `jcmd <pid> GC.heap_info`, `jcmd <pid> GC.class_histogram`, `jcmd <pid> GC.heap_dump file.hprof` then analyse in Eclipse MAT looking for dominator trees; `-Xlog:gc*,safepoint:file=gc.log:time,uptime` for GC logging; Java Flight Recorder (`jcmd <pid> JFR.start duration=60s filename=rec.jfr`) for allocation profiling; compare `jvm_memory_used_bytes` over time to distinguish a leak (sawtooth with rising floor) from a large steady live set.

### Alternatives and trade-offs

| Choice | Benefit | Cost |
|---|---|---|
| G1 default (used) | Balanced, no tuning | Pauses of tens of milliseconds, humongous allocation sensitivity |
| ZGC | Very low pauses, scalable heaps | Higher CPU and memory overhead |
| Shorter-lived containers with a restart policy | Hides slow leaks | Not a fix; loses warm caches |
| Off-heap caches (Redis) | Survive restarts, shared across nodes | Network latency, serialisation |
| GraalVM native image | Fast start, low memory | Closed-world constraints, reflection configuration, no JIT peak performance |

### Interview questions

Question: How does the garbage collector decide an object is garbage?
Answer: By reachability from GC roots: thread stacks, static fields, JNI references and active class loaders. Anything not reachable by a chain of strong references can be reclaimed. Reference counting is not used, so cycles are not a problem. In the executed example the object became collectable once the last strong reference (`o = null`) was dropped, and `WeakReference.get()` then returned null after a collection.

Question: Describe the generational heap and why it exists.
Answer: Most objects die soon after creation, so the heap is split into a young generation, collected often and cheaply by copying survivors, and an old generation collected rarely. Surviving objects are aged and promoted. The cost of a young collection is proportional to live data, not to the amount of garbage, which is why high allocation rates of short-lived objects are tolerable.

Question: A container keeps being killed with exit code 137 although the Java heap is only 60 percent full. Why?
Answer: The container limit applies to the whole process: heap plus metaspace, thread stacks, code cache, direct buffers and native libraries. With `MaxRAMPercentage=75` the heap alone may use 75 percent, leaving 25 percent for everything else; many threads or large direct buffers can exceed it. Check with native memory tracking (`-XX:NativeMemoryTracking=summary`, `jcmd <pid> VM.native_memory`) and reduce the thread count or heap percentage.

Question: How would you find a memory leak?
Answer: Confirm with monitoring that the post-GC floor keeps rising. Take a class histogram twice and compare; take a heap dump and open it in MAT; look at the dominator tree and the path to GC roots for the largest retained sets. Typical culprits: static maps, unbounded caches, listeners, `ThreadLocal` values on pool threads, and classes held by a class loader.

Question: What is a humongous allocation in G1?
Answer: An allocation of at least half a region (regions are 1 to 32 MB depending on heap size). It is placed directly in contiguous old regions, can trigger early concurrent cycles and fragmentation, and is visible in logs as "G1 Humongous Allocation", as seen when the demo allocated 8 MB arrays. Mitigate by avoiding huge temporary arrays or enlarging the region size.

## A8. JVM execution model and class loading

### What it is and why it exists

A Java program is compiled by `javac` into platform-independent bytecode in `.class` files. The JVM loads those classes, verifies them, interprets bytecode, and compiles hot methods to machine code at run time with the JIT compilers. This gives portability, security checks (the verifier) and performance that improves while the program runs.

### How it works internally

Class loading has three phases. Loading finds the binary form through a class loader and defines the class. Linking verifies the bytecode, prepares static fields with default values, and optionally resolves symbolic references. Initialisation runs static initialisers and static field initialisers exactly once, under a lock, when the class is first actively used (instance creation, static method call, non-constant static field access).

The built-in class loaders form a delegation hierarchy: the bootstrap loader (core `java.base` classes, shown as `null` by `getClassLoader()`), the platform loader (other JDK modules), and the application (system) loader for the class path. A loader first asks its parent and only then tries to define the class itself (parent-first delegation), which prevents application code from replacing `java.lang.String`. A class is identified by its name plus its defining loader, so the same bytes loaded by two loaders are two distinct types (the source of `ClassCastException: X cannot be cast to X`). Spring Boot's executable jar uses its own `LaunchedURLClassLoader` to read dependency jars nested inside the fat jar.

Execution starts in the interpreter. HotSpot counts method invocations and loop back-edges; hot methods are compiled by C1 (fast, lightly optimised) and then by C2 (slower to compile, aggressively optimised with inlining, escape analysis and speculative optimisations backed by deoptimisation). This is tiered compilation, the reason benchmarks need warm-up and why the first requests after a deployment are slower. Escape analysis can allocate short-lived objects on the stack or remove them entirely (scalar replacement). The `-Xint` flag disables the JIT, and the JMM example above shows that behaviour can differ between the interpreter and compiled code.

The JVM spec also defines the runtime data areas (see A7), the bytecode instruction set (`invokevirtual`, `invokeinterface`, `invokedynamic`, which implements lambdas and string concatenation), and the class file constant pool.

### How THIS PROJECT is affected by it

> Project-implemented behaviour: the project builds a Spring Boot fat jar (`spring-boot-maven-plugin`, Lombok excluded from the jar) and runs it with `java -jar /app/app.jar` in the Docker image (`ENTRYPOINT ["java","-jar","/app/app.jar"]`).

Consequences you can explain in an interview:

- Startup time includes class loading and JIT warm-up of Spring, Hibernate and Jackson; the performance guide notes that its load test ran about 25 seconds after JVM start so JIT warm-up was only partly excluded.
- Flyway finds migrations under `classpath:db/migration` (and `classpath:db/migration-pg` in the docker profile) through the class loader, so the same code works from an IDE class path and from inside the fat jar.
- `TenantContext` uses static state; static state lives per class loader, which is irrelevant in a single-application jar but would matter in an application server with several deployments.
- `Class.forName(bd.getBeanClassName())` in `ArchitectureRulesTest` loads controller classes by name after scanning the class path with `ClassPathScanningCandidateComponentProvider`.
- Lombok (`@Getter`, `@Setter`, `@NoArgsConstructor` on entities) runs at compile time as an annotation processor and writes ordinary bytecode; it has no run-time footprint, and that is why it is excluded from the packaged jar.
- Records and `sealed` types are compile-time features that appear in bytecode as `Record` attributes; frameworks (Jackson, Spring's property binder) read the canonical constructor reflectively, which is why `@ConfigurationProperties` can bind to `CxProperties` records.
- The `-parameters` compiler flag (applied by Spring Boot's parent POM) keeps constructor and method parameter names in the class file, which Spring uses for `@RequestParam` without an explicit name and for binding record components.

Not implemented: custom class loaders, `ServiceLoader` plugins, Java agents, CDS archives (class data sharing can cut startup), AOT or GraalVM native image, `-XX:TieredStopAtLevel=1`, explicit JIT warm-up. Spring Boot 3.3 supports AppCDS and AOT processing; neither is configured.

### Conceptual learning example (compiled and executed): class initialisation order (from `GcDemo.java` and `JmmDemo.java`)

The output shown in A6 and A7 demonstrates three class-loading facts: `String.class.getClassLoader()` is `null` (bootstrap), the application class is loaded by `AppClassLoader` whose parent is `PlatformClassLoader`, and a class literal does not run static initialisers (`[class init] Holder` appears only on the first static call). The holder idiom printed "before first use of Holder" before "Holder initialised".

### Performance

Fast startup needs fewer classes loaded and less reflection: component scanning, proxies and Hibernate metadata dominate Spring startup. Options: lazy initialisation (`spring.main.lazy-initialization`, trading startup for first-request latency), AppCDS, AOT/native. Peak performance needs warm code; avoid code shapes that defeat inlining (huge methods, megamorphic call sites) in hot paths. Do not benchmark with a single run; use JMH.

### Security

The verifier and delegation model protect the runtime from malformed or malicious bytecode and from replacing core classes. Deserialisation of untrusted data and dynamic class loading from untrusted locations are the main risks: the project avoids Java serialisation entirely (Jackson with explicit DTOs, fixed type tokens), rejects expression syntax in workflow values (`looksLikeExpression`), and refuses to follow redirects in the HTTP AI provider (`followRedirects(NEVER)`). Keep the JDK patched: the Docker base image is `eclipse-temurin:21-jre`, which should be rebuilt regularly.

### Common mistakes and debugging

Mistakes: static initialiser that throws (`ExceptionInInitializerError`, then `NoClassDefFoundError` forever); circular static initialisation; relying on finalisers; assuming `Class.forName` and `ClassLoader.loadClass` behave the same (the first initialises); shading or duplicating a library so two copies are loaded by different loaders. Debugging: `-verbose:class` or `-Xlog:class+load` prints each class load with its source; `-Xlog:class+init`; `jcmd <pid> VM.classloader_stats`; `javap -c -p MyClass` shows bytecode; `-XX:+PrintCompilation` shows JIT activity; `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` explains inlining decisions.

### Alternatives and trade-offs

| Option | Startup | Peak throughput | Notes |
|---|---|---|---|
| HotSpot JIT (used) | Slow | Best | Warm-up needed |
| AppCDS | Faster | Same | Build-time archive step |
| Spring AOT + GraalVM native image | Fastest, low memory | Often lower | Reflection and proxy configuration, longer builds |
| CRaC (checkpoint/restore) | Very fast | Same | Needs a supporting JDK and an operational workflow |

### Interview questions

Question: Explain class loading and the delegation model.
Answer: A class loader locates bytes for a class name and defines it. Loaders delegate to their parent first, so `java.lang.*` always comes from the bootstrap loader and cannot be spoofed by application code. A class's identity is its name plus its defining loader. Linking verifies and prepares the class; initialisation runs static initialisers once, lazily, on first active use, as the executed example shows.

Question: What is the JIT compiler and what is tiered compilation?
Answer: The JIT translates hot bytecode to optimised machine code at run time using profile data. Tiered compilation starts in the interpreter, moves hot code to C1 quickly, and the hottest to C2 with aggressive optimisations such as inlining and escape analysis. It explains warm-up and why `-Xint` can change the behaviour of racy code.

Question: What does `ExceptionInInitializerError` followed by `NoClassDefFoundError` mean?
Answer: A static initialiser threw. The first use fails with `ExceptionInInitializerError`; the class is then marked erroneous, so every later use fails with `NoClassDefFoundError: Could not initialize class X`. Fix the first stack trace, which is usually far back in the logs.

Question: Why is Lombok excluded from the packaged jar?
Answer: It is only an annotation processor used at compile time; the generated getters and setters are normal bytecode. Including it would add an unused dependency, so the Boot plugin excludes it.

Question: How would you speed up the startup of this service?
Answer: Measure first (`-Xlog:class+load`, Spring's startup actuator). Then consider AppCDS for the application, lazy beans for rarely used modules (KB, AI), trimming auto-configuration the local profile does not need (the project already excludes Rabbit, Redis and Mongo auto-configuration in the `local` profile), and, for a radical change, Spring AOT with a native image, accepting the reflection-configuration cost.
## A9. Exceptions and error handling

### What it is and why it exists

An exception is an object that represents an abnormal condition and transfers control to the nearest matching handler, unwinding the stack on the way. The Java hierarchy has `Throwable` at the root with two branches: `Error` (JVM-level problems such as `OutOfMemoryError`, `StackOverflowError`; do not catch them to continue) and `Exception`. Under `Exception`, `RuntimeException` and its subclasses are unchecked; every other exception is checked and must be declared or caught. Exceptions exist so that error handling is separated from the normal flow and cannot be silently ignored like a return code.

### How it works internally

When `throw` executes, the JVM looks up the current method's exception table for a handler covering the bytecode index and matching the exception class; if none, the frame is popped and the search continues in the caller. A `finally` block is compiled into duplicated code paths so it runs on normal and exceptional exits. `try-with-resources` closes resources in reverse order and attaches exceptions thrown by `close()` as suppressed exceptions on the primary one (`getSuppressed()`). The expensive part of an exception is `fillInStackTrace`, which walks the stack when the object is constructed; throwing is not the cost, creating deep stack traces is. Exceptions used for control flow in hot paths are therefore a performance smell.

### Design guidance that matters in practice

Use unchecked exceptions for programming errors and for failures the caller cannot reasonably handle; use checked exceptions rarely, where the caller can recover and the language should force the decision. Wrap with the cause (`new X(msg, cause)`) so no information is lost. Catch the narrowest type at the level that can act; translate at boundaries (a service-layer exception becomes an HTTP status in one place). Never swallow `InterruptedException`: restore the flag. Never `return` from `finally`.

### How THIS PROJECT uses exceptions

> Project-implemented behaviour: one exception type for expected business failures, one handler for the HTTP mapping, specific types for infrastructure failures.

| Type | File | Role |
|---|---|---|
| `ApiException(ErrorCode, message, fieldErrors)` | `common/ApiException.java` | Unchecked; carries a machine-readable `ErrorCode` (status plus text); factories `notFound`, `forbidden`, `validation`, `conflict`. 404 is also used for cross-tenant ids so existence is never revealed |
| `RateLimitedException(message, retryAfterSeconds)` | `common/RateLimitedException.java` | Becomes HTTP 429 with a `Retry-After` header |
| `AiProviderException(message, retryable)` | `ai/AiProviderException.java` | The `retryable` flag drives the retry loop; non-retryable ends it immediately |
| `NonRetryableException` | `notification-service/.../NonRetryableException.java` | Poison input: `RetryPolicy.decide` sends it straight to the dead-letter queue |
| `IllegalStateException` | `TenantContext.require`, `StartupGuards`, `JwtService` constructor | Invariant violations and refusal to start with unsafe configuration |
| `IllegalTransactionStateException` (Spring) | raised by `OutboxService.publish` through `Propagation.MANDATORY` | An event can never be written outside a transaction |
| `ObjectOptimisticLockingFailureException`, `DataIntegrityViolationException` | mapped in `GlobalExceptionHandler` | Concurrent edit becomes 409 `TICKET_VERSION_CONFLICT`; constraint violation becomes 409 `CONFLICT` with a generic message and a server-side warning log |

Patterns worth reciting:

- Fail fast at startup: `JwtService` throws if the secret is shorter than 32 bytes; `StartupGuards` (docker profile) throws if the JWT secret is the development default; `AiFaultInjector` throws if a fault mode is active in the docker profile.
- Never leak internals: the catch-all handler logs the stack trace with the correlation id and returns the fixed message "Unexpected error". `server.error.include-stacktrace` and `include-message` are `never`.
- Resilient loops: `OutboxRelay.poll` catches `RuntimeException` and logs it so one bad poll cannot kill the scheduler (a thrown exception from a `@Scheduled` method is logged by Spring and the schedule continues, but explicit handling gives better messages and metrics). `SlaMonitorJob.scanTenant` catches per tenant, increments `cx.sla.job.errors` and continues with the next tenant.
- Degrade instead of error: `AiAssistService.callProvider` never throws; every failure becomes an `Outcome` with a reason (`timeout`, `provider_error`, `overloaded`, `circuit_open`, `deadline`, `interrupted`), and the controller answers HTTP 200 with `degraded: true`.
- Interrupt discipline: `InterruptedException` handlers call `Thread.currentThread().interrupt()`.
- Retry arithmetic: `OutboxRelay.fail` computes `min(300, base << min(10, attempts-1))` seconds (base 2 s by default) and marks the row `FAILED` after `cx.outbox.max-attempts` (8). Unreadable payloads are failed permanently at once.

Not implemented: custom checked exceptions, exception chaining across modules with cause codes, `@ResponseStatus`-annotated exceptions, Problem Details (RFC 9457) responses (explicitly disabled by `spring.mvc.problemdetails.enabled=false` because the project defines its own error schema, covered in B6), and retries through Spring Retry (the project writes its own bounded loops).

### Conceptual learning example (compiled and executed): `ExceptionsDemo.java`

```java
try (Res a = new Res("A"); Res b = new Res("B")) { throw new ApiException(Code.NOT_FOUND, "Ticket not found"); }
catch (ApiException e) { for (Throwable s : e.getSuppressed()) System.out.println("suppressed: " + s.getMessage()); }
```

Real output:

```text
open A
open B
close B
close A
primary: Ticket not found (404)
suppressed: close-failed-B
suppressed: close-failed-A
return in finally swallows the exception: 1
multi-catch: NumberFormatException
cause preserved: disk
CCE is an Error? false, a RuntimeException? true
StackOverflowError caught (an Error, not an Exception)
```

Resources close in reverse order, the primary exception survives, and the exceptions thrown by `close()` are attached as suppressed. The `finally { return 1; }` line proves that a `return` inside `finally` discards the in-flight exception; static analysers flag it for that reason.

### Performance, security, mistakes, debugging

Performance: avoid creating exceptions for ordinary control flow in hot loops; if you must, preallocate or override `fillInStackTrace`. Security: error responses are an information-disclosure channel. The project returns `Authentication required` and `Access denied` without saying which check failed, answers cross-tenant lookups as 404, and writes the correlation id so support can find the full stack in the logs. Also sanitise what goes into exception messages that are logged (user-supplied text can forge log lines; `CorrelationIdFilter` validates inbound ids against `^[A-Za-z0-9._-]{1,64}$` for that reason).

Common mistakes: `catch (Exception e) {}`; logging and rethrowing the same exception at every layer (duplicate noise); losing the cause; using exceptions for validation of expected input in hot paths; catching `Throwable`; declaring `throws Exception` on public APIs; mixing checked exceptions with `@Transactional` without knowing the rollback rule (B7). Debugging: read the `Caused by:` chain from the bottom; keep full stack traces in logs and not in responses; search logs by `corr=`; use a conditional breakpoint on the exception constructor to find where an exception was created; enable `-XX:+ShowCodeDetailsInExceptionMessages` (default on in recent JDKs) to get helpful `NullPointerException` messages.

### Alternatives and trade-offs

Result types (`Either`, sealed `Ok`/`Err` interfaces) make failures explicit in signatures and avoid stack-trace cost, at the price of verbosity and no framework integration (Spring's transaction rollback and `@ExceptionHandler` are exception-driven). Checked exceptions document recoverable conditions but infect signatures and lambdas. The pragmatic mix used here: unchecked business exceptions mapped centrally; result-like records for the AI outcome where failure is a normal state.

### Interview questions

Question: Checked versus unchecked exceptions: which do you choose and why?
Answer: Unchecked for most things, because callers usually cannot recover and forced `throws` clauses spread through layers. Checked only when the caller has a meaningful recovery and the compiler should force that decision. This project's business failures are `ApiException` (unchecked, with an `ErrorCode`), mapped to HTTP in one `@RestControllerAdvice`. A consequence for Spring: by default `@Transactional` rolls back on unchecked exceptions and errors but commits on checked ones, which the executed Part B example shows.

Question: What happens if both the try block and `close()` throw?
Answer: With try-with-resources the exception from the try block is the primary; exceptions from `close()` are added with `addSuppressed`. With a hand-written `finally` the exception from `finally` replaces the original and the original is lost, which is why try-with-resources is preferred.

Question: How do you design an error response contract?
Answer: Stable machine-readable codes, an HTTP status derived from the code, a human message that never leaks internals, a path, a timestamp, a correlation id, and a list of field errors for validation. The platform's schema is `{timestamp, status, error, code, message, path, correlationId, fieldErrors[]}` with `NON_NULL` serialisation; every filter-level failure (401, 403, 429 from the gateway) uses the same writer so clients parse one shape.

Question: Why does `OutboxRelay.poll` catch `RuntimeException` itself?
Answer: So the failure is logged with a clear message and cannot propagate into the scheduling infrastructure. It also documents the policy: a failed poll is not fatal; the next tick retries. Per-row failures are handled separately with attempts, backoff and a `FAILED` state, so one poison event never blocks the queue.

Question: What is the difference between `Error` and `Exception`, and would you catch `OutOfMemoryError`?
Answer: `Error` signals conditions the application normally cannot handle. Catching `OutOfMemoryError` rarely helps because allocation may fail again; the project instead configures `-XX:+ExitOnOutOfMemoryError` so the process restarts cleanly. The executed GC demo caught it only to show the message.

## A10. Data structures, algorithms and complexity in the platform

### What it is and why it exists

Complexity analysis describes how time and memory grow with input size, using Big-O notation for an upper bound. In a multi-tenant service the inputs that matter are the number of tenants, tickets per tenant, rules per workflow, and requests per second. Choosing an algorithm is mostly about bounding the work per request and per background run so that one large tenant cannot degrade everyone else.

### The algorithms that actually run in the platform

> Project-implemented behaviour: the table separates what runs in production code from what exists only in `dsa-lab` for learning. The `dsa-lab` README uses the same distinction ("Used" versus "Educational").

| Algorithm | Production location | Complexity | Notes |
|---|---|---|---|
| Token bucket, lazy refill | Gateway `TokenBucketLimiter`; core `LoginRateLimiter` | O(1) per request, O(keys) memory, keys bounded | Same algorithm as `dsa-lab` `TokenBucket`; buckets stored in a concurrent map (gateway) or Caffeine (`LoginRateLimiter`) |
| Fixed-window counter | `AiRateLimiter` | O(1) per request | Window starts at first call of a tenant; Caffeine `expireAfterWrite(1 minute)` ends it |
| Business-hours interval arithmetic | `sla/BusinessCalendar` | O(days spanned), at most 20,000 iterations | Walks local dates in the tenant time zone; DST-safe because windows are built with `ZonedDateTime` per local date |
| SLA scan, fair rotation | `TicketRepository.slaScanBatch` and `SlaService.scanCurrentTenant` | O(batch) per tenant per run, batch 200 | Orders by `coalesce(sla_checked_at, epoch)` so tickets checked longest ago are examined first |
| Least-loaded assignment | `WorkflowEngine` action `ASSIGN_TO_ROLE_LEAST_LOADED` | O(n) with n at most 100 candidates, plus one `IN` query | Stream `min` with tie-break by id; not a heap |
| Reachability by BFS | `WorkflowValidator.reachable` | O(V times T) with V at most 8 statuses, T at most 60 transitions | Rejects a workflow in which NEW cannot reach RESOLVED |
| Duplicate detection | `WorkflowValidator.validateTransitions` | O(T) with a `HashSet` | Rejects duplicate `from>to` pairs |
| Exponential backoff with cap | `OutboxRelay.fail`, notification `RetryPolicy` | O(1) | `min(cap, base * 2^(attempt-1))`, then dead state |
| Cache-aside, single flight | `CaffeineCacheService`, `RedisCacheService` | O(1) expected | Eviction by Caffeine's W-TinyLFU locally, TTL with jitter in Redis |
| Keyword scoring | `KbSearchScorer` | O(tokens times text length), tokens at most 8 | Deterministic weights: title 5, summary 3, body up to 5 |
| Pagination bounds | `PageParams` | O(page size), size at most 100 | Sort field whitelist prevents unbounded or injectable sorts |
| SHA-256 request hash | `TicketCommandService.hash` | O(body) | Idempotency: same key and same hash replays, different hash gives 409 |

> Project-implemented behaviour: two things the task description may suggest but the code does NOT do. First, workflow validation does not use topological sort or cycle detection: it checks that NEW can reach RESOLVED by BFS and rejects duplicate transitions. Cycles are legal in a ticket state machine (`OPEN` to `PENDING` and back, `RESOLVED` to `REOPENED` to `OPEN`). Cycle detection belongs to dependency graphs (rule A must run before rule B), which the workflow model does not have. `TopologicalSort` in `dsa-lab` exists for that future need. Second, the business-hours calculation is a day-by-day walk, not an interval-merge; `IntervalMerger` in `dsa-lab` is the generic technique for pause windows, whereas the platform stores a single `sla_paused_at` timestamp and shifts due dates on resume by the business minutes spent paused.

### The dsa-lab module

`dsa-lab` is plain Java 21 with JUnit 5 and no Spring: hash map with chaining, ring buffer, binary heap, ticket priority queue, top-K, merge/quick/heap sort, binary search variants, BST, graph BFS/DFS, topological sort, LRU cache, sliding window, interval merging, trie, and the four rate limiters (token bucket, fixed window, sliding window log, sliding window counter). Each class documents purpose, algorithm, complexity, edge cases, brute force versus optimised, and interview follow-ups. Its README states the run command `mvn -pl dsa-lab -am test` (53 tests) and a per-topic mapping to the platform.

### Conceptual learning example (the real dsa-lab classes, compiled and executed): `DsaDemo.java`

I compiled the sources of `dsa-lab/src/main/java` directly with `javac` and ran a driver that calls `TokenBucket`, `LruCache`, `Graph`, `TopologicalSort` and `Assigners.LeastLoaded`. The business-hours part uses a copy of the production `BusinessCalendar` class with only the factory method that depends on Spring-managed settings removed.

```java
TokenBucket tb = new TokenBucket(3, 1.0);                       // capacity 3, 1 token per second
for (long t : new long[]{0, 0, 0, 0, 500, 1000, 1000, 2500}) tb.tryAcquire("user-1", t);
BusinessCalendar cal = new BusinessCalendar(ZoneId.of("Europe/London"), LocalTime.of(9, 0), LocalTime.of(17, 0),
        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
cal.addBusinessMinutes(fridayAt1630, 120);
```

Real output:

```text
t=0ms:ALLOW t=0ms:ALLOW t=0ms:ALLOW t=0ms:DENY t=500ms:DENY t=1000ms:ALLOW t=1000ms:DENY t=2500ms:ALLOW
LRU evicted=b order(recent first)=[c, a]
topo order=[validate, assign, notify]
hasCycle=true cycle=[validate, assign, notify, validate]
shortest NEW->RESOLVED=[NEW, OPEN, PENDING, RESOLVED] unreachable from OPEN=[NEW]
least-loaded picks=[ana, bo, cy, bo, cy] load(ana)=3
Fri 16:30 + 120 business minutes = 2026-03-30T10:30+01:00[Europe/London]
business minutes Fri 16:30 -> Mon 10:00 = 90
Saturday 12:00 + 30 business min = 2026-03-30T09:30+01:00[Europe/London]
same instant in UTC: 2026-03-30T09:30:00Z
```

How to read it. The token bucket allows a burst of 3 at t=0, denies the fourth, has refilled only half a token at 500 ms, allows one at 1000 ms, denies the next, and by 2500 ms has accumulated enough for another request. The LRU evicted `b` because `a` was touched after both were inserted. The cycle printout shows a concrete cycle, which is the form of error message a validation API would return. In the business-hours lines, Friday 16:30 plus 120 working minutes uses 30 minutes on Friday and rolls into Monday for 90 more, ending at 10:30 local time. The Monday time is `+01:00` because the clocks changed to British Summer Time on Sunday 29 March 2026; the same instant is 09:30 UTC. That DST case is why the class builds each day's window from a `LocalDate` and a zone instead of adding 24 hours.

### Complexity worked examples

Big-O reasoning for `BusinessCalendar.addBusinessMinutes(from, m)`: each loop iteration consumes either one non-working day or one working window, so it runs at most about `m / 480 + weekends` iterations (480 working minutes per day). A 3-day SLA of 1,440 minutes is therefore a handful of iterations. The 20,000 iteration bound turns a misconfiguration (for example an empty weekday set) into an `IllegalStateException` instead of an infinite loop. `degenerate()` handles empty days or start not before end by falling back to wall-clock minutes.

Cost of the SLA scan across tenants: with T tenants and batch size B the job does O(T times B) ticket evaluations per minute, each O(1) plus the calendar walk. The bound `cx.sla.batch-per-tenant` (200) is what keeps the cost linear in tenants rather than in total tickets; the fair-rotation ordering ensures that tickets beyond the first 200 are still reached on later runs.

Backoff worked table for the outbox relay (base 2 s, cap 300 s, max attempts 8; delay applies after a failed attempt):

| Failed attempt | Delay before next attempt |
|---|---|
| 1 | 2 s |
| 2 | 4 s |
| 3 | 8 s |
| 4 | 16 s |
| 5 | 32 s |
| 6 | 64 s |
| 7 | 128 s |
| 8 | row becomes `FAILED` (kept, never purged) |

The geometric series gives a total waiting time of 254 seconds before an event is declared dead, and the `<<` shift is capped at 10 so the arithmetic cannot overflow. Notification `RetryPolicy` uses `base * multiplier^(attempt-1)` capped at `maxDelayMs`, the same idea expressed with doubles.

### Implemented versus not implemented, extensions

Not implemented in production: heap-based assignment (`Assigners.LeastLoaded`), LRU eviction written by hand, interval merging for multiple pause windows, topological ordering of rules, sliding-window rate limiting, tries for autocomplete, Bloom filters for duplicate detection (the platform relies on unique constraints: `processed_event` in the notification module and the idempotency-key table in core).

How extensions would work. Skills-based routing: add candidate filtering by skill before the minimum; with large pools keep an indexed heap keyed by load. Distributed rate limiting: store the bucket in Redis and refill atomically in a Lua script (`dsa-lab` Javadoc names this follow-up). Rule dependencies: add an optional `after` list to rules, run Kahn's algorithm in `WorkflowValidator`, and return `TopologicalSort.findCycle` as the message. Multiple pauses: persist pause intervals, merge them with `IntervalMerger` and subtract the business-hours overlap.

### Performance and security

Every algorithm in the table has an explicit bound: key counts, batch sizes, page sizes, iteration limits, token counts, rule and condition counts (`WorkflowValidator.MAX_RULES` 50, `MAX_CONDITIONS` 10, `MAX_ACTIONS` 10, `MAX_TRANSITIONS` 60, `MAX_TEXT` 200). Bounds are a security control as much as a performance one: they are the algorithmic denial-of-service defence. A regular expression with catastrophic backtracking is the classic example; the project uses simple anchored patterns (`^[A-Za-z0-9][A-Za-z0-9-]{0,29}$`) with bounded lengths.

### Common mistakes and debugging

Mistakes: O(n squared) loops over unbounded input, N+1 queries (the project guards with `NPlusOneGuardTest`, which asserts the number of SQL statements for a list of 30 tickets equals that for 2 and is at most 8), sorting in memory what the database can order with an index, recursive algorithms on user-controlled depth (`StackOverflowError`), integer overflow in time arithmetic (use `long`, and `Math.addExact` when in doubt), and rate limiters keyed by unbounded client input. Debugging: write a tiny benchmark with doubling input sizes and look at the growth ratio; use Hibernate statistics (enabled in the test profile) to count statements; use `EXPLAIN` on the database (the repository has `perf/sql/H2Explain.java` for local plans).

### Alternatives and trade-offs

| Problem | Used | Alternative | Trade-off |
|---|---|---|---|
| Per-user rate limiting | Token bucket | Sliding window log | Log is exact but O(requests) memory per key; bucket is O(1) with burst semantics |
| Local cache eviction | Caffeine W-TinyLFU | Plain LRU | TinyLFU admission resists scans and one-off keys; LRU is simpler and predictable |
| Least loaded | SQL counts plus linear min | Heap | Heap wins only with many agents and in-memory state; SQL counts are authoritative |
| Business time | Day walk | Precomputed calendar table | Table helps at very large scale and with holidays; day walk is stateless |
| Ordering of dependent rules | Not needed | Topological sort | Only when rules gain dependencies |

### Interview questions

Question: Explain the token bucket algorithm and how you would make it distributed.
Answer: A bucket holds up to `capacity` tokens and refills at a constant rate; each request takes a token, so bursts up to the capacity are allowed and the long-run rate is the refill rate. Implementation: store `tokens` and `lastRefill`; on each call add `elapsed * rate` capped at capacity (lazy refill, no timer thread), then take one if at least one is available. The platform uses it in the gateway (per user or IP) and for login attempts. To make it distributed, keep the state in Redis and perform refill-and-take in a single Lua script so the read-modify-write is atomic; accept that each replica's local limiter otherwise multiplies the effective limit by the number of replicas, which the gateway class documents.

Question: Why do SLA calculations use business minutes, and how do you handle DST?
Answer: Customer commitments are in working time (for example 8 business hours), so due dates must skip nights, weekends and non-working days in the tenant's time zone. Adding fixed 24-hour blocks breaks across DST changes, so `BusinessCalendar` builds each day's opening and closing as a `ZonedDateTime` from a local date, and `ChronoUnit.MINUTES.between` on zoned values accounts for the offset change. The executed example crossed the March 2026 clock change correctly.

Question: How would you detect a cycle in a dependency graph, and why does the workflow validator not do it?
Answer: Kahn's algorithm: compute in-degrees, repeatedly remove zero in-degree nodes; if nodes remain, there is a cycle; a three-colour DFS then extracts a concrete cycle. It is O(V+E). The workflow validator checks a state machine, where cycles such as `OPEN` to `PENDING` to `OPEN` are valid, so the relevant property is reachability (NEW must reach RESOLVED), done with BFS. If rules gained ordering dependencies, cycle detection would become necessary.

Question: Why is Caffeine preferred to a hand-written LRU for the local cache?
Answer: It is concurrent, has size and time-based expiry, an admission policy (W-TinyLFU) that keeps frequently used entries when one-off keys flood the cache, and statistics. A hand-written LRU such as the dsa-lab one is single-threaded and is a learning tool. The project wraps Caffeine behind `CacheService` so the same contract can be implemented by Redis.

Question: How do you bound the work of a background job in a multi-tenant system?
Answer: Iterate tenants in pages, bind the tenant for each, process at most a fixed batch, order so that the least recently processed items come first so that nothing starves, make the processing idempotent, and isolate failures per tenant. `SlaMonitorJob` does exactly this: 100 tenants per page, 200 tickets per tenant, ordered by last check time, with per-tenant error counters.

Question: Give the time complexity of the ticket-list endpoint and what prevents N+1.
Answer: One page query and one count query, then constant extra queries: one `IN` lookup each for users, categories and attachments, independent of page size (at most 100 rows). `TicketMapper` batches the lookups and `NPlusOneGuardTest` asserts that 30 rows cost the same number of statements as 2.

## A11. Troubleshooting exercises for Part A

Each exercise gives a symptom, a hint and the expected finding. Try them before reading the answer.

| No. | Symptom | Hint | Answer |
|---|---|---|---|
| 1 | A `HashMap` lookup by a mutated key returns null although the map size did not change | Compare hash before and after mutation (A2 demo) | The entry sits in the bucket of the old hash and `equals` fails; keys must be effectively immutable |
| 2 | A counter is below the expected value only in production | Was the field `volatile`? Is the operation a read-modify-write? | Use `AtomicLong` or `LongAdder`; volatile gives visibility, not atomicity |
| 3 | After a timeout, the worker keeps running | Look at `Future.cancel(true)` and the task's interruption handling | The task must respond to interrupts or check a flag; `AiAssistService` cancels and the faulted provider sleeps interruptibly |
| 4 | Requests from tenant B occasionally show tenant A's data after a deployment that added a thread pool | Where is the tenant stored? | `ThreadLocal` leaks across pooled tasks (executed demo): capture the id, rebind and clear inside the task |
| 5 | The container restarts every few hours, heap usage in the dashboard looks fine | Compare heap with container memory; count threads | Non-heap memory or thread count; use native memory tracking and lower `MaxRAMPercentage` |
| 6 | GC log shows repeated full collections that free nothing | Heap before and after the pause (`50M->50M`) | Live set is too large: leak or undersized heap; take a heap dump |
| 7 | `toMap` throws `IllegalStateException: Duplicate key` in a mapping step | Is the key really unique? | Add a merge function or group instead; check the data |
| 8 | The scheduled outbox relay stops ticking for a minute every minute | Which other jobs share the scheduler? | Default scheduler pool size is 1: a long `SlaMonitorJob` run delays the relay; configure `spring.task.scheduling.pool.size` |
| 9 | An SLA due date lands an hour early after the clock change | How are days added? | Never add `24 * 60` minutes to an `Instant`; build windows per local date with the tenant zone |
| 10 | Under 100 concurrent AI requests, many get `degraded: true` with reason `overloaded` | Compute capacity: workers plus queue | 4 workers plus 16 queued is 20; the rest are rejected by design; raise the pool only if the provider can take the load |
