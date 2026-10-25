package com.example.cx.dsa.interval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Interval merging, insertion, intersection and "working time" arithmetic.
 *
 * <p><b>Purpose / platform use case:</b> SLA clocks. Elapsed SLA time = ticket lifetime minus paused periods (status PENDING /
 * ON_HOLD) intersected with business hours. Overlapping or adjacent pause windows must be merged first, otherwise time is
 * subtracted twice. The same merge produces the "busy" calendar of an agent. (The platform's SLA calculator works on business
 * hours per tenant; this is the generic interval algebra underneath.)
 *
 * <p><b>Algorithm:</b> sort by start; sweep keeping the current merged interval; if the next start is &lt;= current end
 * (touching counts as overlapping) extend the end, otherwise emit and start a new one. {@link #workingMinutes} merges the
 * pauses, then subtracts the overlap of each pause with the ticket span.
 *
 * <p><b>Complexity:</b> O(n log n) for the sort, O(n) sweep, O(n) space. Inserting into an already sorted merged list is O(n).
 *
 * <p><b>Edge cases:</b> empty list, a single interval, fully nested intervals, touching intervals (end == start), identical
 * intervals, unsorted input, zero-length or invalid (start &gt; end) intervals, huge end values (use long), pauses outside the
 * ticket span.
 *
 * <p><b>Brute force vs optimized:</b> comparing every pair repeatedly until no merge happens is O(n^2) or worse; sort + sweep is
 * O(n log n).
 *
 * <p><b>Interview follow-ups:</b> minimum number of meeting rooms (sweep line / heap of end times)? Find free slots between
 * busy intervals? Intersection of two sorted interval lists (two pointers)? Business-hours calendars with time zones and DST?
 */
public final class IntervalMerger {
    private IntervalMerger() { }

    /** Half-open or closed does not matter for merging here; start must be &lt;= end. */
    public record Interval(long start, long end) {
        public Interval {
            if (start > end) throw new IllegalArgumentException("start > end: " + start + ">" + end);
        }

        public long length() { return end - start; }
    }

    public static List<Interval> merge(List<Interval> input) {
        List<Interval> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
        List<Interval> out = new ArrayList<>();
        for (Interval iv : sorted) {
            if (out.isEmpty() || iv.start() > out.get(out.size() - 1).end()) {
                out.add(iv);
            } else {
                Interval last = out.get(out.size() - 1);
                if (iv.end() > last.end()) out.set(out.size() - 1, new Interval(last.start(), iv.end()));
            }
        }
        return out;
    }

    /** Insert into an already merged, sorted list and re-merge in O(n). */
    public static List<Interval> insert(List<Interval> merged, Interval added) {
        List<Interval> out = new ArrayList<>();
        long s = added.start(), e = added.end();
        int i = 0;
        while (i < merged.size() && merged.get(i).end() < s) out.add(merged.get(i++));
        while (i < merged.size() && merged.get(i).start() <= e) {
            s = Math.min(s, merged.get(i).start());
            e = Math.max(e, merged.get(i).end());
            i++;
        }
        out.add(new Interval(s, e));
        while (i < merged.size()) out.add(merged.get(i++));
        return out;
    }

    /** Overlap of two intervals or null when disjoint. */
    public static Interval intersect(Interval a, Interval b) {
        long s = Math.max(a.start(), b.start());
        long e = Math.min(a.end(), b.end());
        return s <= e ? new Interval(s, e) : null;
    }

    /** Time in {@code span} not covered by any pause window (pauses may overlap each other). */
    public static long workingMinutes(Interval span, List<Interval> pauses) {
        long paused = 0;
        for (Interval p : merge(pauses)) {
            Interval x = intersect(span, p);
            if (x != null) paused += x.length();
        }
        return span.length() - paused;
    }

    /** Gaps between merged busy intervals inside the window. */
    public static List<Interval> freeSlots(Interval window, List<Interval> busy) {
        List<Interval> free = new ArrayList<>();
        long cursor = window.start();
        for (Interval b : merge(busy)) {
            Interval x = intersect(window, b);
            if (x == null) continue;
            if (x.start() > cursor) free.add(new Interval(cursor, x.start()));
            cursor = Math.max(cursor, x.end());
        }
        if (cursor < window.end()) free.add(new Interval(cursor, window.end()));
        return free;
    }
}
