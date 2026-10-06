package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seam the patched {@code RegionizedServer#autosaveSafeWorldData} calls (used by the periodic global-tick
 * autosave and by {@code save-all}). The second test drives the real {@code SavedDataStorage#saveSpecific} filter.
 */
@AllFeatures
class WorldMetadataAutosaveTest {

    @Test
    void theWhitelistAddsLevelOverridesAndNoRegionOwnedData() {
        final List<Class<? extends SavedData>> toSave = new ArrayList<>();
        WorldMetadataAutosave.addGlobalOwnedTypes(toSave);

        assertEquals(List.of(PaperLevelOverrides.class), toSave);
        for (final Class<? extends SavedData> type : toSave) {
            assertFalse(WanderingTraderData.class.isAssignableFrom(type), "wandering trader data is region-owned");
            assertFalse(EnderDragonFight.class.isAssignableFrom(type), "the dragon fight is saved by its region");
            assertFalse(PaperWorldPDC.class.isAssignableFrom(type), "world PDC is plugin-written from any thread");
        }
    }

    @Test
    void saveSpecificWritesGameTimeButNotDirtyRegionOwnedData(@TempDir final Path dir) {
        final SavedDataStorage storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        final PaperLevelOverrides overrides = storage.computeIfAbsent(PaperLevelOverrides.TYPE);
        overrides.setGameTime(1_776_107L);
        final WanderingTraderData trader = storage.computeIfAbsent(WanderingTraderData.TYPE);
        trader.setSpawnDelay(1234);
        assertTrue(trader.isDirty());

        storage.saveSpecific(() -> {
            final List<Class<? extends SavedData>> toSave = new ArrayList<>();
            WorldMetadataAutosave.addGlobalOwnedTypes(toSave);
            return toSave;
        }).join();

        assertFalse(overrides.isDirty(), "level overrides were saved");
        assertTrue(trader.isDirty(), "region-owned data is left for its owner");

        final SavedDataStorage reloaded = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        final PaperLevelOverrides read = reloaded.get(PaperLevelOverrides.TYPE);
        assertNotNull(read, "paper:level_overrides was written");
        assertEquals(1_776_107L, read.getGameTime());
        assertNull(reloaded.get(WanderingTraderData.TYPE), "wandering trader data was not written");
    }
}
