package dev.iyanz.sourbycraft.world;

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
 * Pins the two call sites of minecraft feature patch 0056 in {@code RegionizedServer}: the owned saves run on
 * the global tick, inside the interval-gated blocks of {@code autosaveSafeGlobalData} /
 * {@code autosaveSafeWorldData} (so they share {@code autosave.autosaveFrequency} and the forced, synchronous
 * {@code save-all} path), and after the Canvas whitelist save so they never join before it is scheduled. It
 * proves nothing about behaviour; {@link WorldMetadataAutosaveTest} does that for the owned code.
 */
class WorldMetadataAutosavePatchShapeTest {

    private static String patch(final String nameFragment) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString().contains(nameFragment)).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch '*" + nameFragment + "*' not found from " + Path.of("").toAbsolutePath());
    }

    private static int at(final String text, final String needle) {
        final int index = text.indexOf(needle);
        assertTrue(index >= 0, "missing: " + needle);
        return index;
    }

    @Test
    void bothCallSitesSitInsideTheIntervalGatedBlocksAfterTheCanvasSave() throws IOException {
        final String patch = patch("-SourbyCraft-save-global-owned-level-data");
        at(patch, "+++ b/io/papermc/paper/threadedregions/RegionizedServer.java");

        final String global = "+            dev.iyanz.sourbycraft.world.WorldMetadataAutosave.saveServerOwnedData(server, sync);";
        final String world = "+            dev.iyanz.sourbycraft.world.WorldMetadataAutosave.saveWorldOwnedData(level.getDataStorage(), sync);";
        final int globalCall = at(patch, global);
        final int worldCall = at(patch, world);
        assertTrue(globalCall < worldCall, "autosaveSafeGlobalData comes first in the file");

        // Each call is added right before the block's `if (sync) { future.join(); }`, which only exists inside
        // `if (force || elapsed)`: the hunk context after the call must be the comment that introduces that join,
        // and before the call the Canvas saveSpecific supplier must have closed (`});`).
        final String joinComment = "             // if sync we need to just join here. this shouldn't be done";
        final int globalJoin = patch.indexOf(joinComment, globalCall);
        assertTrue(globalJoin > globalCall && globalJoin < worldCall, "global call precedes the global block's join");
        final int worldJoin = patch.indexOf(joinComment, worldCall);
        assertTrue(worldJoin > worldCall, "world call precedes the world block's join");
        assertTrue(patch.lastIndexOf("             });", globalCall) > patch.indexOf("@@", 0), "global call follows the Canvas saveSpecific");
        assertTrue(patch.lastIndexOf("             });", worldCall) > globalJoin, "world call follows its Canvas saveSpecific");

        // Only RegionizedServer changes, and only by adding lines: no baseline behaviour is removed.
        assertTrue(patch.lines().noneMatch(l -> l.startsWith("-") && !l.startsWith("---")), "the patch removes nothing");
        assertTrue(patch.lines().filter(l -> l.startsWith("diff --git")).count() == 1, "one file");
    }

    @Test
    void patch0027StillRoutesLevelOverridesThroughTheSharedWhitelist() throws IOException {
        final String patch = patch("-SourbyCraft-persist-world-metadata-on-autosave");
        at(patch, "+                dev.iyanz.sourbycraft.world.WorldMetadataAutosave.addGlobalOwnedTypes(toSave);");
    }
}
