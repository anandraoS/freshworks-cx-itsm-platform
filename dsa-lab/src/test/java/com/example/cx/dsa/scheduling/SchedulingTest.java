package com.example.cx.dsa.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cx.dsa.scheduling.Schedulers.Job;
import com.example.cx.dsa.scheduling.Schedulers.Scheduled;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchedulingTest {
    @Test
    void roundRobinWraps() {
        Assigners.RoundRobin<String> rr = new Assigners.RoundRobin<>(List.of("a", "b", "c"));
        List<String> got = new ArrayList<>();
        for (int i = 0; i < 7; i++) got.add(rr.next());
        assertEquals(List.of("a", "b", "c", "a", "b", "c", "a"), got);
        assertThrows(IllegalArgumentException.class, () -> new Assigners.RoundRobin<>(List.of()));
    }

    @Test
    void leastLoadedBalancesAndBreaksTiesByName() {
        Assigners.LeastLoaded ll = new Assigners.LeastLoaded(List.of("zed", "amy", "bob"));
        assertEquals("amy", ll.assign(5));
        assertEquals("bob", ll.assign(1));
        assertEquals("zed", ll.assign(1));
        assertEquals("bob", ll.assign(1));           // bob 1, zed 1 tie -> bob
        assertEquals(2, ll.loadOf("bob"));
        ll.release("amy", 5);
        assertEquals("amy", ll.assign(1));
        assertThrows(java.util.NoSuchElementException.class, () -> ll.release("ghost", 1));
        assertThrows(IllegalArgumentException.class, () -> new Assigners.LeastLoaded(List.of()));
    }

    @Test
    void sjfBeatsFcfsOnAverageWaitAndHandlesIdleGap() {
        List<Job> jobs = List.of(new Job("long", 0, 8), new Job("s1", 1, 2), new Job("s2", 2, 1));
        List<Scheduled> t = Schedulers.shortestJobFirst(jobs);
        // non-preemptive: long starts at 0 (only job at time 0), then s2 (burst 1) before s1 (burst 2)
        assertEquals(List.of("long", "s2", "s1"), t.stream().map(Scheduled::id).toList());
        assertEquals((0 + 6 + 8) / 3.0, Schedulers.averageWait(t), 1e-9);

        List<Scheduled> gap = Schedulers.shortestJobFirst(List.of(new Job("a", 0, 2), new Job("b", 10, 1)));
        assertEquals(10, gap.get(1).start());
        assertEquals(0, gap.get(1).waited());
        assertEquals(List.of(), Schedulers.shortestJobFirst(List.of()));
        assertEquals(0.0, Schedulers.averageWait(List.of()));
    }

    @Test
    void agingLetsOldLowPriorityOvertakeNewHighPriority() {
        Schedulers.PriorityAgingQueue noAging = new Schedulers.PriorityAgingQueue(0);
        noAging.add("old-low", 1, 0);
        noAging.add("new-high", 3, 100);
        assertEquals("new-high", noAging.poll());      // starvation without aging

        Schedulers.PriorityAgingQueue aging = new Schedulers.PriorityAgingQueue(0.05);
        aging.add("old-low", 1, 0);                    // after 100 min: 1 + 5 = 6
        aging.add("new-high", 3, 100);                 // 3
        assertEquals("old-low", aging.poll());
        assertEquals("new-high", aging.poll());
        assertNull(aging.poll());
        assertThrows(IllegalArgumentException.class, () -> new Schedulers.PriorityAgingQueue(-1));
    }
}
