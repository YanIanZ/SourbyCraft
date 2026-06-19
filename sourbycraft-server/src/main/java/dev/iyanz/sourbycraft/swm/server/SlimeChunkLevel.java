package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jetbrains.annotations.Nullable;

public class SlimeChunkLevel extends LevelChunk {

    private final SlimeInMemoryWorld inMemoryWorld;
    private final NMSSlimeChunk nmsSlimeChunk;

    public SlimeChunkLevel(
            SlimeLevelInstance world,
            @Nullable SlimeChunk reference,
            ChunkPos pos,
            UpgradeData upgradeData,
            LevelChunkTicks<Block> blockTickScheduler,
            LevelChunkTicks<Fluid> fluidTickScheduler,
            long inhabitedTime,
            @Nullable LevelChunkSection[] sectionArrayInitializer,
            @Nullable LevelChunk.PostLoadProcessor entityLoader,
            @Nullable BlendingData blendingData
    ) {
        super(world, pos, upgradeData, blockTickScheduler, fluidTickScheduler, inhabitedTime, sectionArrayInitializer, entityLoader, blendingData);
        this.inMemoryWorld = world.slimeInstance;
        // ASP dev/26.2 parity: SlimeChunkLevel keeps a single NMSSlimeChunk view
        // of the live chunk. The previous dual-track via `slimeReference` +
        // `SafeNmsChunkWrapper` would silently discard live-mutated blocks on
        // chunk unload because `SafeNmsChunkWrapper.shouldDefaultBackToSlimeChunk`
        // returned true when `wrapper.getChunk().loaded == false`, swapping the
        // live NMS view back to the original on-disk reference. That ate
        // schematic paste blocks the moment SS2's island world unloaded after
        // /is create. See project_asp_swm_port.md.
        this.nmsSlimeChunk = new NMSSlimeChunk(this, reference);
    }

    @Override
    public void loadCallback() {
        this.inMemoryWorld.promoteInChunkStorage(this);
        super.loadCallback();
    }

    public NMSSlimeChunk getNmsSlimeChunk() {
        return nmsSlimeChunk;
    }
}
