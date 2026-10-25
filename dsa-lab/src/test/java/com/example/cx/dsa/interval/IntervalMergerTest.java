package com.example.cx.dsa.interval;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cx.dsa.interval.IntervalMerger.Interval;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalMergerTest {
    private static Interval iv(long s, long e) { return new Interval(s, e); }

    @Test
    void mergesOverlappingTouchingNestedAndUnsorted() {
        assertEquals(List.of(iv(1, 6), iv(8, 10)), IntervalMerger.merge(List.of(iv(8, 10), iv(1, 3), iv(2, 6))));
        assertEquals(List.of(iv(1, 5)), IntervalMerger.merge(List.of(iv(1, 3), iv(3, 5))));          // touching
        assertEquals(List.of(iv(1, 10)), IntervalMerger.merge(List.of(iv(1, 10), iv(2, 3), iv(4, 5))));  // nested
        assertEquals(List.of(), IntervalMerger.merge(List.of()));
        assertEquals(List.of(iv(1, 1)), IntervalMerger.merge(List.of(iv(1, 1), iv(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> iv(5, 1));
    }

    @Test
    void insertIntoMerged() {
        List<Interval> base = List.of(iv(1, 3), iv(6, 9));
        assertEquals(List.of(iv(1, 5), iv(6, 9)), IntervalMerger.insert(base, iv(2, 5)));
        assertEquals(List.of(iv(1, 9)), IntervalMerger.insert(base, iv(3, 6)));
        assertEquals(List.of(iv(0, 0), iv(1, 3), iv(6, 9)), IntervalMerger.insert(base, iv(0, 0)));
        assertEquals(List.of(iv(1, 3), iv(6, 9), iv(20, 21)), IntervalMerger.insert(base, iv(20, 21)));
    }

    @Test
    void slaWorkingMinutesDoNotDoubleCountOverlappingPauses() {
        Interval ticket = iv(0, 600);
        // two overlapping pauses 100-200 and 150-300, one outside the span, one partly outside
        List<Interval> pauses = List.of(iv(100, 200), iv(150, 300), iv(700, 800), iv(550, 650));
        assertEquals(600 - 200 - 50, IntervalMerger.workingMinutes(ticket, pauses));
        assertEquals(600, IntervalMerger.workingMinutes(ticket, List.of()));
        assertEquals(0, IntervalMerger.workingMinutes(ticket, List.of(iv(0, 600))));
    }

    @Test
    void freeSlots() {
        List<Interval> free = IntervalMerger.freeSlots(iv(9, 17), List.of(iv(10, 11), iv(10, 12), iv(15, 20), iv(0, 8)));
        assertEquals(List.of(iv(9, 10), iv(12, 15)), free);
        assertEquals(List.of(iv(9, 17)), IntervalMerger.freeSlots(iv(9, 17), List.of()));
        assertEquals(List.of(), IntervalMerger.freeSlots(iv(9, 17), List.of(iv(8, 18))));
    }
}
