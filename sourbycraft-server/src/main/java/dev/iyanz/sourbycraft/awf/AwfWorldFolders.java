package dev.iyanz.sourbycraft.awf;

import java.nio.file.Path;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where a loaded world's AWF storages live: its dimension folder.
 *
 * <p>On 26.2 every dimension, the overworld included, stores its {@code region}, {@code entities}
 * and {@code poi} folders under {@code <level>/dimensions/<namespace>/<name>/}. A world name is
 * therefore not a folder: {@code world} is the level folder that holds every dimension, and a
 * managed world {@code w2a} has no folder of its own beside it. This maps a Bukkit world name to
 * the dimension that world is, and that dimension to its folder, with the function the server's
 * storage source uses ({@code LevelStorageAccess.getDimensionPath}).</p>
 */
public final class AwfWorldFolders {

    private AwfWorldFolders() {}

    /**
     * The storage folder of one dimension of a level: the same path as vanilla's
     * {@code DimensionType.getStorageFolder(dimension, levelDirectory)}, which
     * {@link #loaded} reaches through the storage source. Built from the identifier here because
     * {@code DimensionType}'s class init needs a bootstrapped registry, which unit tests lack.
     *
     * @param levelDirectory the level folder ({@code world})
     * @param dimension      the dimension key ({@code minecraft:overworld}, {@code minecraft:w2a})
     * @return {@code <level>/dimensions/<namespace>/<path>}, absolute and normalized
     */
    public static Path dimensionFolder(final Path levelDirectory, final ResourceKey<Level> dimension) {
        return dimension.identifier().resolveAgainst(levelDirectory.resolve("dimensions")).toAbsolutePath().normalize();
    }

    /**
     * The dimension folder of the loaded Bukkit world {@code name} ({@code world} is the overworld,
     * {@code world_nether} the nether, a managed world its own dimension), or {@code null} when no
     * world of that name is loaded.
     */
    public static Path loaded(final String name) {
        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(name);
        if (!(world instanceof org.bukkit.craftbukkit.CraftWorld craft)) return null;
        return net.minecraft.server.MinecraftServer.getServer().storageSource
            .getDimensionPath(craft.getHandle().dimension()).toAbsolutePath().normalize();
    }
}
