package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins the PlayerSpawnLocationEvent hook in {@code PrepareSpawnTask.Preparing#tick}: once per join
 * (flag), before Paper's AsyncPlayerSpawnLocationEvent (which then sees the result), inside the
 * block that runs before the chunk-load future exists, with the join mark moved before the chunk
 * load is keyed on the final world. The inherited {@code if (false && ...)} block stays untouched.
 */
class SpawnLocationPatchShapeTest {

    @Test
    void theLegacyEventFiresOnceBeforeTheAsyncEventAndBeforeTheChunkLoad() throws IOException {
        final String patch = OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "-SourbyCraft-fire-PlayerSpawnLocationEvent-on-the-global-tick");
        final String task = OwnedTeleportPatchShapeTestAccess.file(patch, "net/minecraft/server/network/config/PrepareSpawnTask.java");
        final int block = OwnedTeleportPatchShapeTestAccess.at(task, " if (this.chunkLoadFuture == null) {");
        final int mark = OwnedTeleportPatchShapeTestAccess.at(task, "+                if (this.sourbyJoinMarkLevel == null) this.sourbyJoinMarkLevel = this.spawnLevel;");
        final int gate = OwnedTeleportPatchShapeTestAccess.at(task, "+                if (dev.iyanz.sourbycraft.world.SpawnLocationEvents.shouldFireLegacy(this.sourbyLegacySpawnEventFired,");
        final int flag = OwnedTeleportPatchShapeTestAccess.at(task, "+                    this.sourbyLegacySpawnEventFired = true;");
        final int fire = OwnedTeleportPatchShapeTestAccess.at(task, "SpawnLocationEvents.fireLegacy(");
        final int inherited = OwnedTeleportPatchShapeTestAccess.at(task, " if (false && this.eventFuture == null && org.spigotmc.event.player.PlayerSpawnLocationEvent");
        // Paper's AsyncPlayerSpawnLocationEvent block sits between the inherited block and this marker.
        final int paperEnd = OwnedTeleportPatchShapeTestAccess.at(task, " // Paper end - PlayerSpawnLocationEvent");
        final int move = OwnedTeleportPatchShapeTestAccess.at(task, "+                this.spawnLevel = dev.iyanz.sourbycraft.world.SpawnLocationEvents.moveJoinMark(this.sourbyJoinMarkLevel, this.spawnLevel,");
        final int chunk = OwnedTeleportPatchShapeTestAccess.at(task, " ChunkPos spawnChunk = ChunkPos.containing(BlockPos.containing(spawnPosition));");
        assertTrue(block < mark && mark < gate && gate < flag && flag < fire && fire < inherited
            && inherited < paperEnd && paperEnd < move && move < chunk);
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(task), "only additions; the inherited block stays off");
    }
}
