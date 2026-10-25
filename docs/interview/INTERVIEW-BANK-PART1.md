# Interview Question Bank - Part 1

Target role: Lead Full Stack Engineer (IC3), Freshworks-style CX + ITSM SaaS.
Portfolio project: `freshworks-cx-itsm-platform` (multi-tenant ticketing, workflow/SLA, knowledge base, mock AI assist).

## How to read this bank

- Every question has a number (Q1..Q140), tags, a detailed answer, and 1-2 follow-ups with brief answers.
- Tags: `[Java]` `[Spring]` `[React]` `[TS]` `[Browser]` `[API]` `[DSA]` `[Design]` `[DB]` `[Concurrency]`.
- **General concept** = textbook/industry knowledge, true regardless of this repo.
- **In this project** = limited to what the repo's documented contract (`docs/API-CONTRACT.md`, `docs/ARCH-CONSTRAINTS.md`) and stated design guarantees: JWT tenant claims (`tid`, `sub`, `roles`, `jti`), Hibernate tenant filter (optional PostgreSQL RLS under the `docker` profile), optimistic locking (`version`), `Idempotency-Key` on ticket creation, transactional outbox, Caffeine/Redis cache-aside, RabbitMQ retry + DLQ + idempotent consumer, mock rule-based AI with human approval, and a constrained workflow rule model. Anything beyond that is explicitly phrased as "how you would do it" and not as a claim about the repo.
- Honesty note for interviews: the local profile uses H2 in PostgreSQL mode, in-memory cache and an in-memory event relay; Docker-backed pieces (PostgreSQL, Redis, RabbitMQ, MongoDB, RLS) were not executed on the dev machine. Say so if asked.
- DSA solutions (Q93-Q119) are Java 21 code. Blocks starting with `// COMPILE:` were extracted from this file, compiled with `javac` (JDK 21), and exercised by an assertion harness. JUnit snippets are sketches (JUnit was not on the classpath) and were not compiled.
- Parts: this is Part 1. Mock interview sets are at the end.

## Contents

| Range | Section |
|---|---|
| Q1-Q26 | Java core |
| Q27-Q50 | Spring / Spring Boot |
| Q51-Q70 | React and TypeScript |
| Q71-Q76 | Browser / frontend performance |
| Q77-Q90 | REST API design |
| Q91-Q120 | DSA and complexity (coding) |
| Q121-Q140 | HLD / LLD for ticketing |
| Mock sets | 5 sets x 10 questions, 45-minute order, rubric |

---

# Section 1 - Java core (Q1-Q26)

### Q1. Explain the four OOP pillars with a ticketing example. `[Java]`
**Answer.** Encapsulation hides state behind behavior: a `Ticket` exposes `transitionTo(status)` rather than a public `status` setter, so invariants (allowed transitions) live in one place. Abstraction exposes what, not how: `NotificationChannel.send(msg)` hides email vs Slack. Inheritance reuses/specialises types (`SupportTicket extends Ticket`) but is the most abused pillar; prefer composition. Polymorphism lets calling code depend on a supertype and choose behavior at runtime via dynamic dispatch (virtual method call resolved from the receiver's class).
**General concept.** Favor "composition over inheritance"; keep inheritance for true is-a with Liskov-safe behavior.
**Follow-ups.**
- *Overloading vs overriding?* Overloading is compile-time (static) choice by parameter types; overriding is runtime dispatch by receiver type.
- *Can fields be polymorphic?* No, fields are resolved statically by reference type (field hiding), only instance methods dispatch dynamically.

### Q2. Abstract class vs interface in modern Java (21). `[Java]`
**Answer.** Interfaces define a contract, support multiple implementation, and since Java 8 can carry `default`/`static` methods and since 9 `private` methods; they cannot hold instance state. Abstract classes may hold state, constructors and non-public members but allow only single inheritance. Use an interface for capability/role (`Auditable`), an abstract class when subclasses share state and a template method. Sealed interfaces (17+) give a closed hierarchy that the compiler can check in `switch`.
```java
public sealed interface WorkflowAction permits SetPriority, Escalate, AddTag {}
record SetPriority(String level) implements WorkflowAction {}
record Escalate(int toLevel) implements WorkflowAction {}
record AddTag(String tag) implements WorkflowAction {}
```
**In this project.** The contract's workflow action list is a closed set (`ASSIGN_TO_ROLE_LEAST_LOADED, SET_PRIORITY, ESCALATE, NOTIFY, ADD_TAG`); a sealed type is the natural way to model such a closed set (design suggestion, not a claim about the code).
**Follow-ups.**
- *Diamond problem with default methods?* If two interfaces provide the same default, the class must override and may call `A.super.m()`.
- *When is a marker interface still OK?* For type-level capability checks (`RandomAccess`, `Serializable`), though annotations often replace them.

### Q3. Walk through SOLID with one concrete example each. `[Java] [Design]`
**Answer.** **S**: a `TicketService` that validates, persists, sends email and builds reports has four reasons to change; split it. **O**: add a new SLA escalation action without editing the dispatcher by registering a new `ActionHandler` bean (strategy map keyed by action name). **L**: a `ReadOnlyTicketRepository extends TicketRepository` that throws on `save()` breaks substitutability; model read and write contracts separately. **I**: do not force a `NotificationSender` to implement `sendSms` if it only does email; use small interfaces. **D**: high-level `TicketService` depends on `EventPublisher` interface, with in-memory and RabbitMQ implementations chosen by profile.
**In this project.** `docs/ARCH-CONSTRAINTS.md` states runtime profiles work "via interfaces (CacheService, EventPublisher/relay, DocumentStore)" - this is Dependency Inversion in practice: same code, local vs docker adapters.
**Follow-ups.**
- *Is SOLID always worth applying?* No; over-abstraction (interface per class with one impl) hurts. Apply when there is a real variation axis, like the profile adapters above.
- *Which principle does constructor injection serve?* DIP plus testability (swap fakes).

### Q4. State the `equals`/`hashCode` contract and common mistakes. `[Java]`
**Answer.** `equals` must be reflexive, symmetric, transitive, consistent and `x.equals(null)==false`; equal objects must have equal `hashCode`. Mistakes: overriding one but not the other (breaks `HashMap`/`HashSet`), using mutable fields in `hashCode` for keys already inserted (the entry becomes unreachable), and using `getClass()` vs `instanceof` inconsistently (symmetry across subclasses). For JPA entities, equality on generated id is tricky because id is null before persist; use a business key or a constant `hashCode` with id-based equals that treats null id as not equal.
```java
record TicketKey(String tenantId, long number) {} // records get correct equals/hashCode
```
**General concept.** Records auto-generate value-based `equals`/`hashCode`.
**Follow-ups.**
- *What if hashCode always returns 1?* Still correct but every key lands in one bucket: O(n), or O(log n) if keys are `Comparable` and the bin is treeified.
- *Why include tenantId in cache keys?* Two tenants can both have ticket number 1001; see Q47 and Q121.

### Q5. How does `HashMap` work internally? `[Java]`
**Answer.** An array of buckets (power-of-two length, default 16). Index = `(n-1) & hash` where `hash = h ^ (h>>>16)` spreads high bits. Collisions chain in a linked list; when a bin reaches 8 nodes and the table is at least 64 long, the bin converts to a red-black tree (O(log n)); below 6 it untreeifies. When size exceeds `capacity * 0.75` the table doubles and entries are split into low/high lists (no rehash computation needed beyond one bit). `get` is O(1) average. Not thread-safe: concurrent resize can lose updates (and in old JDKs loop forever).
**General concept.** Initial capacity should be `expected / 0.75 + 1` to avoid resizes. Null key allowed (bucket 0).
**Follow-ups.**
- *HashMap vs Hashtable vs ConcurrentHashMap?* Hashtable locks every method; CHM uses finer-grained concurrency and forbids null.
- *Why is a power of two used?* Cheap masking instead of modulo.

### Q6. How does `ConcurrentHashMap` achieve thread safety in Java 8+? `[Java] [Concurrency]`
**Answer.** Reads are lock-free (volatile reads of table/node fields). Inserting into an empty bin uses CAS; non-empty bins are locked by `synchronized` on the head node, so contention is per bin. Size is tracked with striped counters (`LongAdder`-style). Compound operations must use atomic methods: `computeIfAbsent`, `merge`, `compute`, not check-then-put. Null keys/values are rejected to avoid ambiguity in `get`. Iterators are weakly consistent (never throw `ConcurrentModificationException`).
```java
Map<String, LongAdder> hits = new ConcurrentHashMap<>();
hits.computeIfAbsent(tenantId, k -> new LongAdder()).increment();
```
**Follow-ups.**
- *Is `map.get(k)==null ? put : ...` safe?* No, race; use `putIfAbsent`/`computeIfAbsent`.
- *Danger inside `computeIfAbsent`?* Do not mutate the same map from the mapping function (may deadlock/throw).

### Q7. `ArrayList` vs `LinkedList` vs `ArrayDeque`. `[Java]`
**Answer.** `ArrayList` is a resizable array: O(1) random access, amortised O(1) append (grows ~1.5x), O(n) middle insert/remove but with excellent cache locality. `LinkedList` has O(1) insert/remove given a node but O(n) to reach it, high per-node memory, poor locality; it is rarely the right choice. `ArrayDeque` is a circular buffer and the best queue/stack (faster than `LinkedList` and `Stack`). Prefer `ArrayList` by default and `ArrayDeque` for queues.
**Follow-ups.**
- *Why is `Stack` discouraged?* It extends `Vector` (synchronized) and exposes list operations; use `Deque`.
- *`List.of` vs `Arrays.asList`?* `List.of` is immutable and null-hostile; `Arrays.asList` is fixed-size, write-through to the array.

### Q8. What is fail-fast iteration and how do you remove during iteration? `[Java]`
**Answer.** Most non-concurrent collections keep a `modCount`; an iterator compares its expected count and throws `ConcurrentModificationException` on mismatch (best-effort, even single-threaded). Safe removal options: `Iterator.remove()`, `Collection.removeIf(pred)`, or collecting survivors via stream. `CopyOnWriteArrayList` snapshots on write: iteration is safe but writes are O(n), suited to read-heavy listener lists.
```java
tickets.removeIf(t -> t.status() == Status.CLOSED);
```
**Follow-ups.**
- *Does for-each + remove always throw?* Not always (removing the second-to-last element skips the check), which is why it is a bug even when it "works".
- *Fail-safe vs fail-fast naming?* "Weakly consistent" is the accurate term for CHM iterators.

### Q9. Ordered and priority structures: `TreeMap`, `LinkedHashMap`, `PriorityQueue`. `[Java] [DSA]`
**Answer.** `TreeMap` is a red-black tree: O(log n) ops, sorted keys, range views (`subMap`, `floorKey`). `LinkedHashMap` is a hash map plus a doubly linked list giving insertion or access order, which with `removeEldestEntry` is a ready LRU cache. `PriorityQueue` is a binary min-heap in an array: O(log n) offer/poll, O(1) peek, O(n) `remove(Object)`, and iteration order is NOT sorted. Use a comparator that is consistent and never overflows (`Integer.compare`, not subtraction).
**Follow-ups.**
- *Heap build complexity?* `PriorityQueue(Collection)` heapify is O(n), repeated offers are O(n log n).
- *Which structure for "next SLA breach"?* A min-heap keyed by due time, or a DB index on due date for scale (see Q126).

### Q10. Explain generics, type erasure, and PECS. `[Java]`
**Answer.** Generics give compile-time type safety; at runtime the type parameters are erased to bounds (`List<String>` and `List<Integer>` are both `List`). Consequences: no `new T()`, no `instanceof List<String>`, no generic arrays, and bridge methods are generated. PECS: "Producer Extends, Consumer Super". A method that only reads from a collection takes `List<? extends Ticket>`; one that writes takes `List<? super Ticket>`.
```java
static <T extends Comparable<? super T>> T max(Collection<? extends T> c) {
    Iterator<? extends T> it = c.iterator(); T best = it.next();
    while (it.hasNext()) { T x = it.next(); if (x.compareTo(best) > 0) best = x; }
    return best;
}
```
**Follow-ups.**
- *Why is `List<Object>` not a supertype of `List<String>`?* Generics are invariant; otherwise you could add an Integer to a list of Strings.
- *How to get a type token at runtime?* Pass `Class<T>` or capture via a superclass (`TypeReference`/`ParameterizedTypeReference`).

### Q11. What is heap pollution and why are generic arrays illegal? `[Java]`
**Answer.** Heap pollution is when a variable of a parameterized type refers to an object that is not of that type (via raw types or unchecked casts), leading to `ClassCastException` far from the cause. Generic arrays are forbidden because arrays are covariant and reified while generics are erased: `List<String>[] a = new List<String>[1]; Object[] o = a; o[0] = List.of(1);` would pass the array store check yet pollute. `@SafeVarargs` is only valid when the method does not store into or expose the varargs array.
**Follow-ups.**
- *Workaround?* Use `List<List<String>>` or `(T[]) new Object[n]` privately with care.
- *Is raw `List` ever acceptable?* Only in legacy interop.

### Q12. How do Java streams work, and what are the pitfalls? `[Java]`
**Answer.** A stream is a lazy pipeline: intermediate ops (`filter`, `map`, `sorted`) build a description and nothing runs until a terminal op (`collect`, `forEach`, `findFirst`). Elements are pushed through the whole pipeline one at a time (except stateful ops like `sorted`/`distinct` which buffer). Short-circuiting ops (`limit`, `anyMatch`) can stop early. Pitfalls: a stream can be consumed once; side effects in `map`/`peek` are unreliable; `parallelStream()` uses the common ForkJoinPool and hurts for blocking IO or small data; boxing in `Stream<Integer>` is slow vs `IntStream`; `Collectors.toMap` throws on duplicate keys unless you pass a merge function.
**Follow-ups.**
- *`map` vs `flatMap`?* `flatMap` flattens each element's stream into one.
- *Is `forEach` ordered in parallel?* No; use `forEachOrdered`.

### Q13. Group and aggregate tickets with `Collectors` (working code). `[Java]`
**Answer.** Typical analytics: count by priority, average resolution time by agent, top assignees. The downstream collector composes aggregations.
```java
// COMPILE: StreamDemo
import java.util.*;
import java.util.stream.*;

public class StreamDemo {
    enum Priority { LOW, MEDIUM, HIGH, URGENT }
    record Ticket(long id, Priority priority, String assignee, long resolutionMinutes) {}

    public static Map<Priority, Long> countByPriority(List<Ticket> ts) {
        return ts.stream().collect(Collectors.groupingBy(Ticket::priority,
                () -> new EnumMap<>(Priority.class), Collectors.counting()));
    }
    public static Map<String, Double> avgResolutionByAssignee(List<Ticket> ts) {
        return ts.stream().filter(t -> t.assignee() != null)
                .collect(Collectors.groupingBy(Ticket::assignee,
                        TreeMap::new, Collectors.averagingLong(Ticket::resolutionMinutes)));
    }
    public static void main(String[] args) {
        var ts = List.of(new Ticket(1, Priority.HIGH, "a", 30), new Ticket(2, Priority.HIGH, "a", 90),
                new Ticket(3, Priority.LOW, null, 10));
        System.out.println(countByPriority(ts) + " " + avgResolutionByAssignee(ts));
    }
}
```
**In this project.** The analytics dashboard in the contract exposes `ticketsByPriority`, `avgResolutionMinutes` and `agentWorkload`; the endpoint is cached (see Q47). Whether the implementation uses streams or SQL aggregation is an implementation detail - at scale you would aggregate in the database (Q135).
**Follow-ups.**
- *Why `EnumMap`?* Compact array-backed map, stable order, all keys can be pre-populated.
- *Streams vs SQL `GROUP BY`?* Push aggregation down to the DB for large sets; use streams for in-memory post-processing.

### Q14. How should `Optional` be used? `[Java]`
**Answer.** Use it as a return type for "may be absent", chaining with `map`/`flatMap`/`orElseThrow`. Do not use it for fields, method parameters, or collections (return an empty collection). Avoid `Optional.get()` without check and `orElse(expensiveCall())` (eager); use `orElseGet`. Do not wrap with `Optional.of` if the value may be null (`ofNullable`).
```java
Ticket t = repo.findByIdAndTenant(id, tenant)
        .orElseThrow(() -> new NotFoundException("TICKET", id)); // maps to 404 NOT_FOUND
```
**In this project.** `NOT_FOUND` (404) is also what the contract returns for cross-tenant ids, so "absent" and "not yours" look identical to the caller (prevents tenant enumeration).
**Follow-ups.**
- *Is Optional serializable?* No, intentionally; keep it out of DTOs.
- *`orElse` vs `orElseGet`?* `orElse` evaluates its argument always.

### Q15. Java 21 features worth knowing: records, sealed types, pattern matching `switch`. `[Java]`
**Answer.** Records are transparent immutable data carriers (canonical constructor, `equals`/`hashCode`/`toString`) - ideal for DTOs and keys. Sealed types restrict implementers, so `switch` over them can be exhaustive without `default`. Pattern matching for `switch` (final in 21) and record patterns destructure values and allow guards (`when`).
```java
// COMPILE: PatternDemo
public class PatternDemo {
    sealed interface Event permits Created, Assigned, Resolved {}
    record Created(long id) implements Event {}
    record Assigned(long id, String agent) implements Event {}
    record Resolved(long id, long minutes) implements Event {}

    static String describe(Event e) {
        return switch (e) {
            case Created c -> "created " + c.id();
            case Assigned(long id, String agent) when agent.isBlank() -> "unassigned " + id;
            case Assigned(long id, String agent) -> id + " -> " + agent;
            case Resolved r -> "resolved in " + r.minutes();
        };
    }
    public static void main(String[] a) { System.out.println(describe(new Assigned(1, "bo"))); }
}
```
**Follow-ups.**
- *What if a new permitted subtype is added?* Every non-default exhaustive `switch` fails to compile - a feature.
- *Can records extend classes?* No; they implicitly extend `java.lang.Record` and are final, but may implement interfaces.

### Q16. Platform threads vs virtual threads (Java 21). `[Java] [Concurrency]`
**Answer.** Platform threads map 1:1 to OS threads (about 1 MB stack reserved, costly to create). Virtual threads are cheap JVM-scheduled threads mounted on a small pool of carrier threads; when blocked on IO they unmount, so a thread-per-request model scales to many concurrent blocking calls without reactive code. They do not speed up CPU-bound work. Pitfalls: pinning (blocking inside `synchronized` in 21 pins the carrier; prefer `ReentrantLock`), `ThreadLocal`-heavy code (fine but memory), and unbounded concurrency hitting downstream limits (still bound with semaphores/pools).
```java
try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
    ex.submit(() -> callAiProvider());
}
```
**Follow-ups.**
- *Should virtual threads be pooled?* No, create per task; pool the scarce resource (DB connections) instead.
- *Spring Boot 3.2+ switch?* `spring.threads.virtual.enabled=true`.

### Q17. `synchronized` vs `ReentrantLock` vs atomics. `[Java] [Concurrency]`
**Answer.** `synchronized` is a simple, block-scoped, reentrant monitor with JVM optimisations. `ReentrantLock` adds `tryLock` with timeout, interruptible acquisition, fairness option and multiple `Condition`s, at the cost of manual `unlock` in `finally`. Atomics (`AtomicInteger`, `LongAdder`) use CAS for lock-free single-variable updates; `LongAdder` beats `AtomicLong` under high contention. `ReadWriteLock`/`StampedLock` help read-heavy cases.
```java
lock.lock();
try { balance += x; } finally { lock.unlock(); }
```
**Follow-ups.**
- *What is the ABA problem?* A CAS sees the same value though it changed A->B->A; use `AtomicStampedReference`.
- *Optimistic locking analogy?* DB `@Version` is CAS at the row level (Q36).

### Q18. Explain the Java Memory Model: happens-before, `volatile`. `[Java] [Concurrency]`
**Answer.** The JMM defines when one thread's writes are guaranteed visible to another and what reorderings are allowed. Without synchronization, compilers/CPUs may reorder and caches may hide writes. Happens-before edges: monitor unlock -> later lock of the same monitor; volatile write -> later volatile read of the same variable; `Thread.start` -> thread body; thread end -> `join` return; final-field freeze after constructor. `volatile` guarantees visibility and ordering but not atomicity (`count++` is still racy). Safe publication of an object needs one of those edges.
```java
class Holder { private volatile Config cfg; /* publish whole immutable object */ }
```
**Follow-ups.**
- *Double-checked locking?* Valid only with `volatile` on the instance (or use holder idiom/enum).
- *Do `final` fields help?* Yes, properly constructed immutable objects are safely visible without sync.

### Q19. What causes deadlock and how do you prevent it? `[Java] [Concurrency]`
**Answer.** Four Coffman conditions: mutual exclusion, hold-and-wait, no preemption, circular wait. Break one: impose a global lock ordering (lock lower ticket id first when transferring/merging two tickets), use `tryLock` with timeout and back off, reduce lock scope, or avoid nested locks via message passing. Diagnose with `jstack`/thread dumps (reports "Found one Java-level deadlock") or `ThreadMXBean.findDeadlockedThreads`. Database deadlocks behave the same: access rows in consistent order and retry on deadlock-victim errors.
```java
Object first = a.id() < b.id() ? a : b, second = first == a ? b : a;
synchronized (first) { synchronized (second) { merge(a, b); } }
```
**Follow-ups.**
- *Livelock vs starvation?* Livelock: threads keep reacting without progress; starvation: a thread never gets the resource (unfair locks).
- *Deadlock across DB and Java locks?* Avoid holding JVM locks while waiting for DB.

### Q20. How do you size and configure a thread pool? `[Java] [Concurrency]`
**Answer.** CPU-bound: about `cores` (+1) threads. IO-bound: `cores * (1 + wait/compute)` or use virtual threads. Use bounded queues and an explicit `RejectedExecutionHandler` (e.g. `CallerRunsPolicy` for backpressure) - `Executors.newFixedThreadPool` has an unbounded queue and can OOM. Name threads, expose pool metrics, and shut down gracefully (`shutdown`, `awaitTermination`). Separate pools per workload (e.g. AI provider calls vs request handling) to prevent bulkhead failures.
```java
new ThreadPoolExecutor(8, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
        new ThreadPoolExecutor.CallerRunsPolicy());
```
**Follow-ups.**
- *Why does a pool only add threads beyond core when the queue is full?* That is `ThreadPoolExecutor`'s policy: core -> queue -> max.
- *`ForkJoinPool` common pool risk?* Shared across parallel streams and `CompletableFuture` default async.

### Q21. Compose async calls with `CompletableFuture` incl. timeout and fallback (working code). `[Java] [Concurrency]`
**Answer.** Chain with `thenApply/thenCompose/thenCombine`, handle failures with `exceptionally`/`handle`, bound waiting with `orTimeout`/`completeOnTimeout` (Java 9+), and always pass an explicit executor for blocking work.
**In this project.** The contract says AI analyze, "on provider failure/timeout", returns HTTP 200 with `degraded:true` and a `fallbackMessage` and no reply text. A timeout-plus-fallback future is the standard way to produce that; the exact implementation may differ.
```java
// COMPILE: AiFallback
import java.util.concurrent.*;

public class AiFallback {
    record Suggestion(String reply, boolean degraded, String fallbackMessage) {}

    static Suggestion analyze(Callable<String> provider, long timeoutMs, ExecutorService pool) {
        return CompletableFuture.supplyAsync(() -> {
                    try { return new Suggestion(provider.call(), false, null); }
                    catch (Exception e) { throw new CompletionException(e); }
                }, pool)
                .completeOnTimeout(new Suggestion(null, true, "AI timed out"), timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> new Suggestion(null, true, "AI unavailable"))
                .join();
    }
    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        System.out.println(analyze(() -> "hello", 500, pool));
        pool.shutdown();
    }
}
```
**Follow-ups.**
- *Does `completeOnTimeout` cancel the running task?* No; the underlying work keeps running, so make the call itself time-boxed.
- *`thenApply` vs `thenCompose`?* `thenCompose` flattens a returned future (like `flatMap`).

### Q22. Describe JVM memory areas and garbage collectors. `[Java]`
**Answer.** Heap (young: eden + survivors; old), metaspace (class metadata, native memory), thread stacks, code cache, direct buffers. Generational hypothesis: most objects die young, so minor GCs are cheap copying collections. G1 (default since 9) divides the heap into regions and targets pause goals (`-XX:MaxGCPauseMillis`); ZGC/Shenandoah give sub-millisecond-ish pauses for large heaps; Parallel GC maximises throughput. Tune last: first fix allocation rate and leaks, set `-Xms=-Xmx` for servers, and in containers use `-XX:MaxRAMPercentage`. Observe with GC logs (`-Xlog:gc*`), JFR, heap dumps (`jcmd GC.heap_dump`).
**Follow-ups.**
- *`OutOfMemoryError: Metaspace` cause?* Classloader leaks (redeploys, dynamic proxies) or too many generated classes.
- *Stop-the-world phases in G1?* Young/mixed evacuation, remark, cleanup; marking is concurrent.

### Q23. Typical Java memory leaks, especially `ThreadLocal` and caches. `[Java]`
**Answer.** Leaks in Java are unintended strong references: static collections, unbounded caches, listeners never deregistered, `ThreadLocal` values on pooled threads (the thread lives on, the value stays), unclosed resources. For a multi-tenant app a `ThreadLocal<TenantContext>` set by a filter must be cleared in `finally`, otherwise the next request on that pooled thread can inherit the previous tenant's identity - a data-leak bug, not just a memory leak. Bound caches (Caffeine `maximumSize`, `expireAfterWrite`).
```java
try { TenantContext.set(tenantId); chain.doFilter(req, res); }
finally { TenantContext.clear(); MDC.clear(); }
```
**In this project.** Tenant is derived from the JWT `tid` claim on every request (never from client-supplied ids); a request-scoped holder must be cleared per request - general concept applied here.
**Follow-ups.**
- *Does the context propagate to `@Async` or virtual threads you spawn?* Not automatically with plain `ThreadLocal`; use a `TaskDecorator`, or pass the tenant explicitly.
- *How to find a leak?* Heap dump + dominator tree (Eclipse MAT), compare two dumps.

### Q24. Exceptions: checked vs unchecked, `try-with-resources`, `finally` traps. `[Java]`
**Answer.** Checked exceptions model recoverable conditions callers must handle; unchecked (`RuntimeException`) model programming errors or unrecoverable situations. In layered services a common convention is domain unchecked exceptions mapped centrally to HTTP codes. `try-with-resources` closes `AutoCloseable`s in reverse order and attaches close failures as suppressed exceptions. A `return` or `throw` inside `finally` overrides the original exception - never do it. Do not catch `Throwable`/`Exception` just to log and swallow; either handle it, wrap with cause (`new X("msg", e)`), or let it propagate.
```java
try (var in = Files.newInputStream(p); var out = Files.newOutputStream(q)) { in.transferTo(out); }
```
**In this project.** Error contract: all non-2xx return `{timestamp,status,error,code,message,path,correlationId,fieldErrors}` with codes like `INVALID_TRANSITION` (422) and `TICKET_VERSION_CONFLICT` (409) - domain exceptions mapped centrally (Q43).
**Follow-ups.**
- *Spring's default rollback and checked exceptions?* Only unchecked + `Error` roll back by default (Q34).
- *`Error` vs `Exception`?* `Error` signals JVM-level problems (OOM, StackOverflow) - generally do not catch.

### Q25. Immutability, `String` and the pool. `[Java]`
**Answer.** Immutable objects are inherently thread-safe, cacheable and safe as map keys. Make a class immutable: final class, final fields, no setters, defensive copies in/out of mutable components (`List.copyOf`). `String` is immutable, which enables the string pool, cached hash codes and security (no mutation after validation). Literals are interned; `new String("a")` creates a distinct object; `==` compares references, so use `equals`. Concatenate in loops with `StringBuilder` (the compiler optimises simple `+`, and since 9 uses `invokedynamic`).
```java
record Tag(String value) { Tag { value = value.strip().toLowerCase(); } } // normalise in compact ctor
```
**Follow-ups.**
- *Why `char[]` for passwords historically?* It can be zeroed; strings linger until GC. Mostly moot with hashing at the edge.
- *Cost of `intern()`?* Pool lookup contention; rarely worth it.

### Q26. Handle time zones and business hours correctly with `java.time`. `[Java]`
**Answer.** Store instants in UTC (`Instant`, `timestamptz`), convert for display/calculation with a `ZoneId`. Business-hours SLA math must be done in the tenant's zone (DST shifts change the offset) using `ZonedDateTime`/`LocalTime`, never by adding fixed 24h `Duration`s across days. `LocalDate/LocalDateTime` have no zone, so they cannot represent a point in time.
**In this project.** The contract has tenant settings `timezone, businessHoursStart, businessHoursEnd, businessDays[]` and the SLA policy takes "business hours from tenant settings"; tickets carry `firstResponseDueAt`/`resolutionDueAt` and `slaStatus` including `PAUSED`. A worked algorithm is Q116.
**Follow-ups.**
- *`Period` vs `Duration`?* `Period` is calendar-based (days/months), `Duration` is exact seconds.
- *Why avoid `java.util.Date`/`Calendar`?* Mutable, confusing, not thread-safe (`SimpleDateFormat`).

---

# Section 2 - Spring / Spring Boot (Q27-Q50)

### Q27. What is IoC/DI and why prefer constructor injection? `[Spring]`
**Answer.** Inversion of Control means the container creates objects and wires dependencies instead of your code calling `new`. The `ApplicationContext` holds `BeanDefinition`s, instantiates singletons, resolves dependencies by type (then qualifier/name) and applies post-processors. Constructor injection makes dependencies mandatory and final (immutable, no half-built objects), works without the container in unit tests, and exposes circular dependencies early (Boot 2.6+ forbids them by default). Field injection hides dependencies and needs reflection to test; setter injection is for optional ones.
```java
@Service
class TicketService {
    private final TicketRepository repo; private final EventPublisher events;
    TicketService(TicketRepository repo, EventPublisher events) { this.repo = repo; this.events = events; }
}
```
**In this project.** `EventPublisher`, `CacheService` and `DocumentStore` are interfaces with profile-specific implementations (ARCH-CONSTRAINTS), which DI makes swappable.
**Follow-ups.**
- *Two beans of the same type?* Use `@Primary`, `@Qualifier`, or inject `Map<String, T>`/`List<T>`.
- *Circular dependency fix?* Redesign (extract a third service or publish an event); `@Lazy` is a last resort.

### Q28. Describe the Spring bean lifecycle. `[Spring]`
**Answer.** Instantiate (constructor) -> populate properties -> `*Aware` callbacks -> `BeanPostProcessor.postProcessBeforeInitialization` -> `@PostConstruct` / `InitializingBean.afterPropertiesSet` / custom init -> `postProcessAfterInitialization` (this is where AOP proxies are created) -> bean in use -> on context close `@PreDestroy` / `DisposableBean.destroy`. Prototype beans are not destroyed by the container. Because proxies are made after initialization, `@PostConstruct` runs on the target and transactional behaviour is NOT active inside it.
**Follow-ups.**
- *Why can't you call a `@Transactional` method from `@PostConstruct`?* The proxy does not exist yet and the transaction infrastructure may not be ready; use `ApplicationReadyEvent`.
- *`BeanFactoryPostProcessor` vs `BeanPostProcessor`?* The first modifies definitions before instantiation, the second modifies bean instances.

### Q29. Bean scopes and injecting a prototype into a singleton. `[Spring]`
**Answer.** Default is singleton (one per container, not per JVM). Others: prototype, and web scopes request/session/application. A singleton holding a prototype gets only one instance at wiring time. Fix with `ObjectProvider<Prototype>`, `@Lookup`, or a scoped proxy (`@Scope(proxyMode = TARGET_CLASS)`). Singletons must be stateless or thread-safe - request data belongs in method args or request scope, never in singleton fields.
```java
@Component class Foo { Foo(ObjectProvider<Bar> bars) { this.bars = bars; } Bar fresh() { return bars.getObject(); } }
```
**Follow-ups.**
- *Is a request-scoped bean usable in a singleton?* Only through a scoped proxy.
- *Thread-safety of singleton services?* Safe when they hold only final, thread-safe collaborators.

### Q30. How does Spring Boot auto-configuration work? `[Spring]`
**Answer.** `@SpringBootApplication` = `@Configuration` + `@ComponentScan` + `@EnableAutoConfiguration`. Auto-config classes are listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 2.7+/3.x; earlier `spring.factories`) and imported via `AutoConfigurationImportSelector`. Each is guarded by conditions: `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`. User beans win because auto-config backs off with `@ConditionalOnMissingBean`. Debug with `--debug` or `/actuator/conditions` to see the conditions report. Starters just bring dependencies; the classpath triggers configuration.
**Follow-ups.**
- *Write a custom starter?* A module with an `@AutoConfiguration` class, `@ConfigurationProperties`, and the imports file.
- *Exclude one?* `@SpringBootApplication(exclude=...)` or `spring.autoconfigure.exclude`.

