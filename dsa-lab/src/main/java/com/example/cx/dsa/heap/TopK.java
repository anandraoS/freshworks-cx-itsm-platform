package com.example.cx.dsa.heap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Top-K selection with a bounded heap.
 *
 * <p><b>Purpose / platform use case:</b> "top 5 busiest agents", "top 10 most frequent ticket tags", "K slowest tickets" on the
 * analytics dashboard. (The platform computes these with SQL aggregates; this is the streaming/in-memory version.)
 *
 * <p><b>Algorithm:</b> keep a min-heap of size K. For each item, push it; if size exceeds K pop the smallest. At the end the
 * heap holds the K largest. For frequency top-K, first count with a hash map, then run the same selection over the entries.
 *
 * <p><b>Complexity:</b> O(n log K) time, O(K) space (vs O(n log n) time / O(n) space for sorting everything). Quickselect gives
 * O(n) average. Counting then selecting: O(n + m log K) for m distinct values.
 *
 * <p><b>Edge cases:</b> K = 0 (empty), K larger than n (return all), ties at the K-th boundary, empty input, negative K rejected.
 *
 * <p><b>Brute force vs optimized:</b> full sort is simple but does unneeded work when K is much smaller than n.
 *
 * <p><b>Interview follow-ups:</b> why a MIN heap for the K largest? Streaming input that never fits in memory? K-th largest
 * only (heap top)? Merge K sorted lists with a heap? Bucket sort when frequencies are bounded (O(n))?
 */
public final class TopK {
    private TopK() { }

    /** K largest values, returned in descending order. */
    public static List<Integer> largest(int[] values, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        if (k == 0) return List.of();
        PriorityQueue<Integer> minHeap = new PriorityQueue<>(k + 1);
        for (int v : values) {
            minHeap.offer(v);
            if (minHeap.size() > k) minHeap.poll();
        }
        List<Integer> out = new ArrayList<>(minHeap);
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /** The K most frequent items (ties broken by natural item order so results are deterministic). */
    public static <T extends Comparable<T>> List<T> mostFrequent(List<T> items, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        Map<T, Integer> counts = new HashMap<>();
        for (T t : items) counts.merge(t, 1, Integer::sum);
        // heap ordered so the "worst" candidate is at the top and gets evicted first
        Comparator<Map.Entry<T, Integer>> better = Map.Entry.<T, Integer>comparingByValue()
                .thenComparing(Map.Entry.<T, Integer>comparingByKey(Comparator.<T>naturalOrder().reversed()));
        PriorityQueue<Map.Entry<T, Integer>> heap = new PriorityQueue<>(better);
        for (Map.Entry<T, Integer> e : counts.entrySet()) {
            heap.offer(e);
            if (heap.size() > k) heap.poll();
        }
        List<T> out = new ArrayList<>();
        while (!heap.isEmpty()) out.add(heap.poll().getKey());
        Collections.reverse(out);
        return out;
    }
}
