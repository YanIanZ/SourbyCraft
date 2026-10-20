package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature patch 0050: {@code RegionFileStorage.moonrise$readData} times the region-file stage of a
 * chunk-system load into {@link RegionFileLoadLatency#GLOBAL}: a read that found data is one load, a
 * missing chunk is none, a read that throws is a failure and the exception is unchanged. Drives the
 * real patched storage over a temporary region folder (not attached to Aurora World Fabric).
 */
@org.bukkit.support.environment.VanillaFeature
class RegionFileLoadLatencyHookTest {

    @TempDir
    Path folder;

    private String world;
    private RegionFileStorage storage;

    @BeforeEach
    void open() throws IOException {
        // A world name no other test uses, so GLOBAL's recorder for it starts empty.
        this.world = "load-hook-test-" + UUID.randomUUID();
        Files.createDirectories(this.folder);
        this.storage = new RegionFileStorage(new RegionStorageInfo(this.world, Level.OVERWORLD, "chunk"), this.folder, false);
    }

    @AfterEach
    void close() throws IOException {
        this.storage.close();
    }

    private Optional<RegionFileSaveLatency.Snapshot> recorded() {
        return RegionFileLoadLatency.GLOBAL.snapshot().stream().filter(s -> s.world().equals(this.world)).findFirst();
    }

    @Test
    void aReadThatFindsTheChunkIsOneLoad() throws IOException {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("marker", 7);
        this.storage.write(new ChunkPos(3, 4), tag);

        final ReadData read = this.storage.moonrise$readData(3, 4);

        assertEquals(ReadData.ReadResult.HAS_DATA, read.result());
        final var s = recorded().orElseThrow(() -> new AssertionError("no load recorded"));
        assertEquals("minecraft:overworld", s.dimension());
        assertEquals("chunk", s.type());
        assertEquals(1, s.saves(), "saves() counts loads in the load recorder");
        assertEquals(0, s.failures());
        assertEquals(1, s.windowSamples());
        assertTrue(RegionFileSaveLatency.GLOBAL.snapshot().stream().noneMatch(x -> x.world().equals(this.world)
            && x.saves() > 1), "a load is not recorded as a save");
    }

    @Test
    void aMissingChunkIsNotALoad() throws IOException {
        assertEquals(ReadData.ReadResult.NO_DATA, this.storage.moonrise$readData(10, 10).result(), "no region file");
        this.storage.write(new ChunkPos(0, 0), new CompoundTag());
        assertEquals(ReadData.ReadResult.NO_DATA, this.storage.moonrise$readData(1, 1).result(), "region file, no chunk");

        assertTrue(recorded().isEmpty(), "nothing recorded for chunks that do not exist");
    }

    @Test
    void aReadThatThrowsIsAFailureAndTheExceptionPropagates() throws IOException {
        // A directory where the .mca should be: opening the region file throws.
        Files.createDirectories(this.folder.resolve("r.0.0.mca"));

        assertThrows(IOException.class, () -> this.storage.moonrise$readData(0, 0));

        final var s = recorded().orElseThrow(() -> new AssertionError("the failure was not recorded"));
        assertEquals(1, s.saves());
        assertEquals(1, s.failures());
    }
}
