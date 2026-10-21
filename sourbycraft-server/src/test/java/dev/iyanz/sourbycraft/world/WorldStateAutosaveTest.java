package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@AllFeatures
class WorldStateAutosaveTest {
    private static final SavedDataType<Value> TYPE = type("snapshot_probe");
    private static final SavedDataType<Value> OTHER = type("other_probe");

    private static SavedDataType<Value> type(String name) {
        return new SavedDataType<>(Identifier.fromNamespaceAndPath("sourbycraft", name), Value::new,
            CompoundTag.CODEC.xmap(Value::new, v -> v.tag), DataFixTypes.NONE);
    }

    private static final class Value extends SavedData {
        final CompoundTag tag;
        Value() { this(new CompoundTag()); }
        Value(CompoundTag tag) { this.tag = tag; }
    }

    @Test
    void snapshotIsIsolatedAndMutationAfterCaptureRemainsDirty(@TempDir Path dir) throws Exception {
        try (var storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess())) {
            Value live = new Value();
            live.tag.putInt("v", 7);
            live.setDirty();
            var item = new WorldStateAutosave.Item<>(TYPE, live, () -> {
                Value snapshot = new Value(live.tag.copy());
                live.tag.putInt("v", 9);
                live.setDirty();
                return snapshot;
            });
            WorldStateAutosave.saveItems(storage, List.of(item), true);
            assertEquals(7, NbtIo.readCompressed(dir.resolve("sourbycraft/snapshot_probe.dat"),
                NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data").getIntOr("v", -1));
            assertTrue(live.isDirty(), "a change after capture must survive for the next save");
        }
    }

    @Test
    void cleanItemDoesNotEvenTakeASnapshot(@TempDir Path dir) throws Exception {
        try (var storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess())) {
            WorldStateAutosave.saveItems(storage, List.of(new WorldStateAutosave.Item<>(TYPE, new Value(),
                () -> { fail("clean data must not be copied"); return null; })), true);
            assertFalse(Files.exists(dir.resolve("sourbycraft/snapshot_probe.dat")));
        }
    }

    @Test
    void failingSnapshotDoesNotSkipAnotherItem(@TempDir Path dir) throws Exception {
        try (var storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess())) {
            Value bad = new Value();
            Value good = new Value();
            bad.setDirty();
            good.setDirty();
            WorldStateAutosave.saveItems(storage, List.of(
                new WorldStateAutosave.Item<>(TYPE, bad, () -> { throw new IllegalStateException("copy failed"); }),
                new WorldStateAutosave.Item<>(OTHER, good, () -> new Value(good.tag.copy()))), true);
            assertTrue(bad.isDirty());
            assertFalse(good.isDirty());
            assertFalse(Files.exists(dir.resolve("sourbycraft/snapshot_probe.dat")));
            assertTrue(Files.exists(dir.resolve("sourbycraft/other_probe.dat")));
        }
    }

    @Test
    void failingEncodeDoesNotSkipAnotherItem(@TempDir Path dir) throws Exception {
        var broken = new SavedDataType<Value>(Identifier.fromNamespaceAndPath("sourbycraft", "broken"), Value::new,
            CompoundTag.CODEC.xmap(Value::new, value -> { throw new IllegalStateException("encode failed"); }), DataFixTypes.NONE);
        try (var storage = new SavedDataStorage(dir, DataFixers.getDataFixer(), RegistryHelper.registryAccess())) {
            Value bad = new Value();
            Value good = new Value();
            bad.setDirty();
            good.setDirty();
            WorldStateAutosave.saveItems(storage, List.of(
                new WorldStateAutosave.Item<>(broken, bad, () -> new Value()),
                new WorldStateAutosave.Item<>(OTHER, good, () -> new Value())), true);
            assertTrue(bad.isDirty());
            assertFalse(good.isDirty());
            assertTrue(Files.exists(dir.resolve("sourbycraft/other_probe.dat")));
        }
    }
}
