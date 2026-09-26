package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.bridge.BridgeRouter.Kind;
import dev.iyanz.sourbycraft.bridge.BridgeRouter.Operation;
import dev.iyanz.sourbycraft.bridge.BridgeRouter.Route;
import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import org.junit.jupiter.api.Test;

/** The routing table from aurora-plugin-bridge.md, and nothing runs on an arbitrary thread. */
class BridgeRouterTest {

    @Test
    void safeModeFollowsTheOwnershipTable() {
        assertEquals(Route.ENTITY_OWNER, BridgeRouter.route(BridgeMode.SAFE, new Operation(Kind.SYNC_TASK, true, false)));
        assertEquals(Route.REGION_OWNER, BridgeRouter.route(BridgeMode.SAFE, new Operation(Kind.SYNC_TASK, false, true)));
        assertEquals(Route.GLOBAL_REGION, BridgeRouter.route(BridgeMode.SAFE, Operation.syncTask()));
        assertEquals(Route.IO_LANE, BridgeRouter.route(BridgeMode.SAFE, Operation.asyncTask()));
        assertEquals(Route.REJECT, BridgeRouter.route(BridgeMode.SAFE, new Operation(Kind.UNKNOWN_WORLD_MUTATION, false, false)));
    }

    @Test
    void offRoutesNothing() {
        for (final Kind kind : Kind.values()) {
            assertEquals(Route.REJECT, BridgeRouter.route(BridgeMode.OFF, new Operation(kind, true, true)));
        }
    }
}
