package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf awf}: Aurora World Fabric storage rows. The row bodies were moved here from
 * {@code PerfCommand.renderAwf} (the hunk owned by the AWF work); rows added since include
 * {@code AWF loaded worlds}, each storage's {@code oldest dirty age} and the refused writes in the
 * read-failure row.
 */
final class PerfAwfView implements PerfView {

    @Override public String name() { return "awf"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        renderAwf(lines, dev.iyanz.sourbycraft.awf.Durability.degradedDirectoryForces(),
            dev.iyanz.sourbycraft.awf.Durability.degradedReason(),
            dev.iyanz.sourbycraft.awf.RepeatedReadFailureLogFilter.instance().dropped());
    }

    static void renderAwf(final List<Component> lines, final long degradedDirectoryForces, final String degradedReason) {
        renderAwf(lines, degradedDirectoryForces, degradedReason, 0L);
    }

    /**
     * The AWF rows. The degraded directory-force count is a parameter so the row can be tested on
     * a platform that fsyncs directories fine; it is shown only while non-zero, since zero is the
     * normal state and the row is a warning, not a statistic. The collapsed-read-error count
     * ({@code RepeatedReadFailureLogFilter.dropped()}, read at command time) is a parameter for the
     * same reason and follows the same rule.
     */
    static void renderAwf(final List<Component> lines, final long degradedDirectoryForces, final String degradedReason,
                          final long collapsedReadErrors) {
        if (degradedDirectoryForces > 0) {
            add(lines, "AWF directory fsyncs skipped", degradedDirectoryForces + " since start (" + degradedReason
                + "); directory entries rely on the rename alone, commits are not fully durable against power loss here");
        }
        if (collapsedReadErrors > 0) {
            add(lines, "Chunk-system read errors collapsed", collapsedReadErrors + " since start (repeats of an AWF read failure"
                + " that AWF already counted; their chunk-system log lines were dropped)");
        }
        final var engine = dev.iyanz.sourbycraft.awf.AwfEngine.ifStarted();
        final var storages = engine == null ? List.<dev.iyanz.sourbycraft.awf.AwfRegionStorage>of() : engine.storages();
        if (storages.isEmpty()) {
            add(lines, "Aurora World Fabric", "no world is stored in AWF (aurora.awf.worlds)");
            return;
        }
        add(lines, "AWF settings", engine.settings().persistence() + ", commit every "
            + engine.settings().commitIntervalSeconds() + "s, " + engine.settings().residentChunks() + " resident chunks per storage");
        add(lines, "AWF loaded worlds", loadedWorlds(storages) + " (dimension folders with an open AWF storage)");
        if (dev.iyanz.sourbycraft.awf.AwfBackend.named("redis") instanceof dev.iyanz.sourbycraft.awf.redis.RedisBackend redis) {
            add(lines, "AWF Redis leases", redis.leaseSummary());
        }
        for (final var storage : storages) {
            final var stats = storage.stats();
            final var world = stats.world();
            final String name = shortStorageName(stats.name());
            add(lines, name + " resident / dirty / evicted",
                world.residentChunks() + " / " + world.dirtyChunks() + " / " + stats.evicted());
            add(lines, name + " pending commits / oldest", world.saveQueueDepth() + " / " + world.oldestPendingSaveMillis() + " ms");
            add(lines, name + " oldest dirty age", world.dirtyChunks() == 0 ? "no dirty chunk"
                : world.oldestDirtyMillis() + " ms");
            final var commit = world.backend();
            add(lines, name + " commit p50 / p95 / p99", commit.samples() == 0 ? "no commit yet"
                : ms(commit.p50()) + " / " + ms(commit.p95()) + " / " + ms(commit.p99())
                    + " (last " + commit.samples() + " commits)");
            add(lines, name + " reads / from region files / deletes",
                stats.reads() + " / " + stats.baseFallthroughs() + " / " + stats.deletes());
            add(lines, name + " retries / failures / written", world.retries() + " / " + world.failures() + " / "
                + (world.bytesWritten() / 1024) + " KiB");
            // Refused writes alone: a POI storage refusing the POI of a chunk its region storage fenced.
            if (stats.readFailures() > 0 || stats.readRetries() > 0 || stats.refusedWrites() > 0) {
                add(lines, name + " read retries / failed reads / unreadable / refused writes", stats.readRetries()
                    + " / " + stats.readFailures() + " / " + stats.unreadable() + " / " + stats.refusedWrites());
            }
        }
    }

    /**
     * Loaded AWF worlds: the distinct dimension folders of the open storages. On 26.2 every world
     * (overworld, nether, end, each managed world) is one dimension folder.
     */
    static long loadedWorlds(final java.util.Collection<dev.iyanz.sourbycraft.awf.AwfRegionStorage> storages) {
        return storages.stream().map(storage -> java.nio.file.Path.of(storage.name()).getParent()).distinct().count();
    }

    /** The last three path elements: world/dimension/region rather than the whole absolute path. */
    static String shortStorageName(final String path) {
        final String[] parts = path.replace('\\', '/').split("/");
        final int from = Math.max(0, parts.length - 3);
        return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    private static String ms(final double millis) {
        return String.format(java.util.Locale.ROOT, "%.1f ms", millis);
    }
}
