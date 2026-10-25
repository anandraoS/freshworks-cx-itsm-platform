package com.example.cx.dsa.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * Least-recently-used cache built from a {@link HashMap} plus a hand-rolled doubly linked list.
 *
 * <p><b>Purpose / platform use case:</b> the cache-aside pattern used for tenant settings and the analytics dashboard
 * (production uses Caffeine locally, which is a W-TinyLFU cache, and Redis in the docker profile, both with TTLs). The
 * LRU policy here is the foundation: bounded memory, evict the entry that has gone unused the longest.
 *
 * <p><b>Algorithm:</b> the map gives O(1) key to node lookup; the list keeps nodes ordered by recency (head = most recent,
 * tail = least recent). {@code get} moves the node to the head; {@code put} inserts at the head and evicts the tail when
 * over capacity. Sentinel head/tail nodes remove null checks.
 *
 * <p><b>Complexity:</b> get/put O(1), space O(capacity). Not thread safe (wrap with a lock or use Caffeine).
 *
 * <p><b>Edge cases:</b> capacity 1, updating an existing key (must refresh recency, not grow), get of a missing key,
 * null values (rejected so a miss is unambiguous), capacity less than 1 rejected, eviction of the only element.
 *
 * <p><b>Brute force vs optimized:</b> a list scan per access is O(n); {@code LinkedHashMap(accessOrder=true)} gives the same
 * behaviour in a few lines (override {@code removeEldestEntry}).
 *
 * <p><b>Interview follow-ups:</b> make it thread safe and concurrent (striped locks, or buffer reads as Caffeine does)?
 * LFU cache in O(1)? TTL expiry (lazy on read plus a delay queue)? Tenant isolation (key prefix vs separate caches, and why
 * a shared cache without tenant-keyed entries leaks data)? Cache stampede protection?
 */
public class LruCache<K, V> {
    private static final class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;
    }

    private final int capacity;
    private final Map<K, Node<K, V>> index = new HashMap<>();
    private final Node<K, V> head = new Node<>();
    private final Node<K, V> tail = new Node<>();
    private long hits;
    private long misses;

    public LruCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> n = index.get(key);
        if (n == null) {
            misses++;
            return null;
        }
        hits++;
        unlink(n);
        addFirst(n);
        return n.value;
    }

    /** @return the evicted key or null when nothing was evicted */
    public K put(K key, V value) {
        if (value == null) throw new NullPointerException("null values not allowed");
        Node<K, V> n = index.get(key);
        if (n != null) {
            n.value = value;
            unlink(n);
            addFirst(n);
            return null;
        }
        n = new Node<>();
        n.key = key;
        n.value = value;
        index.put(key, n);
        addFirst(n);
        if (index.size() > capacity) {
            Node<K, V> lru = tail.prev;
            unlink(lru);
            index.remove(lru.key);
            return lru.key;
        }
        return null;
    }

    public boolean remove(K key) {
        Node<K, V> n = index.remove(key);
        if (n == null) return false;
        unlink(n);
        return true;
    }

    public int size() { return index.size(); }
    public long hits() { return hits; }
    public long misses() { return misses; }

    /** Keys from most to least recently used (for tests and debugging). */
    public java.util.List<K> keysByRecency() {
        java.util.List<K> keys = new java.util.ArrayList<>();
        for (Node<K, V> n = head.next; n != tail; n = n.next) keys.add(n.key);
        return keys;
    }

    private void unlink(Node<K, V> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void addFirst(Node<K, V> n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }
}
