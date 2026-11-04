package dev.iyanz.sourbycraft.api.world;

import org.bukkit.Difficulty;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Per-world settings an AWF world keeps with it and applies every time it loads — what
 * AdvancedSlimePaper calls a property map. A {@code null} field means "leave the server's default".
 *
 * @param spawn the spawn point, applied on every load
 * @param difficulty applied on every load
 * @param pvp applied on every load
 * @param allowMonsters natural monster spawning, applied on every load
 * @param allowAnimals natural animal spawning, applied on every load
 * @param defaultBiome the biome of a {@code void} world's chunks, as a key such as
 *     {@code minecraft:plains}; ignored for other generators; takes effect for chunks generated
 *     after the next load
 * @param saveBounds chunks outside these bounds are never stored (their changes are lost when
 *     they unload); chunks stored before the bounds were set keep their stored state
 * @param pruneEmptyChunks whether empty chunks nothing has stored are skipped; {@code null} means
 *     the default, which is on for {@code void} worlds and off otherwise. Turning it on for a world
 *     with terrain makes an emptied-out chunk come back as generated terrain
 */
@NullMarked
public record WorldProperties(@Nullable Spawn spawn, @Nullable Difficulty difficulty, @Nullable Boolean pvp,
                              @Nullable Boolean allowMonsters, @Nullable Boolean allowAnimals,
                              @Nullable String defaultBiome, @Nullable Bounds saveBounds,
                              @Nullable Boolean pruneEmptyChunks) {

    /** No overrides at all. */
    public static final WorldProperties NONE = new WorldProperties(null, null, null, null, null, null, null, null);

    /**
     * A spawn point inside the world: finite coordinates, {@code x} and {@code z} between -29999984
     * and 29999984 (half the largest world border), {@code y} from -2032 up to, but not including,
     * 2032 (the build limits a dimension type can have), and a finite yaw. It is applied on every
     * load, so a value outside the world would put every join and respawn there.
     *
     * @throws IllegalArgumentException if a coordinate or the yaw is outside those limits
     */
    public record Spawn(double x, double y, double z, float yaw) {
        private static final double MAX_HORIZONTAL = 29_999_984;
        private static final double MIN_Y = -2032;
        private static final double MAX_Y_EXCLUSIVE = 2032;

        public Spawn {
            if (!Double.isFinite(x) || Math.abs(x) > MAX_HORIZONTAL) {
                throw new IllegalArgumentException("spawn x must be a finite number from -29999984 to 29999984, not " + x);
            }
            if (!Double.isFinite(z) || Math.abs(z) > MAX_HORIZONTAL) {
                throw new IllegalArgumentException("spawn z must be a finite number from -29999984 to 29999984, not " + z);
            }
            if (!Double.isFinite(y) || y < MIN_Y || y >= MAX_Y_EXCLUSIVE) {
                throw new IllegalArgumentException("spawn y must be a finite number from -2032 to below 2032, not " + y);
            }
            if (!Float.isFinite(yaw)) {
                throw new IllegalArgumentException("spawn yaw must be a finite number, not " + yaw);
            }
        }
    }

    /** Inclusive chunk-coordinate bounds. */
    public record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        public Bounds {
            if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
                throw new IllegalArgumentException("bounds min must not exceed max");
            }
        }

        public boolean contains(final int chunkX, final int chunkZ) {
            return chunkX >= this.minChunkX && chunkX <= this.maxChunkX && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
        }
    }

    public WorldProperties withSpawn(final @Nullable Spawn value) {
        return new WorldProperties(value, this.difficulty, this.pvp, this.allowMonsters, this.allowAnimals,
            this.defaultBiome, this.saveBounds, this.pruneEmptyChunks);
    }

    public WorldProperties withDifficulty(final @Nullable Difficulty value) {
        return new WorldProperties(this.spawn, value, this.pvp, this.allowMonsters, this.allowAnimals,
            this.defaultBiome, this.saveBounds, this.pruneEmptyChunks);
    }

    public WorldProperties withPvp(final @Nullable Boolean value) {
        return new WorldProperties(this.spawn, this.difficulty, value, this.allowMonsters, this.allowAnimals,
            this.defaultBiome, this.saveBounds, this.pruneEmptyChunks);
    }

    public WorldProperties withSpawning(final @Nullable Boolean monsters, final @Nullable Boolean animals) {
        return new WorldProperties(this.spawn, this.difficulty, this.pvp, monsters, animals,
            this.defaultBiome, this.saveBounds, this.pruneEmptyChunks);
    }

    public WorldProperties withDefaultBiome(final @Nullable String value) {
        return new WorldProperties(this.spawn, this.difficulty, this.pvp, this.allowMonsters, this.allowAnimals,
            value, this.saveBounds, this.pruneEmptyChunks);
    }

    public WorldProperties withSaveBounds(final @Nullable Bounds value) {
        return new WorldProperties(this.spawn, this.difficulty, this.pvp, this.allowMonsters, this.allowAnimals,
            this.defaultBiome, value, this.pruneEmptyChunks);
    }

    public WorldProperties withPruneEmptyChunks(final @Nullable Boolean value) {
        return new WorldProperties(this.spawn, this.difficulty, this.pvp, this.allowMonsters, this.allowAnimals,
            this.defaultBiome, this.saveBounds, value);
    }
}
