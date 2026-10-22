package dev.iyanz.sourbycraft.perf;

/**
 * The active region with the highest average MSPT over the five-second window, as of the last
 * collection. Operator diagnostics for {@code /perf region}; not part of the plugin metrics API.
 *
 * <p>Ids are Aurora's own: {@code worldId} is the registry's world id (assigned per loaded world),
 * {@code regionId} the backend's region id, and {@code generationId} changes whenever the region
 * splits or merges. Region coordinates are not tracked.</p>
 */
public record SlowRegion(long worldId, long regionId, long generationId, double averageMspt, double maximumMspt,
                         long samples) {

    /** Keeps the slower of two candidates; either may be {@code null}. */
    static SlowRegion slower(final SlowRegion current, final SlowRegion candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        return candidate.averageMspt > current.averageMspt ? candidate : current;
    }
}
