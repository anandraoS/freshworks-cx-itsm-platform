package com.example.cx.dsa.sorting;

import java.util.function.LongPredicate;

/**
 * Binary search variants: exact match, lower bound, upper bound, rotated array, and search-on-answer.
 *
 * <p><b>Purpose / platform use case:</b> find all tickets created within a time window in a createdAt-sorted list (lower bound of
 * start, upper bound of end), and capacity planning questions such as "what is the smallest number of agents that clears the
 * backlog within the SLA?" (search on answer). Database B-tree indexes do the same job for {@code WHERE created_at BETWEEN}.
 *
 * <p><b>Algorithm:</b> maintain a half-open range {@code [lo, hi)} and halve it. {@code lowerBound}: first index with
 * {@code a[i] >= x}. {@code upperBound}: first index with {@code a[i] > x}. {@code searchOnAnswer}: for a monotone predicate
 * (false...false true...true) find the smallest value in {@code [lo,hi]} that satisfies it.
 *
 * <p><b>Complexity:</b> O(log n) time, O(1) space (O(log(hi-lo)) predicate evaluations for search-on-answer).
 *
 * <p><b>Edge cases:</b> empty array, target smaller/larger than all, duplicates (count = upper - lower), overflow in
 * {@code (lo+hi)/2} (use {@code lo + (hi-lo)/2}), infinite loops from wrong bound updates, predicate that is not monotone.
 *
 * <p><b>Brute force vs optimized:</b> linear scan is O(n); the sorted order lets us discard half of the range each step.
 *
 * <p><b>Interview follow-ups:</b> why half-open ranges prevent off-by-one bugs? Search in a rotated sorted array? Peak element?
 * Binary search on floating-point answers (iterate fixed times)? Exponential search for unbounded ranges?
 */
public final class BinarySearch {
    private BinarySearch() { }

    /** Index of any occurrence of x, or -1. */
    public static int indexOf(int[] a, int x) {
        int i = lowerBound(a, x);
        return i < a.length && a[i] == x ? i : -1;
    }

    /** First index i with a[i] >= x (a.length if none). */
    public static int lowerBound(int[] a, int x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] < x) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** First index i with a[i] > x (a.length if none). */
    public static int upperBound(int[] a, int x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] <= x) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Number of elements in [from, to] inclusive of a sorted array. */
    public static int countInRange(int[] a, int from, int to) {
        if (from > to) return 0;
        return upperBound(a, to) - lowerBound(a, from);
    }

    /** Search in a rotated sorted array without duplicates; returns index or -1. */
    public static int searchRotated(int[] a, int x) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] == x) return mid;
            if (a[lo] <= a[mid]) {                       // left half sorted
                if (a[lo] <= x && x < a[mid]) hi = mid - 1; else lo = mid + 1;
            } else {                                     // right half sorted
                if (a[mid] < x && x <= a[hi]) lo = mid + 1; else hi = mid - 1;
            }
        }
        return -1;
    }

    /** Smallest v in [lo, hi] with feasible(v) true, or hi+1 if none. feasible must be monotone (false then true). */
    public static long searchOnAnswer(long lo, long hi, LongPredicate feasible) {
        long l = lo, h = hi + 1;
        while (l < h) {
            long mid = l + (h - l) / 2;
            if (feasible.test(mid)) h = mid; else l = mid + 1;
        }
        return l;
    }

    /**
     * Example of search on answer: minimum number of agents so that the backlog (each ticket needs workMinutes[i], tickets are
     * assigned greedily in order to agents with contiguous blocks) is cleared within {@code deadlineMinutes}.
     * Monotone: more agents never makes it worse.
     */
    public static int minAgents(int[] workMinutes, long deadlineMinutes) {
        long max = 0;
        for (int w : workMinutes) {
            if (w > deadlineMinutes) return -1;          // a single ticket can never fit
            max = Math.max(max, w);
        }
        if (workMinutes.length == 0) return 0;
        return (int) searchOnAnswer(1, workMinutes.length, agents -> canFinish(workMinutes, (int) agents, deadlineMinutes));
    }

    private static boolean canFinish(int[] work, int agents, long deadline) {
        int used = 1;
        long load = 0;
        for (int w : work) {
            if (load + w > deadline) {
                used++;
                load = 0;
            }
            load += w;
        }
        return used <= agents;
    }
}
