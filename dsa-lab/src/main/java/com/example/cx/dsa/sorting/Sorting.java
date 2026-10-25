package com.example.cx.dsa.sorting;

import java.util.Arrays;

/**
 * Merge sort, quick sort and heap sort on {@code int[]} (in place where noted; inputs are not mutated unless stated).
 *
 * <p><b>Purpose / platform use case:</b> ordering tickets by due date or priority for in-memory views, and understanding what
 * {@code Arrays.sort}/{@code List.sort} do (dual-pivot quicksort for primitives, TimSort (merge-based, stable) for objects).
 * The platform delegates ordering to SQL {@code ORDER BY} and the JDK sort.
 *
 * <p><b>Algorithm:</b> <i>Merge sort</i>: split, sort halves, merge (stable, needs O(n) buffer). <i>Quick sort</i>: partition
 * around a pivot (median-of-three here, Lomuto scheme) then recurse on the smaller side first to bound stack depth by O(log n).
 * <i>Heap sort</i>: build a max-heap in O(n), repeatedly swap the max to the end and sift down.
 *
 * <p><b>Complexity:</b> merge O(n log n) always, O(n) space, stable. Quick O(n log n) average, O(n^2) worst (mitigated by pivot
 * choice), O(log n) space, not stable. Heap O(n log n) always, O(1) space, not stable, poor cache locality.
 *
 * <p><b>Edge cases:</b> empty and single element, already sorted / reverse sorted (quick sort pivot degeneracy), all equal,
 * duplicates, negative numbers, {@code Integer.MIN_VALUE} (use {@code Integer.compare}/mid computed without overflow).
 *
 * <p><b>Brute force vs optimized:</b> bubble/insertion/selection sort are O(n^2); insertion sort is still the best choice for
 * tiny or nearly sorted arrays (TimSort and the JDK use it for small runs).
 *
 * <p><b>Interview follow-ups:</b> which is stable and why does it matter (sort tickets by priority then keep creation order)?
 * External sorting for data larger than memory? Lower bound for comparison sorts (n log n)? Counting/radix sort when keys are small
 * integers (priority has only 4 values)? Quickselect for the median/K-th element?
 */
public final class Sorting {
    private Sorting() { }

    public static int[] mergeSort(int[] input) {
        int[] a = Arrays.copyOf(input, input.length);
        if (a.length < 2) return a;
        mergeSort(a, new int[a.length], 0, a.length);
        return a;
    }

    private static void mergeSort(int[] a, int[] tmp, int lo, int hi) {
        if (hi - lo < 2) return;
        int mid = lo + (hi - lo) / 2;
        mergeSort(a, tmp, lo, mid);
        mergeSort(a, tmp, mid, hi);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) tmp[k++] = a[i] <= a[j] ? a[i++] : a[j++];   // <= keeps it stable
        while (i < mid) tmp[k++] = a[i++];
        while (j < hi) tmp[k++] = a[j++];
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }

    public static int[] quickSort(int[] input) {
        int[] a = Arrays.copyOf(input, input.length);
        quickSort(a, 0, a.length - 1);
        return a;
    }

    private static void quickSort(int[] a, int lo, int hi) {
        while (lo < hi) {
            int p = partition(a, lo, hi);
            // recurse into the smaller part, loop on the larger one: stack depth O(log n)
            if (p - lo < hi - p) {
                quickSort(a, lo, p - 1);
                lo = p + 1;
            } else {
                quickSort(a, p + 1, hi);
                hi = p - 1;
            }
        }
    }

    private static int partition(int[] a, int lo, int hi) {
        int mid = lo + (hi - lo) / 2;
        // median-of-three moved to hi to defend against sorted input
        if (a[mid] < a[lo]) swap(a, mid, lo);
        if (a[hi] < a[lo]) swap(a, hi, lo);
        if (a[mid] < a[hi]) swap(a, mid, hi);
        int pivot = a[hi];
        int i = lo;
        for (int j = lo; j < hi; j++) {
            if (a[j] < pivot) swap(a, i++, j);
        }
        swap(a, i, hi);
        return i;
    }

    public static int[] heapSort(int[] input) {
        int[] a = Arrays.copyOf(input, input.length);
        int n = a.length;
        for (int i = n / 2 - 1; i >= 0; i--) siftDown(a, i, n);
        for (int end = n - 1; end > 0; end--) {
            swap(a, 0, end);
            siftDown(a, 0, end);
        }
        return a;
    }

    private static void siftDown(int[] a, int i, int n) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, largest = i;
            if (l < n && a[l] > a[largest]) largest = l;
            if (r < n && a[r] > a[largest]) largest = r;
            if (largest == i) return;
            swap(a, i, largest);
            i = largest;
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
