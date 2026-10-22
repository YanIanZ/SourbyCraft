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

    /** The single biome of every chunk, or null for the dimension's own biome source. */
    private final org.bukkit.block.Biome biome;

    public VoidGenerator() {
        this(null);
    }

    /** @param biomeKey a biome key such as {@code minecraft:plains}, or null */
    public VoidGenerator(final String biomeKey) {
        org.bukkit.block.Biome found = null;
        if (biomeKey != null && !biomeKey.isBlank()) {
            final org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(biomeKey.toLowerCase(java.util.Locale.ROOT));
            if (key == null) throw new IllegalArgumentException("not a biome key: " + biomeKey);
            found = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(key);
            if (found == null) throw new IllegalArgumentException("unknown biome " + biomeKey);
        }
        this.biome = found;
    }

    @Override
    public org.bukkit.generator.BiomeProvider getDefaultBiomeProvider(final org.bukkit.generator.WorldInfo worldInfo) {
        if (this.biome == null) return null;
        final org.bukkit.block.Biome single = this.biome;
        return new org.bukkit.generator.BiomeProvider() {
            @Override
            public org.bukkit.block.Biome getBiome(final org.bukkit.generator.WorldInfo info, final int x, final int y, final int z) {
                return single;
            }

            @Override
            public java.util.List<org.bukkit.block.Biome> getBiomes(final org.bukkit.generator.WorldInfo info) {
                return java.util.List.of(single);
            }
        };
    }

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
