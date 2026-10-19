package dev.iyanz.aurora.engine.util.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util/migration: the pearl-data shape migration (pre-merger 26.2 top-level keys
 * wrapped under {@code data}). Must be idempotent and lossless, since it rewrites a world file.
 *
 * <p>{@code conduct}/{@code hasOldData}/{@code hasNewData} are tested through the package-private
 * overloads that take the resolved new file and the {@code PearlsMigration(Path legacyPath)} test
 * constructor, so the whole move runs on a {@code @TempDir}. Resolving the new file from a
 * {@code WorldMigrationContext} ({@code resolveNewPath}) needs a real {@code LevelStorageAccess}
 * and is not covered.</p>
 */
class PearlsMigrationTest {

    @Test
    void modernShapeIsRecognisedOnlyWhenDataIsACompound() {
        final CompoundTag modern = new CompoundTag();
        modern.put("data", new CompoundTag());
        final CompoundTag wrongType = new CompoundTag();
        wrongType.put("data", IntTag.valueOf(5));

        assertTrue(PearlsMigration.isModernPearlsTag(modern));
        assertFalse(PearlsMigration.isModernPearlsTag(wrongType));
        assertFalse(PearlsMigration.isModernPearlsTag(new CompoundTag()));
    }

    @Test
    void legacyKeysAreWrappedUnderDataAndDataVersionStaysOnTop() {
        final CompoundTag legacy = legacy();
        final CompoundTag expectedData = legacy.copy();
        expectedData.remove("DataVersion");

        final CompoundTag migrated = PearlsMigration.migratePearlsTag(legacy);

        assertSame(legacy, migrated, "migrated in place");
        assertEquals(Set.of("DataVersion", "data"), migrated.keySet());
        assertEquals(IntTag.valueOf(4_400), migrated.get("DataVersion"));
        assertEquals(expectedData, migrated.get("data"));
    }

    @Test
    void aLegacyKeyNamedDataOfTheWrongTypeIsPreservedInsideTheWrapper() {
        final CompoundTag legacy = new CompoundTag();
        legacy.put("data", IntTag.valueOf(5));
        legacy.put("other", StringTag.valueOf("x"));

        final CompoundTag migrated = PearlsMigration.migratePearlsTag(legacy);

        final CompoundTag data = (CompoundTag) migrated.get("data");
        assertEquals(IntTag.valueOf(5), data.get("data"));
        assertEquals(StringTag.valueOf("x"), data.get("other"));
        assertEquals(Set.of("data"), migrated.keySet());
    }

    @Test
    void migrationIsIdempotent() {
        final CompoundTag once = PearlsMigration.migratePearlsTag(legacy());
        final CompoundTag snapshot = once.copy();

        assertSame(once, PearlsMigration.migratePearlsTag(once));
        assertEquals(snapshot, once);
    }

    @Test
    void fileMigrationRewritesLegacyDataOnceAndThenReportsNothingToDo(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("canvas").resolve("pearls.dat");
        assertFalse(PearlsMigration.needsMigration(file), "absent file needs nothing");

        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(legacy(), file);
        assertTrue(PearlsMigration.needsMigration(file));

        PearlsMigration.migratePearlsFile(file);

        assertFalse(PearlsMigration.needsMigration(file));
        final CompoundTag expected = PearlsMigration.migratePearlsTag(legacy());
        assertEquals(expected, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));

