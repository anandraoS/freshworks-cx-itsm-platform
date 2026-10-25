package com.example.cx.dsa.scheduling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Work scheduling policies: shortest-job-first and priority with aging.
 *
 * <p><b>Purpose / platform use case:</b> choosing which queued ticket an agent works next. SJF minimises average waiting time
 * (clear many quick password resets first); priority scheduling serves URGENT first; aging stops LOW tickets from starving
 * forever and breaching SLA. The platform currently orders by priority/SLA in SQL; these are the policies you would discuss.
 *
 * <p><b>Algorithm:</b> <i>SJF (non-preemptive)</i>: at each time step among jobs that have arrived pick the smallest burst (heap).
 * <i>Priority with aging</i>: effective priority = {@code base + agingPerMinute * minutesWaited}. Because every job ages at the same
 * rate, comparing effective priorities at any instant is equivalent to comparing the static key {@code base - agingPerMinute *
 * enqueueMinute}, so a plain heap works (no re-keying as time passes).
 *
 * <p><b>Complexity:</b> SJF simulation O(n log n); aging scheduler add/poll O(log n).
 *
 * <p><b>Edge cases:</b> CPU idle gaps when nothing has arrived, equal bursts (break ties by arrival then id), zero burst,
 * aging rate 0 (degenerates to strict priority), very old low-priority job overtaking new high-priority ones (intended).
 *
 * <p><b>Brute force vs optimized:</b> rescanning all queued jobs each time is O(n^2); heaps make it O(n log n).
 *
 * <p><b>Interview follow-ups:</b> SJF needs burst estimates (exponential averaging) and can starve long jobs; preemptive SRTF;
 * Linux CFS; multilevel feedback queues; fair scheduling across tenants (weighted fair queueing).
 */
public final class Schedulers {
    private Schedulers() { }

    public record Job(String id, long arrival, long burst) { }

    public record Scheduled(String id, long start, long end, long waited) { }

    /** Non-preemptive shortest job first. Returns the execution timeline. */
    public static List<Scheduled> shortestJobFirst(List<Job> jobs) {
        List<Job> byArrival = new ArrayList<>(jobs);
        byArrival.sort(Comparator.comparingLong(Job::arrival).thenComparing(Job::id));
        PriorityQueue<Job> ready = new PriorityQueue<>(
                Comparator.comparingLong(Job::burst).thenComparingLong(Job::arrival).thenComparing(Job::id));
        List<Scheduled> timeline = new ArrayList<>();
        long now = 0;
        int i = 0;
        while (i < byArrival.size() || !ready.isEmpty()) {
            if (ready.isEmpty() && byArrival.get(i).arrival() > now) now = byArrival.get(i).arrival();   // idle gap
            while (i < byArrival.size() && byArrival.get(i).arrival() <= now) ready.add(byArrival.get(i++));
            Job j = ready.poll();
            long start = now;
            now += j.burst();
            timeline.add(new Scheduled(j.id(), start, now, start - j.arrival()));
        }
        return timeline;
    }

    public static double averageWait(List<Scheduled> timeline) {
        return timeline.stream().mapToLong(Scheduled::waited).average().orElse(0);
    }

    /** Priority queue with linear aging (higher number = more urgent). */
    public static final class PriorityAgingQueue {
        private record Entry(String id, double staticKey, long seq) { }

        private final double agingPerMinute;
        private final PriorityQueue<Entry> heap = new PriorityQueue<>(
                Comparator.comparingDouble(Entry::staticKey).reversed().thenComparingLong(Entry::seq));
        private long seq;

        public PriorityAgingQueue(double agingPerMinute) {
            if (agingPerMinute < 0) throw new IllegalArgumentException("aging must be >= 0");
            this.agingPerMinute = agingPerMinute;
        }

        public void add(String id, int basePriority, long enqueuedAtMinute) {
            heap.add(new Entry(id, basePriority - agingPerMinute * enqueuedAtMinute, seq++));
        }

        /** Most urgent id at the current time (time is irrelevant to the order, see class doc), or null. */
        public String poll() {
            Entry e = heap.poll();
            return e == null ? null : e.id();
        }

        public int size() { return heap.size(); }
    }
}
