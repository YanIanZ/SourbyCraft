package dev.yanianz.intave.integration;

import ca.spottedleaf.moonrise.common.util.TickThread;
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

/** Read-only view: every lookup, including a shape's neighbors, checks region ownership. */
public final class NativeBlockView implements BlockGetter {
    private final ServerLevel level;

    public NativeBlockView(World world) {
        level = ((CraftWorld) Objects.requireNonNull(world, "world")).getHandle();
    }

    private void checkOwner(BlockPos pos) {
        TickThread.ensureTickThread(level, pos, "Native Intave block read outside owner region");
    }

    private LevelChunk loadedChunk(BlockPos pos) {
        checkOwner(pos);
        LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("Native Intave requires an already loaded chunk at " + pos);
        return chunk;
    }

    public boolean isLoaded(int x, int z) {
        checkOwner(new BlockPos(x, level.getMinY(), z));
        return level.getChunkSource().getChunkAtIfLoadedImmediately(x >> 4, z >> 4) != null;
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        checkOwner(pos);
        if (level.isOutsideBuildHeight(pos)) return Blocks.AIR.defaultBlockState();
        return loadedChunk(pos).getBlockState(pos);
    }

    @Override public BlockState getBlockStateIfLoaded(BlockPos pos) {
        // Shape code may interpret null as AIR. Unknown neighbor data cannot become a safe shape.
        return getBlockState(pos);
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public FluidState getFluidIfLoaded(BlockPos pos) {
        return getFluidState(pos);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        // CHECK can deserialize pending NBT/create a block entity. A shape read must not do so.
        if (!getBlockState(pos).hasBlockEntity()) return null;
        throw new UnsupportedOperationException("Native Intave block-entity shape access requires an audited snapshot");
    }

    @Override public int getHeight() { return level.getHeight(); }
    @Override public int getMinY() { return level.getMinY(); }
}
