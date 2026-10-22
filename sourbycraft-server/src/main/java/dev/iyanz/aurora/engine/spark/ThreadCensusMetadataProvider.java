package dev.iyanz.aurora.engine.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import me.lucko.spark.paper.common.platform.MetadataProvider;
import me.lucko.spark.paper.common.util.ThreadFinder;

/**
 * Attaches a thread census to every spark report, under {@code sourbycraft-threads}: for each
 * {@link SparkThreadClass}, how many live platform threads it has and the pool names a viewer shows
 * for them (spark's {@code BY_POOL} labels). This is the one place a report can say what the
 * threads that were <em>not</em> sampled are, because a sampler's thread dumper and grouper cannot
 * be extended by a platform plugin.
 *
 * <p>It states, rather than hides, what spark cannot show: the global tick and the region ticks
 * share their threads, and virtual threads are invisible to spark. Cheap: one walk of the thread
 * groups, no stack traces.</p>
 */
public final class ThreadCensusMetadataProvider implements MetadataProvider {

    /** The key under which the census appears in a report. */
    public static final String KEY = "sourbycraft-threads";

    private final Supplier<? extends Collection<String>> threadNames;

    public ThreadCensusMetadataProvider() {
        this(() -> new ThreadFinder().getThreads().map(Thread::getName).toList());
    }

    /** Test seam: the names of the threads to classify. */
    ThreadCensusMetadataProvider(final Supplier<? extends Collection<String>> threadNames) {
        this.threadNames = threadNames;
    }

    @Override
    public Map<String, JsonElement> get() {
        return Map.of(KEY, census(this.threadNames.get()));
    }

    static JsonObject census(final Collection<String> names) {
        final Map<SparkThreadClass, Integer> counts = new EnumMap<>(SparkThreadClass.class);
        final Map<SparkThreadClass, TreeSet<String>> pools = new EnumMap<>(SparkThreadClass.class);
        for (final String name : names) {
            final SparkThreadClass threadClass = SparkThreadClass.of(name);
            counts.merge(threadClass, 1, Integer::sum);
            pools.computeIfAbsent(threadClass, ignored -> new TreeSet<>()).add(SparkThreadClass.poolOf(name));
        }

        final JsonObject classes = new JsonObject();
        for (final SparkThreadClass threadClass : SparkThreadClass.values()) {
            final int count = counts.getOrDefault(threadClass, 0);
            if (count == 0) {
                continue;
            }
            final JsonObject entry = new JsonObject();
            entry.addProperty("threads", count);
            final JsonArray labels = new JsonArray();
            pools.get(threadClass).forEach(labels::add);
            entry.add("pools", labels);
            classes.add(threadClass.label(), entry);
        }

        final JsonObject root = new JsonObject();
        root.add("classes", classes);
        root.addProperty("note", "Spark labels samples by thread name only. The global tick and the region ticks run on the same "
            + "region scheduler threads and cannot be told apart; virtual threads (the Sourby I/O lane) are not visible to spark.");
        return root;
    }

    /** Several providers as one; the first provider to define a key wins. */
    public static MetadataProvider combine(final MetadataProvider... providers) {
        return () -> {
            final Map<String, JsonElement> merged = new TreeMap<>();
            for (final MetadataProvider provider : providers) {
                provider.get().forEach(merged::putIfAbsent);
            }
            return merged;
        };
    }
}
