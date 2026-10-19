package dev.iyanz.aurora.engine.util.migration;

import io.papermc.paper.world.migration.WorldMigrationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;

public class PearlsMigration implements Migration {

    public static final Path OLD_PATH = Path.of("pearls.dat").toAbsolutePath().normalize();

    private final Path legacyPath;

    public PearlsMigration() {
        this(OLD_PATH);
    }

    /**
     * Test seam: a migration whose pre-26.1 file is {@code legacyPath} instead of {@code pearls.dat}
     * in the working directory. Production always uses {@link #OLD_PATH} through the public
     * constructor.
     */
    PearlsMigration(final Path legacyPath) {
        this.legacyPath = legacyPath;
    }

    @Override
    public void conduct(final WorldMigrationContext context) {
        conduct(resolveNewPath(context));
    }

    /** {@link #conduct(WorldMigrationContext)} with the new file already resolved (the testable part). */
    void conduct(final Path newPath) {
        try {
            // create directories first or else we throw
            Files.createDirectories(newPath.getParent());

            if (Files.exists(newPath)) {
                migratePearlsFile(newPath);
            } else {
                final CompoundTag oldData = NbtIo.readCompressed(this.legacyPath, NbtAccounter.unlimitedHeap());
                NbtIo.writeCompressed(migratePearlsTag(oldData), newPath);
                Files.delete(this.legacyPath);
            }
        } catch (final IOException ioe) {
            throw new RuntimeException("Couldn't conduct pearl migration", ioe);
        }
    }

    public static void migratePearlsFile(final Path path) throws IOException {
        final CompoundTag oldData = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        NbtIo.writeCompressed(migratePearlsTag(oldData), path);
    }

    public static CompoundTag migratePearlsTag(final CompoundTag oldData) {
        if (isModernPearlsTag(oldData)) {
            return oldData;
        }

        final CompoundTag wrappedData = new CompoundTag();
        for (final String key : new ArrayList<>(oldData.keySet())) {
            if (key.equals("DataVersion")) {
                continue;
            }

            final Tag value = oldData.remove(key);
            if (value != null) {
                wrappedData.put(key, value);
            }
        }

        oldData.put("data", wrappedData);
        return oldData;
    }

    public static boolean isModernPearlsTag(final CompoundTag tag) {
        return tag.get("data") instanceof CompoundTag;
    }

    public static boolean needsMigration(final Path path) {
        if (!Files.exists(path)) {
            return false;
        }

        try {
            return !isModernPearlsTag(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
        } catch (final IOException ioe) {
            throw new RuntimeException("Couldn't read pearl data for migration", ioe);
        }
    }

    @Override
    public boolean hasOldData(final WorldMigrationContext context) {
        return hasOldData(resolveNewPath(context));
    }

    boolean hasOldData(final Path newPath) {
        return Files.exists(this.legacyPath) || needsMigration(newPath); // pre-26.1 path or pre-merger 26.2 data shape
    }

    @Override
    public boolean hasNewData(final WorldMigrationContext context) {
        return hasNewData(resolveNewPath(context));
    }

    boolean hasNewData(final Path newPath) {
        return Files.exists(newPath) && !needsMigration(newPath);
    }

    private static Path resolveNewPath(final WorldMigrationContext context) {
        return context.rootAccess().getLevelPath(LevelResource.DATA).resolve("canvas/pearls.dat").toAbsolutePath().normalize();
    }
}
