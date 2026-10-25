package com.example.cx.dsa.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RingBufferTest {
    @Test
    void fifoWithWrapAroundAndBackPressure() {
        RingBufferQueue<Integer> q = new RingBufferQueue<>(3);
        assertNull(q.poll());
        assertNull(q.peek());
        assertTrue(q.offer(1) && q.offer(2) && q.offer(3));
        assertFalse(q.offer(4));
        assertTrue(q.isFull());
        assertEquals(1, q.poll());
        assertTrue(q.offer(4));                      // wraps
        assertEquals(2, q.poll());
        assertEquals(3, q.poll());
        assertEquals(4, q.poll());
        assertTrue(q.isEmpty());
    }

    @Test
    void dequeBothEndsAndOverwrite() {
        RingBufferDeque<String> d = new RingBufferDeque<>(3);
        d.offerLast("b");
        d.offerFirst("a");
        d.offerLast("c");
        assertEquals("a", d.peekFirst());
        assertEquals("c", d.peekLast());
        assertEquals("b", d.get(1));
        assertEquals("a", d.offerLastOverwriting("d"));
        assertEquals("d", d.pollLast());
        assertEquals("b", d.pollFirst());
        assertThrows(java.util.NoSuchElementException.class, () -> d.get(5));
        assertThrows(NullPointerException.class, () -> d.offerLast(null));
        assertThrows(IllegalArgumentException.class, () -> new RingBufferDeque<>(0));
    }

    @Test
    void capacityOneAndRandomizedAgainstArrayDeque() {
        RingBufferDeque<Integer> one = new RingBufferDeque<>(1);
        assertTrue(one.offerFirst(1));
        assertFalse(one.offerLast(2));
        assertEquals(1, one.pollLast());

        RingBufferDeque<Integer> d = new RingBufferDeque<>(5);
        ArrayDeque<Integer> ref = new ArrayDeque<>();
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            switch (r.nextInt(4)) {
                case 0 -> { if (ref.size() < 5) { ref.addLast(i); assertTrue(d.offerLast(i)); } else assertFalse(d.offerLast(i)); }
                case 1 -> { if (ref.size() < 5) { ref.addFirst(i); assertTrue(d.offerFirst(i)); } else assertFalse(d.offerFirst(i)); }
                case 2 -> assertEquals(ref.pollFirst(), d.pollFirst());
                default -> assertEquals(ref.pollLast(), d.pollLast());
            }
            assertEquals(ref.size(), d.size());
        }
    }
}
