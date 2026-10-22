package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature patch 0043: {@code RegionFileStorage.moonrise$finishWrite} times the region-file stage of
 * every chunk-system save into {@link RegionFileSaveLatency#GLOBAL}, records a save that throws as a
 * failure, and lets the exception out unchanged. Drives the real, patched {@code RegionFileStorage}
 * over a temporary region folder (not attached to Aurora World Fabric).
 */
@org.bukkit.support.environment.VanillaFeature
class RegionFileSaveLatencyHookTest {

    @TempDir
    Path folder;

    private String world;
    private RegionFileStorage storage;

    @BeforeEach
    void open() throws IOException {
        // A world name no other test uses, so GLOBAL's recorder for it starts empty.
        this.world = "hook-test-" + UUID.randomUUID();
        Files.createDirectories(this.folder);
        this.storage = new RegionFileStorage(new RegionStorageInfo(this.world, Level.OVERWORLD, "chunk"), this.folder, false);
    }

    @AfterEach
    void close() throws IOException {
        this.storage.close();
    }

    private RegionFileSaveLatency.Snapshot recorded() {
        return RegionFileSaveLatency.GLOBAL.snapshot().stream()
            .filter(s -> s.world().equals(this.world))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no save recorded for " + this.world));
    }

    private static WriteData write(final WriteData.WriteResult result, final MoonriseIo io) {
        return new WriteData(new CompoundTag(), result, null, io::run);
    }

    /** {@code RegionDataController.IORunnable} without naming the region file type in each lambda. */
    @FunctionalInterface
    private interface MoonriseIo {
        void run(net.minecraft.world.level.chunk.storage.RegionFile regionFile) throws IOException;
    }

    @Test
    void aSuccessfulSaveIsTimedAgainstTheRegionFile() throws IOException {
        final boolean[] ran = {false};
        this.storage.moonrise$finishWrite(3, 4, write(WriteData.WriteResult.WRITE, regionFile -> {
            assertTrue(regionFile != null, "the region-file branch hands the write its .mca");
            ran[0] = true;
        }));

        assertTrue(ran[0]);
        final var s = recorded();
        assertEquals("minecraft:overworld", s.dimension());
        assertEquals("chunk", s.type());
        assertEquals(1, s.saves());
        assertEquals(0, s.failures());
        assertEquals(1, s.windowSamples());
        assertTrue(Files.exists(this.folder.resolve("r.0.0.mca")), "the save really reached a region file");
    }

    @Test
    void aSaveThatThrowsIsRecordedAsAFailureAndTheSameExceptionPropagates() {
        final IOException boom = new IOException("disk full");
        final IOException thrown = assertThrows(IOException.class, () -> this.storage.moonrise$finishWrite(0, 0,
            write(WriteData.WriteResult.WRITE, regionFile -> {
                throw boom;
            })));
        assertSame(boom, thrown, "the hook neither wraps nor replaces the exception");

        final IllegalStateException unchecked = new IllegalStateException("bug");
        assertSame(unchecked, assertThrows(IllegalStateException.class, () -> this.storage.moonrise$finishWrite(0, 0,
            write(WriteData.WriteResult.WRITE, regionFile -> {
                throw unchecked;
            }))), "unchecked exceptions too");

        final var s = recorded();
        assertEquals(2, s.saves());
        assertEquals(2, s.failures());
        assertEquals(2, s.windowSamples(), "failed saves are timed as well");
    }

    @Test
    void aDeleteIsTimedEvenWhenNoRegionFileExists() throws IOException {
        this.storage.moonrise$finishWrite(64, 64, write(WriteData.WriteResult.DELETE, regionFile -> {
            throw new AssertionError("a delete never runs the write");
        }));
        final var s = recorded();
        assertEquals(1, s.saves());
        assertEquals(0, s.failures());
    }
}
