# dsa-lab

Plain Java 21 + JUnit 5 (no Spring). Package `com.example.cx.dsa.*`. Every class has Javadoc covering: purpose and platform use case,
algorithm, time/space, edge cases, brute force vs optimized, and interview follow-ups.

```
mvn -pl dsa-lab -am test      # from the repo root (53 tests)
```

## Topic to platform mapping

"Used" means the same algorithm runs in the shipped platform code. "Educational" means the platform uses a library or SQL for this
and the class exists to practise and explain the technique.

| Topic (class) | Platform use case | Status |
|---|---|---|
| Custom hash map, chaining + resize (`hashing.SimpleHashMap`) | Tenant/ticket lookup tables | Educational (platform uses `HashMap`/`ConcurrentHashMap`) |
| Duplicate detection (`hashing.DuplicateDetection`) | Idempotent consumer on `eventId`, `Idempotency-Key` replay | Educational for the algorithm; the same idea is USED via DB unique constraints (`processed_event`, idempotency table) |
| Ring-buffer queue / deque (`queue.*`) | Bounded buffers, back-pressure between dispatcher and consumer | Educational (RabbitMQ prefetch and `ArrayDeque` are used) |
| Binary heap (`heap.BinaryHeap`) | Foundation for priority queues | Educational (JDK `PriorityQueue`) |
| Ticket priority queue (`heap.TicketPriorityQueue`) | "Next ticket" by priority, SLA deadline, FIFO | Educational (platform orders with SQL `ORDER BY`) |
| Heap top-K (`heap.TopK`) | Top agents / tags / slowest tickets on the dashboard | Educational (SQL aggregates) |
| Merge / quick / heap sort (`sorting.Sorting`) | In-memory ordering of tickets | Educational (JDK sort) |
| Binary search variants, search on answer (`sorting.BinarySearch`) | Time-window range queries, agent capacity planning | Educational (DB B-tree index does range lookups) |
| BST + traversals (`tree.Bst`) | Ordered index by due date | Educational (`TreeMap`, DB B-trees) |
| Graph BFS/DFS, shortest hops (`graph.Graph`) | Workflow transition reachability, dead states | Educational (workflow validation is a constrained rule model) |
| Topological sort + cycle detection (`graph.TopologicalSort`) | Workflow/rule dependency ordering, reject cyclic definitions | Educational |
| LRU cache (`cache.LruCache`) | Cache-aside eviction policy | Educational (platform uses Caffeine locally, Redis in docker) |
| Sliding window (`window.SlidingWindow`) | Trend metrics, peak windows | Educational (analytics are SQL aggregates) |
| Interval merging (`interval.IntervalMerger`) | SLA pause windows vs business hours | Educational (concept mirrors SLA clock arithmetic) |
| Token bucket (`ratelimit.TokenBucket`) | API and login rate limiting | USED: gateway `TokenBucketLimiter` and core `LoginRateLimiter` implement this algorithm |
| Fixed window, sliding window log / counter (`ratelimit.*`) | Quotas, rolling limits | Educational |
| Round robin, least loaded via heap (`scheduling.Assigners`) | `ASSIGN_TO_ROLE_LEAST_LOADED` workflow action | Educational (platform derives load from DB counts) |
| SJF, priority + aging (`scheduling.Schedulers`) | Queue discipline, starvation avoidance | Educational |
| Trie prefix search (`trie.Trie`) | KB / tag type-ahead | Educational (platform uses full-text search) |

Retry/backoff in the notification service (`RetryPolicy`) is also a small applied algorithm (bounded attempts, constant or exponential delay)
but is not part of this module.
