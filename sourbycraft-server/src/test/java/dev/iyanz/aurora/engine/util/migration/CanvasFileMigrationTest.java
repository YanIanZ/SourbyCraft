package dev.iyanz.aurora.engine.util.migration;

import io.papermc.paper.world.migration.WorldMigrationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine util/migration: {@link CanvasFileMigration#initMigration}, the per-level entry point
 * that decides which migrations to run. The per-migration logic is covered by
 * {@link PearlsMigrationTest}; this covers the decision (old data present and new data absent), that
 * it runs again harmlessly, and that a failure is loud.
 *
 * <p>The level's data folder comes from a mocked {@code LevelStorageAccess}, so the new
 * {@code canvas/pearls.dat} lives in a {@code @TempDir}. The legacy path is the fixed
 * {@code pearls.dat} in the working directory; the tests that would be disturbed by one being there are
 * skipped rather than touching it. {@code paper.disableMigrationDelay} is set so the 8 s
 * "interrupt now" pause is not served, and restored afterwards.</p>
 */
@ResourceLock("java.lang.System.properties")
class CanvasFileMigrationTest {

    private String previousDelayFlag;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void disableThePause() {
        this.previousDelayFlag = System.getProperty("paper.disableMigrationDelay");
        System.setProperty("paper.disableMigrationDelay", "true");
    }

    @AfterEach
    void restoreTheFlag() {
        if (this.previousDelayFlag == null) {
            System.clearProperty("paper.disableMigrationDelay");
        } else {
            System.setProperty("paper.disableMigrationDelay", this.previousDelayFlag);
        }
    }

    private static WorldMigrationContext context(final Path dataFolder) {
        final LevelStorageSource.LevelStorageAccess access = mock(LevelStorageSource.LevelStorageAccess.class);
        when(access.getLevelPath(any(LevelResource.class))).thenReturn(dataFolder);
        return new WorldMigrationContext(access, null, "world", null, null);
    }

    private static Path pearls(final Path dataFolder) {
        return dataFolder.resolve("canvas").resolve("pearls.dat");
    }

    private static CompoundTag legacy() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 4_400);
        tag.putString("dimension", "minecraft:overworld");
        return tag;
    }

    private static void requireNoLegacyFileInTheWorkingDirectory() {
        assumeFalse(Files.exists(PearlsMigration.OLD_PATH), PearlsMigration.OLD_PATH + " exists; leaving the operator's file alone");
    }

    @Test
    void aPreMergerPearlsFileAtTheNewPathIsReshapedAndASecondRunChangesNothing(@TempDir final Path data) throws IOException {
        requireNoLegacyFileInTheWorkingDirectory();
        final Path file = pearls(data);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(legacy(), file);
        assertTrue(PearlsMigration.needsMigration(file));

        CanvasFileMigration.initMigration(context(data));

        assertFalse(PearlsMigration.needsMigration(file));
        assertEquals(PearlsMigration.migratePearlsTag(legacy()), NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));

        final byte[] settled = Files.readAllBytes(file);
        CanvasFileMigration.initMigration(context(data));
        assertArrayEquals(settled, Files.readAllBytes(file), "the second run neither rewrites nor touches the migrated file");
    }

    @Test
    void aFileAlreadyInTheModernShapeIsNotRewritten(@TempDir final Path data) throws IOException {
        requireNoLegacyFileInTheWorkingDirectory();
        final Path file = pearls(data);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(PearlsMigration.migratePearlsTag(legacy()), file);
        final byte[] before = Files.readAllBytes(file);

        CanvasFileMigration.initMigration(context(data));

        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void aLevelWithNoPearlsDataCreatesNothing(@TempDir final Path data) {
        requireNoLegacyFileInTheWorkingDirectory();

        CanvasFileMigration.initMigration(context(data));

        assertFalse(Files.exists(pearls(data)));
        assertFalse(Files.exists(data.resolve("canvas")), "no directory is created when there is nothing to migrate");
    }

    @Test
    void anUnreadablePearlsFileStopsTheLevelLoadInsteadOfBeingSkipped(@TempDir final Path data) throws IOException {
        requireNoLegacyFileInTheWorkingDirectory();
        final Path file = pearls(data);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not gzip nbt");

        assertThrows(RuntimeException.class, () -> CanvasFileMigration.initMigration(context(data)));

        assertEquals("not gzip nbt", Files.readString(file), "the operator's file is left as it was");
    }
}
