package dev.iyanz.sourbycraft.bridge;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;

/**
 * Where a bridged operation may run, by the routing table in
 * {@code docs/architecture/aurora-plugin-bridge.md}.
 *
 * <p>Entity-owned work goes to the entity's owner, location-owned work to the region that owns the
 * location, global-safe work to the global region, blocking work to the plugin/I/O lane, and
 * anything whose owner cannot be determined is rejected. There is no "run it wherever the caller
 * is" route: that is exactly the arbitrary-thread execution SAFE mode exists to prevent.</p>
 */
public final class BridgeRouter {

    /** Where the work runs. */
    public enum Route {
        /** The region currently owning the entity the work names. */
        ENTITY_OWNER,
        /** The region owning the location the work names. */
        REGION_OWNER,
        /** The global region: server-wide state that no region owns. */
        GLOBAL_REGION,
        /** The async scheduler: no region-owned state, may block. */
        IO_LANE,
        /** Not run; recorded as a rejected operation. */
        REJECT
    }

    /** What kind of work a legacy plugin asked for. */
    public enum Kind {
        /** A Bukkit "sync" scheduler task: legacy code expecting the main thread. */
        SYNC_TASK,
        /** A Bukkit async scheduler task. */
        ASYNC_TASK,
        /** A direct world mutation whose owner the bridge cannot determine. */
        UNKNOWN_WORLD_MUTATION
    }

    /**
     * One request.
     *
     * @param kind what was asked for
     * @param namesEntity the request names an entity whose owner can be resolved
     * @param namesLocation the request names a location whose owner can be resolved
     */
    public record Operation(Kind kind, boolean namesEntity, boolean namesLocation) {
        public static Operation syncTask() { return new Operation(Kind.SYNC_TASK, false, false); }
        public static Operation asyncTask() { return new Operation(Kind.ASYNC_TASK, false, false); }
    }

    private BridgeRouter() {}

    public static Route route(final BridgeMode mode, final Operation operation) {
        if (mode != BridgeMode.SAFE) {
            return Route.REJECT;
        }
        return switch (operation.kind()) {
            // Legacy "sync" means "the thread that owned everything". The nearest owner that
            // exists is the one for the thing named; with nothing named, the global region.
            case SYNC_TASK -> operation.namesEntity() ? Route.ENTITY_OWNER
                : operation.namesLocation() ? Route.REGION_OWNER
                : Route.GLOBAL_REGION;
            case ASYNC_TASK -> Route.IO_LANE;
            case UNKNOWN_WORLD_MUTATION -> Route.REJECT;
        };
    }
}
