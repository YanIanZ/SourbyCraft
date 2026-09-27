package dev.iyanz.sourbycraft.execution.region;

/**
 * A chunk that a region owned when the anchor was taken. Scheduling on it runs the work on
 * whichever region owns that chunk when the work comes due, so region merges and splits are
 * followed rather than assumed away.
 *
 * @param world the Bukkit world, typed loosely so bridge code can hold it without the server API
 */
public record RegionAnchor(Object world, int chunkX, int chunkZ) {}
