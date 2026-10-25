package com.example.cx.dsa.window;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlidingWindowTest {
    @Test
    void maxSumHandlesNegativesAndBounds() {
        assertEquals(9, SlidingWindow.maxSumOfWindow(new int[] {1, 2, 3, 4, 5}, 2));
        assertEquals(-3, SlidingWindow.maxSumOfWindow(new int[] {-5, -3, -4}, 1));
        assertEquals(15, SlidingWindow.maxSumOfWindow(new int[] {1, 2, 3, 4, 5}, 5));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.maxSumOfWindow(new int[] {1}, 2));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.maxSumOfWindow(new int[] {1}, 0));
    }

    @Test
    void longestUniqueSubstring() {
        assertEquals(3, SlidingWindow.longestUniqueSubstring("abcabcbb"));
        assertEquals(1, SlidingWindow.longestUniqueSubstring("bbbb"));
        assertEquals(3, SlidingWindow.longestUniqueSubstring("pwwkew"));
        assertEquals(0, SlidingWindow.longestUniqueSubstring(""));
        assertEquals(2, SlidingWindow.longestUniqueSubstring("abba"));
    }

    @Test
    void shortestSubarray() {
        assertEquals(2, SlidingWindow.shortestSubarrayWithSumAtLeast(new int[] {2, 3, 1, 2, 4, 3}, 7));
        assertEquals(0, SlidingWindow.shortestSubarrayWithSumAtLeast(new int[] {1, 1}, 10));
        assertEquals(1, SlidingWindow.shortestSubarrayWithSumAtLeast(new int[] {5}, 5));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.shortestSubarrayWithSumAtLeast(new int[] {-1}, 1));
    }

    @Test
    void windowMaximums() {
        assertArrayEquals(new int[] {3, 3, 5, 5, 6, 7}, SlidingWindow.windowMaximums(new int[] {1, 3, -1, -3, 5, 3, 6, 7}, 3));
        assertArrayEquals(new int[] {4, 4}, SlidingWindow.windowMaximums(new int[] {4, 4}, 1));
        assertArrayEquals(new int[] {9}, SlidingWindow.windowMaximums(new int[] {9}, 1));
    }
}
