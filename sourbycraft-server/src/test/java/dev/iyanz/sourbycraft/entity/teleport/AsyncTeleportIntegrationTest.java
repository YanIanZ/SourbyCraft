package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Pins integration order in the compiled patches without initializing Minecraft's registries. */
class AsyncTeleportIntegrationTest {

    private static ClassNode read(final String name) throws Exception {
        try (InputStream bytes = AsyncTeleportIntegrationTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            final ClassNode node = new ClassNode();
            new ClassReader(java.util.Objects.requireNonNull(bytes, name)).accept(node, 0);
            return node;
        }
    }

    private static int call(final MethodNode method, final String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode invoke && invoke.name.equals(name)) return i;
        }
        return -1;
    }

    @Test
    void listenerRedirectsAreReclassifiedBeforeSameRegionPlacement() throws Exception {
        final ClassNode entity = read("net/minecraft/world/entity/Entity");
        final MethodNode teleport = entity.methods.stream().filter(m -> call(m, "playerTeleport") >= 0).findFirst().orElseThrow();
        final int event = call(teleport, "playerTeleport");
        final int placement = call(teleport, "teleportSyncSameRegion");
        boolean rechecked = false;
        for (int i = event + 1; i < placement; i++) {
            if (teleport.instructions.get(i) instanceof MethodInsnNode invoke && invoke.name.equals("isTickThreadFor")) rechecked = true;
        }
        assertTrue(rechecked, "a listener may change both the world and owning region after the initial classification");
    }

    @Test
    void portalPlacementRetargetsDetachedEntitiesBeforeApplyingTheExit() throws Exception {
        final ClassNode entity = read("net/minecraft/world/entity/Entity");
        final MethodNode waiter = entity.methods.stream().filter(m -> call(m, "portalOutcome") >= 0).findFirst().orElseThrow();
        assertTrue(call(waiter, "retargetPortalEntity") > call(waiter, "portalOutcome"));
        assertTrue(call(waiter, "retargetPortalEntity") < call(waiter, "transform"),
            "cancellation and redirection must restore the level of every detached passenger before placement");
    }

    @Test
    void portalEventsResolveRelativeMovementBeforeApplyingListenerChanges() throws Exception {
        final ClassNode events = read("dev/iyanz/sourbycraft/entity/teleport/AsyncTeleportEvents");
        final MethodNode outcome = events.methods.stream().filter(m -> m.name.equals("portalOutcome")).findFirst().orElseThrow();
        assertTrue(call(outcome, "calculateAbsolute") >= 0, "no-op listeners must preserve relative rotation");
        assertTrue(call(outcome, "calculateAbsolute") < call(outcome, "playerPortal"));
    }

    @Test
    void joinedTeleportsKeepTheOwnedMotionPath() throws Exception {
        final ClassNode owned = read("dev/iyanz/sourbycraft/entity/teleport/OwnedTeleport");
        final MethodNode join = owned.methods.stream().filter(m -> m.name.equals("joinAsync")).findFirst().orElseThrow();
        for (var instruction : join.instructions) {
            if (instruction instanceof MethodInsnNode invoke) {
                assertFalse(invoke.owner.equals("org/bukkit/craftbukkit/entity/CraftEntity") && invoke.name.equals("teleportAsync"),
                    "the inherited async API ignores the synchronous caller's velocity flags");
            }
        }
    }

    @Test
    void redirectedPortalRestoresTheClientsAlreadySentDimension() throws Exception {
        final ClassNode events = read("dev/iyanz/sourbycraft/entity/teleport/AsyncTeleportEvents");
        final MethodNode retarget = events.methods.stream().filter(m -> m.name.equals("retargetPortalEntity")).findFirst().orElseThrow();
        assertTrue(call(retarget, "sendRespawnClientbound") > call(retarget, "setServerLevel"),
            "the default portal loading screen already sent the original destination dimension before the event");
    }
}
