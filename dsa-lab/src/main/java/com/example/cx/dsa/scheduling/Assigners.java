package com.example.cx.dsa.scheduling;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Agent assignment strategies: round robin and least loaded (heap).
 *
 * <p><b>Purpose / platform use case:</b> workflow action {@code ASSIGN_TO_ROLE_LEAST_LOADED} and plain round-robin routing of new
 * tickets among AGENTs. (The platform computes "least loaded" from open-ticket counts in the database; the heap version is the
 * in-memory algorithm for a hot path or a simulation.)
 *
 * <p><b>Algorithm:</b> round robin: a rotating index. Least loaded: a min-heap keyed by (load, name); assign pops the lightest
 * agent, adds the weight and pushes it back. Ties break by name so results are deterministic.
 *
 * <p><b>Complexity:</b> round robin O(1). Least loaded assign O(log n); release (a ticket closes) O(n) here because a
 * {@link PriorityQueue} cannot update in place (use an indexed heap or TreeSet for O(log n)).
 *
 * <p><b>Edge cases:</b> no agents (error), single agent, agent removed/added mid-flight (round robin index must wrap), equal
 * loads, weights (an URGENT ticket may count as 3), unavailable agents (skip them).
 *
 * <p><b>Brute force vs optimized:</b> scanning all agents for the minimum is O(n) per assignment; the heap makes it O(log n),
 * which matters only with many agents. For a handful of agents the scan is perfectly fine and simpler.
 *
 * <p><b>Interview follow-ups:</b> skills-based routing, capacity limits, sticky assignment (same requester to the same agent),
 * "power of two choices" for distributed balancing, fairness vs throughput.
 */
public final class Assigners {
    private Assigners() { }

    public static final class RoundRobin<T> {
        private final List<T> members;
        private int next;

        public RoundRobin(List<T> members) {
            if (members.isEmpty()) throw new IllegalArgumentException("no members");
            this.members = List.copyOf(members);
        }

        public T next() {
            T t = members.get(next);
            next = (next + 1) % members.size();
            return t;
        }
    }

    public static final class LeastLoaded {
        private static final class Agent {
            final String name;
            int load;
            Agent(String name, int load) { this.name = name; this.load = load; }
        }

        private final PriorityQueue<Agent> heap = new PriorityQueue<>(
                Comparator.<Agent>comparingInt(a -> a.load).thenComparing(a -> a.name));

        public LeastLoaded(List<String> agents) {
            if (agents.isEmpty()) throw new IllegalArgumentException("no agents");
            for (String a : agents) heap.add(new Agent(a, 0));
        }

        /** Assigns work of the given weight to the least loaded agent and returns its name. */
        public String assign(int weight) {
            Agent a = heap.poll();
            if (a == null) throw new NoSuchElementException();
            a.load += weight;
            heap.add(a);
            return a.name;
        }

        public void release(String agent, int weight) {
            for (Agent a : heap) {
                if (a.name.equals(agent)) {
                    heap.remove(a);
                    a.load = Math.max(0, a.load - weight);
                    heap.add(a);
                    return;
                }
            }
            throw new NoSuchElementException(agent);
        }

        public int loadOf(String agent) {
            for (Agent a : heap) if (a.name.equals(agent)) return a.load;
            throw new NoSuchElementException(agent);
        }
    }
}
