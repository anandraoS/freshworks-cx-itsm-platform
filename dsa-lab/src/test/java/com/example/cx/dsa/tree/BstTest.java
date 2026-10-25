package com.example.cx.dsa.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.TreeSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BstTest {
    private Bst<Integer> sample() {
        Bst<Integer> t = new Bst<>();
        for (int v : new int[] {8, 3, 10, 1, 6, 14, 4, 7, 13}) t.insert(v);
        return t;
    }

    @Test
    void traversals() {
        Bst<Integer> t = sample();
        assertEquals(List.of(1, 3, 4, 6, 7, 8, 10, 13, 14), t.inOrder());
        assertEquals(List.of(8, 3, 1, 6, 4, 7, 10, 14, 13), t.preOrder());
        assertEquals(List.of(1, 4, 7, 6, 3, 13, 14, 10, 8), t.postOrder());
        assertEquals(List.of(8, 3, 10, 1, 6, 14, 4, 7, 13), t.levelOrder());
        assertEquals(4, t.height());
    }

    @Test
    void emptyTreeAndDuplicates() {
        Bst<Integer> t = new Bst<>();
        assertTrue(t.isEmpty());
        assertEquals(List.of(), t.inOrder());
        assertEquals(List.of(), t.levelOrder());
        assertNull(t.min());
        assertTrue(t.insert(1));
        assertFalse(t.insert(1));
        assertEquals(1, t.size());
        assertFalse(t.delete(99));
    }

    @Test
    void deleteLeafOneChildTwoChildrenRoot() {
        Bst<Integer> t = sample();
        assertTrue(t.delete(1));        // leaf
        assertTrue(t.delete(14));       // one child
        assertTrue(t.delete(3));        // two children
        assertTrue(t.delete(8));        // root
        assertEquals(List.of(4, 6, 7, 10, 13), t.inOrder());
        assertEquals(5, t.size());
        assertFalse(t.contains(8));
        assertEquals(4, t.min());
        assertEquals(13, t.max());
    }

    @Test
    void rangeQueryAndRandomizedAgainstTreeSet() {
        Bst<Integer> t = sample();
        assertEquals(List.of(4, 6, 7, 8, 10), t.range(4, 10));
        assertEquals(List.of(), t.range(100, 200));

        Random r = new Random(9);
        Bst<Integer> b = new Bst<>();
        TreeSet<Integer> ref = new TreeSet<>();
        for (int i = 0; i < 3000; i++) {
            int v = r.nextInt(300);
            if (r.nextBoolean()) assertEquals(ref.add(v), b.insert(v));
            else assertEquals(ref.remove(v), b.delete(v));
            assertEquals(ref.size(), b.size());
        }
        assertEquals(List.copyOf(ref), b.inOrder());
    }

    @Test
    void sortedInsertionsDegenerateButIterativeInOrderIsSafe() {
        Bst<Integer> t = new Bst<>();
        for (int i = 0; i < 3000; i++) t.insert(i);
        assertEquals(3000, t.height());
        assertEquals(3000, t.inOrder().size());
    }
}