### Q31. Profiles and typed configuration. `[Spring]`
**Answer.** Profiles activate beans/config by environment (`@Profile("docker")`, `application-docker.yml`, `spring.profiles.active`). Prefer `@ConfigurationProperties` records over scattered `@Value` for typed, validated, relaxed-binding config. Externalize secrets via environment variables or a secrets manager, never commit them. Precedence (high to low) includes command line, env vars, profile files, then default file.
```java
@ConfigurationProperties("cx.jwt") @Validated
record JwtProps(@NotBlank String issuer, @Positive long ttlSeconds) {}
```
**In this project.** Two runtime profiles: `local` (default; H2 in PostgreSQL mode, Caffeine, in-memory relay, in-memory AI/KB store) and `docker` (PostgreSQL, Redis, RabbitMQ, MongoDB); Flyway has `db/migration` for portable SQL and `db/migration-pg` (partial indexes, GIN full text, RLS) only under `docker`. Seed passwords are dummy local values.
**Follow-ups.**
- *Risk of H2 in PG mode locally?* Behavioral drift (locking, JSON, index types); hence docker-only tests for PG-specific features, honestly reported as not executed locally.
- *Fail fast on bad config?* `@Validated` on properties.

### Q32. How does `@Transactional` work, and why does self-invocation break it? `[Spring]`
**Answer.** Spring wraps the bean in a proxy (JDK dynamic if it implements interfaces and proxyTargetClass is off; CGLIB subclass otherwise - Boot defaults to CGLIB). The proxy's interceptor opens a transaction via `PlatformTransactionManager`, binds the connection to the thread, invokes the target, then commits or rolls back. Calling `this.otherTransactionalMethod()` inside the same class bypasses the proxy, so no advice runs. Also, `@Transactional` on private/final methods or on non-Spring-managed objects is ignored. Fixes: move the method to another bean, inject self, or use `TransactionTemplate`.
```java
@Service class A { @Transactional public void outer() { inner(); } // inner's REQUIRES_NEW ignored
                   @Transactional(propagation = REQUIRES_NEW) public void inner() {} }
```
**Follow-ups.**
- *Where should the boundary be?* The service/use-case layer, not controllers or repositories.
- *Does the transaction cover lazy loading in the view?* Not with open-in-view disabled (recommended).

