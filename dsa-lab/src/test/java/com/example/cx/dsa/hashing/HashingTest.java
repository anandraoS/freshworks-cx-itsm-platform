package com.example.cx.dsa.hashing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void putGetOverwriteRemove() {
        SimpleHashMap<String, Integer> m = new SimpleHashMap<>();
        assertNull(m.put("a", 1));
        assertEquals(1, m.put("a", 2));
        assertEquals(2, m.get("a"));
        assertEquals(1, m.size());
        assertEquals(2, m.remove("a"));
        assertNull(m.remove("a"));
        assertNull(m.get("a"));
        assertTrue(m.isEmpty());
    }

    @Test
    void nullKeyAndValueSupported() {
        SimpleHashMap<String, String> m = new SimpleHashMap<>();
        m.put(null, "x");
        m.put("k", null);
        assertEquals("x", m.get(null));
        assertTrue(m.containsKey("k"));
        assertNull(m.get("k"));
    }

    @Test
    void resizesAndKeepsAllEntriesMatchingJdkMap() {
        SimpleHashMap<Integer, Integer> m = new SimpleHashMap<>(2, 0.75);
        Map<Integer, Integer> ref = new HashMap<>();
        Random r = new Random(42);
        for (int i = 0; i < 5000; i++) {
            int k = r.nextInt(2000) - 1000;                  // includes negatives and overwrites
            if (r.nextInt(4) == 0) assertEquals(ref.remove(k), m.remove(k));
            else assertEquals(ref.put(k, i), m.put(k, i));
        }
        assertEquals(ref.size(), m.size());
        for (var e : ref.entrySet()) assertEquals(e.getValue(), m.get(e.getKey()));
        assertTrue(m.resizeCount() > 3);
        assertTrue(m.capacity() * 0.75 >= m.size());
    }

    @Test
    void collidingKeysShareChainButStayCorrect() {
        record Bad(int id) { @Override public int hashCode() { return 7; } }
        SimpleHashMap<Bad, Integer> m = new SimpleHashMap<>();
        for (int i = 0; i < 50; i++) m.put(new Bad(i), i);
        for (int i = 0; i < 50; i++) assertEquals(i, m.get(new Bad(i)));
        assertEquals(50, m.longestChain());
    }

    @Test
    void duplicateDetectionAgrees() {
        assertFalse(DuplicateDetection.hasDuplicateBruteForce(List.of()));
        assertFalse(DuplicateDetection.hasDuplicateBruteForce(List.of(1)));
        assertTrue(DuplicateDetection.hasDuplicateBruteForce(List.of(1, 2, 1)));
        assertTrue(DuplicateDetection.hasDuplicateHashing(List.of("a", "b", "a")));
        assertFalse(DuplicateDetection.hasDuplicateHashing(List.of("a", "b")));
        assertTrue(DuplicateDetection.hasDuplicateSorting(new int[] {3, 1, 3}));
        assertFalse(DuplicateDetection.hasDuplicateSorting(new int[] {}));
        assertEquals("b", DuplicateDetection.firstDuplicate(List.of("a", "b", "c", "b", "a")));
        assertNull(DuplicateDetection.firstDuplicate(List.of("a")));
        assertEquals(List.of("b", "a"), DuplicateDetection.allDuplicates(List.of("a", "b", "b", "a", "a")));
        assertEquals(3, DuplicateDetection.findDuplicateInRange(new int[] {1, 3, 4, 2, 3}));
        assertEquals(DuplicateDetection.normalizeTitle("  VPN   Down "), DuplicateDetection.normalizeTitle("vpn down"));
    }
}
