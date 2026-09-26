package dev.iyanz.sourbycraft.perf;

/**
 * The two staleness rules an async path result must pass before it is applied, as used by
 * {@code PathNavigation} in minecraft feature patch 0006.
 *
 * <p>Both are identity comparisons, deliberately: the objects involved are replaced, never
 * mutated into something new, whenever the result would stop being valid.</p>
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
}
