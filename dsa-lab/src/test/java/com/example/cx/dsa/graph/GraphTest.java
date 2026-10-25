package com.example.cx.dsa.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphTest {
    private Graph<String> workflow() {
        Graph<String> g = new Graph<>();
        g.addEdge("NEW", "OPEN").addEdge("OPEN", "PENDING").addEdge("OPEN", "RESOLVED").addEdge("PENDING", "OPEN")
                .addEdge("RESOLVED", "CLOSED").addEdge("CLOSED", "REOPENED").addEdge("REOPENED", "OPEN");
        g.addNode("ORPHAN");
        return g;
    }

    @Test
    void bfsDfsOrderAndUnknownStart() {
        Graph<String> g = workflow();
        assertEquals(List.of("NEW", "OPEN", "PENDING", "RESOLVED", "CLOSED", "REOPENED"), g.bfs("NEW"));
        assertEquals(List.of("NEW", "OPEN", "PENDING", "RESOLVED", "CLOSED", "REOPENED"), g.dfs("NEW"));
        assertEquals(List.of(), g.bfs("nope"));
        assertEquals(List.of(), g.dfs("nope"));
        assertEquals(Set.of("ORPHAN"), g.unreachableFrom("NEW"));
    }

    @Test
    void dfsGoesDeepBeforeWide() {
        Graph<Integer> g = new Graph<>();
        g.addEdge(1, 2).addEdge(1, 3).addEdge(2, 4).addEdge(3, 4);
        assertEquals(List.of(1, 2, 4, 3), g.dfs(1));
        assertEquals(List.of(1, 2, 3, 4), g.bfs(1));
    }

    @Test
    void shortestPath() {
        Graph<String> g = workflow();
        assertEquals(List.of("RESOLVED", "CLOSED", "REOPENED", "OPEN"), g.shortestPath("RESOLVED", "OPEN"));
        assertEquals(List.of("NEW"), g.shortestPath("NEW", "NEW"));
        assertEquals(List.of(), g.shortestPath("NEW", "ORPHAN"));
        assertEquals(List.of(), g.shortestPath("x", "NEW"));
    }

    @Test
    void topologicalOrderRespectsDependencies() {
        Graph<String> g = new Graph<>();
        g.addEdge("validate", "assign").addEdge("assign", "notify").addEdge("validate", "tag").addEdge("tag", "notify");
        Optional<List<String>> order = TopologicalSort.sort(g);
        assertTrue(order.isPresent());
        List<String> o = order.get();
        assertEquals(4, o.size());
        for (String n : g.nodes()) for (String m : g.neighbours(n)) assertTrue(o.indexOf(n) < o.indexOf(m), n + "->" + m);
        assertFalse(TopologicalSort.hasCycle(g));
        assertEquals(List.of(), TopologicalSort.findCycle(g));
    }

    @Test
    void cycleDetectionAndReportedCycle() {
        Graph<String> g = new Graph<>();
        g.addEdge("a", "b").addEdge("b", "c").addEdge("c", "b").addEdge("c", "d");
        assertTrue(TopologicalSort.sort(g).isEmpty());
        assertTrue(TopologicalSort.hasCycle(g));
        assertEquals(List.of("b", "c", "b"), TopologicalSort.findCycle(g));
    }

    @Test
    void selfLoopEmptyAndSingle() {
        Graph<String> self = new Graph<>();
        self.addEdge("x", "x");
        assertTrue(TopologicalSort.hasCycle(self));
        assertEquals(List.of("x", "x"), TopologicalSort.findCycle(self));
        assertEquals(Optional.of(List.of()), TopologicalSort.sort(new Graph<String>()));
        assertEquals(Optional.of(List.of("solo")), TopologicalSort.sort(new Graph<String>().addNode("solo")));
    }
}
