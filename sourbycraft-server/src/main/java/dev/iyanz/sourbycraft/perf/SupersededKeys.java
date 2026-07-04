package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * SourbyCraft S5 — superseded-key audit reporter.
 *
 * <p>Emits ONE INFO line at boot naming all keys that were superseded by moonrise /
 * Paper 26.1.2, together with their Paper equivalents. For each such key whose
 * operator-configured value differs from the compiled default, a WARN is emitted so
 * the operator knows their setting has no effect and where to re-apply it in Paper's
 * own config files.
 *
 * <p>The {@code performance.v9.*} block is treated specially: if the section exists at
 * all in the operator yml, it warrants a WARN because no v9 fields were retained.
 *
 * <p>No per-tick cost — called once at the end of {@link SourbyCraftConfig#init}.
 */
public final class SupersededKeys {

    private SupersededKeys() {}

    /**
     * Emit the boot INFO line and per-key WARNs.
     *
     * @param config the operator's loaded {@link YamlConfiguration}
     */
    public static void report(YamlConfiguration config) {
        // ----- ONE INFO summary line -----
        SourbyLogger.info("[SourbyCraft] Superseded config keys (26.1.2 migration — these keys are loaded"
            + " but drive no engine; the fields below are now owned by moonrise / Paper):"
            + " multithreading.enabled → Paper async-chunks;"
            + " performance.async-chunk-load + performance.async-pathfinding → moonrise;"
            + " performance.structured-concurrency → JVM virtual threads (natively on Java 21+);"
            + " performance.v9.* → moonrise (whole block removed);"
            + " memory.{skip-empty-sections, pool-entity-data, pre-size-packets, chunk-compression-cache}"
            + " → Paper internals / moonrise;"
            + " chunk.async-save-batch → moonrise async-save;"
            + " network.proxy-mode / netty.* / proxy-kick-* → paper-global.yml proxies.*;"
            + " entity-tracker.* → paper-world.yml entity-tracking-range.*;"
            + " perf.entity-tick-rate → live Knob (still wired in 26.1.2).");

        // ----- Per-key WARN when operator value differs from compiled default -----

        if (SourbyCraftConfig.multithreadingEnabled != false) {
            SourbyLogger.warn("[SourbyCraft] multithreading.enabled is superseded in 26.1.2;"
                + " use paper-global.yml async-chunks.* (moonrise owns async dispatch).");
        }

        if (SourbyCraftConfig.asyncChunkLoad != false) {
            SourbyLogger.warn("[SourbyCraft] performance.async-chunk-load is superseded in 26.1.2;"
                + " moonrise handles async chunk loading automatically.");
        }

        if (SourbyCraftConfig.asyncPathfinding != false) {
            SourbyLogger.warn("[SourbyCraft] performance.async-pathfinding is superseded in 26.1.2;"
                + " moonrise handles async pathfinding automatically.");
        }

        if (SourbyCraftConfig.structuredConcurrency != true) {
            SourbyLogger.warn("[SourbyCraft] performance.structured-concurrency is superseded in 26.1.2;"
                + " JVM virtual threads are used natively (Java 21+); no custom dispatcher exists.");
        }

        if (SourbyCraftConfig.maxPlatformThreads != 4) {
            SourbyLogger.warn("[SourbyCraft] performance.max-platform-threads is superseded in 26.1.2;"
                + " S5 sets system property max.bg.threads at config-load time (see above log);"
                + " Util.BACKGROUND_EXECUTOR is already constructed before that point.");
        }

        if (SourbyCraftConfig.skipEmptySections != true) {
            SourbyLogger.warn("[SourbyCraft] memory.skip-empty-sections is superseded in 26.1.2;"
                + " moonrise handles empty-section optimisation natively.");
        }

        if (SourbyCraftConfig.poolEntityData != true) {
            SourbyLogger.warn("[SourbyCraft] memory.pool-entity-data is superseded in 26.1.2;"
                + " use paper-world.yml entity-per-chunk-save-limit for entity-data budgets.");
        }

        if (SourbyCraftConfig.preSizePackets != false) {
            SourbyLogger.warn("[SourbyCraft] memory.pre-size-packets is superseded in 26.1.2;"
                + " Paper netty pipeline manages packet buffer sizing internally.");
        }

        if (SourbyCraftConfig.chunkCompressionCache != false) {
            SourbyLogger.warn("[SourbyCraft] memory.chunk-compression-cache is superseded in 26.1.2;"
                + " Paper's chunk serializer has its own cache layer.");
        }

        if (SourbyCraftConfig.asyncSaveBatch != true) {
            SourbyLogger.warn("[SourbyCraft] chunk.async-save-batch is superseded in 26.1.2;"
                + " moonrise batches chunk saves automatically; this key is a no-op.");
        }

        // v9.* block: warn if section exists at all (no fields retained)
        if (config != null && config.contains("performance.v9")) {
            SourbyLogger.warn("[SourbyCraft] performance.v9.* section detected in operator yml."
                + " These keys are fully superseded in 26.1.2 — moonrise owns async-lighting,"
                + " pathfinding and memory pools. Remove this section to silence this warning.");
        }
    }
}
