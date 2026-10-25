package com.example.cx.dsa.heap;

import java.util.Comparator;

/**
 * Work queue that always yields the most urgent ticket: higher priority first, then earlier SLA deadline, then FIFO.
 *
 * <p><b>Purpose / platform use case:</b> the "next ticket for an agent" and escalation scans. Priority values follow the
 * API contract: LOW, MEDIUM, HIGH, URGENT. (The platform sorts via SQL {@code ORDER BY}; this is the in-memory equivalent.)
 *
 * <p><b>Algorithm:</b> {@link BinaryHeap} with a composite comparator; a monotonically increasing sequence number makes
 * the order total and stable (equal tickets leave in arrival order), which a plain heap does not guarantee.
 *
 * <p><b>Complexity:</b> add/poll O(log n), peek O(1), space O(n).
 *
 * <p><b>Edge cases:</b> empty poll returns null, equal priority and deadline, null deadline (treated as "no deadline", sorted
 * last), unknown priority string rejected.
 *
 * <p><b>Brute force vs optimized:</b> re-sorting the list on every insert is O(n log n); scanning for the max is O(n).
 *
 * <p><b>Interview follow-ups:</b> priority changes while queued (lazy deletion with versioning, or indexed heap with
 * decrease-key)? Starvation of LOW tickets (aging, see the scheduling package)? Multi-tenant fairness (one heap per
 * tenant plus round robin)?
 */
public class TicketPriorityQueue {
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }

    /** @param slaDeadlineMillis epoch millis or null when there is no SLA deadline */
    public record Ticket(String id, Priority priority, Long slaDeadlineMillis) { }

    private record Entry(Ticket ticket, long seq) { }

    private final BinaryHeap<Entry> heap = new BinaryHeap<>(
            Comparator.<Entry>comparingInt(e -> -e.ticket().priority().ordinal())
                    .thenComparingLong(e -> e.ticket().slaDeadlineMillis() == null ? Long.MAX_VALUE : e.ticket().slaDeadlineMillis())
                    .thenComparingLong(Entry::seq));
    private long seq;

    public void add(Ticket t) {
        if (t == null || t.priority() == null) throw new IllegalArgumentException("ticket and priority required");
        heap.push(new Entry(t, seq++));
    }

    public Ticket poll() { return heap.isEmpty() ? null : heap.pop().ticket(); }
    public Ticket peek() { return heap.isEmpty() ? null : heap.peek().ticket(); }
    public int size() { return heap.size(); }
}