        PearlsMigration.migratePearlsFile(file);
        assertEquals(expected, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), "second run changes nothing");
    }

    @Test
    void unreadableFileFailsLoudlyInsteadOfBeingTreatedAsMigrated(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("pearls.dat"), "not gzip nbt");
        assertThrows(RuntimeException.class, () -> PearlsMigration.needsMigration(file));
    }

    @Test
    void conductMovesTheLegacyFileIntoTheNewLocationWrappedAndDeletesTheOldOne(@TempDir final Path dir) throws IOException {
        final Path legacyFile = dir.resolve("pearls.dat");
        final Path newFile = dir.resolve("world").resolve("data").resolve("canvas").resolve("pearls.dat");
        NbtIo.writeCompressed(legacy(), legacyFile);
        final PearlsMigration migration = new PearlsMigration(legacyFile);

        assertTrue(migration.hasOldData(newFile));
        assertFalse(migration.hasNewData(newFile));

        migration.conduct(newFile);   // creates the missing parent directories

        assertFalse(Files.exists(legacyFile), "legacy file removed after a successful move");
        assertEquals(PearlsMigration.migratePearlsTag(legacy()), NbtIo.readCompressed(newFile, NbtAccounter.unlimitedHeap()));
        assertFalse(migration.hasOldData(newFile), "nothing left to migrate");
        assertTrue(migration.hasNewData(newFile));
    }

    @Test
    void conductReshapesAnExistingNewFileInPlaceAndLeavesTheLegacyFileAlone(@TempDir final Path dir) throws IOException {
        final Path legacyFile = dir.resolve("pearls.dat");
        final Path newFile = dir.resolve("data").resolve("canvas").resolve("pearls.dat");
        Files.createDirectories(newFile.getParent());
        NbtIo.writeCompressed(legacy(), newFile);          // pre-merger 26.2 shape already at the new path
        NbtIo.writeCompressed(legacy(), legacyFile);
        final PearlsMigration migration = new PearlsMigration(legacyFile);

        assertTrue(migration.hasOldData(newFile));
        assertFalse(migration.hasNewData(newFile), "a legacy-shaped file at the new path is not new data");

        migration.conduct(newFile);

        assertTrue(Files.exists(legacyFile), "the new-path branch never touches the legacy file");
        assertEquals(PearlsMigration.migratePearlsTag(legacy()), NbtIo.readCompressed(newFile, NbtAccounter.unlimitedHeap()));
        assertTrue(migration.hasNewData(newFile));
    }

    @Test
    void hasOldDataIsFalseWhenNeitherLocationHoldsLegacyData(@TempDir final Path dir) {
        final PearlsMigration migration = new PearlsMigration(dir.resolve("pearls.dat"));
        final Path newFile = dir.resolve("data").resolve("canvas").resolve("pearls.dat");

        assertFalse(migration.hasOldData(newFile));
        assertFalse(migration.hasNewData(newFile));
    }

    @Test
    void conductFailsLoudlyAndLeavesNothingBehindWhenThereIsNoSourceData(@TempDir final Path dir) {
        final Path newFile = dir.resolve("data").resolve("canvas").resolve("pearls.dat");
        final PearlsMigration migration = new PearlsMigration(dir.resolve("missing-pearls.dat"));

        final RuntimeException failure = assertThrows(RuntimeException.class, () -> migration.conduct(newFile));

        assertEquals("Couldn't conduct pearl migration", failure.getMessage());
        assertTrue(failure.getCause() instanceof IOException);
        assertFalse(Files.exists(newFile));
    }

    @Test
    void conductDoesNotDeleteTheLegacyFileWhenItCannotBeParsed(@TempDir final Path dir) throws IOException {
        final Path legacyFile = Files.writeString(dir.resolve("pearls.dat"), "not gzip nbt");
        final Path newFile = dir.resolve("data").resolve("canvas").resolve("pearls.dat");
        final PearlsMigration migration = new PearlsMigration(legacyFile);

        assertThrows(RuntimeException.class, () -> migration.conduct(newFile));

        assertTrue(Files.exists(legacyFile), "operator data survives a failed migration");
        assertFalse(Files.exists(newFile));
    }

    @Test
    void publicConstructorKeepsThePre26Point1WorkingDirectoryPath() {
        assertEquals(java.nio.file.Path.of("pearls.dat").toAbsolutePath().normalize(), PearlsMigration.OLD_PATH);
    }

    private static CompoundTag legacy() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 4_400);
        final ListTag pearls = new ListTag();
        final CompoundTag pearl = new CompoundTag();
        pearl.putString("owner", "00000000-0000-0000-0000-000000000001");
        pearl.putInt("x", 12);
        pearls.add(pearl);
        tag.put("pearls", pearls);
        tag.putString("dimension", "minecraft:overworld");
        return tag;
    }
}
