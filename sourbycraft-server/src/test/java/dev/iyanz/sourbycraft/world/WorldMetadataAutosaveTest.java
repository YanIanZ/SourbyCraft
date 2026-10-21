package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.serialization.Codec;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.WorldData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seams the patched {@code RegionizedServer#autosaveSafeWorldData} / {@code autosaveSafeGlobalData} call
 * (feature patches 0027 and 0056; the periodic global-tick autosave and {@code save-all}). The storage tests
 * drive the real {@code SavedDataStorage#saveSpecific} filter and read the files back.
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

    // --- patch 0056: contained per-type saves on the global tick ---

    @Test
    void theContainedListsNameOnlyGlobalTickOwnedTypes() {
        assertEquals(List.of(WorldBorder.class), WorldMetadataAutosave.WORLD_CONTAINED_TYPES);
        assertEquals(2, WorldMetadataAutosave.SERVER_CONTAINED_TYPES.size(), "random sequences + command storage containers");
        assertTrue(WorldMetadataAutosave.SERVER_CONTAINED_TYPES.contains(RandomSequences.class));
        assertEquals("net.minecraft.world.level.storage.CommandStorage$Container",
            WorldMetadataAutosave.SERVER_CONTAINED_TYPES.get(1).getName(), "the private container class still resolves");
        for (final Class<? extends SavedData> type : WorldMetadataAutosave.WORLD_CONTAINED_TYPES) {
            assertFalse(Raids.class.isAssignableFrom(type), "raids are mutated on their regions; encoding them off-region is unsafe");
            assertFalse(EnderDragonFight.class.isAssignableFrom(type), "the dragon fight is saved by its region");
            assertFalse(PaperWorldPDC.class.isAssignableFrom(type), "world PDC is plugin-written from any thread");
        }
    }

    @Test
    void theWorldSaveWritesTheWorldBorderAndLeavesRaidsForShutdown(@TempDir final Path dir) throws IOException {
        final SavedDataStorage storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        final WorldBorder border = storage.computeIfAbsent(WorldBorder.TYPE);
        border.setSize(1234.0);
        assertTrue(border.isDirty());
        final Raids raids = storage.computeIfAbsent(Raids.TYPE);
        raids.setDirty();

        WorldMetadataAutosave.saveWorldOwnedData(storage, true);

        assertFalse(border.isDirty(), "the world border was saved");
        assertTrue(raids.isDirty(), "raids are left for their owner / the shutdown save");
        assertEquals(1234.0, readData(dir, "world_border.dat").getDoubleOr("size", -1.0));
        assertNull(find(dir, "raids.dat"), "raids were not written");
    }

    @Test
    void theServerSaveWritesRandomSequencesAndCommandStorageOnly(@TempDir final Path dir) throws IOException {
        final SavedDataStorage storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        final RandomSequences sequences = storage.computeIfAbsent(RandomSequences.TYPE);
        sequences.get(Identifier.fromNamespaceAndPath("sourby", "probe"), 7L).nextInt();
        assertTrue(sequences.isDirty());
        final CommandStorage commands = new CommandStorage(storage);
        final CompoundTag probe = new CompoundTag();
        probe.putInt("v", 42);
        commands.set(Identifier.fromNamespaceAndPath("sourby", "probe"), probe);
        final PaperLevelOverrides control = storage.computeIfAbsent(PaperLevelOverrides.TYPE);
        control.setGameTime(5L);
        assertTrue(control.isDirty());

        WorldMetadataAutosave.saveServerOwnedData(storage, true);

        assertFalse(sequences.isDirty(), "random sequences were saved");
        assertTrue(control.isDirty(), "a type outside the list is not touched");
        assertNotNull(find(dir, "random_sequences.dat"));
        assertNull(find(dir, "level_overrides.dat"));
        final SavedDataStorage reloaded = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        assertEquals(42, new CommandStorage(reloaded).get(Identifier.fromNamespaceAndPath("sourby", "probe")).getIntOr("v", -1));
    }

    @Test
    void aFailingEncodeKeepsItsTypeDirtyAndStillWritesTheOthers(@TempDir final Path dir) throws IOException {
        final SavedDataStorage storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess());
        final Boom boom = storage.computeIfAbsent(Boom.TYPE);
        boom.setDirty();
        final WorldBorder border = storage.computeIfAbsent(WorldBorder.TYPE);
        border.setSize(99.0);

        WorldMetadataAutosave.saveContained(storage, List.of(Boom.class, WorldBorder.class), true);

        assertTrue(boom.isDirty(), "the type that failed to encode is dirty again for the next autosave");
        assertFalse(border.isDirty(), "a failing type does not stop the others");
        assertEquals(99.0, readData(dir, "world_border.dat").getDoubleOr("size", -1.0));
        assertNull(find(dir, "boom.dat"));
    }

    @Test
    void levelDatIsWrittenThroughTheStorageAccessAndAFailureIsReported(@TempDir final Path dir) throws IOException {
        final LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(dir).createAccess("w");
        try {
            final CompoundTag data = new CompoundTag();
            data.putString("LevelName", "probe");
            final WorldData good = mock(WorldData.class);
            when(good.createTag(null)).thenReturn(data);

            assertTrue(WorldMetadataAutosave.saveLevelDat(access, good, null));
            final CompoundTag root = NbtIo.readCompressed(dir.resolve("w").resolve("level.dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            assertEquals("probe", root.getCompoundOrEmpty("Data").getStringOr("LevelName", ""));

            final WorldData bad = mock(WorldData.class);
            when(bad.createTag(null)).thenThrow(new IllegalStateException("torn"));
            assertFalse(WorldMetadataAutosave.saveLevelDat(access, bad, null), "a failed encode is reported, not thrown");
            assertTrue(Files.exists(dir.resolve("w").resolve("level.dat")), "the previous level.dat is untouched");
        } finally {
            access.close();
        }
    }

    /** A saved data whose codec refuses to encode, for the containment test. */
    static final class Boom extends SavedData {
        static final Codec<Boom> CODEC = Codec.BOOL.xmap(ignored -> new Boom(), boom -> { throw new IllegalStateException("boom"); });
        static final SavedDataType<Boom> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("sourby", "boom"), Boom::new, CODEC, DataFixTypes.SAVED_DATA_WORLD_BORDER);
    }

    private static Path find(final Path dir, final String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        }
    }

    private static CompoundTag readData(final Path dir, final String fileName) throws IOException {
        final Path file = find(dir, fileName);
        assertNotNull(file, fileName + " was written under " + dir);
        return NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
    }
}
