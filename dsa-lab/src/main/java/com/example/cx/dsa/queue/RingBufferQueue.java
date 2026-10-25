package com.example.cx.dsa.queue;

/**
 * Bounded FIFO queue backed by {@link RingBufferDeque}.
 *
 * <p><b>Purpose / platform use case:</b> a bounded hand-off buffer (for example between the in-memory event dispatcher and a
 * consumer): when full, {@link #offer} returns false so the producer applies back-pressure instead of growing without bound.
 *
 * <p><b>Algorithm:</b> enqueue at tail, dequeue at head of a circular array (see {@link RingBufferDeque}).
 *
 * <p><b>Complexity:</b> O(1) per operation, O(capacity) space.
 *
 * <p><b>Edge cases:</b> poll/peek on empty return null, offer on full returns false, wrap-around after many cycles.
 *
 * <p><b>Brute force vs optimized:</b> shifting array vs O(1) circular indices.
 *
 * <p><b>Interview follow-ups:</b> implement a queue with two stacks (amortised O(1)); blocking version with
 * {@code ReentrantLock}+conditions; why an unbounded queue is an outage waiting to happen.
 */
public class RingBufferQueue<T> {
    private final RingBufferDeque<T> deque;

    public RingBufferQueue(int capacity) { this.deque = new RingBufferDeque<>(capacity); }

    public boolean offer(T t) { return deque.offerLast(t); }
    public T poll() { return deque.pollFirst(); }
    public T peek() { return deque.peekFirst(); }
    public int size() { return deque.size(); }
    public boolean isEmpty() { return deque.isEmpty(); }
    public boolean isFull() { return deque.isFull(); }
    public int capacity() { return deque.capacity(); }
}