### Q33. Propagation and isolation levels. `[Spring] [DB]`
**Answer.** Propagation: `REQUIRED` (join or create, default), `REQUIRES_NEW` (suspend and start new, e.g. audit that must persist even if outer fails), `NESTED` (savepoint), `SUPPORTS`, `MANDATORY`, `NEVER`, `NOT_SUPPORTED`. Isolation: READ_COMMITTED (PostgreSQL default) prevents dirty reads; REPEATABLE_READ (MySQL InnoDB default; in PostgreSQL it's snapshot isolation) prevents non-repeatable reads; SERIALIZABLE prevents write skew but may abort with serialization failures that you must retry. Higher isolation is rarely the right fix; prefer constraints, optimistic locking, or targeted `SELECT ... FOR UPDATE`.
**Follow-ups.**
- *Example of write skew?* Two agents both see "least loaded = 3 tickets" and both self-assign the last slot; a constraint or lock is needed.
- *Does `REQUIRES_NEW` get its own connection?* Yes, so beware pool exhaustion when nested.

### Q34. Rollback rules and `readOnly`. `[Spring]`
**Answer.** By default only `RuntimeException` and `Error` trigger rollback; checked exceptions commit unless `rollbackFor` is set. A caught exception inside the method means the proxy never sees it, so no rollback. If an inner `REQUIRED` call marks the transaction rollback-only and the outer swallows the exception, commit fails with `UnexpectedRollbackException`. `readOnly=true` is a hint: Hibernate skips dirty checking/flush, the driver/DB may route to replicas or optimise, but it does not forbid writes on all setups.
```java
@Transactional(rollbackFor = Exception.class) public void importTickets() throws IOException { ... }
```
**Follow-ups.**
- *Timeout?* `@Transactional(timeout=5)` is enforced at statement level when supported.
- *Why keep transactions short?* Long transactions hold locks and connections.

### Q35. The N+1 problem and `LazyInitializationException`. `[Spring] [DB]`
**Answer.** N+1: loading N tickets then lazily touching each ticket's `assignee` fires N extra queries. Fix with `join fetch`, `@EntityGraph`, batch fetching (`hibernate.default_batch_fetch_size`), or DTO projections that select only needed columns. `LazyInitializationException` occurs when a lazy association is accessed after the session closed; fix by fetching inside the transaction or projecting to a DTO, not by enabling open-session-in-view. Beware: `join fetch` with collections and pagination makes Hibernate paginate in memory (warning `HHH000104`); fetch ids first, then entities.
```java
@EntityGraph(attributePaths = {"assignee", "category"})
Page<Ticket> findByStatus(Status s, Pageable p);
```
**Follow-ups.**
- *Detect N+1?* Enable SQL logging/statistics in tests and assert query counts.
- *`FetchType.EAGER` as a fix?* No; it is global and often worse.

### Q36. Optimistic locking with `@Version` and how it maps to the API. `[Spring] [DB]`
**Answer.** An `@Version` column is incremented on each update, and Hibernate issues `UPDATE ... WHERE id=? AND version=?`; if 0 rows change it throws `OptimisticLockException`/`ObjectOptimisticLockingFailureException`. No DB locks are held, so throughput is high; conflicts are rare in practice, and the client resolves them (reload and retry).
**In this project.** `Ticket` carries `version`. `PUT /tickets/{id}`, `assign` and `transition` require `version` in the body and return **409 `TICKET_VERSION_CONFLICT`** on a stale version. Typical UI behavior: show "ticket changed, reload" and re-fetch (Q58).
```java
@Entity class Ticket { @Id Long id; @Version long version; /* ... */ }
```
**General concept.** The version in the body (rather than `If-Match`) is functionally the same as an ETag precondition (Q82).
**Follow-ups.**
- *Does the client-sent version get applied automatically?* You must compare/set it onto the loaded entity (or check explicitly); otherwise Hibernate only protects within the loaded snapshot.
- *When is pessimistic better?* High contention hotspots (e.g. a counter row) - Q37.

### Q37. Optimistic vs pessimistic locking; `SKIP LOCKED` for work queues. `[DB] [Design]`
**Answer.** Optimistic: detect conflict at write; pessimistic: lock the row up front (`@Lock(PESSIMISTIC_WRITE)` -> `SELECT ... FOR UPDATE`). Pick optimistic for low-contention user edits, pessimistic for short critical sections with high contention or when retry is costly. `FOR UPDATE SKIP LOCKED` lets many workers pull distinct rows from a table-as-queue (such as an outbox relay) without blocking each other. Always acquire locks in a consistent order and keep the transaction short.
```sql
SELECT id FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED;
```
**General concept.** `SKIP LOCKED` is PostgreSQL syntax; H2 support varies, which is why the local profile uses an in-memory relay (ARCH-CONSTRAINTS).
**Follow-ups.**
- *Retry strategy on optimistic failure?* Bounded retry with jitter for server-side merges; surface 409 to humans.
- *Lock timeout?* Set `lock_timeout` to avoid indefinite waits.

### Q38. How does a Hibernate tenant filter work, and where can it leak? `[Spring] [Design]`
**Answer.** Shared-schema multi-tenancy: every tenant-owned table has `tenant_id`. A Hibernate `@FilterDef`/`@Filter` adds `tenant_id = :tenantId` to entity queries; the filter is enabled per session/transaction with the tenant from the security context. Leak risks: (1) `EntityManager.find`/`getReference` by primary key do NOT apply filters, so a guessed id from another tenant would load - add an explicit tenant check or query by `(id, tenant_id)`; (2) native SQL and JPQL bulk updates may bypass it; (3) filter not enabled on background threads/consumers; (4) second-level or application caches keyed without tenant. Defense in depth: also scope unique constraints/indexes by tenant and optionally use PostgreSQL RLS (Q39).
```java
session.enableFilter("tenantFilter").setParameter("tenantId", TenantContext.get());
```
**In this project.** Tenant comes from the JWT `tid` claim, never from client input; cross-tenant ids return `NOT_FOUND` rather than `FORBIDDEN`. The Hibernate tenant filter is the primary mechanism, with PostgreSQL RLS optional under the `docker` profile.
**Follow-ups.**
- *Why 404 not 403 for another tenant's ticket?* Do not reveal existence.
- *How to test isolation?* Create data in tenant A, call as tenant B, assert 404 for GET/PUT/transition and absence from lists.

### Q39. What is PostgreSQL Row-Level Security and how do you wire it with a pool? `[DB] [Spring]`
**Answer.** RLS attaches policies to tables (`CREATE POLICY ... USING (tenant_id = current_setting('app.tenant_id')::uuid)`) and `ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY`, so the DB itself rejects cross-tenant reads/writes even if app code forgets a filter. The app sets `SET LOCAL app.tenant_id = '...'` (or `set_config(..., true)`) at the start of each transaction; `LOCAL` scopes it to the transaction, which is essential with connection pools (a session-level `SET` would leak to the next borrower). The app role must not be a superuser or table owner (owners bypass RLS unless `FORCE`).
```sql
ALTER TABLE ticket ENABLE ROW LEVEL SECURITY; ALTER TABLE ticket FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ticket
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```
**In this project.** RLS is optional and lives in the PostgreSQL-only migrations (`db/migration-pg`, `docker` profile). It was not executed on the dev machine (no Docker); say so.
**Follow-ups.**
- *Performance?* Policy predicates are added to every query; make sure `tenant_id` leads relevant indexes.
- *What if `current_setting` is unset?* Use the `missing_ok=true` form so it yields NULL and matches nothing (fail closed).

### Q40. Explain the Spring Security filter chain for a stateless JWT API. `[Spring]`
**Answer.** Requests pass through a `SecurityFilterChain` of ordered filters. For a stateless API: disable session creation (`SessionCreationPolicy.STATELESS`), disable CSRF if you use Authorization headers rather than cookies, add a custom `OncePerRequestFilter` before `UsernamePasswordAuthenticationFilter` that parses `Authorization: Bearer`, validates the token and sets an `Authentication` (with authorities from `roles`) into `SecurityContextHolder`. Authorization via `authorizeHttpRequests` plus method security (`@PreAuthorize("hasRole('ADMIN')")`). Authentication failure -> `AuthenticationEntryPoint` (401); authorization failure -> `AccessDeniedHandler` (403); map both to the JSON error contract.
```java
http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
    .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/login", "/actuator/health/**").permitAll()
                                  .anyRequest().authenticated())
    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```
**In this project.** Roles `CUSTOMER, AGENT, MANAGER, ADMIN` come from the `roles` claim; endpoints are annotated in the contract (e.g. workflow read MANAGER+, write ADMIN). Row-level rules (customers see only their own tickets, no INTERNAL comments) need object-level checks too (Q87).
**Follow-ups.**
- *Why disable CSRF?* No ambient cookie credentials; if tokens are in cookies, keep CSRF protection.
- *Role vs authority prefix?* `hasRole('ADMIN')` expects `ROLE_ADMIN`.

### Q41. How should a JWT be validated, and how does logout work? `[Spring] [API]`
**Answer.** Verify signature with the expected algorithm (reject `alg=none` and algorithm confusion), `exp`/`nbf` with small clock skew, issuer and audience, then extract claims. Keep tokens short-lived. Stateless tokens cannot be recalled, so for logout/compromise keep a denylist of `jti` (in Redis or in memory with TTL equal to token remaining life) and check it in the filter. Signing keys come from config/secret store and are rotated (support `kid`). Do not put sensitive data in claims; they are only encoded.
**In this project.** Claims: `sub`=userId, `tid`=tenantId, `roles`, `email`, `jti`. `POST /auth/logout` returns 204 and "revokes token jti". Switching tenants = re-login, so a token is bound to exactly one tenant.
**Follow-ups.**
- *Where do you store the token in a browser?* Memory (plus refresh via httpOnly cookie) is safest against XSS; localStorage is easy but readable by injected script (Q64).
- *Revocation list size problem?* Entries expire with the token, so it stays bounded.

### Q42. Bean Validation: `@Valid`, groups and the `fieldErrors` response. `[Spring] [API]`
**Answer.** Annotate request DTOs (`@NotBlank`, `@Size`, `@Email`, `@Positive`) and add `@Valid` on `@RequestBody` (or `@Validated` on the class for path/query params). Failures raise `MethodArgumentNotValidException` (body) or `ConstraintViolationException` (params), which a `@RestControllerAdvice` converts to a 400 with per-field messages. Validation proves shape, not business rules (state transitions, uniqueness, cross-tenant references) - those belong in the service and map to 422/409. Custom constraints implement `ConstraintValidator`.
```java
record CreateTicket(@NotBlank @Size(max = 200) String title, @NotNull Priority priority) {}
```
**In this project.** Failures return `VALIDATION_FAILED` (400) with `fieldErrors:[{field,message}]`; invalid status changes return `INVALID_TRANSITION` (422).
**Follow-ups.**
- *400 vs 422 split?* Syntax/shape errors 400; well-formed but semantically unacceptable (rule violation) 422.
- *Validate enums safely?* Unknown enum text causes `HttpMessageNotReadableException`; handle it as 400.

### Q43. Centralised exception handling with `@RestControllerAdvice`. `[Spring] [API]`
**Answer.** One advice class maps exception types to the error schema, ensuring consistent shape and never leaking stack traces. Include correlation id from MDC so support can find logs. Order handlers from specific to generic; the fallback logs at error level with the full exception and returns a generic `INTERNAL_ERROR`. Spring 6 also supports `ProblemDetail` (RFC 7807/9457).
```java
@RestControllerAdvice class Errors {
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ApiError> conflict(OptimisticLockingFailureException e, HttpServletRequest r) {
    return ResponseEntity.status(409).body(ApiError.of(409, "CONFLICT", "TICKET_VERSION_CONFLICT", "Ticket was modified", r));
  }
}
```
**In this project.** Documented codes: `VALIDATION_FAILED(400), UNAUTHENTICATED(401), FORBIDDEN(403), NOT_FOUND(404), TICKET_VERSION_CONFLICT(409), DUPLICATE_SUBMISSION(409), INVALID_TRANSITION(422), RATE_LIMITED(429), AI_UNAVAILABLE(503), INTERNAL_ERROR(500)`; the schema includes `correlationId`.
**Follow-ups.**
- *Errors thrown in filters?* `@ControllerAdvice` does not see them; use the entry point/handlers or a filter that catches.
- *Should clients branch on `message`?* No, on `code`; messages are human text.

### Q44. Spring Boot Actuator: what do you expose and how do you secure it? `[Spring]`
**Answer.** Actuator offers health, metrics, info, env, loggers, threaddump, heapdump, etc. Expose the minimum over HTTP (`management.endpoints.web.exposure.include=health,prometheus,info`), protect the sensitive ones with security and ideally put management on a separate port/network. Health groups: liveness (is the process alive; restart if not) vs readiness (can it accept traffic; depends on DB/broker). Metrics via Micrometer; Prometheus scraping at `/actuator/prometheus`. Add custom `HealthIndicator`s for critical dependencies but avoid making liveness depend on downstream services (cascading restarts).
**In this project.** Ops endpoints in the contract: `/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`, plus Swagger UI per service.
**Follow-ups.**
- *Danger of exposing `env`/`heapdump`?* Secret leakage; keep disabled or admin-only.
- *Business metric example?* A counter of tickets created per tenant tag (watch cardinality).

### Q45. The transactional outbox pattern in Spring. `[Spring] [Design]`
**Answer.** Problem: "write DB then publish message" is a dual write; a crash between them loses or duplicates events. Outbox: in the same local transaction that changes state, insert an event row into an `outbox` table. A relay (poller or CDC) reads unpublished rows, publishes to the broker, then marks them published. Delivery becomes at-least-once, so consumers must be idempotent (Q49). Ordering per aggregate can be preserved by keying/partitioning and ordering by id. Clean up published rows periodically.
```java
@Transactional
public Ticket create(CreateTicket cmd) {
    Ticket t = repo.save(Ticket.from(cmd));
    outbox.save(new OutboxEvent("TicketCreated", t.getId(), toJson(t)));
    return t; // commit persists both atomically
}
```
**In this project.** Outbox is a stated design element; locally an "in-memory event relay" stands in for RabbitMQ, via the `EventPublisher/relay` interface (ARCH-CONSTRAINTS).
**Follow-ups.**
- *Why not `@TransactionalEventListener(AFTER_COMMIT)` publishing directly?* If the process dies after commit but before publish, the event is lost; outbox row survives.
- *Poller vs CDC?* Poller is simple; CDC (Debezium) lowers latency/load but adds infra.

### Q46. JDK proxies vs CGLIB and what AOP can't do. `[Spring]`
**Answer.** Spring AOP is proxy-based: JDK dynamic proxies implement interfaces; CGLIB subclasses the class (so the class and methods cannot be `final`). Advice only applies to calls entering through the proxy, to public (effectively) methods of Spring beans; self-invocation, private methods and `new`-ed objects are not advised. AspectJ weaving (compile/load-time) removes those limits at the cost of build complexity. Common uses: transactions, caching (`@Cacheable`), security, metrics/timing aspects.
```java
@Aspect @Component class Timing {
  @Around("@annotation(Timed)") Object t(ProceedingJoinPoint p) throws Throwable { long s = System.nanoTime(); try { return p.proceed(); } finally { /* record */ } }
}
```
**Follow-ups.**
- *Why can't you `@Autowired` the concrete class when a JDK proxy is used?* The proxy implements the interface, not the class; Boot's default CGLIB avoids this.
- *Order of multiple aspects?* `@Order`.

### Q47. Cache-aside with Caffeine/Redis in Spring and its tenant-key pitfalls. `[Spring] [Design]`
**Answer.** Cache-aside: read from cache, on miss load from DB and populate; on write, evict or update. Spring's abstraction (`@Cacheable`, `@CacheEvict`) works with Caffeine (in-process, fastest, per-instance) or Redis (shared across instances). Choose TTL by staleness tolerance, bound size, protect against stampede (single-flight / `sync=true`, jittered TTLs), and cache negative results briefly if misses are expensive. In multi-tenant systems the tenant id MUST be part of every key, or one tenant's data is served to another.
```java
@Cacheable(cacheNames = "dashboard", key = "T(com.example.TenantContext).get() + ':' + #days")
public Dashboard dashboard(int days) { ... }
```
**In this project.** The analytics dashboard uses Redis cache-aside with TTL 60 s and tenant-keyed entries (per contract); the `local` profile uses an in-memory Caffeine implementation behind `CacheService`.
**Follow-ups.**
- *Staleness acceptable?* For a dashboard, up to the 60 s TTL; not for ticket detail pages needing version freshness.
- *Caffeine + Redis together?* Two-level cache; invalidation across instances needs pub/sub or short local TTLs.

### Q48. Testing strategy for Spring Boot: slices, containers, honesty about what ran. `[Spring]`
**Answer.** Pyramid: many plain unit tests (no Spring), fewer slice tests (`@WebMvcTest` with mocked services for controllers and error contract; `@DataJpaTest` for repositories), and few full `@SpringBootTest` integration tests. Testcontainers gives real PostgreSQL/RabbitMQ for behaviours H2 cannot reproduce (RLS, partial indexes, `SKIP LOCKED`). Test the contract: status codes, error codes, tenant isolation, optimistic conflict, idempotency replay. Keep time and randomness injectable (`Clock`).
**In this project.** Tests requiring Docker are tagged `@Tag("docker")` / named `*DockerIT`, excluded by default via surefire `excludedGroups=docker`, and are reported as NOT EXECUTED when Docker is unavailable (it is not installed on the dev machine).
**Follow-ups.**
- *Why not use H2 for everything?* Dialect drift gives false confidence.
- *`@MockBean` downsides?* Dirties the context cache, slowing suites.

### Q49. RabbitMQ with Spring AMQP: retry, DLQ and idempotent consumers. `[Spring] [Design]`
**Answer.** At-least-once delivery means duplicates happen. Make consumers idempotent: record processed message ids (unique constraint on `message_id` in the same transaction as the side effect) or make the operation naturally idempotent (upsert). For failures: transient errors -> bounded retries with backoff (Spring Retry or delayed requeue); poison messages -> reject without requeue so the queue's dead-letter exchange routes them to a DLQ for inspection and replay. Use manual or `AUTO` ack deliberately, set prefetch, and keep handlers small.
```yaml
spring.rabbitmq.listener.simple:
  retry: { enabled: true, max-attempts: 3, initial-interval: 1s, multiplier: 2 }
  default-requeue-rejected: false   # exhausted messages go to the DLQ via x-dead-letter-exchange
```
**In this project.** Design is RabbitMQ with retry + DLQ and an idempotent consumer (notification-service). Docker was unavailable on the dev machine, so broker behaviour was not executed locally (local uses an in-memory relay).
**Follow-ups.**
- *Exactly-once?* Not achievable end to end; at-least-once + idempotency gives effectively-once.
- *Message ordering with multiple consumers?* Lost unless partitioned by key (consistent-hash exchange / single active consumer).

### Q50. Filters vs interceptors vs aspects; correlation ids and MDC. `[Spring]`
**Answer.** Servlet `Filter` (incl. `OncePerRequestFilter`) runs outermost, before Spring MVC - the place for correlation id, security, request logging. `HandlerInterceptor` runs around controller dispatch with handler info. AOP advice wraps bean method calls (service layer). A correlation filter reads or creates `X-Correlation-Id`, puts it in `MDC`, adds it to the response and clears MDC in `finally`; log patterns include `%X{correlationId}`. When publishing events or calling downstream services, forward the id so one request is traceable across services.
```java
String cid = Optional.ofNullable(req.getHeader("X-Correlation-Id")).orElse(UUID.randomUUID().toString());
MDC.put("correlationId", cid); res.setHeader("X-Correlation-Id", cid);
try { chain.doFilter(req, res); } finally { MDC.remove("correlationId"); }
```
**In this project.** The contract accepts and returns `X-Correlation-Id`, and error bodies carry `correlationId`.
**Follow-ups.**
- *MDC and async?* MDC is thread-local; copy it with a `TaskDecorator`.
- *Interceptor vs filter for auth?* Filter (or Security) so it also protects static/error paths.

---

# Section 3 - React and TypeScript (Q51-Q70)

### Q51. How does React reconcile updates, and why do keys matter? `[React]`
**Answer.** React renders components into a tree of elements (the "virtual DOM"), diffs the new tree against the previous one, and commits minimal DOM changes. The diff is O(n) thanks to two heuristics: elements of different types produce different trees (remount), and lists are matched by `key`. Stable, unique keys (ticket id) let React move/reuse items and preserve their state; index keys break when the list is reordered or filtered (inputs show the wrong text, state attaches to the wrong row). Since React 18 rendering can be interruptible (concurrent), but the commit phase is synchronous.
```tsx
{tickets.map(t => <TicketRow key={t.id} ticket={t} />)}  // not key={index}
```
**Follow-ups.**
- *Is the virtual DOM always faster than direct DOM?* No; it buys declarative simplicity with acceptable cost; hot paths may need memoisation or virtualisation.
- *How to deliberately reset component state?* Change its `key`.

### Q52. `useState` pitfalls: functional updates, batching, derived state. `[React]`
**Answer.** State updates are asynchronous and batched (automatic batching in React 18 across events, timeouts, promises). `setCount(count + 1)` twice uses a stale value; `setCount(c => c + 1)` composes correctly. Do not mirror props into state (`useState(props.x)` ignores later prop changes); compute derived values during render or with `useMemo`. Never mutate state objects/arrays; create new ones (`[...items, x]`) so reference equality detects change. Lift state to the nearest common parent; keep server data out of local state (see TanStack Query, Q57).
```tsx
setSelected(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
```
**Follow-ups.**
- *Lazy initial state?* `useState(() => expensive())` runs once.
- *When is `flushSync` needed?* Rarely: to read DOM synchronously after an update.

### Q53. `useEffect`: dependencies, cleanup, stale closures, StrictMode. `[React]`
**Answer.** An effect synchronises the component with something external (subscriptions, timers, non-React widgets). Dependencies list every reactive value used inside; omitting them creates stale closures (the effect sees old props/state). Return a cleanup to unsubscribe/abort. Effects are not for deriving data or handling user events. In development StrictMode mounts, unmounts and remounts to expose missing cleanups, so effects must be idempotent. For fetching, prefer a data library; if hand-rolling, abort in-flight requests.
```tsx
useEffect(() => {
  const ac = new AbortController();
  fetch(`/api/v1/tickets/${id}`, { signal: ac.signal }).then(r => r.json()).then(setTicket).catch(e => { if (e.name !== 'AbortError') setError(e); });
  return () => ac.abort();
}, [id]);
```
**Follow-ups.**
- *Why does an object in deps retrigger every render?* New reference each render; memoise or depend on primitives.
- *`useLayoutEffect` vs `useEffect`?* Layout effect runs synchronously before paint (measure DOM), blocks paint if heavy.

### Q54. When do `React.memo`, `useMemo`, `useCallback` help? `[React]`
**Answer.** They help when (a) a child is expensive and re-renders with unchanged props (`React.memo`), (b) a computation is genuinely costly (`useMemo`), or (c) a function/object identity is a dependency of a memoised child or an effect (`useCallback`). They add overhead and complexity otherwise, so measure with the React DevTools Profiler first. Memoisation only works if props are referentially stable: passing an inline object or function to a memoised child defeats it. Moving state down (colocation) or passing `children` often removes the need entirely.
```tsx
const Row = React.memo(function Row({ ticket, onOpen }: Props) { /* ... */ });
const onOpen = useCallback((id: string) => navigate(`/tickets/${id}`), [navigate]);
```
**Follow-ups.**
- *Does `useMemo` guarantee caching?* No, it is a performance hint; React may discard it.
- *React Compiler?* It auto-memoises, reducing manual hooks, but the same referential rules still underlie it.

### Q55. `useRef`, `useReducer`, and writing custom hooks. `[React]`
**Answer.** `useRef` holds a mutable value that does not trigger re-render (DOM nodes, timers, latest-callback pattern). `useReducer` suits state with many related transitions (e.g. a multi-step ticket wizard) and gives testable pure reducers. Custom hooks extract reusable stateful logic (`useDebouncedValue`, `useTicketFilters`); they follow the rules of hooks (call at top level, only from components/hooks) and can compose other hooks.
```tsx
function useDebounced<T>(value: T, ms = 300) {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}
```
**Follow-ups.**
- *Why can't hooks be called conditionally?* React matches hooks by call order.
- *`useReducer` vs Redux?* Same pattern at component scope; Redux adds global store, devtools and middleware.

### Q56. Controlled vs uncontrolled forms and validation. `[React]`
**Answer.** Controlled inputs keep value in React state (`value` + `onChange`), enabling instant validation and dependent fields but re-rendering on each keystroke; uncontrolled inputs (refs / `FormData`, as in React Hook Form) read the DOM on submit and scale better for big forms. Validate with a schema (zod/yup) shared between field-level and submit-level, show errors next to fields, associate them for accessibility, disable the submit button while pending, and map server `fieldErrors` back onto fields.
```tsx
const parsed = schema.safeParse(values);
if (!parsed.success) setErrors(Object.fromEntries(parsed.error.issues.map(i => [i.path[0], i.message])));
```
**In this project.** The API returns `VALIDATION_FAILED` with `fieldErrors:[{field,message}]`, which maps directly onto form fields. Client validation is for UX only; the server is authoritative.
**Follow-ups.**
- *Where do uncontrolled inputs bite?* Programmatic resets and conditional defaults need `defaultValue`/`reset` handling.
- *How to prevent double submit?* Disable and also send an `Idempotency-Key` (Q59).

### Q57. How does TanStack Query caching work? `[React]`
**Answer.** Each query is identified by a `queryKey` (array, serialised deterministically); data is cached by key. `staleTime` is how long data counts as fresh (default 0: stale immediately, so mounting/focus/reconnect triggers background refetch); `gcTime` (formerly `cacheTime`, default 5 min) is how long unused cache is kept. The UI shows cached data instantly and revalidates (stale-while-revalidate). Mutations do not touch the cache by themselves: invalidate (`queryClient.invalidateQueries({queryKey:['tickets']})`) or update the cache with the response (`setQueryData`). Include every input in the key (filters, page, tenant/user) so caches never mix.
```tsx
const { data, isPending, error } = useQuery({
  queryKey: ['tickets', { page, status, q }],
  queryFn: ({ signal }) => api.listTickets({ page, status, q }, signal),
  placeholderData: keepPreviousData, staleTime: 15_000,
});
```
**In this project.** Server state (tickets, comments, notifications) is the textbook case for this library; on logout/tenant switch (= re-login) the whole cache must be cleared (`queryClient.clear()`) so a previous tenant's data is never shown.
**Follow-ups.**
- *`isPending` vs `isFetching`?* Pending = no data yet; fetching = any in-flight request incl. background refetch.
- *Pagination flicker?* Use `placeholderData: keepPreviousData`.

### Q58. Optimistic updates and handling a 409 version conflict in the UI. `[React] [API]`
**Answer.** Optimistic update flow with TanStack Query: `onMutate` cancels outgoing queries, snapshots the cache, writes the optimistic value; `onError` rolls back from the snapshot; `onSettled` invalidates to reconcile with the server. For version conflicts, the 409 body carries `code: TICKET_VERSION_CONFLICT`; the UI should not silently overwrite - refetch the ticket, show a "this ticket was updated by someone else" message, and let the user re-apply their change on the new version. Optimism is good for low-risk toggles (assign), worse for rule-bound transitions where the server may refuse (`INVALID_TRANSITION` 422).
```tsx
useMutation({
  mutationFn: (v: { id: string; toStatus: Status; version: number }) => api.transition(v),
  onError: (e: ApiError) => { if (e.code === 'TICKET_VERSION_CONFLICT') qc.invalidateQueries({ queryKey: ['ticket', id] }); },
});
```
**In this project.** `transition` and `assign` carry `version` and 409 on staleness (Q36). After success the response contains the new `version`, which the client must use for the next call.
**Follow-ups.**
- *Why cancel queries in `onMutate`?* An in-flight refetch could overwrite the optimistic value.
- *Merge vs reject on conflict?* Field-level merge only if the product defines it; otherwise reload.

### Q59. Prevent duplicate ticket creation from the client (Idempotency-Key). `[React] [API]`
**Answer.** Generate one `Idempotency-Key` (UUID) per user intent - when the form mounts or when the user first submits - and reuse it for retries of the same submission (network retry, double click); generate a new one for a new ticket. The server replays the original response for the same key. Pair with disabling the button while pending.
```tsx
const keyRef = useRef(crypto.randomUUID());          // new form instance => new intent
const create = useMutation({
  mutationFn: (body: NewTicket) => api.createTicket(body, keyRef.current),
  onSuccess: () => { keyRef.current = crypto.randomUUID(); },  // next ticket gets a fresh key
});
```
**In this project.** `POST /tickets` requires the `Idempotency-Key` header and "same key replays original response"; `DUPLICATE_SUBMISSION` (409) exists as an error code. (Behaviour for same key with a different body is not specified in the contract; typical designs reject it - see Q77.)
**Follow-ups.**
- *Why not generate the key inside `mutationFn`?* Each retry would get a new key, defeating idempotency.
- *Storage across reloads?* Optional: `sessionStorage` if you want a refresh mid-submit to be safe.

### Q60. Error boundaries: what they catch and don't. `[React]`
**Answer.** An error boundary is a class component with `getDerivedStateFromError` (render fallback) and `componentDidCatch` (log). It catches errors during rendering, lifecycle methods and constructors of its descendants. It does NOT catch errors in event handlers, async code (promises, timeouts), SSR, or in the boundary itself. Place boundaries at route and widget level so one failing panel does not blank the app; offer a reset (`resetKeys`) and log to monitoring with the correlation id. `react-error-boundary` provides a hook-friendly wrapper.
```tsx
class Boundary extends React.Component<{children: React.ReactNode}, {err?: Error}> {
  state = { err: undefined as Error | undefined };
  static getDerivedStateFromError(err: Error) { return { err }; }
  componentDidCatch(err: Error, info: React.ErrorInfo) { report(err, info.componentStack); }
  render() { return this.state.err ? <p role="alert">Something went wrong.</p> : this.props.children; }
}
```
**Follow-ups.**
- *Handle async errors?* Query libraries expose `error` state; or `useErrorBoundary` to rethrow into the boundary.
- *Why `role="alert"`?* Screen readers announce it.

### Q61. Accessibility essentials for a ticket UI. `[React]`
**Answer.** Use semantic HTML first (`button`, `a`, `table`, `label`, headings) - it gives keyboard and screen reader behaviour for free. Every input needs an associated `<label htmlFor>`; errors linked via `aria-describedby` and `aria-invalid`. Dynamic updates (toast, new notification count) use `aria-live="polite"`/`role="status"`. Manage focus on route changes and when opening/closing dialogs (trap focus, return it to the trigger, close on Escape). Ensure colour contrast (4.5:1 text), do not rely on colour alone for SLA status (add text/icon: "Breached"), visible focus outlines, and touch targets of adequate size. Test with keyboard-only navigation, axe/Lighthouse, and a screen reader.
```tsx
<label htmlFor="title">Title</label>
<input id="title" aria-invalid={!!err} aria-describedby={err ? 'title-err' : undefined} />
{err && <p id="title-err" role="alert">{err}</p>}
```
**Follow-ups.**
- *`div onClick` vs `button`?* The div has no focus, key handling or role; the button does.
- *Skip links?* Let keyboard users bypass navigation.

### Q62. TypeScript: unions, discriminated unions and exhaustive checks. `[TS]`
**Answer.** Model finite states with literal unions and narrow with a discriminant; the compiler then enforces exhaustiveness via `never`. This eliminates impossible states (e.g. `{loading:false, data:undefined, error:undefined}`).
```ts
type Status = 'NEW'|'OPEN'|'PENDING'|'ON_HOLD'|'RESOLVED'|'CLOSED'|'REOPENED';
type Load<T> = { kind:'loading' } | { kind:'error'; error: ApiError } | { kind:'ok'; data: T };
function assertNever(x: never): never { throw new Error(`unhandled ${String(x)}`); }
function label(s: Status): string {
  switch (s) { case 'NEW': return 'New'; case 'OPEN': return 'Open'; case 'PENDING': return 'Pending';
    case 'ON_HOLD': return 'On hold'; case 'RESOLVED': return 'Resolved'; case 'CLOSED': return 'Closed';
    case 'REOPENED': return 'Reopened'; default: return assertNever(s); }
}
```
**In this project.** The status set `NEW,OPEN,PENDING,ON_HOLD,RESOLVED,CLOSED,REOPENED` and priorities `LOW|MEDIUM|HIGH|URGENT` come from the contract and map naturally to unions.
**Follow-ups.**
- *`enum` vs union of literals?* Unions are erased, tree-shake and serialise as plain strings; enums emit runtime code.
- *`as const`?* Freezes literals so arrays/objects infer narrow types.

### Q63. TypeScript utility types, `unknown` vs `any`, and runtime validation of API data. `[TS]`
**Answer.** `any` turns off checking; `unknown` forces narrowing before use - prefer it for external data. Utility types: `Partial`, `Required`, `Pick`, `Omit`, `Record`, `ReturnType`, `Readonly`. Types are erased at runtime, so an API response typed as `Ticket` is only a promise; validate at the boundary (zod `parse`) or generate types from OpenAPI so drift is caught. Prefer `satisfies` for checking shape without widening. Enable `strict`, `noUncheckedIndexedAccess`, and avoid non-null `!` and unchecked casts.
```ts
const TicketSchema = z.object({ id: z.string(), title: z.string(), version: z.number() });
type Ticket = z.infer<typeof TicketSchema>;
const ticket = TicketSchema.parse(await res.json());
```
**Follow-ups.**
- *`interface` vs `type`?* Interfaces merge and are preferred for object shapes that extend; types handle unions/mapped types.
- *Generics constraint example?* `function pick<T, K extends keyof T>(o: T, k: K): T[K]`.

### Q64. Auth in the SPA: token storage, context and XSS. `[React] [API]`
**Answer.** An access token in `localStorage` is readable by any injected script; keeping it in memory (React state/closure) limits exposure to XSS but is lost on refresh, so pair with a short-lived token plus an httpOnly, `SameSite` refresh cookie, or accept re-login. Cookie-based auth needs CSRF protection. Centralise the token in an auth provider / API client that attaches `Authorization: Bearer`, handles 401 (clear state, redirect to login), and clears the query cache on logout. Hide UI by role for UX, but remember authorization is enforced on the server.
**In this project.** Login returns `{accessToken, expiresInSeconds, user}`; `GET /auth/me` rehydrates the user; `POST /auth/logout` revokes the `jti`. A refresh-token flow is not in the contract - do not claim one.
**Follow-ups.**
- *CSP role?* A strict Content-Security-Policy reduces XSS impact (Q75).
- *Role-based UI = security?* No; it is presentation, enforcement is server-side.

### Q65. Routing, code splitting and route guards. `[React] [Browser]`
**Answer.** Split bundles per route with `React.lazy` + `Suspense` (or the router's lazy routes) so the ticket list does not download the admin/analytics code. Guards: a wrapper route checks `isAuthenticated` and required role, redirecting to login (preserving `from`). Keep URLs as the source of truth for list state (page, filters, sort in query string) so views are shareable and back/forward works. Preload likely next routes on hover/idle.
```tsx
const Analytics = React.lazy(() => import('./pages/Analytics'));
<Route element={<RequireRole roles={['MANAGER','ADMIN']} />}><Route path="analytics" element={<Suspense fallback={<Spinner/>}><Analytics/></Suspense>} /></Route>
```
**Follow-ups.**
- *Where should filters live: state or URL?* URL search params for shareable lists.
- *Error for chunk load failure after deploy?* Catch and prompt refresh (stale hashed chunks).

### Q66. React 18 concurrent features and StrictMode. `[React]`
**Answer.** `useTransition` marks a state update as non-urgent so typing stays responsive while a heavy list re-renders; `useDeferredValue` gives a lagging copy of a value for the expensive part. Suspense can show fallbacks while data/code loads. Automatic batching groups updates. StrictMode (dev only) double-invokes render and effects to surface impure renders and missing cleanups - failures there are real bugs, not StrictMode bugs. Render functions must be pure: no mutation of props/external variables, no side effects.
```tsx
const [isPending, startTransition] = useTransition();
onChange={e => { setInput(e.target.value); startTransition(() => setFilter(e.target.value)); }}
```
**Follow-ups.**
- *Does a transition make rendering faster?* No, it makes it interruptible and lower priority.
- *Why can effects run twice in dev?* StrictMode checks cleanup symmetry.

### Q67. Testing React with React Testing Library and mocked network. `[React]`
**Answer.** Test behaviour as a user would: query by role/label/text (`getByRole('button', {name:/create/i})`), use `userEvent`, and await async UI with `findBy*`. Mock the network at the boundary (MSW) rather than mocking hooks, so the real query/caching code runs. Assert accessible outcomes (alerts, disabled states) and error paths (409, 422, 503 degraded AI). Avoid testing implementation details (state values, internal component names). Create a fresh `QueryClient` per test with `retry:false`.
```tsx
server.use(http.post('/api/v1/tickets', () => HttpResponse.json({ code:'VALIDATION_FAILED', fieldErrors:[{field:'title',message:'must not be blank'}] }, { status: 400 })));
await user.click(screen.getByRole('button', { name: /create/i }));
expect(await screen.findByText(/must not be blank/i)).toBeInTheDocument();
```
**Follow-ups.**
- *Snapshot tests?* Brittle for large trees; use sparingly.
- *E2E vs component tests?* E2E (Playwright) for critical flows; component tests for the rest.

### Q68. Rendering 10,000 tickets: pagination, virtualisation, memoisation. `[React] [Browser]`
**Answer.** First choice is server-side pagination (the API caps `size` at 100), which keeps payloads and DOM small. If a long scroll list is required, virtualise (react-window/TanStack Virtual) so only visible rows mount (DOM stays ~tens of nodes). Memoise row components with stable props, avoid per-row inline objects, and keep row state local. Avoid layout thrash (fixed row heights help), and use `content-visibility: auto` for long static lists.
**In this project.** Pagination contract: `page` (0-based), `size` 1..100 default 20, `sort=field,dir`; response `{content,page,size,totalElements,totalPages}`.
**Follow-ups.**
- *Infinite scroll vs pages?* Pages give stable URLs and counts; infinite scroll suits feeds but hurts accessibility and "go to bottom".
- *Why is `key` important in virtual lists?* Row recycling needs stable identity.

### Q69. Debounced server search with cancellation. `[React]`
**Answer.** Debounce the input value (250-400 ms), put the debounced value in the query key, and rely on `signal` to abort stale requests so late responses never overwrite newer ones (race conditions). Reset to page 0 when filters change. Show previous results while loading to avoid flicker, and announce result counts to assistive tech.
```tsx
const q = useDebounced(text, 300);
const res = useQuery({ queryKey: ['tickets', { q, page: 0 }], queryFn: ({ signal }) => api.listTickets({ q, page: 0 }, signal), enabled: q.length === 0 || q.length >= 2 });
```
**Follow-ups.**
- *Why do races happen without cancellation?* Responses can arrive out of order.
- *Debounce vs throttle?* Debounce waits for quiet; throttle limits rate.

### Q70. Polling and live updates for notifications. `[React]`
**Answer.** For a modest unread-badge, short polling with `refetchInterval` (e.g. 30-60 s, paused when the tab is hidden via `refetchIntervalInBackground:false`) is simple and robust. Server-Sent Events or WebSockets give lower latency at the cost of connection management and sticky infra. On receiving a push, invalidate the relevant query rather than hand-merging state.
```tsx
useQuery({ queryKey: ['notifications','unread-count'], queryFn: api.unreadCount, refetchInterval: 30_000 });
```
**In this project.** Contract: `GET /notifications/unread-count -> {count}` and `GET /notifications?unread=true`, served by notification-service (consumer fed by the event bus). No push channel is documented, so polling is the consistent choice; do not claim WebSockets.
**Follow-ups.**
- *Scaling many pollers?* Cache the count briefly, use ETag/304, or move to SSE.
- *Reconnect strategy for SSE?* Built-in `EventSource` retry with `Last-Event-ID`.

---

# Section 4 - Browser / frontend performance (Q71-Q76)

### Q71. Core Web Vitals and how to improve them. `[Browser]`
**Answer.** LCP (largest contentful paint, target <= 2.5 s): reduce render-blocking resources, compress/size images, preload the hero resource, serve from CDN. INP (interaction to next paint, <= 200 ms; replaced FID in 2024): break up long tasks (>50 ms), avoid heavy handlers, use transitions/virtualisation. CLS (layout shift, <= 0.1): reserve space for images/skeletons, set width/height, avoid injecting content above existing content. Measure with lab tools (Lighthouse) and field data (`web-vitals` library, RUM), because lab numbers can differ from real users.
**Follow-ups.**
- *Where does TTFB fit?* It feeds LCP; improve with caching/CDN/server speed.
- *Why do SPAs suffer LCP?* JS bundle must download and run before content; code split and consider SSR for public pages.

### Q72. Critical rendering path, reflow and repaint. `[Browser]`
**Answer.** HTML -> DOM, CSS -> CSSOM, combined into the render tree; then layout (reflow), paint, composite. CSS blocks rendering; synchronous scripts block parsing (use `defer`/`async`, or `type=module`). Reading layout properties (`offsetHeight`) after writes forces synchronous layout; batch reads then writes. Animate `transform`/`opacity` (compositor-only) instead of `top/left/width`. Use `will-change` sparingly.
**Follow-ups.**
- *`async` vs `defer`?* `async` runs when downloaded, unordered; `defer` runs after parsing, in order.
- *What is layout thrashing?* Interleaved read/write of layout properties in a loop.

### Q73. Bundle size: splitting, tree shaking, analysis. `[Browser]`
**Answer.** Use ES modules so bundlers can tree-shake unused exports (side-effect-free packages flagged via `sideEffects:false`). Split by route and by heavy libraries (charts used only on analytics). Analyse with `rollup-plugin-visualizer`/`source-map-explorer`; replace large utilities (e.g. moment -> date-fns/`Intl`), import named functions, and enforce a bundle budget in CI. Compress (brotli) and hash filenames for long-term caching.
**Follow-ups.**
- *Why does importing `lodash` whole hurt?* CJS build cannot be tree-shaken; use `lodash-es` or per-method imports.
- *Dynamic import cost?* Extra request; mitigate with prefetch.

### Q74. HTTP caching: Cache-Control, ETag, service workers. `[Browser] [API]`
**Answer.** Hashed static assets: `Cache-Control: public, max-age=31536000, immutable`. HTML: `no-cache` (revalidate) so new deploys are picked up. API GETs: `ETag`/`If-None-Match` returning 304 saves bytes; `private` for per-user data, `Vary: Authorization`-style care so shared caches never serve one user's data to another. Do not cache authenticated multi-tenant responses in shared caches. Service workers enable offline/precache but add invalidation complexity.
**Follow-ups.**
- *`no-cache` vs `no-store`?* `no-cache` allows storing but requires revalidation; `no-store` forbids storing.
- *CDN and tenant data?* Never cache tenant-specific JSON at the CDN without a tenant-aware key.

### Q75. XSS, CSP, and rendering user-supplied comments safely. `[Browser] [React]`
**Answer.** React escapes text by default; the danger is `dangerouslySetInnerHTML`, `href="javascript:"` URLs, and rendering unsanitised rich text (KB articles, comment bodies). If HTML is needed, sanitise on render with DOMPurify (and ideally also server-side) with an allow-list. Add a CSP (`default-src 'self'`, no `unsafe-inline` scripts) as defence in depth, `X-Content-Type-Options: nosniff`, and validate URLs against `http(s):`. Stored XSS in support tickets is high impact because agents (privileged users) view customer-written content.
```tsx
<div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(article.body) }} />
```
**Follow-ups.**
- *Is JSON from the API safe to put into the DOM?* Only as text nodes or after sanitising.
- *Why are agents the main victims?* Customer input rendered in the agent console.

### Q76. Event loop: micro vs macro tasks, and long tasks. `[Browser]`
**Answer.** JavaScript runs on one thread: the call stack executes synchronously, then the microtask queue (promise callbacks, `queueMicrotask`) is drained completely, then the next macrotask (timers, events, IO) runs, with rendering opportunities in between. A long synchronous loop blocks input and paint; chunk work (`setTimeout`, `requestIdleCallback`, `scheduler.yield`) or move it to a Web Worker.
```js
console.log(1); setTimeout(() => console.log(2)); Promise.resolve().then(() => console.log(3)); console.log(4); // 1 4 3 2
```
**Follow-ups.**
- *Can microtasks starve rendering?* Yes, an endless microtask chain blocks the render step.
- *When use a worker?* CPU-heavy parsing/search not touching the DOM.

---

# Section 5 - REST API design (Q77-Q90)

### Q77. Design idempotent creation with `Idempotency-Key`. `[API] [Design]`
**Answer.** Client sends a unique key per logical operation. Server stores `(tenant, key) -> (request fingerprint, status, response)` with a unique constraint. First request: insert the key row "in progress", perform the work and save the response in the same transaction (or finalise after). Replay with same key and same fingerprint: return the stored response (same status/body). Same key but different payload: reject (422/409) as misuse. Concurrent duplicate while the first is in flight: block on the unique constraint or return 409 and let the client retry. Keys expire (e.g. 24 h). Scope keys per tenant/user so they cannot collide or be probed.
```sql
CREATE TABLE idempotency_key (tenant_id UUID, key VARCHAR(80), request_hash CHAR(64), status INT, response JSONB, created_at TIMESTAMPTZ, PRIMARY KEY (tenant_id, key));
```
**In this project.** `POST /tickets` requires `Idempotency-Key`; same key replays the original 201 response; `DUPLICATE_SUBMISSION` (409) exists. The storage schema above is a general design, not a claim about the repo's table.
**Follow-ups.**
- *Why not just dedupe on title?* Legitimate duplicates exist; the key captures client intent.
- *Is POST the only method needing it?* PUT/DELETE are already idempotent by definition; POSTs that create or charge need it.

### Q78. Which HTTP methods are safe/idempotent, and PUT vs PATCH vs POST? `[API]`
**Answer.** Safe (no state change): GET, HEAD, OPTIONS. Idempotent (repeating has same effect): GET, HEAD, PUT, DELETE, OPTIONS. POST and PATCH are not guaranteed idempotent. PUT replaces the whole resource (omitted fields reset) and fits "update ticket" with full representation; PATCH sends a partial change (JSON Merge Patch or JSON Patch). Actions that are not CRUD (assign, transition, approve) are modelled as sub-resource POSTs to carry intent and invariants rather than letting clients set arbitrary status via PUT.
**In this project.** `PUT /tickets/{id}` for edits, `POST /tickets/{id}/assign`, `POST /tickets/{id}/transition` for rule-checked state changes, `POST /ai/suggestions/{id}/approve`.
**Follow-ups.**
- *Is DELETE idempotent if the second call returns 404?* Yes: idempotency is about server state, not response code.
- *Why separate `transition` endpoint?* To enforce the state machine and record reason/audit.

### Q79. API versioning strategies. `[API]`
**Answer.** Options: URI (`/api/v1/...`: simple, cache-friendly, visible), header/media type (`Accept: application/vnd.x.v2+json`: cleaner URIs, harder to test), query param. Prefer non-breaking evolution first (additive fields, tolerant readers), and only bump the major version for breaking changes; run old and new in parallel with deprecation headers (`Deprecation`, `Sunset`) and usage metrics before removing.
**In this project.** URI versioning: gateway routes `/api/v1/**` to core-service. Workflow definitions also have their own **versions** (a new `POST /workflows` creates a new version; `activate` switches the active one) - that is data versioning, distinct from API versioning.
**Follow-ups.**
- *What is a breaking change?* Removing/renaming fields, changing types or semantics, tightening validation, new required inputs.
- *Consumer-driven contracts?* Pact-style tests protect consumers.

### Q80. Offset vs keyset (cursor) pagination. `[API] [DB]`
**Answer.** Offset (`page`,`size`) is simple, supports jumping to a page and total counts, but `OFFSET n` scans and discards n rows (slow deep pages) and results can shift (duplicates/gaps) under concurrent inserts. Keyset (cursor) uses `WHERE (created_at, id) < (:lastCreated, :lastId) ORDER BY created_at DESC, id DESC LIMIT :size`, is O(log n) with an index, stable under inserts, but cannot jump to arbitrary pages. Always include a unique tiebreaker in the sort and cap `size`.
**In this project.** Offset pagination: `page` 0-based, `size` 1..100 default 20, response includes `totalElements`/`totalPages`. For tickets lists that is fine; keyset would be the upgrade for very large tenants or export/streaming endpoints (general concept).
**Follow-ups.**
- *Cost of `totalElements`?* A `COUNT(*)` per request; cache or drop for huge sets.
- *Opaque cursors?* Encode the key tuple (base64) so clients do not depend on its structure.

### Q81. Designing a consistent error contract. `[API]`
**Answer.** One schema for all non-2xx: machine-readable `code` (stable), HTTP status, human `message`, `path`, `timestamp`, `correlationId`, and `fieldErrors` for validation. Clients branch on `code`, show `message` for humans, and users/support quote the `correlationId`. Never expose stack traces or internal ids; use 404 for unauthorised-object lookups. RFC 7807/9457 `application/problem+json` is the standardised equivalent (`type`, `title`, `status`, `detail`, `instance` + extensions).
**In this project.** Schema `{timestamp,status,error,code,message,path,correlationId,fieldErrors[]}` and the code list in Q43.
**Follow-ups.**
- *Localised messages?* Keep `code` stable, localise `message` on the client or via `Accept-Language`.
- *Retryable vs not?* 429/503/some 409s are retryable; 400/422 are not.

### Q82. Concurrency control in REST: `ETag`/`If-Match` vs version in the body. `[API]`
**Answer.** `ETag` + `If-Match` is the standard HTTP precondition mechanism: server returns `ETag: "7"` (e.g. the row version); the client sends `If-Match: "7"` on update; mismatch gives `412 Precondition Failed` (or 409 by convention); missing header on a required-precondition API gives `428`. Embedding `version` in the JSON body is functionally equivalent and easier for SPAs/mobile clients, at the cost of not using standard HTTP caches/tools.
**In this project.** Body `version` on `PUT`, `assign`, `transition`; stale -> 409 `TICKET_VERSION_CONFLICT`.
**Follow-ups.**
- *Why 409 vs 412?* Project convention; both express a failed precondition.
- *Weak vs strong ETag?* Weak (`W/`) means semantic equivalence; for concurrency control use strong.

### Q83. Choosing between 400, 401, 403, 404, 409, 422, 429, 503. `[API]`
**Answer.** 400: malformed/invalid request shape. 401: not authenticated/invalid or expired token. 403: authenticated but not allowed (role). 404: not found - also used when the resource exists in another tenant (do not leak existence). 409: state conflict (stale version, duplicate submission). 422: well-formed but violates domain rules (invalid transition). 429: rate limited (send `Retry-After`). 503: dependency unavailable.
**In this project.** All of these appear in the contract: `VALIDATION_FAILED(400)`, `UNAUTHENTICATED(401)`, `FORBIDDEN(403)`, `NOT_FOUND(404)`, `TICKET_VERSION_CONFLICT(409)`, `DUPLICATE_SUBMISSION(409)`, `INVALID_TRANSITION(422)`, `RATE_LIMITED(429)`, `AI_UNAVAILABLE(503)`. Note the AI analyze endpoint is documented to degrade with HTTP 200 + `degraded:true` on provider timeout, rather than 503.
**Follow-ups.**
- *Why 200 with `degraded` instead of 503?* The ticket page still works; failure is partial and the agent can carry on manually.
- *When 403 vs 404?* 403 when the caller may know the resource exists (own tenant) but lacks rights.

### Q84. 404 vs 403 for cross-tenant access, and enumeration. `[API] [Design]`
**Answer.** Returning 403 tells an attacker "that id exists in someone else's tenant", enabling enumeration; 404 gives no information. Use sequential-looking ids with caution (use UUIDs or tenant-scoped numbers). Apply the same response (and ideally similar timing) for "not found" and "not yours". Log the attempt with tenant and user for security monitoring.
**In this project.** `NOT_FOUND(404; also used for cross-tenant ids)`; tenant is from JWT only.
**Follow-ups.**
- *Are `number`s like 1001 enumerable?* They are only unique per tenant; protection comes from the tenant filter, not obscurity.
- *Timing side channel?* Make lookups go through the same tenant-scoped query path.

### Q85. Rate limiting at the API gateway. `[API] [Design]`
**Answer.** Limit per tenant and per user/IP using token bucket or sliding window, enforced at the gateway (shared state in Redis for multiple instances, in-memory per instance otherwise). Return 429 with `Retry-After` and informative headers (`X-RateLimit-Remaining`). Differentiate limits by endpoint cost (AI analyze is more expensive than list). Protect login endpoints against credential stuffing with stricter limits and lockout/backoff. Algorithms are implemented in Q97/Q98.
**In this project.** `RATE_LIMITED(429)` is a documented code, and the architecture has a gateway on :8080. The exact limits/algorithm are not specified in the contract.
**Follow-ups.**
- *Fixed window downside?* Burst at window boundaries (2x limit).
- *Distributed correctness?* Redis Lua script for atomic check-and-decrement.

### Q86. Filtering, sorting and search parameters. `[API]`
**Answer.** Keep filters as explicit, typed query params (`status=OPEN&priority=HIGH`), allow-list sortable fields (`sort=createdAt,desc`) to avoid SQL injection/unindexed sorts, validate enum values, and cap `size`. Free text `q` should hit a dedicated full-text index (PostgreSQL GIN `tsvector`, or a search engine), not `LIKE '%x%'`. Provide multi-value via repeated params or comma lists consistently. Document defaults and treat unspecified order as undefined unless a tiebreaker is given.
**In this project.** `GET /tickets?page&size&sort&q&status&priority&assigneeId&categoryId&type&slaStatus&mine=true`. PostgreSQL-only migrations include GIN full-text and partial indexes under the `docker` profile.
**Follow-ups.**
- *Danger of accepting arbitrary `sort` strings?* Injection and expensive unindexed sorts.
- *How do you index `status` filters well?* Composite index starting with `tenant_id`, then selective columns; partial index for open tickets.

### Q87. Object-level authorization (BOLA/IDOR) beyond role checks. `[API] [Spring]`
**Answer.** Role checks (`hasRole('AGENT')`) answer "what kind of user"; object-level checks answer "may this user touch this object". Typical failures: a CUSTOMER requests another customer's ticket id, or lists comments and sees INTERNAL ones. Enforce in the service/query layer: tenant filter always, plus for customers `requester_id = :me`, and filter comment visibility by role. Do not rely on the UI hiding links. Write negative tests per role/endpoint matrix.
**In this project.** Contract rules: CUSTOMER sees only own tickets + public comments + public articles; cannot post/see INTERNAL comments; AGENT+ works tickets/KB/AI; MANAGER adds analytics + workflow read; ADMIN adds users/roles/settings/workflow-write/audit.
**Follow-ups.**
- *Where is the best enforcement point?* Close to the data (query predicate), so every path inherits it.
- *Mass assignment risk?* Bind to explicit DTOs; never accept `tenantId` or `roles` from clients on self-service endpoints.

### Q88. Correlation ids, logs and traces across services. `[API] [Spring]`
**Answer.** Gateway generates `X-Correlation-Id` if absent; each service logs it via MDC and forwards it on downstream HTTP calls and in message headers; error responses include it. This ties together a request through gateway, core-service, outbox event and notification-service. For richer tracing use W3C `traceparent` with Micrometer Tracing/OpenTelemetry, which also gives spans and latency breakdowns. Log structured JSON, never log tokens or passwords, and be careful with personal data in ticket text.
**In this project.** `X-Correlation-Id` accepted/returned and present in the error schema; Prometheus metrics endpoint exposed. Distributed tracing is not claimed.
**Follow-ups.**
- *Correlation id vs trace id?* Correlation id is a simple request tag; trace id is part of a standard with spans.
- *What to put in message headers?* Correlation id, tenant id, event id for idempotency.

### Q89. Asynchronous processing and delivery guarantees in APIs. `[API] [Design]`
**Answer.** Long or non-critical work (notifications, indexing, analytics updates) is moved off the request path: the API commits state, writes an outbox event, returns, and consumers act later. Guarantee is at-least-once with idempotent consumers; ordering is per key at best; eventual consistency means the UI may briefly lag (e.g. unread count). For long-running user-visible jobs return `202 Accepted` with a status URL. Webhooks need signing (HMAC), retries with backoff, and a dead-letter/replay story.
**In this project.** Outbox + RabbitMQ with retry + DLQ + idempotent consumer feeding notification-service; the contract exposes notifications through the gateway.
**Follow-ups.**
- *Read-your-writes in UI?* Return the created object in the response and update the cache from it.
- *When is synchronous better?* When the caller needs the result to continue.

### Q90. Evolving an API without breaking clients. `[API]`
**Answer.** Additive changes only within a version: new optional fields, new endpoints, new enum values (clients must tolerate unknown enums). Keep old fields until usage metrics show zero consumers; mark deprecation in OpenAPI and headers. Version the contract in one source of truth (OpenAPI), generate clients, and run contract tests in CI. For database changes use expand/contract migrations (add nullable column -> backfill -> switch reads -> drop) so old and new app versions overlap during rolling deploys.
**In this project.** `docs/API-CONTRACT.md` is the stated source of truth for backend and frontend; changes must update it. Flyway manages schema (Q140).
**Follow-ups.**
- *Is adding a required request field breaking?* Yes.
- *How to handle unknown enum values in the UI?* Default branch with a neutral rendering (avoid `assertNever` crashing production; log instead).

---

# Section 6 - DSA and complexity (Q91-Q120)

Format for coding questions: problem -> approach -> code (compiled) -> complexity -> edge cases -> JUnit-style test sketch (not compiled; JUnit not on the classpath) -> follow-ups. The "in this project" note is given only where the problem genuinely resembles something in the platform's documented contract; otherwise it is a general concept.

### Q91. How do you analyse time/space complexity, and what are the common classes? `[DSA]`
**Answer.** Count how the number of basic operations grows with input size n, ignoring constants and lower-order terms: O(1), O(log n) (halving: binary search, balanced tree), O(n) (single pass), O(n log n) (sorting, heap-based top-K), O(n^2) (nested loops), O(2^n) (subsets), O(n!) (permutations). State best/average/worst when they differ (quicksort O(n log n) avg, O(n^2) worst; HashMap O(1) avg). Also state space, including recursion depth. For loops with two inputs use both variables (O(V+E) for graphs). In interviews say the complexity before and after optimisation and the trade (time vs memory).
**General concept.** Rough budget: 10^8 simple ops per second; n up to 10^5 allows O(n log n), up to 10^3 allows O(n^2).
**Follow-ups.**
- *Complexity of nested loop where inner runs `i` times?* Sum 1..n = n(n+1)/2 -> O(n^2).
- *Why does O(n log n) from a heap beat sorting for top-K?* O(n log k) with k small and O(k) memory.

### Q92. What is amortised analysis? Give examples. `[DSA]`
**Answer.** Amortised cost is the average cost per operation over a worst-case sequence, not a probabilistic average. `ArrayList.add` costs O(n) on resize but doubling/1.5x growth means total cost for n adds is O(n), so O(1) amortised. Two-stack queue: each element moves between stacks at most once. Union-Find with path compression is near-constant amortised (inverse Ackermann). Sliding-window/monotonic-deque algorithms are O(n) because each element enters and leaves at most once, despite the inner `while`.
**Follow-ups.**
- *Amortised vs average case?* Amortised gives a guarantee over any sequence; average assumes input distribution.
- *Is HashMap resize amortised?* Yes, similar to array doubling.

### Q93. Implement an LRU cache with O(1) `get`/`put`. `[DSA] [Design]`
**Approach.** HashMap for key -> node, plus a doubly linked list ordered by recency (head = most recent). `get` moves the node to head; `put` inserts at head and evicts the tail when over capacity. Sentinel head/tail nodes remove null checks. (`LinkedHashMap(accessOrder=true)` + `removeEldestEntry` does the same in 5 lines; interviewers usually want the manual version.)
**In this project.** The cache layer is Caffeine (local) / Redis; Caffeine uses a TinyLFU-style policy, not plain LRU. This question tests the underlying structure (general concept).
```java
// COMPILE: LruCache
import java.util.HashMap;
import java.util.Map;

public class LruCache<K, V> {
    private final class Node {
        final K key; V val; Node prev, next;
        Node(K key, V val) { this.key = key; this.val = val; }
    }
    private final int capacity;
    private final Map<K, Node> map = new HashMap<>();
    private final Node head = new Node(null, null), tail = new Node(null, null);

    public LruCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        head.next = tail; tail.prev = head;
    }
    public V get(K key) {
        Node n = map.get(key);
        if (n == null) return null;
        unlink(n); addFirst(n);
        return n.val;
    }
    public void put(K key, V val) {
        Node n = map.get(key);
        if (n != null) { n.val = val; unlink(n); addFirst(n); return; }
        if (map.size() == capacity) { Node lru = tail.prev; unlink(lru); map.remove(lru.key); }
        n = new Node(key, val); map.put(key, n); addFirst(n);
    }
    public int size() { return map.size(); }
    private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addFirst(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
}
```
**Complexity.** O(1) time per op, O(capacity) space. Not thread-safe (wrap with a lock, or use Caffeine).
**Edge cases.** capacity 1; `put` of existing key must refresh recency; null values are ambiguous with "miss" (use Optional or forbid).
```java
@Test void evictsLeastRecentlyUsed() {
    var c = new LruCache<Integer,String>(2);
    c.put(1,"a"); c.put(2,"b"); c.get(1); c.put(3,"c");   // evicts 2
    assertNull(c.get(2)); assertEquals("a", c.get(1)); assertEquals("c", c.get(3));
}
```
**Follow-ups.**
- *Make it thread-safe and fast?* Striped locks are hard because the list is global; use Caffeine, or a `ReentrantLock` around the whole structure for small caches.
- *LRU vs LFU?* LFU keeps frequently used items (scan-resistant) but needs O(1) frequency buckets; TinyLFU approximates it cheaply.

### Q94. Top-K frequent elements. `[DSA]`
**Approach.** Count frequencies in a HashMap (O(n)), keep a min-heap of size k keyed by frequency: push each entry, pop when size exceeds k. Result are the k largest by frequency. Alternative: bucket sort by frequency for O(n).
**Use case.** Top-K most-used KB articles or most frequent ticket tags in a window (general concept).
```java
// COMPILE: TopK
import java.util.*;

public class TopK {
    public static List<Integer> topKFrequent(int[] nums, int k) {
        if (k <= 0) return List.of();
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);
        PriorityQueue<Map.Entry<Integer, Integer>> pq =
                new PriorityQueue<>((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
            pq.offer(e);
            if (pq.size() > k) pq.poll();
        }
        List<Integer> out = new ArrayList<>();
        while (!pq.isEmpty()) out.add(pq.poll().getKey());
        Collections.reverse(out);          // most frequent first
        return out;
    }
}
```
**Complexity.** O(n + d log k) time (d = distinct values), O(d) space.
**Edge cases.** k greater than distinct count (returns all); k <= 0; ties (specify tie-breaking: here arbitrary).
```java
@Test void topTwo() { assertEquals(List.of(1,2), TopK.topKFrequent(new int[]{1,1,1,2,2,3}, 2)); }
```
**Follow-ups.**
- *Streaming data?* Count-Min Sketch + heap (approximate) or Space-Saving algorithm.
- *O(n) approach?* Bucket by frequency (index = count), scan from the highest bucket.

### Q95. Merge overlapping intervals. `[DSA]`
**Approach.** Sort by start; sweep, extending the current interval while the next start <= current end, otherwise emit. Do not mutate the caller's input (clone).
**Use case.** Merging pause windows (`PAUSED` SLA periods) or maintenance windows so elapsed time is not double-counted (general concept).
```java
// COMPILE: MergeIntervals
import java.util.*;

public class MergeIntervals {
    public static int[][] merge(int[][] intervals) {
        if (intervals.length == 0) return new int[0][];
        int[][] s = intervals.clone();
        Arrays.sort(s, (a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        int[] cur = s[0].clone();
        for (int i = 1; i < s.length; i++) {
            if (s[i][0] <= cur[1]) cur[1] = Math.max(cur[1], s[i][1]);
            else { out.add(cur); cur = s[i].clone(); }
        }
        out.add(cur);
        return out.toArray(new int[0][]);
    }
}
```
**Complexity.** O(n log n) time (sort), O(n) space.
**Edge cases.** Empty input; touching intervals `[1,4],[4,5]` merge (since `<=`); one interval nested in another; unsorted input; with `Integer.compare` (not subtraction) to avoid overflow.
```java
@Test void merges() { assertArrayEquals(new int[][]{{1,6},{8,10}}, MergeIntervals.merge(new int[][]{{8,10},{1,3},{2,6}})); }
```
**Follow-ups.**
- *Insert one interval into a sorted merged list?* Linear scan in three phases (before, overlapping, after) O(n).
- *Total covered duration?* Sum of merged lengths.

### Q96. Topological sort (dependency ordering). `[DSA]`
**Approach.** Kahn's algorithm: compute in-degree, queue all zero in-degree nodes, repeatedly pop, append to output and decrement neighbours. If the output has fewer than n nodes, a cycle exists.
**Use case.** Ordering "blocked-by" ticket/task dependencies or validating that rule chains do not depend on each other cyclically (general concept).
```java
// COMPILE: TopoSort
import java.util.*;

public class TopoSort {
    /** edges[i] = {from, to} meaning from must come before to. Nodes are 0..n-1. */
    public static List<Integer> order(int n, int[][] edges) {
        List<List<Integer>> g = new ArrayList<>();
        for (int i = 0; i < n; i++) g.add(new ArrayList<>());
        int[] indeg = new int[n];
        for (int[] e : edges) { g.get(e[0]).add(e[1]); indeg[e[1]]++; }
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q.add(i);
        List<Integer> out = new ArrayList<>(n);
        while (!q.isEmpty()) {
            int u = q.poll();
            out.add(u);
            for (int v : g.get(u)) if (--indeg[v] == 0) q.add(v);
        }
        if (out.size() != n) throw new IllegalStateException("cycle detected");
        return out;
    }
}
```
**Complexity.** O(V+E) time and space.
**Edge cases.** Disconnected graph; self-loop (cycle); duplicate edges (in-degree counted twice, still consistent); n = 0.
```java
@Test void detectsCycle() { assertThrows(IllegalStateException.class, () -> TopoSort.order(2, new int[][]{{0,1},{1,0}})); }
@Test void orders() { assertEquals(List.of(0,1,2), TopoSort.order(3, new int[][]{{0,1},{1,2}})); }
```
**Follow-ups.**
- *Lexicographically smallest order?* Replace the queue with a min-heap.
- *DFS alternative?* Post-order reversed; needs three-colour marking for cycle detection.

### Q97. Implement a token-bucket rate limiter. `[DSA] [Design]`
**Approach.** Bucket holds up to `capacity` tokens and refills at a constant rate; a request consumes tokens if available. Compute refill lazily from elapsed time (no timer thread). Inject the clock for tests. Allows bursts up to capacity while enforcing average rate.
**In this project.** `RATE_LIMITED(429)` exists in the contract; the algorithm used is not specified, so this is a general design.
```java
// COMPILE: TokenBucket
import java.util.function.LongSupplier;

public class TokenBucket {
    private final long capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long last;

    public TokenBucket(long capacity, double tokensPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0 || tokensPerSecond <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.tokensPerNano = tokensPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.last = nanoClock.getAsLong();
    }
    public synchronized boolean tryAcquire(int permits) {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * tokensPerNano);
        last = now;
        if (tokens >= permits) { tokens -= permits; return true; }
        return false;
    }
}
```
**Complexity.** O(1) per call, O(1) memory per bucket (one bucket per tenant/user key in a map).
**Edge cases.** Clock going backwards (use monotonic `System::nanoTime`; guard negative elapsed); permits greater than capacity (never satisfiable); many keys (evict idle buckets).
```java
@Test void refills() {
    long[] t = {0}; var b = new TokenBucket(2, 1.0, () -> t[0]);
    assertTrue(b.tryAcquire(2)); assertFalse(b.tryAcquire(1));
    t[0] += 1_000_000_000L; assertTrue(b.tryAcquire(1));
}
```
**Follow-ups.**
- *Distributed version?* Redis Lua script storing `tokens,last` per key, atomic.
- *Leaky bucket vs token bucket?* Leaky bucket smooths output; token bucket permits bursts.

### Q98. Sliding-window-log rate limiter (per key). `[DSA] [Design]`
**Approach.** For each key store timestamps of accepted requests in a deque. On a request, drop timestamps older than `now - window`; accept if remaining count < limit and add `now`. Exact (no boundary burst) but O(limit) memory per key.
```java
// COMPILE: SlidingWindowLimiter
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class SlidingWindowLimiter {
    private final int limit;
    private final long windowMillis;
    private final LongSupplier clockMillis;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int limit, long windowMillis, LongSupplier clockMillis) {
        this.limit = limit; this.windowMillis = windowMillis; this.clockMillis = clockMillis;
    }
    public boolean allow(String key) {
        long now = clockMillis.getAsLong();
        ArrayDeque<Long> dq = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst() <= now - windowMillis) dq.pollFirst();
            if (dq.size() >= limit) return false;
            dq.addLast(now);
            return true;
        }
    }
}
```
**Complexity.** Amortised O(1) per call (each timestamp added/removed once); O(limit) memory per active key.
**Edge cases.** Boundary: a request exactly `windowMillis` old is expired (`<=`); keys never removed (add idle eviction); key must include tenant (`tenantId:userId`).
```java
@Test void blocksAfterLimit() {
    long[] t = {0}; var l = new SlidingWindowLimiter(2, 1000, () -> t[0]);
    assertTrue(l.allow("a")); assertTrue(l.allow("a")); assertFalse(l.allow("a"));
    t[0] = 1000; assertTrue(l.allow("a"));
}
```
**Follow-ups.**
- *Reduce memory?* Sliding window counter: two fixed-window counts weighted by overlap.
- *Why include tenant in the key?* Otherwise tenants share quotas (noisy neighbour).

### Q99. Two Sum (unsorted) and its variants. `[DSA]`
**Approach.** One pass with a HashMap value -> index: for each x, check whether `target - x` was seen. Handles duplicates because lookup happens before insert (so an element is not paired with itself).
```java
// COMPILE: TwoSum
import java.util.*;

public class TwoSum {
    public static int[] twoSum(int[] a, long target) {
        Map<Long, Integer> seen = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            Integer j = seen.get(target - a[i]);
            if (j != null) return new int[]{j, i};
            seen.put((long) a[i], i);
        }
        return new int[0];            // no solution
    }
}
```
**Complexity.** O(n) time, O(n) space. Use `long` for the complement to avoid overflow.
**Edge cases.** Duplicates (`[3,3]`, target 6); negatives; empty/one element; no answer.
```java
@Test void dupes() { assertArrayEquals(new int[]{0,1}, TwoSum.twoSum(new int[]{3,3}, 6)); }
```
**Follow-ups.**
- *Count all pairs?* Keep counts in the map and add `count[target-x]` before inserting.
- *Data too large for memory?* Sort externally + two pointers (Q100).

### Q100. Two Sum on a sorted array (two pointers). `[DSA]`
**Approach.** Pointers at both ends; sum too small -> move left up; too large -> move right down. O(1) extra space because sortedness lets us discard one candidate each step.
```java
// COMPILE: TwoSumSorted
public class TwoSumSorted {
    public static int[] twoSum(int[] a, long target) {
        int l = 0, r = a.length - 1;
        while (l < r) {
            long s = (long) a[l] + a[r];
            if (s == target) return new int[]{l, r};
            if (s < target) l++; else r--;
        }
        return new int[0];
    }
}
```
**Complexity.** O(n) time, O(1) space.
**Edge cases.** Array must be sorted ascending (document or sort first, losing original indices); same element cannot be reused (`l < r`).
```java
@Test void finds() { assertArrayEquals(new int[]{0,2}, TwoSumSorted.twoSum(new int[]{1,3,4,9}, 5)); }
```
**Follow-ups.**
- *Return all unique pairs?* Skip duplicates after a match.
- *Why not binary search per element?* O(n log n) vs O(n).

### Q101. Three Sum (unique triplets). `[DSA]`
**Approach.** Sort; fix `i`, run two pointers on the rest, skip duplicate values for `i`, `l`, `r` to keep triplets unique.
```java
// COMPILE: ThreeSum
import java.util.*;

public class ThreeSum {
    public static List<List<Integer>> threeSum(int[] nums) {
        int[] a = nums.clone();
        Arrays.sort(a);
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < a.length - 2; i++) {
            if (a[i] > 0) break;
            if (i > 0 && a[i] == a[i - 1]) continue;
            int l = i + 1, r = a.length - 1;
            while (l < r) {
                long s = (long) a[i] + a[l] + a[r];
                if (s == 0) {
                    out.add(List.of(a[i], a[l], a[r]));
                    while (l < r && a[l] == a[l + 1]) l++;
                    while (l < r && a[r] == a[r - 1]) r--;
                    l++; r--;
                } else if (s < 0) l++; else r--;
            }
        }
        return out;
    }
}
```
**Complexity.** O(n^2) time, O(1) extra (ignoring output and sort).
**Edge cases.** All zeros (`[0,0,0,0]` -> one triplet); fewer than 3 elements; many duplicates.
```java
@Test void classic() { assertEquals(List.of(List.of(-1,-1,2), List.of(-1,0,1)), ThreeSum.threeSum(new int[]{-1,0,1,2,-1,-4})); }
```
**Follow-ups.**
- *K-sum generalisation?* Recursion reducing to two-sum; O(n^(k-1)).
- *3Sum closest?* Track the best absolute difference.

### Q102. Longest substring without repeating characters (sliding window). `[DSA]`
**Approach.** Window `[left, right]`; map char -> last index. On a repeat inside the window, jump `left` to `lastIndex+1` (never backwards). Track max length.
**Use case.** General sliding-window pattern; also useful for dedupe-style scans on streaming events.
```java
// COMPILE: LongestUnique
import java.util.HashMap;
import java.util.Map;

public class LongestUnique {
    public static int length(String s) {
        Map<Character, Integer> last = new HashMap<>();
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            Integer prev = last.put(s.charAt(right), right);
            if (prev != null && prev >= left) left = prev + 1;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```
**Complexity.** O(n) time, O(min(n, alphabet)) space.
**Edge cases.** Empty string -> 0; all same -> 1; `"abba"` (left must not move backwards - the `prev >= left` check).
```java
@Test void abba() { assertEquals(2, LongestUnique.length("abba")); assertEquals(3, LongestUnique.length("abcabcbb")); }
```
**Follow-ups.**
- *Unicode code points?* Iterate `codePoints()` rather than `charAt`.
- *At most K distinct characters?* Maintain counts and shrink while distinct > K.

### Q103. Minimum size subarray with sum >= target. `[DSA]`
**Approach.** Positive numbers: expand `right`, add to window sum; while sum >= target, record length and shrink `left`. Each index enters/leaves once.
**Use case.** Shortest time window covering at least N ticket-hours of work (general).
```java
// COMPILE: MinSubarray
public class MinSubarray {
    /** Returns 0 when no subarray reaches the target. Elements must be positive. */
    public static int minLen(long target, int[] a) {
        int left = 0, best = Integer.MAX_VALUE;
        long sum = 0;
        for (int right = 0; right < a.length; right++) {
            sum += a[right];
            while (sum >= target) {
                best = Math.min(best, right - left + 1);
                sum -= a[left++];
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }
}
```
**Complexity.** O(n) time, O(1) space.
**Edge cases.** No solution -> 0; single element >= target; with negatives the window technique fails (use prefix sums + monotonic deque).
```java
@Test void sample() { assertEquals(2, MinSubarray.minLen(7, new int[]{2,3,1,2,4,3})); assertEquals(0, MinSubarray.minLen(100, new int[]{1,2})); }
```
**Follow-ups.**
- *Negative numbers allowed?* Prefix sums with a monotonic deque, O(n).
- *Why is the inner while still O(n) total?* `left` only moves forward.

### Q104. Binary search on the answer (minimum capacity to finish in D shifts). `[DSA]`
**Approach.** The answer (capacity) lies in `[max(weights), sum(weights)]` and feasibility is monotonic: if capacity C works, any larger works. Binary search the smallest feasible C; feasibility check greedily packs items in order.
**Use case.** "Minimum per-shift ticket-hours so the backlog finishes within D shifts, items processed in order" (general).
```java
// COMPILE: ShipCapacity
public class ShipCapacity {
    public static int minCapacity(int[] weights, int days) {
        if (days <= 0 || weights.length == 0) throw new IllegalArgumentException();
        int lo = 0; long hi = 0;
        for (int w : weights) { lo = Math.max(lo, w); hi += w; }
        long l = lo, h = hi;
        while (l < h) {
            long mid = l + (h - l) / 2;
            if (feasible(weights, days, mid)) h = mid; else l = mid + 1;
        }
        return (int) l;
    }
    private static boolean feasible(int[] w, int days, long cap) {
        int used = 1; long load = 0;
        for (int x : w) {
            if (load + x > cap) { used++; load = 0; }
            load += x;
        }
        return used <= days;
    }
}
```
**Complexity.** O(n log(sum)) time, O(1) space.
**Edge cases.** days >= n (answer = max weight); days = 1 (answer = sum); overflow (use long); lower bound must be max element.
```java
@Test void sample() { assertEquals(15, ShipCapacity.minCapacity(new int[]{1,2,3,4,5,6,7,8,9,10}, 5)); }
```
**Follow-ups.**
- *When does "binary search on the answer" apply?* Monotonic predicate over a numeric range (min of max, max of min).
- *Related problems?* Split array largest sum, Koko eating bananas.

### Q105. BFS shortest transition path in a workflow graph. `[DSA] [Design]`
**Approach.** Statuses are nodes, allowed transitions are directed edges. BFS from `from` finds the fewest-step path to `to`; keep a parent map to reconstruct. Return empty if unreachable.
**In this project.** The contract documents default transitions (NEW->OPEN; OPEN->PENDING|ON_HOLD|RESOLVED; ...) that are workflow-configurable. A "how do I get from X to Y" helper is a plausible UI feature; whether the repo has one is not claimed.
```java
// COMPILE: WorkflowPath
import java.util.*;

public class WorkflowPath {
    public static List<String> shortestPath(Map<String, List<String>> transitions, String from, String to) {
        if (from.equals(to)) return List.of(from);
        Map<String, String> parent = new HashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(from); parent.put(from, null);
        while (!q.isEmpty()) {
            String u = q.poll();
            for (String v : transitions.getOrDefault(u, List.of())) {
                if (parent.containsKey(v)) continue;
                parent.put(v, u);
                if (v.equals(to)) return rebuild(parent, to);
                q.add(v);
            }
        }
        return List.of();
    }
    private static List<String> rebuild(Map<String, String> parent, String end) {
        LinkedList<String> path = new LinkedList<>();
        for (String cur = end; cur != null; cur = parent.get(cur)) path.addFirst(cur);
        return path;
    }
}
```
**Complexity.** O(V+E) time and space.
**Edge cases.** `from == to`; unknown states (treated as no outgoing edges); cycles (visited set via parent map); unreachable -> empty list.
```java
@Test void newToClosed() {
    var t = Map.of("NEW", List.of("OPEN"), "OPEN", List.of("PENDING","ON_HOLD","RESOLVED"), "RESOLVED", List.of("CLOSED","REOPENED"));
    assertEquals(List.of("NEW","OPEN","RESOLVED","CLOSED"), WorkflowPath.shortestPath(t, "NEW", "CLOSED"));
}
```
**Follow-ups.**
- *Weighted transitions (cost)?* Dijkstra.
- *Why BFS not DFS?* BFS guarantees fewest edges in an unweighted graph.

### Q106. Validate a workflow graph: unreachable states and dead ends. `[DSA] [Design]`
**Approach.** Cycles are legitimate in ticket workflows (OPEN<->PENDING, RESOLVED->REOPENED->OPEN), so do not reject them. A useful validator instead computes (a) states unreachable from the start state via BFS and (b) states from which no terminal state is reachable (reverse-graph BFS from terminals). Report both as errors/warnings.
**In this project.** `POST /workflows/validate -> {valid, errors[]}` exists and the workflow model is constrained (ops EQ, NE, GT, LT, IN, CONTAINS; five actions). What exactly the validator checks beyond the model shape is not specified here; this is a design sketch.
```java
// COMPILE: WorkflowValidator
import java.util.*;

public class WorkflowValidator {
    public static Set<String> unreachable(Set<String> states, Map<String, List<String>> edges, String start) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>(List.of(start));
        seen.add(start);
        while (!q.isEmpty()) {
            for (String v : edges.getOrDefault(q.poll(), List.of())) if (seen.add(v)) q.add(v);
        }
        Set<String> bad = new TreeSet<>(states);
        bad.removeAll(seen);
        return bad;
    }
    /** States that cannot reach any terminal state. */
    public static Set<String> deadEnds(Set<String> states, Map<String, List<String>> edges, Set<String> terminals) {
        Map<String, List<String>> rev = new HashMap<>();
        edges.forEach((u, vs) -> vs.forEach(v -> rev.computeIfAbsent(v, k -> new ArrayList<>()).add(u)));
        Set<String> canFinish = new HashSet<>(terminals);
        ArrayDeque<String> q = new ArrayDeque<>(terminals);
        while (!q.isEmpty()) {
            for (String u : rev.getOrDefault(q.poll(), List.of())) if (canFinish.add(u)) q.add(u);
        }
        Set<String> bad = new TreeSet<>(states);
        bad.removeAll(canFinish);
        return bad;
    }
}
```
**Complexity.** O(V+E).
**Edge cases.** Start not in states; edges that reference unknown states; multiple terminal states (CLOSED only, or RESOLVED too); self-loops.
```java
@Test void findsOrphan() { assertEquals(Set.of("ARCHIVED"), WorkflowValidator.unreachable(Set.of("NEW","OPEN","ARCHIVED"), Map.of("NEW", List.of("OPEN")), "NEW")); }
```
**Follow-ups.**
- *Where do cycles matter?* Rule evaluation that triggers further rule evaluation (action -> status change -> rule) needs a depth/iteration cap.
- *Why constrain the rule model?* Closed ops/actions make rules analysable and safe (no arbitrary code).

### Q107. First and last position of a target in a sorted array. `[DSA]`
**Approach.** Two lower-bound searches: `lowerBound(target)` (first index with `a[i] >= target`) and `lowerBound(target+1) - 1`. Use half-open `[lo, hi)` invariant to avoid off-by-one.
```java
// COMPILE: SearchRange
public class SearchRange {
    public static int[] range(int[] a, int target) {
        int first = lowerBound(a, target);
        if (first == a.length || a[first] != target) return new int[]{-1, -1};
        int last = lowerBound(a, (long) target + 1) - 1;   // widen first: no int overflow
        return new int[]{first, last};
    }
    static int lowerBound(int[] a, long target) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] < target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
```
**Complexity.** O(log n), O(1) space.
**Edge cases.** Empty array; target absent; all equal; `Integer.MAX_VALUE` target (the `+1` is done in `long` so it cannot overflow); `mid` computed as `lo + (hi-lo)/2`.
```java
@Test void dupes() { assertArrayEquals(new int[]{3,4}, SearchRange.range(new int[]{5,7,7,8,8,10}, 8)); }
```
**Follow-ups.**
- *Why `lo + (hi - lo) / 2`?* `(lo + hi) / 2` can overflow for huge arrays.
- *`Arrays.binarySearch` for duplicates?* Returns any match, not first/last.

### Q108. Kth largest element. `[DSA]`
**Approach.** Min-heap of size k: after processing all elements the root is the kth largest. O(n log k). Quickselect gives O(n) average but O(n^2) worst and mutates the array.
```java
// COMPILE: KthLargest
import java.util.PriorityQueue;

public class KthLargest {
    public static int kthLargest(int[] nums, int k) {
        if (k < 1 || k > nums.length) throw new IllegalArgumentException("k out of range");
        PriorityQueue<Integer> heap = new PriorityQueue<>(k);
        for (int x : nums) {
            if (heap.size() < k) heap.offer(x);
            else if (x > heap.peek()) { heap.poll(); heap.offer(x); }
        }
        return heap.peek();
    }
}
```
**Complexity.** O(n log k) time, O(k) space.
**Edge cases.** Duplicates count separately (`[3,2,3,1,2,4,5,5,6]`, k=4 -> 4); k=1 (max); k=n (min).
```java
@Test void sample() { assertEquals(5, KthLargest.kthLargest(new int[]{3,2,1,5,6,4}, 2)); }
```
**Follow-ups.**
- *Streaming "kth largest" with adds?* Keep the heap; each add is O(log k).
- *Quickselect pivot choice?* Randomised pivot to avoid worst case.

### Q109. Merge k sorted streams. `[DSA]`
**Approach.** Min-heap of the current head of each list `(value, listIndex, position)`. Pop the smallest, push the next from the same list. Heap size <= k gives O(N log k) for N total elements.
**Use case.** Merging per-shard or per-queue sorted event/activity feeds into one timeline (general).
```java
// COMPILE: MergeK
import java.util.*;

public class MergeK {
    public static List<Integer> merge(List<int[]> lists) {
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 0; i < lists.size(); i++)
            if (lists.get(i).length > 0) pq.offer(new int[]{lists.get(i)[0], i, 0});
        List<Integer> out = new ArrayList<>();
        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            out.add(top[0]);
            int[] src = lists.get(top[1]);
            int next = top[2] + 1;
            if (next < src.length) pq.offer(new int[]{src[next], top[1], next});
        }
        return out;
    }
}
```
**Complexity.** O(N log k) time, O(k) heap space.
**Edge cases.** Empty lists inside the input; k=0; duplicates across lists; stable order not guaranteed on ties.
```java
@Test void merges() { assertEquals(List.of(1,1,2,3,4,4,5,6), MergeK.merge(List.of(new int[]{1,4,5}, new int[]{1,3,4}, new int[]{2,6}))); }
```
**Follow-ups.**
- *Divide and conquer alternative?* Pairwise merge, also O(N log k).
- *Lazy/iterator version?* Keep iterators and yield on demand.

### Q110. Valid brackets (rule-expression parsing basics). `[DSA]`
**Approach.** Stack of expected closers. On an opener push the matching closer; on a closer pop and compare. At the end the stack must be empty.
**Use case.** Quick validation of nested rule/filter expressions before deeper parsing (general; the project's rule model is structured JSON, not free-text expressions).
```java
// COMPILE: Brackets
import java.util.ArrayDeque;
import java.util.Deque;

public class Brackets {
    public static boolean isValid(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '(' -> stack.push(')');
                case '[' -> stack.push(']');
                case '{' -> stack.push('}');
                case ')', ']', '}' -> { if (stack.isEmpty() || stack.pop() != c) return false; }
                default -> { /* ignore other characters */ }
            }
        }
        return stack.isEmpty();
    }
}
```
**Complexity.** O(n) time, O(n) space.
**Edge cases.** Empty string valid; only closers; interleaving `([)]`; very deep nesting (iterative stack avoids recursion overflow).
```java
@Test void cases() { assertTrue(Brackets.isValid("{[()]}")); assertFalse(Brackets.isValid("([)]")); assertFalse(Brackets.isValid("(")); }
```
**Follow-ups.**
- *Minimum insertions to balance?* Track unmatched opens/closes counters.
- *Why `ArrayDeque` not `Stack`?* Faster, unsynchronised, proper Deque API.

### Q111. Subarray sum equals K (prefix sums + hash map). `[DSA]`
**Approach.** Let `prefix[i]` be the sum of first i elements. A subarray `(j, i]` sums to K iff `prefix[i] - prefix[j] = K`. Maintain a map prefix-sum -> count; for each running sum `s` add `count[s-K]`. Works with negatives, unlike sliding window.
```java
// COMPILE: SubarraySumK
import java.util.HashMap;
import java.util.Map;

public class SubarraySumK {
    public static int count(int[] a, int k) {
        Map<Long, Integer> seen = new HashMap<>();
        seen.put(0L, 1);
        long sum = 0; int total = 0;
        for (int x : a) {
            sum += x;
            total += seen.getOrDefault(sum - k, 0);
            seen.merge(sum, 1, Integer::sum);
        }
        return total;
    }
}
```
**Complexity.** O(n) time, O(n) space.
**Edge cases.** Seed `seen[0]=1` (subarrays starting at index 0); negatives and zeros (several equal prefixes); K=0.
```java
@Test void sample() { assertEquals(2, SubarraySumK.count(new int[]{1,1,1}, 2)); assertEquals(3, SubarraySumK.count(new int[]{1,-1,0}, 0)); }
```
**Follow-ups.**
- *Longest such subarray?* Store first index of each prefix instead of counts.
- *2D version?* Fix row pairs and reduce to 1D.

### Q112. Minimum number of agents needed (meeting rooms II). `[DSA]`
**Approach.** Sweep line: sort starts and ends separately; walk through starts, and whenever the earliest end is <= current start, reuse that agent (advance end pointer); otherwise a new agent is needed. The answer is the peak overlap.
**Use case.** Peak concurrent chats/calls to size an agent shift (general).
```java
// COMPILE: MinAgents
import java.util.Arrays;

public class MinAgents {
    public static int minAgents(int[][] intervals) {
        int n = intervals.length;
        int[] starts = new int[n], ends = new int[n];
        for (int i = 0; i < n; i++) { starts[i] = intervals[i][0]; ends[i] = intervals[i][1]; }
        Arrays.sort(starts); Arrays.sort(ends);
        int agents = 0, e = 0;
        for (int i = 0; i < n; i++) {
            if (starts[i] >= ends[e]) e++;      // someone freed up exactly at or before this start
            else agents++;
        }
        return agents;
    }
}
```
**Complexity.** O(n log n) time, O(n) space.
**Edge cases.** Empty input -> 0; back-to-back `[1,2],[2,3]` shares one agent (end == start frees); identical intervals.
```java
@Test void sample() { assertEquals(2, MinAgents.minAgents(new int[][]{{0,30},{5,10},{15,20}})); assertEquals(1, MinAgents.minAgents(new int[][]{{1,2},{2,3}})); }
```
**Follow-ups.**
- *Return the assignment?* Min-heap of end times with agent ids.
- *Why sort starts and ends separately is valid?* Only the count of concurrent intervals matters, not which one ends.

### Q113. Running median of a stream (resolution-time p50). `[DSA]`
**Approach.** Two heaps: a max-heap `lo` of the smaller half and a min-heap `hi` of the larger half, sizes differing by at most 1. Add to `lo`, move its top to `hi`, rebalance. Median is `lo.peek()` (odd) or the mean of both tops (even).
**Use case.** Live median resolution time on a dashboard (general; the contract only documents averages like `avgResolutionMinutes`).
```java
// COMPILE: MedianFinder
import java.util.Collections;
import java.util.PriorityQueue;

public class MedianFinder {
    private final PriorityQueue<Integer> lo = new PriorityQueue<>(Collections.reverseOrder()); // max-heap
    private final PriorityQueue<Integer> hi = new PriorityQueue<>();                            // min-heap

    public void add(int x) {
        lo.offer(x);
        hi.offer(lo.poll());
        if (hi.size() > lo.size()) lo.offer(hi.poll());
    }
    public double median() {
        if (lo.isEmpty()) throw new IllegalStateException("no data");
        return lo.size() > hi.size() ? lo.peek() : ((double) lo.peek() + hi.peek()) / 2.0;
    }
}
```
**Complexity.** O(log n) add, O(1) median, O(n) space.
**Edge cases.** Empty (throws); one element; even count; overflow avoided by casting to `double` before adding.
```java
@Test void median() { var m = new MedianFinder(); m.add(1); m.add(2); assertEquals(1.5, m.median(), 1e-9); m.add(3); assertEquals(2.0, m.median(), 1e-9); }
```
**Follow-ups.**
- *Sliding-window median?* Two multisets/heaps with lazy deletion.
- *Percentiles at huge scale?* t-digest / HDR histogram approximations.

### Q114. Union-Find to cluster duplicate tickets. `[DSA]`
**Approach.** Disjoint-set with path compression and union by size. Each "these two are duplicates" pair becomes `union(a,b)`; `find` gives the cluster representative; the number of clusters is tracked as components.
**Use case.** Grouping duplicate/related tickets into one incident (general; not a documented feature).
```java
// COMPILE: UnionFind
public class UnionFind {
    private final int[] parent, size;
    private int components;

    public UnionFind(int n) {
        parent = new int[n]; size = new int[n]; components = n;
        for (int i = 0; i < n; i++) { parent[i] = i; size[i] = 1; }
    }
    public int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }   // path halving
        return x;
    }
    public boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra; size[ra] += size[rb]; components--;
        return true;
    }
    public int components() { return components; }
    public int sizeOf(int x) { return size[find(x)]; }
}
```
**Complexity.** Near O(1) amortised per op (inverse Ackermann).
**Edge cases.** Union of already-connected nodes returns false; out-of-range ids throw `ArrayIndexOutOfBounds`; iterative `find` avoids deep recursion.
```java
@Test void clusters() { var u = new UnionFind(4); u.union(0,1); u.union(2,3); assertEquals(2, u.components()); u.union(1,2); assertEquals(1, u.components()); assertEquals(4, u.sizeOf(0)); }
```
**Follow-ups.**
- *Offline vs online connectivity?* Union-Find handles incremental unions only, not deletions.
- *Mapping string ids?* Map each ticket id to an int index first.

### Q115. Trie for autocomplete (KB title suggestions). `[DSA]`
**Approach.** Each node has children by character and an end-of-word flag. Insert is O(L). To suggest, walk to the prefix node then DFS collecting up to `limit` words. `TreeMap` children give lexicographic output.
**Use case.** Type-ahead for article titles or tags (general; the repo's search is described as full-text / `q` parameter, not a trie).
```java
// COMPILE: Trie
import java.util.*;

public class Trie {
    private static final class Node { final TreeMap<Character, Node> next = new TreeMap<>(); boolean end; }
    private final Node root = new Node();

    public void insert(String word) {
        Node n = root;
        for (char c : word.toCharArray()) n = n.next.computeIfAbsent(c, k -> new Node());
        n.end = true;
    }
    public List<String> suggest(String prefix, int limit) {
        Node n = root;
        for (char c : prefix.toCharArray()) { n = n.next.get(c); if (n == null) return List.of(); }
        List<String> out = new ArrayList<>();
        collect(n, new StringBuilder(prefix), out, limit);
        return out;
    }
    private void collect(Node n, StringBuilder sb, List<String> out, int limit) {
        if (out.size() >= limit) return;
        if (n.end) out.add(sb.toString());
        for (Map.Entry<Character, Node> e : n.next.entrySet()) {
            sb.append(e.getKey());
            collect(e.getValue(), sb, out, limit);
            sb.deleteCharAt(sb.length() - 1);
            if (out.size() >= limit) return;
        }
    }
}
```
**Complexity.** Insert/lookup O(L); suggest O(L + visited nodes up to limit); memory O(total characters).
**Edge cases.** Empty prefix (all words, capped); prefix equals a full word; case normalisation (lowercase on insert/search); limit <= 0 returns empty.
```java
@Test void suggests() { var t = new Trie(); t.insert("reset"); t.insert("reset password"); t.insert("refund"); assertEquals(List.of("reset","reset password"), t.suggest("res", 5)); }
```
**Follow-ups.**
- *Rank by popularity?* Store a score per terminal node and use a bounded heap.
- *Memory heavy?* Radix/compressed trie or FST.

### Q116. Compute an SLA due time that counts only business hours. `[DSA] [Design]`
**Approach.** Walk forward day by day in the tenant's zone: snap the start into the next business window; consume the minutes available until closing; if the SLA needs more, continue at next business day's opening. Use `ZonedDateTime.of(date, time, zone)` for day boundaries so DST days are handled by `java.time`.
**In this project.** Tenant settings define `timezone, businessHoursStart, businessHoursEnd, businessDays[]` and SLA policies hold `firstResponseMinutes`/`resolutionMinutes` with `escalationAfterPercent[]`; tickets have `firstResponseDueAt`/`resolutionDueAt`. This function is a general reference implementation of the idea, not a copy of the repo's code. Holidays and `PAUSED` handling are not modelled here.
```java
// COMPILE: BusinessHours
import java.time.*;
import java.util.Set;

public class BusinessHours {
    public static ZonedDateTime addBusinessMinutes(ZonedDateTime start, long minutes,
                                                   LocalTime open, LocalTime close, Set<DayOfWeek> days) {
        if (minutes < 0) throw new IllegalArgumentException("minutes must be >= 0");
        if (days.isEmpty() || !open.isBefore(close)) throw new IllegalArgumentException("no business time");
        ZoneId zone = start.getZone();
        ZonedDateTime cur = snapIntoWindow(start, open, close, days);
        long remaining = minutes;
        while (true) {
            ZonedDateTime closeToday = ZonedDateTime.of(cur.toLocalDate(), close, zone);
            long available = Duration.between(cur, closeToday).toMinutes();
            if (remaining <= available) return cur.plusMinutes(remaining);
            remaining -= available;
            cur = snapIntoWindow(closeToday.plusNanos(1), open, close, days);
        }
    }
    /** Moves t forward to the nearest instant inside a business window. */
    static ZonedDateTime snapIntoWindow(ZonedDateTime t, LocalTime open, LocalTime close, Set<DayOfWeek> days) {
        ZoneId zone = t.getZone();
        LocalDate d = t.toLocalDate();
        LocalTime time = t.toLocalTime();
        if (days.contains(d.getDayOfWeek())) {
            if (time.isBefore(open)) return ZonedDateTime.of(d, open, zone);
            if (time.isBefore(close)) return t;
        }
        do { d = d.plusDays(1); } while (!days.contains(d.getDayOfWeek()));
        return ZonedDateTime.of(d, open, zone);
    }
}
```
**Complexity.** O(number of business days spanned); for very long SLAs compute whole days arithmetically first.
**Edge cases.** Start on weekend/after hours (snaps to next opening); exactly at closing time (moves to next day); 0 minutes; SLA longer than a day; DST transition days (available minutes differ - computed from real instants); holidays (extend `days` check with a holiday set); paused periods (subtract merged pause intervals, see Q95).
```java
@Test void fridayAfternoonSpillsToMonday() {
    var tz = ZoneId.of("UTC");
    var fri16 = ZonedDateTime.of(2026, 10, 16, 16, 0, 0, 0, tz);   // Friday
    var due = BusinessHours.addBusinessMinutes(fri16, 120, LocalTime.of(9,0), LocalTime.of(17,0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    assertEquals(ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, tz), due);   // 60 min Fri + 60 min Mon
}
```
**Follow-ups.**
- *How do `escalationAfterPercent[]` thresholds use this?* Compute the due time for each percentage of the SLA minutes and schedule a check at each instant.
- *Why not store only "minutes left" and decrement with a ticker?* Timers drift and are lost on restart; storing absolute due instants and recomputing on pause/resume is robust.

### Q117. Edit distance (fuzzy match for duplicate-title detection). `[DSA]`
**Approach.** DP: `dp[i][j]` = min edits to turn first i chars of A into first j chars of B. Transitions: delete, insert, replace (cost 0 if chars match). Keep two rows for O(min(m,n)) space.
```java
// COMPILE: EditDistance
public class EditDistance {
    public static int distance(String a, String b) {
        if (a.length() < b.length()) { String t = a; a = b; b = t; }   // b is the shorter one
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
```
**Complexity.** O(m*n) time, O(min(m,n)) space.
**Edge cases.** Empty strings (distance = other length); identical strings -> 0; case/whitespace normalisation before comparing; Unicode surrogate pairs.
```java
@Test void kitten() { assertEquals(3, EditDistance.distance("kitten", "sitting")); assertEquals(5, EditDistance.distance("", "hello")); }
```
**Follow-ups.**
- *Scaling to millions of tickets?* Use blocking (n-grams / MinHash) to prune candidates before computing distances.
- *Recover the edit script?* Keep the full table and backtrack.

### Q118. Coin change (minimum items to reach an amount). `[DSA]`
**Approach.** Bottom-up DP: `dp[x]` = min coins to make x; `dp[x] = min(dp[x-c] + 1)` over coins c <= x. Unreachable amounts stay at a sentinel. Greedy fails for arbitrary denominations (`[1,3,4]`, 6 -> 3+3 not 4+1+1).
```java
// COMPILE: CoinChange
import java.util.Arrays;

public class CoinChange {
    public static int minCoins(int[] coins, int amount) {
        if (amount < 0) throw new IllegalArgumentException();
        int INF = Integer.MAX_VALUE / 2;
        int[] dp = new int[amount + 1];
        Arrays.fill(dp, INF);
        dp[0] = 0;
        for (int x = 1; x <= amount; x++)
            for (int c : coins)
                if (c > 0 && c <= x) dp[x] = Math.min(dp[x], dp[x - c] + 1);
        return dp[amount] >= INF ? -1 : dp[amount];
    }
}
```
**Complexity.** O(amount * coins) time, O(amount) space.
**Edge cases.** amount 0 -> 0; no solution -> -1; non-positive coins ignored; large amounts (memory).
```java
@Test void cases() { assertEquals(2, CoinChange.minCoins(new int[]{1,3,4}, 6)); assertEquals(-1, CoinChange.minCoins(new int[]{2}, 3)); assertEquals(0, CoinChange.minCoins(new int[]{1}, 0)); }
```
**Follow-ups.**
- *Count the number of ways instead?* Outer loop over coins, inner over amounts (combinations vs permutations ordering).
- *Why DP rather than recursion?* Overlapping subproblems; memoisation turns exponential into polynomial.

### Q119. Consistent hashing ring (placing tenants on shards). `[DSA] [Design]`
**Approach.** Hash nodes (with many virtual nodes each) onto a ring in a `TreeMap<hash, node>`; a key maps to the first ring entry at or after its hash (wrap around with `firstKey`). Adding/removing a node only remaps keys adjacent to its points (about 1/N of keys), unlike `hash % N` which remaps nearly all.
**In this project.** Not implemented; the project is a single shared database. This answers "how would you shard tenants / cache nodes" (see Q136).
```java
// COMPILE: ConsistentHashRing
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class ConsistentHashRing {
    private final TreeMap<Integer, String> ring = new TreeMap<>();
    private final int virtualNodes;

    public ConsistentHashRing(int virtualNodes) {
        if (virtualNodes <= 0) throw new IllegalArgumentException();
        this.virtualNodes = virtualNodes;
    }
    public void addNode(String node) { for (int i = 0; i < virtualNodes; i++) ring.put(hash(node + "#" + i), node); }
    public void removeNode(String node) { for (int i = 0; i < virtualNodes; i++) ring.remove(hash(node + "#" + i)); }
    public String nodeFor(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("no nodes");
        Map.Entry<Integer, String> e = ring.ceilingEntry(hash(key));
        return (e != null ? e : ring.firstEntry()).getValue();
    }
    /** FNV-1a 32-bit: deterministic and well spread; not cryptographic. */
    static int hash(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xFF); h *= 16777619; }
        return h;
    }
}
```
**Complexity.** O(log(N*V)) lookup, O(N*V) memory.
**Edge cases.** Empty ring; hash collisions between virtual nodes (later put overwrites - use more bits or detect); hot tenants (one huge tenant still lands on one node; weight or dedicate).
```java
@Test void stableMapping() { var r = new ConsistentHashRing(50); r.addNode("A"); r.addNode("B"); String before = r.nodeFor("tenant-42"); assertEquals(before, r.nodeFor("tenant-42")); }
```
**Follow-ups.**
- *Why virtual nodes?* Smooth load distribution and proportional weights.
- *Alternative?* Rendezvous (HRW) hashing or a directory service mapping tenant -> shard (simpler to rebalance manually).

### Q120. How do you choose the right data structure? A decision checklist. `[DSA]`
**Answer.** Ask: what operations dominate, what order matters, what are the size and concurrency needs? Fast membership/lookup by key: hash map/set. Sorted iteration, range queries, floor/ceiling: `TreeMap`/`TreeSet` or a DB B-tree index. Repeated "give me the min/max": heap. FIFO/LIFO: `ArrayDeque`. Prefix search: trie (or DB prefix index). Connectivity/clustering: union-find. Approximate membership at scale: Bloom filter. Frequency estimation: Count-Min Sketch. Time-ordered expiry: delay queue / timing wheel / indexed due-date column. At system level the same logic maps to indexes: B-tree for ranges and sorting, hash for equality, GIN for full text, partial indexes for "only open tickets".
**In this project.** The documented PostgreSQL-only migrations include partial indexes and GIN full-text; ticket list filters (status, priority, assignee, SLA status) are the access patterns indexes must serve, always with `tenant_id` in the key.
**Follow-ups.**
- *HashMap vs TreeMap trade?* O(1) vs O(log n), but TreeMap gives order and range operations.
- *When does a Bloom filter help?* Cheap pre-check to avoid expensive lookups (e.g. "have I seen this event id?") accepting false positives but no false negatives.

---

# Section 7 - HLD / LLD for ticketing (Q121-Q140)

Reminder: "In this project" lines are limited to the documented contract and constraints. Everything else is a general design you would propose, labelled as such.

### Q121. Multi-tenancy models: pooled, schema-per-tenant, database-per-tenant. `[Design]`
**Answer.** **Pooled** (shared schema, `tenant_id` column): cheapest, simplest migrations, best utilisation, weakest isolation (needs filters/RLS; noisy neighbours). **Schema-per-tenant**: better logical isolation and per-tenant restore, but thousands of schemas strain migrations and catalog size. **Database-per-tenant**: strongest isolation/compliance/per-tenant tuning, highest cost and operational load. Many SaaS products use pooled for the long tail and dedicated databases for large/regulated customers (a "bridge" model). Whatever the model, tenant identity must come from the authenticated principal, be applied at the lowest layer possible, and be part of every cache key, message header, log line and unique constraint.
**In this project.** Pooled model: JWT `tid` claim -> Hibernate tenant filter on every query; optional PostgreSQL RLS under the `docker` profile; cross-tenant ids -> 404; tenant switching = re-login; seed tenants `acme-retail`, `globex-it`, `initech-health`.
**Follow-ups.**
- *How do you migrate a big tenant to its own DB later?* Keep `tenant_id` everywhere, export by tenant, route via a tenant directory.
- *Per-tenant encryption keys?* Possible in bridge/dedicated models; not claimed here.

### Q122. Give a high-level design for a CX + ITSM ticketing platform. `[Design]`
**Answer.** Clients (React SPA) -> API gateway (routing, auth edge, rate limits, correlation id) -> core-service (tickets, comments, categories, workflow/SLA, KB, AI assist, analytics, users/tenants) -> relational store (system of record), cache (read-heavy aggregates), message broker (async events) -> notification-service (consumer, in-app notifications). Cross-cutting: multi-tenancy, RBAC, audit, observability. Keep synchronous paths short (create ticket: validate, persist, outbox row, respond) and move fan-out (notifications, analytics refresh) to async consumers.
**In this project.** Gateway :8080 routes `/api/v1/**` to core-service :8081; notification-service :8082 exposed via `/api/v1/notifications/**`; PostgreSQL (docker) / H2 (local); MongoDB and Redis in the docker profile; RabbitMQ events via outbox; Caffeine/Redis cache-aside; frontend dev server on 5173. (MongoDB's precise role is not detailed in the contract; local profile uses an in-memory document store for AI/KB.)
**Follow-ups.**
- *Why a gateway?* One place for routing, CORS, throttling, header normalisation.
- *Monolith vs microservices?* Two services here is deliberate: the notification workload differs; avoid fine-grained services without need.

### Q123. Design the ticket data model and its indexes. `[Design] [DB]`
**Answer.** Core tables: `tenant`, `app_user` (+ roles), `ticket` (tenant_id, number per tenant, type, title, description, status, priority, severity, category_id, requester_id, assignee_id, due dates, sla_status, version, timestamps), `ticket_comment` (visibility), `ticket_tag`, `attachment` (metadata only), `ticket_activity`/`audit_event`, `workflow` (versioned), `sla_policy`, `outbox_event`, `idempotency_key`, `ai_suggestion`. Constraints: unique `(tenant_id, number)`, FKs include `tenant_id` or are validated by it. Indexes lead with `tenant_id`: `(tenant_id, status, priority)`, `(tenant_id, assignee_id, status)`, `(tenant_id, resolution_due_at) WHERE status NOT IN ('RESOLVED','CLOSED')` (partial), GIN on a `tsvector` for text search, `(tenant_id, created_at DESC, id DESC)` for keyset lists. Per-tenant ticket numbers need a counter row or sequence per tenant (lock or `UPDATE ... RETURNING`), which is a contention point to discuss.
**In this project.** Ticket fields follow the contract (`number`, `version`, SLA dates, `slaStatus`, `escalationLevel`, `reopenCount`); PostgreSQL-only migrations add partial indexes, GIN full text and RLS; portable migrations run on H2 and PostgreSQL. Exact DDL beyond the contract is not claimed.
**Follow-ups.**
- *Why `tenant_id` first in indexes?* Every query is tenant-scoped; this keeps tenants' rows clustered and the planner efficient.
- *Store tags how?* Join table (queryable) or array/JSONB with GIN.

### Q124. Model the ticket lifecycle as a state machine (LLD). `[Design]`
**Answer.** Represent allowed transitions as data: `Map<Status, Set<Status>>` loaded from the active workflow; `transition(ticket, to, reason, version)` validates, applies side effects (set `resolvedAt`, increment `reopenCount` on reopen, pause/resume SLA) and records an activity entry, all in one transaction with optimistic locking. Reject illegal moves with a domain exception -> 422 `INVALID_TRANSITION`. Keep the table-driven approach rather than a giant `switch` so admins can configure it and tests can enumerate it.
```java
enum Status { NEW, OPEN, PENDING, ON_HOLD, RESOLVED, CLOSED, REOPENED }
record Transitions(Map<Status, Set<Status>> allowed) {
    void check(Status from, Status to) {
        if (!allowed.getOrDefault(from, Set.of()).contains(to))
            throw new InvalidTransitionException(from, to);
    }
}
```
**In this project.** Statuses and the default transitions are in the contract (`NEW->OPEN; OPEN->PENDING|ON_HOLD|RESOLVED; PENDING->OPEN|RESOLVED; ON_HOLD->OPEN; RESOLVED->CLOSED|REOPENED; CLOSED->REOPENED; REOPENED->OPEN|RESOLVED`), "workflow-configurable"; `transition` takes `{toStatus, reason, version}`; `INVALID_TRANSITION` is 422. Transition-time side effects listed above are the general design; the contract itself mentions `reopenCount` and `PAUSED` SLA status.
**Follow-ups.**
- *Where do guards live (e.g. "needs assignee before OPEN")?* As rules attached to transitions, evaluated before the move.
- *Test approach?* Parameterised test over all (from, to) pairs against the expected matrix.

### Q125. Design a safe, constrained workflow rule engine. `[Design]`
**Answer.** Unrestricted scripting (arbitrary expressions or code) is a security and stability risk in multi-tenant SaaS. A constrained model offers a closed set of condition fields/operators and a closed set of actions, validated at save time. Rules are `when` (conditions ANDed) -> `then` (actions). Evaluate on events (ticket created/updated) in a deterministic order, with a hard cap on rule firings per event to prevent loops (an action that changes priority may retrigger rules). Workflows are versioned and immutable once saved; activation switches the active version so in-flight tickets are not changed mid-way and rollback is easy. Record which rule fired in the activity log for explainability.
```java
record Condition(String field, Op op, String value) {}
record Action(ActionType type, Map<String, String> params) {}
record Rule(String name, List<Condition> when, List<Action> then) {}
```
**In this project.** Contract: rule `{name, when:[{field,op,value}], then:[{action,params}]}`; ops `EQ, NE, GT, LT, IN, CONTAINS`; actions `ASSIGN_TO_ROLE_LEAST_LOADED, SET_PRIORITY, ESCALATE, NOTIFY, ADD_TAG`; `POST /workflows` creates a new **version**, `POST /workflows/{id}/activate`, `POST /workflows/validate -> {valid, errors[]}`; read MANAGER+, write ADMIN.
**Follow-ups.**
- *Why AND-only conditions?* Simpler to reason about; OR via multiple rules.
- *How would you test it?* Table-driven tests of (ticket, rule) -> actions, plus property tests that evaluation terminates.

### Q126. Design the SLA engine: due dates, breach detection, escalation. `[Design]`
**Answer.** On creation (and on priority change) look up the SLA policy by priority, compute `firstResponseDueAt` and `resolutionDueAt` using the tenant's business hours (Q116). Statuses like `PENDING`/`ON_HOLD` can pause the clock: store paused-at and accumulate paused duration, then shift the due date on resume (recomputing is safer than ticking). Detect breaches with a scheduled job that queries the indexed due-date column for tickets approaching thresholds (`escalationAfterPercent[]` e.g. 50/75/90%) and breached ones, rather than one in-memory timer per ticket (not restart-safe). The job is idempotent (checks the current `escalationLevel`/`slaStatus` before acting) and, with multiple instances, uses a lock or `SKIP LOCKED` to avoid double-processing; effects (notify, escalate) go through the outbox. `slaStatus` is derived: `ON_TRACK`, `AT_RISK`, `BREACHED`, `MET`, `PAUSED`.
**In this project.** The contract defines `firstResponseDueAt`, `resolutionDueAt`, `firstRespondedAt`, `resolvedAt`, `slaStatus`, `escalationLevel`; SLA policy `{priority, firstResponseMinutes, resolutionMinutes, escalationAfterPercent[]}` via `GET/PUT /sla-policies`; business hours come from tenant settings. The scheduler mechanics above are a general design, not a statement about the repo's implementation.
**Follow-ups.**
- *Policy changes after a ticket exists?* Decide per product: usually existing due dates stay unless priority changes.
- *Why a DB-polled job instead of per-ticket timers?* Survives restarts and scales via an index scan.

### Q127. Design "assign to least-loaded agent". `[Design]`
**Answer.** Define load as open assigned tickets (optionally weighted by priority) per eligible agent (role, active, and optionally availability/skills). Query: `SELECT assignee_id, COUNT(*) ... WHERE tenant_id=? AND status IN (open states) GROUP BY assignee_id` joined to eligible users with zero-load agents included (LEFT JOIN). Tie-break deterministically (lowest count, then round-robin or least recent assignment). Races: two concurrent assignments may pick the same agent; acceptable for balance, but if hard caps matter use row locks or a per-agent counter updated atomically. Assignment is a normal ticket update, so it carries `version` and is audited.
**In this project.** Workflow action `ASSIGN_TO_ROLE_LEAST_LOADED` and the analytics field `agentWorkload:[{agentId,name,openCount}]` exist in the contract; the exact definition of load is not specified there.
**Follow-ups.**
- *Fairness over time?* Add last-assigned timestamp as tie-breaker.
- *Cache the workload?* Short TTL is fine for balance; not for hard limits.

### Q128. Design the outbox relay (publisher side). `[Design]`
**Answer.** Table `outbox_event(id, aggregate_id, tenant_id, type, payload, created_at, published_at)`. Written in the same transaction as the state change. A relay polls `WHERE published_at IS NULL ORDER BY id LIMIT n` (using `FOR UPDATE SKIP LOCKED` for multiple relays), publishes to the broker with publisher confirms, then sets `published_at` (or deletes). Crash after publish but before marking => re-publish => duplicates, hence consumer idempotency (event id in headers). Preserve per-aggregate order by routing key and single-threaded handling per key. Add metrics: backlog size, oldest unpublished age (alert), publish failures. Retention job purges old rows.
**In this project.** Outbox is part of the design; the `local` profile swaps the broker with an in-memory relay behind an interface so the same code path runs; RabbitMQ publishing was not exercised without Docker.
**Follow-ups.**
- *Ordering across aggregates?* Not guaranteed; do not depend on it.
- *CDC alternative?* Tail the WAL (Debezium) to remove polling latency.

### Q129. Design an idempotent notification consumer with retry and DLQ. `[Design]`
**Answer.** Message carries `eventId`, `tenantId`, `type`, ticket reference. Consumer steps in one local transaction: insert `processed_event(event_id)` (unique) - if it already exists, ack and skip; else create the notification row(s) for the target users (tenant-scoped). Transient failure (DB timeout): let the retry policy re-deliver with backoff; poison message (invalid payload) -> reject without requeue -> dead-letter exchange -> DLQ with alerts and a replay tool. Keep consumers fast; prefetch tuned; handle unknown event types by logging and acking (forward compatibility). Notification list/unread-count endpoints are tenant- and user-scoped reads.
**In this project.** notification-service on :8082 with RabbitMQ retry + DLQ + idempotent consumer; endpoints `GET /notifications`, `POST /notifications/{id}/read`, `GET /notifications/unread-count`.
**Follow-ups.**
- *Ordering problem: "read" arrives before "created"?* Not possible here (different channels); but for out-of-order events use versions/timestamps and upserts.
- *DLQ replay danger?* Replays duplicate messages; idempotency makes it safe.

### Q130. Design ticket and knowledge-base search. `[Design]`
**Answer.** Start with PostgreSQL full-text search (`tsvector` column + GIN index, `plainto_tsquery`, rank) scoped by `tenant_id` and role visibility (customers: only PUBLISHED + PUBLIC articles); this avoids a separate cluster until scale demands it. Move to a search engine (OpenSearch/Elasticsearch) when you need typo tolerance, facets, synonyms and relevance tuning; keep it fed from outbox events (eventual consistency) with a reindex path. Always apply authorization filters inside the query, not after fetching. For "recommended articles" for a ticket, a simple approach is keyword/term overlap scoring (the mock AI's approach); embeddings come later.
**In this project.** Contract has `q` on tickets and articles; PostgreSQL-only migrations include GIN full-text; AI analyze returns `recommendedArticles:[{id,title,score}]` from a mock rule-based provider (`MOCK_RULE_BASED`). No external search engine is claimed.
**Follow-ups.**
- *`LIKE '%word%'` problem?* Cannot use a B-tree index; scans.
- *Index freshness?* Synchronous with the row for PG FTS; eventual for external engines.

### Q131. Design attachment handling. `[Design]`
**Answer.** Do not stream large files through the API server: use object storage (S3-compatible) with pre-signed upload URLs; store only metadata (file name, content type, size, storage key, checksum, tenant) in the DB; key paths include the tenant. Enforce size/type allow-lists, scan for malware asynchronously, serve downloads via short-lived signed URLs after an authorization check, and sanitise file names (path traversal, header injection). Mark attachments `pending` until upload completes.
**In this project.** The contract carries attachments as metadata on the ticket (`attachments:[{id,fileName,contentType,sizeBytes}]`, and in create the same without id). A real object store, malware scanning or signed URLs are not claimed; this is the production-grade design.
**Follow-ups.**
- *Why not store in DB?* Bloat, backups and bandwidth; fine for tiny files only.
- *Content-type trust?* Never trust the client header; sniff server-side.

### Q132. Design the audit trail. `[Design]`
**Answer.** Append-only table (`at, tenant_id, actor_id, action, entity_type, entity_id, details`), written in the same transaction as the change (so no audit without change and vice versa), never updated or deleted by app code (separate DB role/permissions). Capture who/what/when and before-after for sensitive fields; avoid storing secrets or large payloads. Query by entity or actor with pagination; retain per policy and archive to cold storage. Distinguish the customer-visible activity timeline from the admin audit log.
**In this project.** `GET /audit-events?page&size&entityType&actorId` (ADMIN) returns `{id, at, actorId, actorEmail, action, entityType, entityId, details}`; `GET /tickets/{id}/activity` gives the per-ticket timeline; AI generation and decisions are audited per the contract.
**Follow-ups.**
- *Tamper evidence?* Hash chaining or write to WORM storage (general; not claimed).
- *Sensitive PII in details?* Redact or store references.

### Q133. Design AI assist with a human in the loop. `[Design]`
**Answer.** Treat model output as an untrusted suggestion. Flow: analyze (summary, category and priority suggestion with confidence and an explanation, recommended articles, draft reply) -> persist as a suggestion in `PENDING_REVIEW` -> an agent approves (optionally edited), rejects with reason -> only then a comment is created for the customer. Guardrails: strict timeouts and graceful degradation, no automatic customer-facing sends, audit generation and decision, label AI-generated content, tenant isolation of any context sent to a provider, redaction of sensitive data, rate limits, and metrics (acceptance rate, edit rate). Prompt-injection awareness: ticket text is attacker-controlled; never let model output trigger privileged actions directly.
**In this project.** Mock rule-based provider (`provider: MOCK_RULE_BASED`, `generated:true`); `POST /tickets/{id}/ai/analyze`; suggestions `GET /tickets/{id}/ai/suggestions`; `approve` (`editedReply?`, status `APPROVED|EDITED_APPROVED`) posts the reply as a comment with `aiGenerated`; `reject {reason}`; "Nothing reaches a customer without approve"; failure/timeout returns HTTP 200 with `degraded:true`, `fallbackMessage` and no reply text; dashboard exposes `aiAcceptanceRatePercent`. It is a mock - no real LLM is called.
**Follow-ups.**
- *How would you swap in a real LLM?* Behind the same provider interface (`integrations.aiProvider` setting exists), with timeouts, retries, caching and redaction.
- *Why 200 with `degraded` rather than 503?* Partial failure should not break the agent screen (Q83).

### Q134. Edge concerns at the gateway: auth, CORS, rate limiting, headers. `[Design] [API]`
**Answer.** The gateway terminates TLS (or sits behind a load balancer that does), validates the JWT signature/expiry (and optionally `jti` denylist) so invalid traffic never reaches services, propagates identity as trusted headers or forwards the token, adds/propagates `X-Correlation-Id`, applies per-tenant/per-IP rate limits, sets security headers, and enforces CORS allow-lists (explicit origins, not `*` with credentials). Services must still verify authorization themselves (do not trust the network). Request size limits and timeouts prevent slow-loris and resource exhaustion.
**In this project.** Gateway :8080 routes `/api/v1/**` to core and notifications; the contract requires a Bearer JWT and returns `UNAUTHENTICATED`/`FORBIDDEN`/`RATE_LIMITED` codes. Specific CORS/limit settings are not documented.
**Follow-ups.**
- *Why re-validate in core-service?* Defense in depth; internal calls could bypass the gateway.
- *CORS misconfiguration risk?* Reflecting any origin with credentials enables cross-site data theft.

### Q135. Analytics dashboard: query design and cache-aside. `[Design]`
**Answer.** Dashboard metrics (open count, by priority, averages, SLA compliance, backlog, trend, workload) are aggregates over tenant-scoped tickets within a date range. Compute with SQL aggregates (`GROUP BY`, `date_trunc`, `FILTER`) using supporting indexes, not by loading entities. Cache the assembled result per `(tenant, days)` with a short TTL (cache-aside) - freshness vs load trade-off - and avoid stampedes on expiry. At bigger scale, use pre-aggregated rollup tables updated by events, or a read replica/OLAP store. Note cache keys include tenant to prevent leakage.
**In this project.** `GET /analytics/dashboard?days=30` (MANAGER+) with Redis cache-aside, TTL 60 s, tenant-keyed (Caffeine locally). Response fields are listed in the contract.
**Follow-ups.**
- *Invalidate on write or TTL only?* TTL alone is simpler and fine for dashboards; targeted eviction needs event wiring.
- *How to compute SLA compliance?* MET / (MET + BREACHED) over tickets resolved in range.

### Q136. Scaling: read replicas, sharding tenants, noisy neighbours. `[Design]`
**Answer.** Scale stateless services horizontally behind a load balancer. Scale reads via caching and read replicas (accept replica lag; keep read-your-write flows on primary). Partition writes by tenant (shard map/directory; consistent hashing is the technique in Q119) once a single primary is saturated. Noisy neighbours: per-tenant rate limits, query timeouts, bounded pagination, bulkheaded thread pools, and the ability to move a heavy tenant to dedicated capacity. Prioritise cheap wins first: indexes, N+1 removal, caching, async fan-out. Capacity plan with load tests, not guesses.
**In this project.** Single shared database design with tenant filter, rate limiting at the gateway, tenant-keyed caches; sharding is a "how I would evolve it" answer, not implemented.
**Follow-ups.**
- *First bottleneck for a ticket system?* Usually DB read queries (list/filter/search) and counters/ticket-number allocation.
- *Cross-tenant admin reports?* Run offline against replicas/warehouse.

### Q137. Resilience: timeouts, retries with backoff, circuit breaker, bulkheads. `[Design]`
**Answer.** Every remote call has a timeout shorter than the caller's own budget. Retry only idempotent operations, with exponential backoff plus jitter and a cap, so failures do not synchronise into retry storms. A circuit breaker (Resilience4j) opens after a failure threshold, fails fast, and half-opens to probe recovery. Bulkheads isolate thread pools/semaphores per dependency so a slow AI provider cannot starve request threads. Provide fallbacks that degrade gracefully (the AI `degraded` response). Idempotency keys make client retries on POST safe.
```java
// Example shape only (Resilience4j style); not compiled here
Supplier<Suggestion> guarded = Decorators.ofSupplier(() -> provider.analyze(ticket))
        .withCircuitBreaker(cb).withBulkhead(bh).withFallback(List.of(Exception.class), e -> Suggestion.degraded()).decorate();
```
**In this project.** AI timeout/failure degrades to HTTP 200 + `degraded:true`; RabbitMQ consumers retry then dead-letter; ticket creation is retry-safe through `Idempotency-Key`. A circuit-breaker library is not claimed.
**Follow-ups.**
- *Why jitter?* Avoid thundering herd.
- *Retries amplify load?* Yes; add retry budgets and only retry transient failures.

### Q138. Observability: logs, metrics, traces, SLOs. `[Design]`
**Answer.** Structured logs with `correlationId`, `tenantId`, `userId` (no tokens/PII); RED metrics (rate, errors, duration) per endpoint and USE metrics (utilisation, saturation, errors) per resource; business metrics (tickets created, SLA breaches, AI acceptance). Define SLOs (e.g. 99.9% of `GET /tickets` under 300 ms) and alert on burn rate rather than raw thresholds. Add dashboards for DB pool saturation, outbox lag, DLQ depth and cache hit rate. Use health groups: liveness for restarts, readiness for traffic.
**In this project.** Actuator health/readiness and Prometheus endpoint per the contract; correlation id in headers and errors. Dashboards/alert rules are not claimed.
**Follow-ups.**
- *Cardinality trap?* Do not put ticket ids or user ids as metric labels; tenant id only if the count is bounded.
- *Which metrics prove outbox health?* Backlog count and age of oldest unpublished row.

### Q139. LLD: class design for the ticket service and patterns used. `[Design] [Java]`
**Answer.** Layers: controller (HTTP, validation, DTO mapping) -> application service (transaction boundary, orchestration) -> domain (entities with behaviour, state machine, SLA calculator) -> repositories/adapters (JPA, cache, event publisher). Patterns: Strategy (workflow action handlers, notification channels), State/table-driven machine (transitions), Repository, Adapter (provider/cache/publisher interfaces), Factory (building tickets from commands), Observer/Event (domain events -> outbox), Template Method (common activity/audit recording). Keep entities from leaking into the API (map to DTOs), keep side effects behind interfaces, inject `Clock` for time. Test domain logic with plain unit tests; test the contract with slice/integration tests.
```java
interface ActionHandler { ActionType supports(); void apply(Ticket t, Map<String,String> params, RuleContext ctx); }
// Spring collects List<ActionHandler>; dispatcher builds Map<ActionType, ActionHandler>
```
**In this project.** Interface-based adapters (`CacheService`, `EventPublisher/relay`, `DocumentStore`) are documented; the action handler shape above is a design suggestion for the closed action set.
**Follow-ups.**
- *Anemic vs rich domain model?* Put invariants (transitions, SLA) in the domain; services orchestrate.
- *Why inject a `Clock`?* Deterministic SLA tests.

### Q140. Zero-downtime schema evolution with Flyway. `[Design] [DB]`
**Answer.** Use expand/contract: (1) expand - add new nullable column/table/index (online, e.g. `CREATE INDEX CONCURRENTLY` in PostgreSQL), deploy code that writes both; (2) backfill in batches; (3) switch reads; (4) contract - drop the old column after all instances run the new code. Never rename/drop in the same release as the code change. Migrations are versioned, immutable once applied, and run on startup or in a pipeline step with a lock. Keep portable SQL separate from vendor-specific SQL.
**In this project.** Flyway with `db/migration` (portable: H2 in PostgreSQL mode and PostgreSQL) and `db/migration-pg` (PostgreSQL-only: partial indexes, GIN full text, RLS) added only under the `docker` profile. The PG-only set was not executed locally (no Docker).
**Follow-ups.**
- *Why can `CREATE INDEX CONCURRENTLY` not run in a transaction?* It needs multiple transactions internally; configure the migration as non-transactional.
- *Rollback strategy?* Prefer forward fixes; keep changes backward compatible so the previous app version still works.

---

# Mock interview sets

Each set has 10 questions (by number), a 45-minute running order, and a scoring rubric. Interviewer tip: pick the follow-ups from the question entries; push on "in this project vs general" honesty.

**Shared scoring rubric (per question, 0-4):**
- 0 = no answer or incorrect.
- 1 = vague or memorised definition, cannot apply it.
- 2 = correct core idea, misses trade-offs or edge cases.
- 3 = correct, with trade-offs, an example, and one pitfall.
- 4 = senior level: correct, concrete, ties to the project honestly (what exists vs what is a general design), anticipates follow-ups and failure modes.

**Overall bands (sum of 10 questions, max 40):** 0-19 not ready; 20-27 borderline, needs depth; 28-34 hire for IC3 with coaching; 35-40 strong IC3 / lead-ready. Add up to 2 bonus points (not above 40) for calling out an assumption or an unverified claim unprompted. Deduct 2 for claiming a project feature outside the documented list (JWT tenant claims, Hibernate tenant filter, optimistic locking, Idempotency-Key, outbox, Caffeine/Redis cache-aside, RabbitMQ retry+DLQ+idempotent consumer, mock rule-based AI with approval, constrained workflow rules).

## Set 1 - Java backend

| Min | Q | Topic |
|---|---|---|
| 0-3 | intro | Walk through the project architecture in 2 minutes |
| 3-8 | Q5 | HashMap internals |
| 8-13 | Q6 | ConcurrentHashMap |
| 13-18 | Q16 | Virtual threads vs platform threads |
| 18-23 | Q18 | JMM, volatile, happens-before |
| 23-27 | Q19 | Deadlock |
| 27-31 | Q20 | Thread pool sizing |
| 31-34 | Q21 | CompletableFuture with timeout fallback |
| 34-38 | Q23 | ThreadLocal tenant context leak |
| 38-42 | Q24 | Exceptions design |
| 42-45 | Q36 | Optimistic locking in the API |

Questions: Q5, Q6, Q16, Q18, Q19, Q20, Q21, Q23, Q24, Q36. Pass-mark: 28/40. Red flags: "synchronized makes everything safe"; cannot explain why `count++` on a volatile is unsafe.

## Set 2 - React / frontend

| Min | Q | Topic |
|---|---|---|
| 0-3 | intro | Frontend architecture of the portfolio app |
| 3-8 | Q51 | Reconciliation and keys |
| 8-13 | Q53 | useEffect, cleanup, stale closures |
| 13-18 | Q54 | Memoisation: when it helps |
| 18-23 | Q56 | Controlled forms and server fieldErrors |
| 23-28 | Q57 | TanStack Query caching |
| 28-32 | Q58 | Optimistic updates and 409 handling |
| 32-36 | Q59 | Idempotency-Key from the client |
| 36-40 | Q60 | Error boundaries |
| 40-43 | Q61 | Accessibility |
| 43-45 | Q64 | Token storage and XSS |

Questions: Q51, Q53, Q54, Q56, Q57, Q58, Q59, Q60, Q61, Q64. Pass-mark: 28/40. Red flags: index as key in dynamic lists; storing server data in `useState` and manually syncing; believing UI role checks are security.

## Set 3 - Full-stack coding and debugging

| Min | Q | Task |
|---|---|---|
| 0-3 | intro | Trace "create ticket" from click to DB and event |
| 3-8 | Q77 | Design idempotent POST (whiteboard the table and flow) |
| 8-12 | Q59 | Client side: where is the key generated? Spot the retry bug |
| 12-17 | Q32 | Debug: `@Transactional` not applying (self-invocation) |
| 17-22 | Q38 | Debug: cross-tenant data visible via `findById` (filter bypass) |
| 22-26 | Q35 | Debug: N+1 on ticket list |
| 26-30 | Q58 | Debug: 409 on every second edit (client reuses an old `version`) |
| 30-35 | Q97 | Code: token bucket limiter (write and test) |
| 35-39 | Q123 | Design: indexes for the ticket list filters |
| 39-42 | Q93 | Code: LRU cache (structure and O(1) argument) |
| 42-45 | Q116 | Discuss only: business-hours SLA due date approach |

Questions: Q77, Q59, Q32, Q38, Q35, Q58, Q97, Q123, Q93, Q116. Pass-mark: 28/40. Red flags: fixes N+1 by adding `FetchType.EAGER`; no failing test before the fix; no mention of tests.

## Set 4 - DSA problem solving

| Min | Q | Problem |
|---|---|---|
| 0-3 | Q91 | Warm-up: complexity analysis out loud |
| 3-8 | Q99 | Two Sum (Q100 as follow-up) |
| 8-13 | Q102 | Sliding window |
| 13-19 | Q93 | LRU cache |
| 19-24 | Q95 | Merge intervals |
| 24-29 | Q96 | Topological sort |
| 29-34 | Q105 | BFS on a workflow graph |
| 34-39 | Q104 | Binary search on the answer |
| 39-42 | Q111 | Prefix sums + hash map |
| 42-45 | Q116 | Approach only: business-hours date arithmetic |

Questions: Q91, Q99, Q102, Q93, Q95, Q96, Q105, Q104, Q111, Q116. Pass-mark: 28/40. Scoring guide per problem: approach and complexity stated before coding, working code, edge cases and tests, alternatives/optimisation. Red flags: coding before clarifying inputs; no complexity statement; off-by-one in binary search with no invariant.

## Set 5 - Spring deep-dive

| Min | Q | Topic |
|---|---|---|
| 0-4 | Q27 | IoC/DI, constructor injection |
| 4-9 | Q28 | Bean lifecycle and post-processors |
| 9-14 | Q30 | Auto-configuration internals |
| 14-20 | Q32 | `@Transactional` proxy mechanics |
| 20-25 | Q33 | Propagation and isolation |
| 25-30 | Q36 | Optimistic locking and the 409 contract |
| 30-35 | Q38 | Hibernate tenant filter limitations |
| 35-39 | Q40 | Stateless JWT security chain |
| 39-43 | Q45 | Outbox pattern |
| 43-45 | Q49 | RabbitMQ retry, DLQ, idempotent consumer |

Questions: Q27, Q28, Q30, Q32, Q33, Q36, Q38, Q40, Q45, Q49. Pass-mark: 28/40. Red flags: cannot explain why self-invocation bypasses the proxy; believes `@Filter` protects `findById`; thinks outbox gives exactly-once.

---

## Appendix - Verification statement

- Total numbered questions: 140 (Q1-Q140), each with an answer and 1-2 follow-ups.
- Coding solutions (Q93-Q119 with `// COMPILE:` blocks, plus Q13, Q15, Q21 demos) were extracted from this file and compiled with the JDK 21 `javac`, then run through an assertion harness. See the final report for exact counts.
- JUnit-style tests are sketches (not compiled). Non-Java snippets (TypeScript/TSX, SQL, YAML) were not compiled or executed.
- No application code from the repo was modified by this document; claims about the project are limited to the contract and constraints files.
