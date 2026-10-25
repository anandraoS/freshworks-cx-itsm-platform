package com.example.cx.dsa.queue;

import java.util.NoSuchElementException;

/**
 * Fixed-capacity circular double-ended queue (array + head index + size). With {@link #offerLast}/{@link #pollFirst}
 * it is a bounded FIFO ring buffer; {@link RingBufferQueue} is the thin FIFO facade.
 *
 * <p><b>Purpose / platform use case:</b> bounded in-memory buffers: recent-activity feed per ticket, a back-pressure
 * buffer between the event dispatcher and a slow consumer (offer fails when full so the caller can shed load or retry),
 * "last N" audit lines. The platform uses RabbitMQ prefetch and {@code ArrayDeque}; this shows the mechanism.
 *
 * <p><b>Algorithm:</b> {@code tail = (head + size) % capacity}; pushing at the front decrements head modulo capacity.
 * No shifting ever happens, so all operations are O(1). Optional overwrite mode drops the oldest element.
 *
 * <p><b>Complexity:</b> every operation O(1) time; O(capacity) space, no allocation after construction.
 *
 * <p><b>Edge cases:</b> empty poll/peek, offer on full, wrap-around of head, capacity 1, negative modulo
 * ({@link Math#floorMod}), null elements rejected, overwrite policy.
 *
 * <p><b>Brute force vs optimized:</b> {@code ArrayList.remove(0)} is O(n) per dequeue; a linked list allocates a node per
 * element; the ring buffer is O(1) with cache-friendly contiguous memory.
 *
 * <p><b>Interview follow-ups:</b> make it lock-free single-producer/single-consumer (two volatile indices, one slot kept
 * empty to tell full from empty)? Power-of-two capacity and mask instead of modulo? Growable version (copy in order)?
 * LMAX Disruptor? How to distinguish full from empty without a size field?
 */
public class RingBufferDeque<T> {
    private final Object[] data;
    private int head;
    private int size;

    public RingBufferDeque(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.data = new Object[capacity];
    }

    public int capacity() { return data.length; }
    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public boolean isFull() { return size == data.length; }

    public boolean offerLast(T t) {
        if (t == null) throw new NullPointerException();
        if (isFull()) return false;
        data[(head + size) % data.length] = t;
        size++;
        return true;
    }

    public boolean offerFirst(T t) {
        if (t == null) throw new NullPointerException();
        if (isFull()) return false;
        head = Math.floorMod(head - 1, data.length);
        data[head] = t;
        size++;
        return true;
    }

    /** Appends, evicting the oldest element when full. Returns the evicted element or null. */
    @SuppressWarnings("unchecked")
    public T offerLastOverwriting(T t) {
        T evicted = null;
        if (isFull()) evicted = pollFirst();
        offerLast(t);
        return evicted;
    }

    @SuppressWarnings("unchecked")
    public T pollFirst() {
        if (size == 0) return null;
        T t = (T) data[head];
        data[head] = null;
        head = (head + 1) % data.length;
        size--;
        return t;
    }

    @SuppressWarnings("unchecked")
    public T pollLast() {
        if (size == 0) return null;
        int idx = (head + size - 1) % data.length;
        T t = (T) data[idx];
        data[idx] = null;
        size--;
        return t;
    }

    @SuppressWarnings("unchecked")
    public T peekFirst() { return size == 0 ? null : (T) data[head]; }

    @SuppressWarnings("unchecked")
    public T peekLast() { return size == 0 ? null : (T) data[(head + size - 1) % data.length]; }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (index < 0 || index >= size) throw new NoSuchElementException("index " + index);
        return (T) data[(head + index) % data.length];
    }
}
