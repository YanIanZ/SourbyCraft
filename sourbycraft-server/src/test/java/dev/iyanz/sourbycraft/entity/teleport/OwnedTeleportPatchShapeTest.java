package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the call sites of the owned teleport and spawn-location hooks in the feature patches. The
 * glue runs only inside a booted server, so this proves the hooks sit where the unit-tested
 * decisions assume: the route is taken before the inherited refusal, the refusal is kept (not
 * deleted) as the fail-closed branch, Paper's synchronous body runs inside the admission scope, the
 * NMS refusals stay for everyone but admitted entities, and the destination is re-checked after the
 * Bukkit teleport event and before anything moves. It proves nothing about behaviour.
 */
class OwnedTeleportPatchShapeTest {

    static String patch(final String featureDir, final String nameContains) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of(featureDir, "sourbycraft-server/" + featureDir)) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString().contains(nameContains))
                            .findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch *" + nameContains + "* not found under " + featureDir);
    }

    /** The section of a patch that belongs to one file. */
    static String file(final String patch, final String path) {
        final int start = patch.indexOf("diff --git a/" + path);
        assertTrue(start >= 0, "patch touches " + path);
        final int next = patch.indexOf("diff --git a/", start + 1);
        return next < 0 ? patch.substring(start) : patch.substring(start, next);
    }

    static int at(final String text, final String needle) {
        final int i = text.indexOf(needle);
        assertTrue(i >= 0, "missing: " + needle);
        return i;
    }

    static int count(final String text, final String needle) {
        int n = 0;
        for (int from = 0, i; (i = text.indexOf(needle, from)) >= 0; from = i + 1) n++;
        return n;
    }

    /** Removed lines in a diff section (the {@code ---} file header is not one). */
    static int removed(final String section) {
        int n = 0;
        for (final String line : section.split("\n")) {
            if (line.startsWith("-") && !line.startsWith("---")) n++;
        }
        return n;
    }

    private static String paperPatch() throws IOException {
        return patch("paper-patches/features", "-SourbyCraft-owned-sync-teleport-route");
    }

    private static String minecraftPatch() throws IOException {
        return patch("minecraft-patches/features", "-SourbyCraft-owned-sync-teleport-on-the-owning-region");
    }

    @Test
    void craftEntityTakesTheRouteBeforeTheInheritedRefusalAndKeepsItForRefuse() throws IOException {
        final String craft = file(paperPatch(), "src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java");
        final int route = at(craft, "+        final dev.iyanz.sourbycraft.entity.teleport.TeleportRoute.Route sourbyRoute = dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.route(this.getHandleRaw(), location);");
        final int async = at(craft, "Route.ASYNC) return dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.scheduleAsync(this, location, cause, flags);");
        final int notSync = at(craft, "+        if (sourbyRoute != dev.iyanz.sourbycraft.entity.teleport.TeleportRoute.Route.SYNC) {");
        final int refusal = at(craft, "             throw new UnsupportedOperationException(\"Must use teleportAsync while in region threading\");");
        final int close = at(craft, "+        } // SourbyCraft - owned sync teleport");
        final int body = at(craft, "+        return dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.runSync(this.entity, () -> this.entity.teleport(new TeleportTransition(");
        assertTrue(route < async && async < notSync && notSync < refusal && refusal < close && close < body,
            "route, ASYNC return, REFUSE-only refusal, then Paper's body inside the scope");
        assertEquals(0, count(craft, "-            throw new UnsupportedOperationException"), "the inherited refusal is kept, not deleted");
    }

    @Test
    void craftPlayerKeepsPapersChecksAndLeavesTheRouteToSuper() throws IOException {
        final String player = file(paperPatch(), "src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java");
        final int off = at(player, "+        if (!dev.iyanz.sourbycraft.entity.teleport.TeleportRoute.ENABLED) {");
        final int refusal = at(player, "             throw new UnsupportedOperationException(\"Must use teleportAsync while in region threading\");");
        final int close = at(player, "+        } // SourbyCraft - owned sync teleport");
        final int route = at(player, "+        if (dev.iyanz.sourbycraft.entity.teleport.TeleportRoute.ENABLED) return dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.playerMayProceed((net.minecraft.server.level.ServerPlayer) this.getHandleRaw()) && super.teleport0(location, cause, flags);");
        final int paperChecks = at(player, " if (this.getHandle().connection == null) {");
        assertTrue(off < refusal && refusal < close && close < route && route < paperChecks,
            "with the owned route on, Paper's player checks (which read player state) are never reached off-region");
        assertEquals(0, removed(player), "CraftPlayer only gains the switch and the route");
    }

    @Test
    void nmsRefusalsStayForEveryoneButAdmittedEntities() throws IOException {
        final String patch = minecraftPatch();
        for (final String path : List.of("net/minecraft/world/entity/Entity.java", "net/minecraft/server/level/ServerPlayer.java")) {
            final String section = file(patch, path);
            final int admitted = at(section, "+        if (!dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.admitted(this, transition)) {");
            final int refusal = at(section, "             throw new UnsupportedOperationException(\"Must use teleportAsync while in region threading\");");
            final int close = at(section, "+        } // SourbyCraft - owned sync teleport");
            assertTrue(admitted < refusal && refusal < close, path);
            assertEquals(0, removed(section), path + ": only additions");
        }
    }

    @Test
    void theDestinationIsRecheckedAfterTheEventAndBeforeAnythingMoves() throws IOException {
        final String patch = minecraftPatch();
        final String entity = file(patch, "net/minecraft/world/entity/Entity.java");
        final int removedCheck = at(entity, " // Paper end - Call EntityPortalExitEvent");
        final int recheck = at(entity, "+            if (!dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.continueAfterEvents(this, transition)) return null;");
        final int move = at(entity, " ServerLevel newLevel = transition.newLevel();");
        assertTrue(removedCheck < recheck && recheck < move, "after the portal-exit event, before the dimension split");

        final String player = file(patch, "net/minecraft/server/level/ServerPlayer.java");
        final int eventApplied = at(player, " transition = transition.withChangedLocation(newExit).withVelocity(Vec3.ZERO).withRelatives(Set.of());");
        final int playerRecheck = at(player, "+        if (!dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport.continueAfterEvents(this, transition)) return null;");
        final int dismount = at(player, " if (!transition.asPassenger()) {");
        assertTrue(eventApplied < playerRecheck && playerRecheck < dismount, "after PlayerTeleportEvent's result, before the vehicle is removed");
    }
}
