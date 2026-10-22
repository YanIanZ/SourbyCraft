package dev.iyanz.sourbycraft.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins the SourbyCraft-owned wording of the region scheduler start line (Minecraft feature patch
 * 0075, Build 48 gate "Startup log boot -> Done clean"): the engine baseline's
 * {@code Initialised EDF Folia scheduler ...} line is replaced, text only, with the scheduler type
 * and thread count arguments kept in the same order.
 */
class RegionSchedulerLogWordingTest {

    @Test
    void theSchedulerStartLineNamesAuroraNotFolia() throws IOException {
        final String patch = OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "-SourbyCraft-Aurora-wording-for-the-region-scheduler-start-line");
        final String regions = OwnedTeleportPatchShapeTestAccess.file(patch, "io/papermc/paper/threadedregions/TickRegions.java");
        OwnedTeleportPatchShapeTestAccess.at(regions,
            "-        LOGGER.info(\"Initialised {} Folia scheduler with initial {} target thread(s)\", config.scheduler, tickThreads);");
        final int added = OwnedTeleportPatchShapeTestAccess.at(regions,
            "+        LOGGER.info(\"Initialised Aurora region scheduler ({}) with initial {} target thread(s)\", config.scheduler, tickThreads);");
        assertTrue(added > 0);
        assertEquals(1, OwnedTeleportPatchShapeTestAccess.removed(regions), "one line replaced, nothing else");
        for (final String line : regions.split("\n")) {
            if (line.startsWith("+")) assertFalse(line.contains("Folia"), line);
        }
    }
}
