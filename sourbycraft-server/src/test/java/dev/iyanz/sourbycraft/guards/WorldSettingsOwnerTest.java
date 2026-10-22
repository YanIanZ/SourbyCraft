package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * World settings ({@code CraftWorld} time, weather, game rules, hardcore, spawn limits, ticks-per-spawn) are owned by
 * the startup thread until the region scheduler starts, then by the global tick only (paper 0025).
 */
class WorldSettingsOwnerTest {

    @Test
    void startupThreadOwnsTheSettingsBeforeTheSchedulerStarts() {
        assertEquals(WorldSettingsOwner.Decision.ALLOWED, WorldSettingsOwner.decide(false, false, true));
    }

    @Test
    void anyOtherThreadIsRefusedBeforeTheSchedulerStarts() {
        assertEquals(WorldSettingsOwner.Decision.REFUSED, WorldSettingsOwner.decide(false, false, false));
    }

    @Test
    void afterTheSchedulerStartedTheGlobalTickRuleIsUnchangedEvenForTheFormerStartupThread() {
        assertEquals(WorldSettingsOwner.Decision.GLOBAL_TICK_REQUIRED, WorldSettingsOwner.decide(false, true, true));
        assertEquals(WorldSettingsOwner.Decision.GLOBAL_TICK_REQUIRED, WorldSettingsOwner.decide(false, true, false));
    }

    @Test
    void theShutdownThreadOwnsEverything() {
        assertEquals(WorldSettingsOwner.Decision.ALLOWED, WorldSettingsOwner.decide(true, true, false));
        assertEquals(WorldSettingsOwner.Decision.ALLOWED, WorldSettingsOwner.decide(true, false, false));
    }

    @Test
    void onlyTheWorldSettingSettersMoveAndEachKeepsItsMessage() throws IOException {
        final String patch = OwnedTeleportPatchShapeTestAccess.patch("paper-patches/features",
            "-SourbyCraft-world-settings-may-be-changed-on-the-startup-thread-before-regions-tick");
        final String world = OwnedTeleportPatchShapeTestAccess.file(patch, "src/main/java/org/bukkit/craftbukkit/CraftWorld.java");
        final Pattern removed = Pattern.compile("(?m)^-\\s+io\\.papermc\\.paper\\.threadedregions\\.RegionizedServer\\.ensureGlobalTickThread\\((\".*?\")\\);");
        final Pattern added = Pattern.compile("(?m)^\\+\\s+dev\\.iyanz\\.sourbycraft\\.guards\\.WorldSettingsOwner\\.ensure\\((\".*?\")\\);");
        final List<String> was = all(removed.matcher(world));
        final List<String> now = all(added.matcher(world));
        assertEquals(12, was.size(), "time x2, weather x5, hardcore, ticks-per-spawn, spawn limit, game rule x2");
        assertEquals(was, now, "same sites, same messages, same order");
        assertEquals(12, OwnedTeleportPatchShapeTestAccess.removed(world), "nothing else removed");
        for (final String method : List.of("setTime(", "setFullTime(", "setStorm(", "setWeatherDuration(", "setThundering(",
            "setThunderDuration(", "setClearWeatherDuration(", "setHardcore(", "setTicksPerSpawns(", "setSpawnLimit(",
            "setGameRuleValue(", "setGameRule(")) {
            assertTrue(world.contains(method), method);
        }
        for (final String untouched : List.of("ChunkForceLoaded", "getForceLoadedChunks", "Metadata")) {
            assertTrue(!world.contains(untouched), untouched + " keeps the global-tick-only guard");
        }
    }

    private static List<String> all(final Matcher matcher) {
        return matcher.results().map(result -> result.group(1)).toList();
    }
}
