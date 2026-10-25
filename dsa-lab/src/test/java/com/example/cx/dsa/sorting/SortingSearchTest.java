package com.example.cx.dsa.sorting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SortingSearchTest {
    private static int[][] samples() {
        Random r = new Random(3);
        int[] big = r.ints(2000, -500, 500).toArray();
        int[] sorted = Arrays.stream(big).sorted().toArray();
        int[] reversed = Arrays.stream(sorted).boxed().sorted((a, b) -> b - a).mapToInt(i -> i).toArray();
        int[] equal = new int[50];
        Arrays.fill(equal, 4);
        return new int[][] {{}, {1}, {2, 1}, {3, 3, 1, 2, 3}, {Integer.MIN_VALUE, Integer.MAX_VALUE, 0}, big, sorted, reversed, equal};
    }

    @Test
    void allSortsMatchArraysSort() {
        for (int[] s : samples()) {
            int[] expected = s.clone();
            Arrays.sort(expected);
            assertArrayEquals(expected, Sorting.mergeSort(s));
            assertArrayEquals(expected, Sorting.quickSort(s));
            assertArrayEquals(expected, Sorting.heapSort(s));
        }
    }

    @Test
    void sortsDoNotMutateInput() {
        int[] in = {3, 1, 2};
        Sorting.mergeSort(in);
        Sorting.quickSort(in);
        Sorting.heapSort(in);
        assertArrayEquals(new int[] {3, 1, 2}, in);
    }

    @Test
    void lowerUpperBoundWithDuplicates() {
        int[] a = {1, 2, 2, 2, 5, 8};
        assertEquals(1, BinarySearch.lowerBound(a, 2));
        assertEquals(4, BinarySearch.upperBound(a, 2));
        assertEquals(0, BinarySearch.lowerBound(a, -10));
        assertEquals(6, BinarySearch.lowerBound(a, 100));
        assertEquals(6, BinarySearch.upperBound(a, 8));
        assertEquals(0, BinarySearch.lowerBound(new int[] {}, 1));
        assertEquals(3, BinarySearch.countInRange(a, 2, 2));
        assertEquals(5, BinarySearch.countInRange(a, 2, 8));
        assertEquals(0, BinarySearch.countInRange(a, 6, 7));
        assertEquals(0, BinarySearch.countInRange(a, 9, 3));
        assertEquals(-1, BinarySearch.indexOf(a, 3));
        assertEquals(4, BinarySearch.indexOf(a, 5));
    }

    @Test
    void rotatedSearch() {
        int[] a = {6, 7, 8, 1, 2, 3, 4, 5};
        for (int i = 0; i < a.length; i++) assertEquals(i, BinarySearch.searchRotated(a, a[i]));
        assertEquals(-1, BinarySearch.searchRotated(a, 99));
        assertEquals(-1, BinarySearch.searchRotated(new int[] {}, 1));
    }

    @Test
    void searchOnAnswer() {
        assertEquals(7, BinarySearch.searchOnAnswer(0, 100, v -> v * v >= 49));
        assertEquals(101, BinarySearch.searchOnAnswer(0, 100, v -> false));      // none -> hi+1
        // 3 tickets of 60/60/60 minutes with a 120 minute deadline per agent need 2 agents
        assertEquals(2, BinarySearch.minAgents(new int[] {60, 60, 60}, 120));
        assertEquals(1, BinarySearch.minAgents(new int[] {60, 60, 60}, 180));
        assertEquals(-1, BinarySearch.minAgents(new int[] {500}, 120));
        assertEquals(0, BinarySearch.minAgents(new int[] {}, 120));
    }
}
