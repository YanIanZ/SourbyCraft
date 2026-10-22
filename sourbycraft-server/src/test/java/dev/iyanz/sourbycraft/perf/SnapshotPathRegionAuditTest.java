package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.iyanz.aurora.level.SnapshotPathRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * Pins from the DEV-E snapshot-correctness audit ({@code docs/architecture/async-path-snapshot-audit.md})
 * that {@code SnapshotPathRegionTest} does not already hold. The mocked world is one loaded chunk
 * (0,0) whose section at y 48..63 is water, next to an unloaded chunk (1,0); the region spans both.
 *
 * <p>Vanilla {@code PathNavigationRegion.getBlockState} answers an unloaded or out-of-grid chunk
 * through {@code EmptyLevelChunk}, which returns {@code VOID_AIR}; the snapshot has no chunk object
 * there and answers {@code AIR}. The solve classifies both by {@code BlockState.pathType} and
 * {@code isAir()}, so the audit rates that difference benign; this test is what makes that rating
 * checkable rather than asserted.</p>
 */
@org.bukkit.support.environment.VanillaFeature
class SnapshotPathRegionAuditTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int SECTIONS = HEIGHT >> 4;
    private static final int WATER_SECTION_Y = 3; // y 48..63

    private static final BlockPos START = new BlockPos(0, 50, 0);
    private static final BlockPos END = new BlockPos(31, 70, 15);
    private static final BlockPos IN_WATER_SECTION = new BlockPos(5, 50, 5);
    private static final BlockPos IN_UNLOADED_CHUNK = new BlockPos(20, 50, 5);
    private static final BlockPos OUTSIDE_GRID = new BlockPos(-40, 50, 5);

    private final Level level = mock(Level.class);
    private final PalettedContainer<BlockState> live = mockContainer();
    private final PalettedContainer<BlockState> copy = mockContainer();

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState> mockContainer() {
        return mock(PalettedContainer.class);
    }

    private SnapshotPathRegion snapshot() {
        when(this.level.getMinY()).thenReturn(MIN_Y);
        when(this.level.getHeight()).thenReturn(HEIGHT);
        when(this.level.getMinSectionY()).thenReturn(MIN_Y >> 4);
        when(this.level.getSectionsCount()).thenReturn(SECTIONS);
        when(this.level.getWorldBorder()).thenReturn(new WorldBorder());

        when(this.live.get(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        when(this.copy.get(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.WATER.defaultBlockState());
        when(this.live.copy()).thenReturn(this.copy);
        final LevelChunkSection water = mock(LevelChunkSection.class);
        when(water.hasOnlyAir()).thenReturn(false);
        when(water.getStates()).thenReturn(this.live);
        final LevelChunkSection air = mock(LevelChunkSection.class);
        when(air.hasOnlyAir()).thenReturn(true);
        final LevelChunkSection[] sections = new LevelChunkSection[SECTIONS];
        sections[WATER_SECTION_Y - (MIN_Y >> 4)] = water;
        sections[WATER_SECTION_Y + 1 - (MIN_Y >> 4)] = air;

        final LevelChunk loaded = mock(LevelChunk.class);
        when(loaded.getSections()).thenReturn(sections);
        when(loaded.isYSpaceEmpty(anyInt(), anyInt())).thenReturn(false);

        final ChunkSource source = mock(ChunkSource.class);
        when(source.getChunkNow(0, 0)).thenReturn(loaded);
        when(source.getChunkNow(1, 0)).thenReturn(null);
        when(this.level.getChunkSource()).thenReturn(source);
        return new SnapshotPathRegion(this.level, START, END);
    }

    @Test
    void fluidsAreDerivedFromTheCopiedBlockStateLikeAChunkSection() {
        final SnapshotPathRegion region = snapshot();
        // LevelChunkSection.getFluidState is states.get(...).getFluidState(); the snapshot does the same on its copy.
        assertSame(Fluids.WATER.getSource(false).getType(), region.getFluidState(IN_WATER_SECTION).getType());
        assertSame(Fluids.WATER.getSource(false).getType(), region.getFluidIfLoaded(IN_WATER_SECTION).getType());
        assertTrue(region.getFluidState(IN_UNLOADED_CHUNK).isEmpty());
        assertTrue(region.getFluidState(new BlockPos(5, 70, 5)).isEmpty(), "an all-air section is not copied and reads empty");
    }

    @Test
    void eachOccupiedSectionIsCopiedOnceAndTheLiveContainerIsNeverReadAfterward() {
        final SnapshotPathRegion region = snapshot();
        verify(this.live, times(1)).copy();
        region.getBlockState(IN_WATER_SECTION);
        region.getBlockStateIfLoaded(IN_WATER_SECTION);
        region.getFluidState(IN_WATER_SECTION);
        region.getChunkForCollisions(0, 0).getBlockState(IN_WATER_SECTION);
        verify(this.live, never()).get(anyInt(), anyInt(), anyInt());
        verify(this.copy, times(4)).get(anyInt(), anyInt(), anyInt());
    }

    @Test
    void anUnloadedChunkAnswersAirWhereVanillaAnswersVoidAirAndTheSolveCannotTellThemApart() {
        final SnapshotPathRegion region = snapshot();
        final BlockState snapshotAnswer = region.getBlockState(IN_UNLOADED_CHUNK);
        final BlockState outsideAnswer = region.getBlockState(OUTSIDE_GRID);
        // What vanilla PathNavigationRegion.getBlockState returns there: EmptyLevelChunk.getBlockState.
        final BlockState vanillaAnswer = Blocks.VOID_AIR.defaultBlockState();

        assertSame(Blocks.AIR.defaultBlockState(), snapshotAnswer);
        assertSame(Blocks.AIR.defaultBlockState(), outsideAnswer);
        assertNotSame(vanillaAnswer, snapshotAnswer, "the identity differs; the pins below are why that is benign");

        assertTrue(snapshotAnswer.isAir() && vanillaAnswer.isAir());
        // WalkNodeEvaluator.getPathTypeFromState reads BlockState.pathType, the ordinal this function
        // produces in canvas$initCaches (Blocks.rebuildCache); the test environment does not build
        // that cache, so the classification is computed here from the same function.
        assertEquals(PathType.OPEN, WalkNodeEvaluator.canvas$getPathTypeFromBlockState(vanillaAnswer));
        assertEquals(PathType.OPEN, WalkNodeEvaluator.canvas$getPathTypeFromBlockState(snapshotAnswer));
        assertTrue(snapshotAnswer.getCollisionShape(region, IN_UNLOADED_CHUNK).isEmpty());
        assertTrue(vanillaAnswer.getCollisionShape(region, IN_UNLOADED_CHUNK).isEmpty());
        assertTrue(snapshotAnswer.getFluidState().isEmpty() && vanillaAnswer.getFluidState().isEmpty());
        assertEquals(vanillaAnswer.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND),
            snapshotAnswer.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND));
    }
}
