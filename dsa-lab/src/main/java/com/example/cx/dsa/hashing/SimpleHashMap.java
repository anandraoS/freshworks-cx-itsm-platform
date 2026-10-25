package com.example.cx.dsa.hashing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Custom hash map with separate chaining and load-factor driven resizing.
 *
 * <p><b>Purpose / platform use case:</b> per-tenant lookup tables (tenantId to settings, ticketNumber to ticketId).
 * The platform itself uses {@code java.util.HashMap}/{@code ConcurrentHashMap}; this class shows what they do.
 *
 * <p><b>Algorithm:</b> {@code index = spread(hash) & (capacity-1)} with a power-of-two table; each bucket is a singly linked
 * chain. When {@code size > loadFactor * capacity} the table doubles and every node is re-bucketed (rehash).
 * The hash is spread ({@code h ^ h>>>16}) so that poor low bits do not cluster keys (same trick as the JDK).
 *
 * <p><b>Complexity:</b> get/put/remove O(1) expected, O(n) worst case (all keys in one chain; the JDK treeifies chains
 * into red-black trees to bound this at O(log n)). Resize is O(n) but amortised O(1) per insert. Space O(n + capacity).
 *
 * <p><b>Edge cases:</b> null key (supported, stored in bucket 0), null value, overwrite returns old value, removing a
 * missing key, negative hash codes, many colliding keys, resize while chains are long.
 *
 * <p><b>Brute force vs optimized:</b> a list of entries needs O(n) per lookup; hashing trades memory for O(1) expected.
 *
 * <p><b>Interview follow-ups:</b> why power-of-two capacity (mask instead of modulo)? Why load factor 0.75? How does
 * a concurrent map avoid a global lock (per-bin CAS/synchronized)? What breaks if keys are mutable or
 * {@code equals} and {@code hashCode} disagree? Open addressing vs chaining? Incremental resize to avoid latency spikes?
 */
public class SimpleHashMap<K, V> {
    private static final class Node<K, V> {
        final K key;
        final int hash;
        V value;
        Node<K, V> next;

        Node(K key, int hash, V value, Node<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private Node<K, V>[] table;
    private int size;
    private final double loadFactor;
    private int resizeCount;

    public SimpleHashMap() { this(16, 0.75); }

    @SuppressWarnings("unchecked")
    public SimpleHashMap(int initialCapacity, double loadFactor) {
        if (initialCapacity < 1 || loadFactor <= 0) throw new IllegalArgumentException("capacity>=1 and loadFactor>0");
        int cap = 1;
        while (cap < initialCapacity) cap <<= 1;
        this.table = (Node<K, V>[]) new Node[cap];
        this.loadFactor = loadFactor;
    }

    private static int hash(Object key) {
        int h = Objects.hashCode(key);
        return h ^ (h >>> 16);
    }

    private int indexFor(int hash, int length) { return hash & (length - 1); }

    public V put(K key, V value) {
        int h = hash(key);
        int i = indexFor(h, table.length);
        for (Node<K, V> n = table[i]; n != null; n = n.next) {
            if (n.hash == h && Objects.equals(n.key, key)) {
                V old = n.value;
                n.value = value;
                return old;
            }
        }
        table[i] = new Node<>(key, h, value, table[i]);
        if (++size > loadFactor * table.length) resize();
        return null;
    }

    public V get(Object key) {
        int h = hash(key);
        for (Node<K, V> n = table[indexFor(h, table.length)]; n != null; n = n.next) {
            if (n.hash == h && Objects.equals(n.key, key)) return n.value;
        }
        return null;
    }

    public boolean containsKey(Object key) {
        int h = hash(key);
        for (Node<K, V> n = table[indexFor(h, table.length)]; n != null; n = n.next) {
            if (n.hash == h && Objects.equals(n.key, key)) return true;
        }
        return false;
    }

    public V remove(Object key) {
        int h = hash(key);
        int i = indexFor(h, table.length);
        Node<K, V> prev = null;
        for (Node<K, V> n = table[i]; n != null; prev = n, n = n.next) {
            if (n.hash == h && Objects.equals(n.key, key)) {
                if (prev == null) table[i] = n.next; else prev.next = n.next;
                size--;
                return n.value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] old = table;
        Node<K, V>[] bigger = (Node<K, V>[]) new Node[old.length * 2];
        for (Node<K, V> head : old) {
            for (Node<K, V> n = head; n != null; ) {
                Node<K, V> next = n.next;
                int i = indexFor(n.hash, bigger.length);
                n.next = bigger[i];
                bigger[i] = n;
                n = next;
            }
        }
        table = bigger;
        resizeCount++;
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public int capacity() { return table.length; }
    public int resizeCount() { return resizeCount; }

    /** Longest chain: a quick health indicator for hash quality. */
    public int longestChain() {
        int max = 0;
        for (Node<K, V> head : table) {
            int len = 0;
            for (Node<K, V> n = head; n != null; n = n.next) len++;
            max = Math.max(max, len);
        }
        return max;
    }

    public List<K> keys() {
        List<K> out = new ArrayList<>(size);
        for (Node<K, V> head : table) for (Node<K, V> n = head; n != null; n = n.next) out.add(n.key);
        return out;
    }
}
