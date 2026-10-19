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
 * <p>{@code conduct}/{@code hasOldData}/{@code hasNewData} resolve the legacy file as
 * {@code pearls.dat} in the process working directory ({@code PearlsMigration.OLD_PATH}) and need a
 * {@code WorldMigrationContext}; only the static tag/file helpers are covered. A seam taking the
 * legacy path would make the full move testable on a temp dir.</p>
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
