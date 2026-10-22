package dev.iyanz.sourbycraft.startup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The plugin dependency graph, built from descriptors before the plugin manager runs.
 *
 * <p>Diagnostic only. The plugin manager computes and enforces the real load order; this graph
 * never reorders or skips a plugin. It exists so a boot can say up front which plugins are missing
 * a hard dependency or sit in a dependency cycle — the two cases the manager reports one plugin at
 * a time, deep in the load log.</p>
 */
public final class DependencyGraph {

    /**
     * @param missing plugin to the hard dependencies it names that are not present
     * @param cycles each cycle's members, sorted; a plugin in a cycle appears in exactly one
     * @param order a load order consistent with every edge, for plugins outside cycles
     */
    public record Report(Map<String, List<String>> missing, List<List<String>> cycles, List<String> order) {
        public Report {
            missing = java.util.Collections.unmodifiableMap(new TreeMap<>(missing));
            cycles = List.copyOf(cycles);
            order = List.copyOf(order);
        }

        public boolean clean() {
            return this.missing.isEmpty() && this.cycles.isEmpty();
        }
    }

    private DependencyGraph() {}

    public static Report analyse(final Collection<PluginDescriptor> plugins) {
        final Map<String, PluginDescriptor> byName = new TreeMap<>();
        for (final PluginDescriptor p : plugins) byName.putIfAbsent(p.name(), p);

        // Edge a -> b means a must load before b.
        final Map<String, Set<String>> edges = new TreeMap<>();
        final Map<String, List<String>> missing = new TreeMap<>();
        for (final String name : byName.keySet()) edges.put(name, new TreeSet<>());
        for (final PluginDescriptor p : byName.values()) {
            for (final String dep : p.depend()) {
                if (byName.containsKey(dep)) edges.get(dep).add(p.name());
                else missing.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(dep);
            }
            for (final String dep : p.softDepend()) {
                if (byName.containsKey(dep)) edges.get(dep).add(p.name());
            }
            for (final String later : p.loadBefore()) {
                if (byName.containsKey(later)) edges.get(p.name()).add(later);
            }
        }
        final List<List<String>> cycles = stronglyConnected(edges);
        final Set<String> inCycle = new TreeSet<>();
        cycles.forEach(inCycle::addAll);
        return new Report(missing, cycles, topological(edges, inCycle));
    }

    /** Kahn's algorithm over the acyclic part, alphabetical among ready nodes so the output is stable. */
    private static List<String> topological(final Map<String, Set<String>> edges, final Set<String> excluded) {
        final Map<String, Integer> indegree = new TreeMap<>();
        for (final String n : edges.keySet()) if (!excluded.contains(n)) indegree.put(n, 0);
        for (final Map.Entry<String, Set<String>> e : edges.entrySet()) {
            if (excluded.contains(e.getKey())) continue;
            for (final String to : e.getValue()) if (indegree.containsKey(to)) indegree.merge(to, 1, Integer::sum);
        }
        final TreeSet<String> ready = new TreeSet<>();
        indegree.forEach((n, d) -> { if (d == 0) ready.add(n); });
        final List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            final String n = ready.pollFirst();
            order.add(n);
            for (final String to : edges.get(n)) {
                if (!indegree.containsKey(to)) continue;
                if (indegree.merge(to, -1, Integer::sum) == 0) ready.add(to);
            }
        }
        // Nodes downstream of a cycle never reach indegree 0; they are still loadable once the
        // cycle is broken, so they are appended rather than silently dropped.
        for (final String n : indegree.keySet()) if (!order.contains(n)) order.add(n);
        return order;
    }

    /** Tarjan's algorithm, iterative so a long dependency chain cannot overflow the stack. */
    private static List<List<String>> stronglyConnected(final Map<String, Set<String>> edges) {
        final Map<String, Integer> index = new TreeMap<>();
        final Map<String, Integer> low = new TreeMap<>();
        final Deque<String> stack = new ArrayDeque<>();
        final Set<String> onStack = new TreeSet<>();
        final List<List<String>> out = new ArrayList<>();
        int next = 0;
        for (final String root : edges.keySet()) {
            if (index.containsKey(root)) continue;
            final Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[] {root, edges.get(root).iterator()});
            index.put(root, next); low.put(root, next); next++;
            stack.push(root); onStack.add(root);
            while (!work.isEmpty()) {
                final Object[] frame = work.peek();
                final String v = (String)frame[0];
                @SuppressWarnings("unchecked") final java.util.Iterator<String> it = (java.util.Iterator<String>)frame[1];
                if (it.hasNext()) {
                    final String w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, next); low.put(w, next); next++;
                        stack.push(w); onStack.add(w);
                        work.push(new Object[] {w, edges.get(w).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    final String parent = (String)work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    final List<String> component = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        component.add(w);
                    } while (!w.equals(v));
                    final boolean selfLoop = edges.get(v).contains(v);
                    if (component.size() > 1 || selfLoop) {
                        component.sort(null);
                        out.add(component);
                    }
                }
            }
        }
        out.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        return out;
    }
}
