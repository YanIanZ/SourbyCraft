package dev.yanianz.intave.integration;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.jspecify.annotations.Nullable;

/**
 * Read-only block view for Intave checks that run on Netty/async threads.
 *
 * <p>Owner-authorised policy (2026-10-07): reads are a TOLERATED RACY PALETTE READ of chunk
 * sections that are already loaded, the same class of access Paper's own
 * {@code Level#getBlockStateIfLoaded} offers to async callers. It is not a lift of region
 * ownership: this view never writes, never touches entities or block entities, and never loads,
 * generates, tickets or schedules a chunk. Lookups use only
 * {@code ServerChunkCache#getChunkAtIfLoadedImmediately} (the concurrent full-chunk map) followed by
 * {@code LevelChunk#getBlockState}; Paper's tree-capture branch is skipped because it consults
 * region-local world data. A chunk that is not loaded reads as "unknown", mapped to AIR like
 * upstream Intave does when a chunk is absent (type AIR, variant 0, empty collision shape).
 */
public final class NativeBlockView implements BlockGetter {
    /** Never loads: both methods only look up already loaded chunks. */
    public interface LoadedStates {
        boolean isChunkLoaded(int chunkX, int chunkZ);
        /** The loaded state, or null when the chunk is not loaded. */
        @Nullable BlockState stateIfLoaded(BlockPos pos);
    }

    private final LoadedStates states;
    private final int minY;
    private final int height;

    public NativeBlockView(World world) {
        Objects.requireNonNull(world, "world");
        if (world instanceof CraftWorld craft) {
            ServerLevel level = craft.getHandle();
            this.states = loadedStatesOf(level);
            this.minY = level.getMinY();
            this.height = level.getHeight();
        } else {
            // Test seam only: every production World is a CraftWorld. Upstream test harnesses
            // generate fake Bukkit worlds; they answer through the Bukkit API they implement.
            this.states = new LoadedStates() {
                @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return world.isChunkLoaded(chunkX, chunkZ); }
                @Override public @Nullable BlockState stateIfLoaded(BlockPos pos) {
                    if (!isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;
                    return ((CraftBlockData) world.getBlockAt(pos.getX(), pos.getY(), pos.getZ()).getBlockData()).getState();
                }
            };
            // Upstream mock worlds do not implement height queries; the seam applies no build-height bound.
            this.minY = -2048;
            this.height = 4096;
        }
    }

    public NativeBlockView(LoadedStates states, int minY, int height) {
        this.states = Objects.requireNonNull(states, "states");
        this.minY = minY;
        this.height = height;
    }

    /** Production lookup: concurrent full-chunk map only; no ticket, load, generation or scheduling. */
    public static LoadedStates loadedStatesOf(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return new LoadedStates() {
            @Override public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return level.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ) != null;
            }
            @Override public @Nullable BlockState stateIfLoaded(BlockPos pos) {
                LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? null : chunk.getBlockState(pos);
            }
        };
    }

    public boolean isLoaded(int x, int z) {
        return states.isChunkLoaded(x >> 4, z >> 4);
    }

    /** Null means unknown: the chunk is not loaded. Outside build height is AIR. */
    public @Nullable BlockState stateIfLoaded(BlockPos pos) {
        if (isOutsideBuildHeight(pos)) return Blocks.AIR.defaultBlockState();
        return states.stateIfLoaded(pos);
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        BlockState state = stateIfLoaded(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override public BlockState getBlockStateIfLoaded(BlockPos pos) { return getBlockState(pos); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public FluidState getFluidIfLoaded(BlockPos pos) { return getFluidState(pos); }

    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        // Block entities are entity state; CHECK could deserialize/create one. Never touched off-owner.
        if (!getBlockState(pos).hasBlockEntity()) return null;
        throw new UnsupportedOperationException("Native Intave never reads block entities from a racy view");
    }

    @Override public int getHeight() { return height; }
    @Override public int getMinY() { return minY; }
}
