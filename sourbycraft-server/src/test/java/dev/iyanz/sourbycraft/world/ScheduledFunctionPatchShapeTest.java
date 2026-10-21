package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ScheduledFunctionPatchShapeTest {
    @Test
    void timerHookRunsOnlyAfterTheGlobalGameTimeAdvance() throws IOException {
        final String patch = patch();
        final String world = OwnedTeleportPatchShapeTestAccess.file(patch,
            "io/papermc/paper/threadedregions/RegionizedServer.java");
        final int advance = world.indexOf("world.serverLevelData.setGameTime(world.serverLevelData.getGameTime() + tickCount);");
        final int hook = world.indexOf("+            dev.iyanz.sourbycraft.world.ScheduledFunctionTicker.tick(world);");
        assertTrue(advance >= 0 && hook > advance, "inside world.tickTime, after advancing game time");
        assertEquals(1, patch.lines().filter(line -> line.startsWith("+")
            && line.contains("ScheduledFunctionTicker.tick(world)")).count());
        assertFalse(patch.contains("diff --git a/net/minecraft/server/level/ServerLevel.java"),
            "the per-region tick must remain disabled");
        assertFalse(patch.contains("diff --git a/net/minecraft/server/MinecraftServer.java"),
            "the server-level accessor must continue throwing");
    }

    @Test
    void scheduleIsRegisteredAndAllQueueAccessUsesTheGlobalThread() throws IOException {
        final String patch = patch();
        assertTrue(patch.contains("+        ScheduleCommand.register(this.dispatcher);"));
        final String command = OwnedTeleportPatchShapeTestAccess.file(patch,
            "net/minecraft/server/commands/ScheduleCommand.java");
        assertEquals(2, command.lines().filter(line -> line.startsWith("+")
            && line.contains("ScheduledFunctionTicker.onGlobal(")).count(), "schedule and clear");
        assertTrue(command.contains("ScheduledFunctionTicker.suggest(c.getSource().getLevel(), p)"),
            "suggestions also read the queue on global");
    }

    private static String patch() throws IOException {
        return OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "0065-SourbyCraft-tick-scheduled-functions-on-the-global-tick");
    }
}
