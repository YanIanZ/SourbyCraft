package dev.iyanz.aurora.engine.util.migration;

import io.papermc.paper.world.migration.WorldMigrationContext;

/**
 * A migration of engine save data from an older name, location or layout (pre-26.1 files, Canvas-era names) to the current one
 */
public interface Migration {
    /**
     * Conducts the migration type
     *
     * @param context
     *     the paper migration context
     */
    void conduct(final WorldMigrationContext context);

    /**
     * Gets if the old data is present
     *
     * @param context
     *     the paper migration context
     *
     * @return if the old data exists
     */
    boolean hasOldData(final WorldMigrationContext context);

    /**
     * Gets if the new data is present
     *
     * @param context
     *     the paper migration context
     *
     * @return if the new data exists
     */
    boolean hasNewData(final WorldMigrationContext context);
}
