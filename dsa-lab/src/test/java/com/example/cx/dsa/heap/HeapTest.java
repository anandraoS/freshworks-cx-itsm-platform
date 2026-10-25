package com.example.cx.dsa.heap;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cx.dsa.heap.TicketPriorityQueue.Priority;
import com.example.cx.dsa.heap.TicketPriorityQueue.Ticket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HeapTest {
    @Test
    void heapSortsAndKeepsInvariant() {
        BinaryHeap<Integer> h = new BinaryHeap<>(Comparator.naturalOrder());
        Random r = new Random(1);
        List<Integer> ref = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int v = r.nextInt(100) - 50;
            h.push(v);
            ref.add(v);
            assertTrue(h.isValidHeap());
        }
        Collections.sort(ref);
        for (int v : ref) assertEquals(v, h.pop());
        assertThrows(NoSuchElementException.class, h::pop);
        assertThrows(NoSuchElementException.class, h::peek);
    }

    @Test
    void heapifyBuildsValidHeap() {
        BinaryHeap<Integer> h = BinaryHeap.heapify(List.of(9, 4, 7, 1, 8, 2), Comparator.naturalOrder());
        assertTrue(h.isValidHeap());
        assertEquals(1, h.pop());
        assertEquals(2, h.pop());
    }

    @Test
    void ticketsOrderedByPriorityThenDeadlineThenArrival() {
        TicketPriorityQueue q = new TicketPriorityQueue();
        assertNull(q.poll());
        q.add(new Ticket("low", Priority.LOW, 1L));
        q.add(new Ticket("high-late", Priority.HIGH, 500L));
        q.add(new Ticket("urgent", Priority.URGENT, null));
        q.add(new Ticket("high-early", Priority.HIGH, 100L));
        q.add(new Ticket("high-early-2", Priority.HIGH, 100L));
        assertEquals("urgent", q.poll().id());
        assertEquals("high-early", q.poll().id());
        assertEquals("high-early-2", q.poll().id());   // FIFO among equals
        assertEquals("high-late", q.poll().id());
        assertEquals("low", q.poll().id());
        assertThrows(IllegalArgumentException.class, () -> q.add(new Ticket("x", null, null)));
    }

    @Test
    void topKLargestAndEdgeCases() {
        assertEquals(List.of(9, 7, 5), TopK.largest(new int[] {5, 9, 1, 7, 3, 5}, 3));
        assertEquals(List.of(), TopK.largest(new int[] {1, 2}, 0));
        assertEquals(List.of(2, 1), TopK.largest(new int[] {1, 2}, 10));
        assertEquals(List.of(), TopK.largest(new int[] {}, 3));
        assertThrows(IllegalArgumentException.class, () -> TopK.largest(new int[] {1}, -1));
    }

    @Test
    void topKMostFrequentDeterministicTies() {
        List<String> tags = List.of("vpn", "mail", "vpn", "printer", "mail", "vpn", "wifi", "printer");
        assertEquals(List.of("vpn", "mail"), TopK.mostFrequent(tags, 2));      // mail & printer tie at 2 -> alphabetical
        assertEquals(List.of(), TopK.mostFrequent(tags, 0));
        assertEquals(4, TopK.mostFrequent(tags, 99).size());
    }
}
