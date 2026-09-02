package dev.iyanz.sourbycraft.api.metrics;

import org.jspecify.annotations.NullMarked;

/**
 * Read-only SourbyCraft performance metrics exposed through Bukkit's services manager.
 *
 * <p>Implementations and returned snapshots are thread-safe. Plugins may obtain and use this
 * service from any thread.</p>
 */
@NullMarked
public interface SourbyMetrics {
    /**
     * Returns the latest immutable performance snapshot.
     *
     * @return the latest snapshot
     */
    PerformanceSnapshot snapshot();
}
