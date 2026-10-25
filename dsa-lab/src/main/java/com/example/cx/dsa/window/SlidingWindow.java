package com.example.cx.dsa.window;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding window techniques: fixed-size maximum sum, longest span meeting a constraint, and a monotonic deque for window maxima.
 *
 * <p><b>Purpose / platform use case:</b> dashboard trend metrics: "busiest 7-day stretch of tickets created" (fixed window sum),
 * "longest run of days without a breach" (variable window), "peak load in every 5-minute window" (window maximum). The
 * analytics endpoint computes aggregates in SQL; this is the in-memory O(n) approach for streams.
 *
 * <p><b>Algorithm:</b> two pointers {@code left}/{@code right}. Fixed window: add the entering element, subtract the leaving
 * one. Variable window: expand right; while the constraint is violated shrink left. Window maximum: keep a deque of indices
 * whose values are decreasing; the front is the current maximum, and stale indices are dropped from the front.
 *
 * <p><b>Complexity:</b> each pointer moves at most n times: O(n) time. Space O(1) for sums, O(k) for the deque,
 * O(distinct) for the character map.
 *
 * <p><b>Edge cases:</b> k larger than n, k = 0 or negative, empty input, all negative numbers (do not initialise the best sum to
 * zero), repeated characters at the window edge, negative numbers break the "shrink while sum &gt; target" approach.
 *
 * <p><b>Brute force vs optimized:</b> recomputing each window is O(n*k); the sliding update is O(n).
 *
 * <p><b>Interview follow-ups:</b> shortest subarray with sum &gt;= target when negatives exist (prefix sums + monotonic deque)?
 * Minimum window substring? Count of subarrays with at most K distinct values? Time-based windows (evict by timestamp) are
 * the same idea as the sliding-window rate limiter.
 */
public final class SlidingWindow {
    private SlidingWindow() { }

    /** Maximum sum over all windows of exactly k consecutive elements. */
    public static long maxSumOfWindow(int[] a, int k) {
        if (k <= 0 || k > a.length) throw new IllegalArgumentException("k must be in [1, n]");
        long sum = 0;
        for (int i = 0; i < k; i++) sum += a[i];
        long best = sum;
        for (int i = k; i < a.length; i++) {
            sum += a[i] - a[i - k];
            best = Math.max(best, sum);
        }
        return best;
    }

    /** Length of the longest substring without repeating characters. */
    public static int longestUniqueSubstring(String s) {
        Map<Character, Integer> lastSeen = new HashMap<>();
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            Integer prev = lastSeen.put(c, right);
            if (prev != null && prev >= left) left = prev + 1;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }

    /** Length of the shortest contiguous subarray (non-negative values) with sum >= target, or 0 when none exists. */
    public static int shortestSubarrayWithSumAtLeast(int[] a, long target) {
        int best = Integer.MAX_VALUE, left = 0;
        long sum = 0;
        for (int right = 0; right < a.length; right++) {
            if (a[right] < 0) throw new IllegalArgumentException("non-negative values required");
            sum += a[right];
            while (sum >= target && left <= right) {
                best = Math.min(best, right - left + 1);
                sum -= a[left++];
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** Maximum of every window of size k (monotonic deque, O(n)). */
    public static int[] windowMaximums(int[] a, int k) {
        if (k <= 0 || k > a.length) throw new IllegalArgumentException("k must be in [1, n]");
        int[] out = new int[a.length - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();           // indices, values decreasing
        for (int i = 0; i < a.length; i++) {
            while (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            while (!dq.isEmpty() && a[dq.peekLast()] <= a[i]) dq.pollLast();
            dq.addLast(i);
            if (i >= k - 1) out[i - k + 1] = a[dq.peekFirst()];
        }
        return out;
    }
}
