package dev.iyanz.sourbycraft.perf;

/**
 * The staleness rules an async path result must pass before it is applied, as used by
 * {@code PathNavigation} in minecraft feature patches 0006 and 0047.
 *
 * <p>The first two are identity comparisons, deliberately: the objects involved are replaced, never
 * mutated into something new, whenever the result would stop being valid. The third is the
 * navigation's request generation, for the one invalidation identity cannot see.</p>
 */
public final class AsyncPathValidity {

    private AsyncPathValidity() {}

    /**
     * The entity the owning region hands back is the entity the solve was computed for. A
     * dimension transfer replaces the entity object, so the old navigation's result must not be
     * applied to the new one.
     */
    public static boolean sameSubject(final Object deliveredOwner, final Object requestedSubject) {
        return deliveredOwner == requestedSubject;
    }

    /**
     * The navigation is still following the path it was following when the solve was requested.
     * A synchronous recompute, {@code moveTo}, a retarget or {@code stop()} each replace the path
     * object (or clear it), which makes the in-flight result stale.
     */
    public static boolean stillRequested(final Object currentPath, final Object requestedPath) {
        return currentPath == requestedPath;
    }

    /**
     * No invalidating operation ran since the solve was requested. {@code PathNavigation}'s request
     * generation (minecraft feature patch 0047) advances on every {@code stop()}, {@code moveTo}
     * that assigns or clears a path, synchronous recompute and applied result. The path object alone
     * cannot see a {@code stop()} that finds the path already {@code null}: an
     * {@code EntityPathfindEvent} listener that stopped the navigation before the token was taken
     * makes the token {@code null}, and a later stop leaves it {@code null} too.
     */
    public static boolean sameGeneration(final int currentGeneration, final int requestedGeneration) {
        return currentGeneration == requestedGeneration;
    }
}
