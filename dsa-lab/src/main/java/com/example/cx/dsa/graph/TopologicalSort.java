package com.example.cx.dsa.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Topological ordering (Kahn's algorithm) with cycle detection.
 *
 * <p><b>Purpose / platform use case:</b> workflow rule/task dependencies (rule B must run after rule A; ticket blocked-by links;
 * migration ordering). When an admin saves a workflow whose dependencies contain a cycle the platform must reject it with a
 * clear error: {@link #findCycle} supplies the offending nodes for the validation message.
 *
 * <p><b>Algorithm:</b> compute in-degrees, queue all zero in-degree nodes, repeatedly remove one, append it to the order and
 * decrement its neighbours. If fewer than V nodes were emitted a cycle exists; the remaining nodes (in-degree > 0) contain it,
 * and a DFS with three colours extracts one concrete cycle.
 *
 * <p><b>Complexity:</b> O(V + E) time, O(V) space.
 *
 * <p><b>Edge cases:</b> empty graph, single node, self loop (is a cycle), disconnected components, multiple valid orders
 * (this implementation is deterministic by insertion order), duplicate edges.
 *
 * <p><b>Brute force vs optimized:</b> trying all permutations is O(V!); repeated scans for a free node is O(V^2); Kahn with a
 * queue is linear.
 *
 * <p><b>Interview follow-ups:</b> DFS-based topological sort (reverse post-order)? Lexicographically smallest order (use a
 * min-heap instead of a queue)? Parallel scheduling levels (process the queue layer by layer)? Detecting cycles in undirected
 * graphs (union-find)?
 */
public final class TopologicalSort {
    private TopologicalSort() { }

    /** A valid ordering, or empty when the graph has a cycle. */
    public static <N> Optional<List<N>> sort(Graph<N> g) {
        Map<N, Integer> indeg = new HashMap<>();
        for (N n : g.nodes()) indeg.putIfAbsent(n, 0);
        for (N n : g.nodes()) for (N m : g.neighbours(n)) indeg.merge(m, 1, Integer::sum);
        Deque<N> ready = new ArrayDeque<>();
        for (N n : g.nodes()) if (indeg.get(n) == 0) ready.add(n);
        List<N> order = new ArrayList<>(g.nodes().size());
        while (!ready.isEmpty()) {
            N n = ready.poll();
            order.add(n);
            for (N m : g.neighbours(n)) {
                if (indeg.merge(m, -1, Integer::sum) == 0) ready.add(m);
            }
        }
        return order.size() == g.nodes().size() ? Optional.of(order) : Optional.empty();
    }

    public static <N> boolean hasCycle(Graph<N> g) { return sort(g).isEmpty(); }

    /** One concrete cycle (first node repeated at the end), or empty list when acyclic. */
    public static <N> List<N> findCycle(Graph<N> g) {
        Map<N, Integer> color = new HashMap<>();      // absent/0 = white, 1 = on stack, 2 = done
        Map<N, N> parent = new HashMap<>();
        for (N start : g.nodes()) {
            if (color.getOrDefault(start, 0) != 0) continue;
            List<N> cycle = dfs(g, start, color, parent);
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    private static <N> List<N> dfs(Graph<N> g, N u, Map<N, Integer> color, Map<N, N> parent) {
        color.put(u, 1);
        Set<N> next = g.neighbours(u);
        for (N v : next) {
            int c = color.getOrDefault(v, 0);
            if (c == 1) {
                List<N> cycle = new ArrayList<>();
                cycle.add(v);
                for (N cur = u; !cur.equals(v); cur = parent.get(cur)) cycle.add(1, cur);
                cycle.add(v);
                return cycle;
            }
            if (c == 0) {
                parent.put(v, u);
                List<N> r = dfs(g, v, color, parent);
                if (!r.isEmpty()) return r;
            }
        }
        color.put(u, 2);
        return List.of();
    }
}
