package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@code MinecraftServer.currentTick} exists again with Paper's linkage (plugins such as FastAsyncWorldEdit 2.15.4 /
 * 2.16.1 read it with {@code getstatic MinecraftServer.currentTick:I}) and counts global ticks.
 */
@ResourceLock("net.minecraft.server.MinecraftServer.currentTick")
class LegacyServerTickTest {

    private int saved;

    @BeforeEach
    void save() {
        this.saved = MinecraftServer.currentTick;
    }

    @AfterEach
    void restore() {
        MinecraftServer.currentTick = this.saved;
    }

    @Test
    void fieldHasPapersLinkage() throws NoSuchFieldException {
        final Field field = MinecraftServer.class.getDeclaredField("currentTick");
        assertEquals(int.class, field.getType(), "descriptor I, as getstatic links it");
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isVolatile(field.getModifiers()), "region and async readers see the global tick's writes");
    }

    @Test
    void eachGlobalTickAdvancesByOne() {
        MinecraftServer.currentTick = 41;
        LegacyServerTick.globalTick();
        assertEquals(42, LegacyServerTick.current());
        LegacyServerTick.globalTick();
        LegacyServerTick.globalTick();
        assertEquals(44, MinecraftServer.currentTick);
    }

    @Test
    void wrapsLikePapersInt() {
        MinecraftServer.currentTick = Integer.MAX_VALUE;
        LegacyServerTick.globalTick();
        assertEquals(Integer.MIN_VALUE, LegacyServerTick.current());
    }

    @Test
    void theGlobalTickHandleAdvancesItBeforeItsTasksAndOnlyThere() throws IOException {
        final String patch = OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "-SourbyCraft-restore-MinecraftServer.currentTick-as-a-global-tick-counter");
        final String server = OwnedTeleportPatchShapeTestAccess.file(patch, "io/papermc/paper/threadedregions/RegionizedServer.java");
        final int tickRegion = OwnedTeleportPatchShapeTestAccess.at(server, " protected void tickRegion(final long tickCount, final long startTime, final long scheduledEnd) {");
        final int hook = OwnedTeleportPatchShapeTestAccess.at(server, "+            dev.iyanz.sourbycraft.bridge.LegacyServerTick.globalTick();");
        final int drain = OwnedTeleportPatchShapeTestAccess.at(server, " this.drainTasks();");
        assertTrue(tickRegion < hook && hook < drain, "advanced at the start of the global tick, as Paper did at tick start");
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(server));
        final String minecraftServer = OwnedTeleportPatchShapeTestAccess.file(patch, "net/minecraft/server/MinecraftServer.java");
        OwnedTeleportPatchShapeTestAccess.at(minecraftServer, "+    public static volatile int currentTick;");
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(minecraftServer), "the inherited commented-out line stays");
        assertEquals(1, patch.split("LegacyServerTick.globalTick\\(\\)", -1).length - 1, "one writer");
    }
}
