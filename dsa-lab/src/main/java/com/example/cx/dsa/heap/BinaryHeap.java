package com.example.cx.dsa.heap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Array-backed binary heap (min-heap with respect to the comparator; pass a reversed comparator for a max-heap).
 *
 * <p><b>Purpose / platform use case:</b> ticket prioritisation (see {@link TicketPriorityQueue}), picking the least-loaded
 * agent, merging sorted streams, top-K dashboards. The JDK equivalent is {@link java.util.PriorityQueue}.
 *
 * <p><b>Algorithm:</b> complete binary tree stored in an array: children of {@code i} are {@code 2i+1, 2i+2}. Insert adds
 * at the end and sifts up; extract-min swaps the last element to the root and sifts down. {@link #heapify} builds a heap
 * from an array bottom-up in O(n).
 *
 * <p><b>Complexity:</b> peek O(1); push/pop O(log n); build O(n); space O(n). Removing an arbitrary element is O(n) to
 * find then O(log n) to fix (use an index map for O(log n), or lazy deletion).
 *
 * <p><b>Edge cases:</b> pop/peek on empty, duplicates, equal priorities (heap is NOT stable; add a sequence tiebreaker if
 * FIFO among equals matters), single element, comparator consistency.
 *
 * <p><b>Brute force vs optimized:</b> scanning a list for the min is O(n) per pick; a sorted list makes insert O(n); the
 * heap balances both at O(log n).
 *
 * <p><b>Interview follow-ups:</b> why is heapify O(n) and not O(n log n)? Decrease-key / update priority? Heap vs balanced
 * BST? d-ary heaps for cache behaviour? Median of a stream with two heaps?
 */
public class BinaryHeap<T> {
    private final List<T> items = new ArrayList<>();
    private final Comparator<? super T> cmp;

    public BinaryHeap(Comparator<? super T> cmp) { this.cmp = cmp; }

    public static <T> BinaryHeap<T> heapify(List<T> source, Comparator<? super T> cmp) {
        BinaryHeap<T> h = new BinaryHeap<>(cmp);
        h.items.addAll(source);
        for (int i = h.items.size() / 2 - 1; i >= 0; i--) h.siftDown(i);
        return h;
    }

    public int size() { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }

    public T peek() {
        if (items.isEmpty()) throw new NoSuchElementException("heap is empty");
        return items.get(0);
    }

    public void push(T t) {
        items.add(t);
        siftUp(items.size() - 1);
    }

    public T pop() {
        if (items.isEmpty()) throw new NoSuchElementException("heap is empty");
        T top = items.get(0);
        T last = items.remove(items.size() - 1);
        if (!items.isEmpty()) {
            items.set(0, last);
            siftDown(0);
        }
        return top;
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (cmp.compare(items.get(i), items.get(parent)) >= 0) break;
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int i) {
        int n = items.size();
        while (true) {
            int l = 2 * i + 1, r = l + 1, smallest = i;
            if (l < n && cmp.compare(items.get(l), items.get(smallest)) < 0) smallest = l;
            if (r < n && cmp.compare(items.get(r), items.get(smallest)) < 0) smallest = r;
            if (smallest == i) return;
            swap(i, smallest);
            i = smallest;
        }
    }

    private void swap(int a, int b) {
        T t = items.get(a);
        items.set(a, items.get(b));
        items.set(b, t);
    }

    /** Invariant check used by tests: every parent is <= its children. */
    public boolean isValidHeap() {
        for (int i = 1; i < items.size(); i++) {
            if (cmp.compare(items.get((i - 1) / 2), items.get(i)) > 0) return false;
        }
        return true;
    }
}
