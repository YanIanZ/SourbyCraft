package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DatapackFunctionsPatchShapeTest {
    private static String patch() throws IOException {
        return OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "0066-SourbyCraft-owned-datapack-functions");
    }

    private static long added(final String text, final String needle) {
        return text.lines().filter(line -> line.startsWith("+") && line.contains(needle)).count();
    }

    @Test
    void functionLibraryIsAReloadListenerAgain() throws IOException {
        final String resources = OwnedTeleportPatchShapeTestAccess.file(patch(),
            "net/minecraft/server/ReloadableServerResources.java");
        assertTrue(resources.contains("-        return List.of(this.recipes, this.advancements); // Canvas - region threading"));
        assertTrue(resources.contains("+        return List.of(this.recipes, this.functionLibrary, this.advancements);"));
    }

    @Test
    void functionAndReturnAreRegisteredButReloadIsNot() throws IOException {
        final String patch = patch();
        final String commands = OwnedTeleportPatchShapeTestAccess.file(patch, "net/minecraft/commands/Commands.java");
        assertEquals(1, added(commands, "        FunctionCommand.register(this.dispatcher);"));
        assertEquals(1, added(commands, "        ReturnCommand.register(this.dispatcher);"));
        assertEquals(0, added(patch, "ReloadCommand.register"), "/reload stays unregistered (no owned reload yet)");
        assertEquals(2, OwnedTeleportPatchShapeTestAccess.removed(commands), "only the two commented registrations");
    }

    @Test
    void lifecycleHooksArePublishAndOneGlobalTickCall() throws IOException {
        final String patch = patch();
        final String manager = OwnedTeleportPatchShapeTestAccess.file(patch, "net/minecraft/server/ServerFunctionManager.java");
        final int postReload = manager.indexOf("this.postReload = true;");
        final int publish = manager.indexOf("+        dev.iyanz.sourbycraft.world.DatapackFunctions.published();");
        assertTrue(postReload >= 0 && publish > postReload, "published from postReload: constructor (boot) and replaceLibrary");
        assertEquals(1, added(patch, "DatapackFunctions.published()"));

        final String server = OwnedTeleportPatchShapeTestAccess.file(patch, "io/papermc/paper/threadedregions/RegionizedServer.java");
        final int console = server.indexOf("minecraftServer.handleConsoleInputs();");
        final int hook = server.indexOf("dev.iyanz.sourbycraft.world.DatapackFunctions.globalTick(minecraftServer,");
        assertTrue(console >= 0 && hook > console, "global tick, after console input");
        assertEquals(1, added(patch, "DatapackFunctions.globalTick("));
        assertTrue(server.contains("getGlobalTickData().getTickManager().doesRunGameElements()"), "frozen tick defers tags");
    }

    @Test
    void theUnconditionalVanillaTickStaysUncalled() throws IOException {
        final String patch = patch();
        assertEquals(0, added(patch, "getFunctions().tick()"), "the manager's tick() would always run #minecraft:tick");
        assertFalse(patch.contains("diff --git a/net/minecraft/server/MinecraftServer.java"),
            "tickChildren runs per region and must not tick functions");
        assertFalse(patch.contains("minecraft-patches/sources"), "feature patch only");
    }
}
