package dev.iyanz.aurora.engine.util.migration;

import com.mojang.logging.LogUtils;
import io.papermc.paper.world.migration.WorldMigrationContext;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * Moves pearl data to {@code data/aurora/pearls.dat}, the file of {@code EnderPearls.TYPE} ({@code aurora:pearls}).
 *
 * <p>Three older places are read, each once: the pre-26.1 {@code pearls.dat} in the working directory, the
 * Canvas-era {@code data/canvas/pearls.dat} (saved data {@code canvas:pearls}, renamed 2026-10-21, owner decision),
 * and a pre-merger 26.2 shape (top-level keys not yet wrapped under {@code data}) at either location. No data is
 * deleted before the new file has been written; a Canvas-era file that cannot be moved because the new file already
 * exists is renamed to {@code pearls.dat.superseded}, never deleted.</p>
 */
public class PearlsMigration implements Migration {

    public static final Path OLD_PATH = Path.of("pearls.dat").toAbsolutePath().normalize();
    /** Directory under the level's {@code data/} folder, the namespace of {@code EnderPearls.TYPE}. */
    public static final String NAMESPACE = "aurora";
    /** The Canvas-era namespace, read once and moved. */
    public static final String LEGACY_NAMESPACE = "canvas";
    static final String FILE_NAME = "pearls.dat";
    static final String SUPERSEDED_SUFFIX = ".superseded";

    private static final Logger LOGGER = LogUtils.getClassLogger();

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

    /** {@code <data>/aurora/pearls.dat}. */
    public static Path newPath(final Path dataDir) {
        return dataDir.resolve(NAMESPACE).resolve(FILE_NAME);
    }

    /** {@code <data>/canvas/pearls.dat}. */
    public static Path canvasPath(final Path dataDir) {
        return dataDir.resolve(LEGACY_NAMESPACE).resolve(FILE_NAME);
    }

    @Override
    public void conduct(final WorldMigrationContext context) {
        conduct(dataDir(context));
    }

    /** {@link #conduct(WorldMigrationContext)} with the level's {@code data/} folder already resolved (the testable part). */
    void conduct(final Path dataDir) {
        final Path newPath = newPath(dataDir);
        final Path canvasPath = canvasPath(dataDir);
        try {
            // create directories first or else we throw
            Files.createDirectories(newPath.getParent());

            if (Files.exists(canvasPath)) {
                if (!Files.exists(newPath)) {
                    final CompoundTag data = NbtIo.readCompressed(canvasPath, NbtAccounter.unlimitedHeap());
                    writeAtomically(migratePearlsTag(data), newPath);
                    Files.delete(canvasPath);
                    deleteIfEmpty(canvasPath.getParent());
                    LOGGER.warn("Moved pearl data from {} (saved data canvas:pearls) to {} (aurora:pearls); the old name is "
                        + "read once and no longer written", canvasPath, newPath);
                } else {
                    final Path kept = canvasPath.resolveSibling(FILE_NAME + SUPERSEDED_SUFFIX);
                    Files.move(canvasPath, kept, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.warn("Both {} and {} exist; kept the new file and renamed the Canvas-era file to {} (not loaded, "
                        + "not deleted)", newPath, canvasPath, kept);
                }
            }

            if (Files.exists(newPath)) {
                migratePearlsFile(newPath);
            } else {
                final CompoundTag oldData = NbtIo.readCompressed(this.legacyPath, NbtAccounter.unlimitedHeap());
                writeAtomically(migratePearlsTag(oldData), newPath);
                Files.delete(this.legacyPath);
            }
        } catch (final IOException ioe) {
            throw new RuntimeException("Couldn't conduct pearl migration", ioe);
        }
    }

    private static void writeAtomically(final CompoundTag data, final Path target) throws IOException {
        final Path temp = target.resolveSibling(target.getFileName() + ".migrating");
        NbtIo.writeCompressed(data, temp);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target);
        }
    }

    private static void deleteIfEmpty(final Path dir) throws IOException {
        try (final Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        }
        Files.deleteIfExists(dir);
    }

    public static void migratePearlsFile(final Path path) throws IOException {
        final CompoundTag oldData = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        if (isModernPearlsTag(oldData)) {
            return;
        }
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
        return hasOldData(dataDir(context));
    }

    boolean hasOldData(final Path dataDir) {
        // pre-26.1 path, Canvas-era path, or pre-merger 26.2 data shape at the new path
        return Files.exists(this.legacyPath) || Files.exists(canvasPath(dataDir)) || needsMigration(newPath(dataDir));
    }

    @Override
    public boolean hasNewData(final WorldMigrationContext context) {
        return hasNewData(dataDir(context));
    }

    boolean hasNewData(final Path dataDir) {
        final Path newPath = newPath(dataDir);
        return Files.exists(newPath) && !needsMigration(newPath) && !Files.exists(canvasPath(dataDir));
    }

    private static Path dataDir(final WorldMigrationContext context) {
        return context.rootAccess().getLevelPath(LevelResource.DATA).toAbsolutePath().normalize();
    }
}
