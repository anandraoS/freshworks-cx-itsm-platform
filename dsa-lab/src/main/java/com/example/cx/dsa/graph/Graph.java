package com.example.cx.dsa.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directed graph (adjacency list) with BFS, DFS and shortest hop path.
 *
 * <p><b>Purpose / platform use case:</b> reachability in the workflow transition graph: "from status NEW which statuses are
 * reachable?", "fewest transitions from RESOLVED to CLOSED", and finding unreachable (dead) states when an admin edits a
 * workflow. Also org hierarchies and ticket-link graphs (related/blocked-by).
 *
 * <p><b>Algorithm:</b> BFS uses a queue and visits nodes by increasing hop distance (so it finds fewest-edge paths). DFS
 * (iterative with an explicit stack here) explores deep first and is the basis for cycle detection and topological sort.
 * A visited set prevents infinite loops on cycles.
 *
 * <p><b>Complexity:</b> O(V + E) time, O(V) space for both.
 *
 * <p><b>Edge cases:</b> unknown start node (empty result), self loops, disconnected components, cycles, duplicate edges,
 * unreachable target (empty path), start equals target.
 *
 * <p><b>Brute force vs optimized:</b> repeatedly relaxing all pairs (Floyd-Warshall) is O(V^3); a single BFS answers
 * single-source unweighted queries in O(V+E). Weighted edges need Dijkstra.
 *
 * <p><b>Interview follow-ups:</b> BFS vs DFS trade-offs (memory on wide vs deep graphs)? Shortest path with weights
 * (Dijkstra, heap)? Connected components / union-find? Recursion depth limits (hence iterative DFS)? Bidirectional BFS?
 */
public class Graph<N> {
    private final Map<N, Set<N>> adj = new LinkedHashMap<>();

    public Graph<N> addNode(N n) {
        adj.computeIfAbsent(n, k -> new LinkedHashSet<>());
        return this;
    }

    public Graph<N> addEdge(N from, N to) {
        addNode(from);
        addNode(to);
        adj.get(from).add(to);
        return this;
    }

    public Set<N> nodes() { return Collections.unmodifiableSet(adj.keySet()); }
    public Set<N> neighbours(N n) { return Collections.unmodifiableSet(adj.getOrDefault(n, Set.of())); }

    /** Nodes in BFS visiting order from start (includes start). */
    public List<N> bfs(N start) {
        List<N> order = new ArrayList<>();
        if (!adj.containsKey(start)) return order;
        Set<N> seen = new HashSet<>(Set.of(start));
        Deque<N> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            N n = q.poll();
            order.add(n);
            for (N m : adj.get(n)) if (seen.add(m)) q.add(m);
        }
        return order;
    }

    /** Nodes in DFS pre-order from start (iterative; neighbours visited in insertion order). */
    public List<N> dfs(N start) {
        List<N> order = new ArrayList<>();
        if (!adj.containsKey(start)) return order;
        Set<N> seen = new HashSet<>();
        Deque<N> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            N n = stack.pop();
            if (!seen.add(n)) continue;
            order.add(n);
            List<N> rev = new ArrayList<>(adj.get(n));
            Collections.reverse(rev);                 // push reversed so the first neighbour is popped first
            for (N m : rev) if (!seen.contains(m)) stack.push(m);
        }
        return order;
    }

    /** Fewest-edge path from -> to (inclusive), or empty list when unreachable. */
    public List<N> shortestPath(N from, N to) {
        if (!adj.containsKey(from) || !adj.containsKey(to)) return List.of();
        Map<N, N> parent = new HashMap<>();
        Set<N> seen = new HashSet<>(Set.of(from));
        Deque<N> q = new ArrayDeque<>();
        q.add(from);
        while (!q.isEmpty()) {
            N n = q.poll();
            if (n.equals(to)) break;
            for (N m : adj.get(n)) {
                if (seen.add(m)) {
                    parent.put(m, n);
                    q.add(m);
                }
            }
        }
        if (!seen.contains(to)) return List.of();
        List<N> path = new ArrayList<>();
        for (N cur = to; cur != null; cur = parent.get(cur)) path.add(cur);
        Collections.reverse(path);
        return path;
    }

    /** Nodes that cannot be reached from start (dead states in a workflow). */
    public Set<N> unreachableFrom(N start) {
        Set<N> all = new LinkedHashSet<>(adj.keySet());
        all.removeAll(new HashSet<>(bfs(start)));
        return all;
    }
}
