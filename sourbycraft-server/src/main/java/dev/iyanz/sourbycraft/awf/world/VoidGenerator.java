package dev.iyanz.sourbycraft.awf.world;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/**
 * An empty world: no terrain, caves, decoration, structures or natural mobs. Selected with the
 * generator name {@code void}; the usual base for islands, lobbies and arenas built from
 * templates. Players spawn at 0.5, 64, 0.5.
 */
public final class VoidGenerator extends ChunkGenerator {

    public static final String NAME = "void";

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final Random random) {
        return new Location(world, 0.5, 64, 0.5);
    }
}
