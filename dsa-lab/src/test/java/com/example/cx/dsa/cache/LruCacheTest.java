package com.example.cx.dsa.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LruCacheTest {
    @Test
    void evictsLeastRecentlyUsed() {
        LruCache<String, Integer> c = new LruCache<>(2);
        assertNull(c.put("a", 1));
        assertNull(c.put("b", 2));
        assertEquals(1, c.get("a"));              // a is now most recent
        assertEquals("b", c.put("c", 3));         // b evicted
        assertNull(c.get("b"));
        assertEquals(List.of("c", "a"), c.keysByRecency());
        assertEquals(1, c.misses());
        assertEquals(1, c.hits());
    }

    @Test
    void updateRefreshesRecencyWithoutGrowing() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        assertNull(c.put("a", 10));
        assertEquals("b", c.put("c", 3));
        assertEquals(10, c.get("a"));
        assertEquals(2, c.size());
    }

    @Test
    void capacityOneRemoveAndValidation() {
        LruCache<Integer, String> c = new LruCache<>(1);
        c.put(1, "x");
        assertEquals(1, c.put(2, "y"));
        assertTrue(c.remove(2));
        assertFalse(c.remove(2));
        assertEquals(0, c.size());
        assertThrows(IllegalArgumentException.class, () -> new LruCache<>(0));
        assertThrows(NullPointerException.class, () -> c.put(1, null));
    }

    @Test
    void matchesLinkedHashMapAccessOrder() {
        int cap = 8;
        LruCache<Integer, Integer> c = new LruCache<>(cap);
        Map<Integer, Integer> ref = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { return size() > cap; }
        };
        Random r = new Random(5);
        for (int i = 0; i < 5000; i++) {
            int k = r.nextInt(20);
            if (r.nextBoolean()) { c.put(k, i); ref.put(k, i); }
            else assertEquals(ref.get(k), c.get(k));
        }
        assertEquals(ref.size(), c.size());
        assertEquals(List.copyOf(ref.keySet()).reversed(), c.keysByRecency());
    }
}
