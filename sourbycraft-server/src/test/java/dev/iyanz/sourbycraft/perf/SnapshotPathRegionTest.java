package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.aurora.level.SnapshotPathRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

/**
 * Direct tests of {@link SnapshotPathRegion}, the immutable world snapshot an off-thread path solve reads
 * (minecraft feature patch 0005). Until now only its callers were tested, against a stubbed region.
 *
 * <p>Every case builds the real snapshot and, where the question is parity with the synchronous path,
 * the vanilla {@link PathNavigationRegion} over the same mocked level. The world is two chunks side by
 * side: chunk (0,0) loaded with one stone section at y 48..63, chunk (1,0) not loaded.</p>
 */
@org.bukkit.support.environment.VanillaFeature
class SnapshotPathRegionTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int SECTIONS = HEIGHT >> 4;
    private static final int STONE_SECTION_Y = 3; // y 48..63

    private static final BlockPos START = new BlockPos(0, 50, 0);
    private static final BlockPos END = new BlockPos(31, 70, 15);

    private static final BlockPos IN_STONE_SECTION = new BlockPos(5, 50, 5);
    private static final BlockPos IN_AIR_SECTION = new BlockPos(5, 100, 5);
    private static final BlockPos IN_UNLOADED_CHUNK = new BlockPos(20, 50, 5);
    private static final BlockPos OUTSIDE_GRID = new BlockPos(-40, 50, 5);

    private final Level level = mock(Level.class);
    private final PalettedContainer<BlockState> live = mockContainer();

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

        // The live container answers air; only the copy taken at construction knows the stone.
        when(this.live.get(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        final PalettedContainer<BlockState> copy = mockContainer();
        when(copy.get(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.STONE.defaultBlockState());
        when(this.live.copy()).thenReturn(copy);
        final LevelChunkSection stone = mock(LevelChunkSection.class);
        when(stone.hasOnlyAir()).thenReturn(false);
        when(stone.getStates()).thenReturn(this.live);
        final LevelChunkSection[] sections = new LevelChunkSection[SECTIONS];
        sections[STONE_SECTION_Y - (MIN_Y >> 4)] = stone;

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
    void aLoadedOccupiedSectionIsServedFromTheCopyNotTheLiveContainer() {
        final SnapshotPathRegion region = snapshot();
        assertSame(Blocks.STONE, region.getBlockState(IN_STONE_SECTION).getBlock());
        assertSame(Blocks.STONE, region.getBlockStateIfLoaded(IN_STONE_SECTION).getBlock());
        assertSame(Blocks.STONE, region.getChunkForCollisions(0, 0).getBlockState(IN_STONE_SECTION).getBlock());
    }

    @Test
    void aLoadedEmptySectionReadsAsAirAndIsLoaded() {
        final SnapshotPathRegion region = snapshot();
        assertSame(Blocks.AIR, region.getBlockState(IN_AIR_SECTION).getBlock());
        final BlockState ifLoaded = region.getBlockStateIfLoaded(IN_AIR_SECTION);
        assertNotNull(ifLoaded, "an air section of a loaded chunk is loaded");
        assertSame(Blocks.AIR, ifLoaded.getBlock());
    }

    @Test
    void anUnloadedChunkReadsAsAirAndNoFluidForPlainReads() {
        // Vanilla serves these from an EmptyLevelChunk (air); that needs a real registry for the plains
        // biome, so the expectation is stated instead of compared.
        final SnapshotPathRegion region = snapshot();
        assertSame(Blocks.AIR, region.getBlockState(IN_UNLOADED_CHUNK).getBlock());
        assertSame(Blocks.AIR, region.getBlockState(OUTSIDE_GRID).getBlock());
        assertEquals(net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(), region.getFluidState(IN_UNLOADED_CHUNK));
    }

    @Test
    void theSnapshotHoldsNoBlockEntitiesAndDetachesTheBorder() {
        final SnapshotPathRegion region = snapshot();
        assertNull(region.getBlockEntity(IN_STONE_SECTION));
        assertNull(region.getChunkForCollisions(0, 0).getBlockEntity(IN_STONE_SECTION));
        assertNotNull(region.getWorldBorder());
        assertSame(region.getWorldBorder(), region.getWorldBorder());
    }

    /**
     * The only {@code *IfLoaded} caller on the solve is {@code WalkNodeEvaluator.getPathTypeFromState}
     * (Paper "do not load chunks during pathfinding"): {@code null} means BLOCKED, anything else is
     * classified by block. Vanilla returns {@code null} for a chunk that was not loaded when the region
     * was built; the snapshot returns air, i.e. {@code PathType.OPEN}, so an off-thread solve may route
     * through unloaded terrain the synchronous solve refuses. Fixed by minecraft feature patch 0048
     * (the snapshot keeps which columns were loaded).
     */
    @Test
    void anUnloadedChunkIsNotLoadedForTheIfLoadedReadsLikeVanilla() {
        final SnapshotPathRegion region = snapshot();
        final PathNavigationRegion vanilla = new PathNavigationRegion(this.level, START, END);
        assertNull(vanilla.getBlockStateIfLoaded(IN_UNLOADED_CHUNK));
        assertNull(vanilla.getBlockStateIfLoaded(OUTSIDE_GRID));
        assertNull(region.getBlockStateIfLoaded(IN_UNLOADED_CHUNK));
        assertNull(region.getBlockStateIfLoaded(OUTSIDE_GRID));
        assertNull(vanilla.getFluidIfLoaded(IN_UNLOADED_CHUNK));
        assertNull(region.getFluidIfLoaded(IN_UNLOADED_CHUNK));
    }

    @Test
    void anUnloadedChunkIsNotLoadedAtAnyHeightLikeVanilla() {
        // Vanilla's getChunkIfLoaded is consulted before any height check.
        final SnapshotPathRegion region = snapshot();
        final PathNavigationRegion vanilla = new PathNavigationRegion(this.level, START, END);
        for (final BlockPos pos : new BlockPos[]{IN_UNLOADED_CHUNK.atY(MIN_Y - 10), IN_UNLOADED_CHUNK.atY(MIN_Y + HEIGHT + 10),
            OUTSIDE_GRID.atY(MIN_Y + HEIGHT + 10)}) {
            assertNull(vanilla.getBlockStateIfLoaded(pos), pos.toString());
            assertNull(region.getBlockStateIfLoaded(pos), pos.toString());
            assertNull(vanilla.getFluidIfLoaded(pos), pos.toString());
            assertNull(region.getFluidIfLoaded(pos), pos.toString());
        }
    }

    @Test
    void aLoadedChunkIsLoadedEvenOutsideTheBuildHeightAndAnswersAirAndNoFluid() {
        // Vanilla hands a loaded chunk's read to LevelChunk, which answers air (and no fluid) outside
        // its sections; it never answers null for a loaded chunk.
        final SnapshotPathRegion region = snapshot();
        for (final BlockPos pos : new BlockPos[]{IN_STONE_SECTION.atY(MIN_Y - 10), IN_STONE_SECTION.atY(MIN_Y + HEIGHT + 10), IN_AIR_SECTION}) {
            assertSame(Blocks.AIR, region.getBlockStateIfLoaded(pos).getBlock(), pos.toString());
            assertEquals(net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(), region.getFluidIfLoaded(pos), pos.toString());
        }
        assertSame(Blocks.STONE, region.getBlockStateIfLoaded(IN_STONE_SECTION).getBlock());
        assertNotNull(region.getFluidIfLoaded(IN_STONE_SECTION));
    }

    @Test
    void theCollisionViewOfAnUnloadedChunkAnswersLikeAnEmptyChunkNotNull() {
        // Vanilla getChunkForCollisions hands out an EmptyLevelChunk for an unloaded chunk, a LevelChunk
        // whose *IfLoaded reads are its plain reads (void air, no fluid): never null. Needs a biome
        // registry to build here, so the expectation is stated. The region's own *IfLoaded stay null.
        final SnapshotPathRegion region = snapshot();
        final net.minecraft.world.level.BlockGetter view = region.getChunkForCollisions(1, 0);
        final BlockState ifLoaded = view.getBlockStateIfLoaded(IN_UNLOADED_CHUNK);
        assertNotNull(ifLoaded);
        assertSame(Blocks.AIR, ifLoaded.getBlock());
        assertEquals(net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(), view.getFluidIfLoaded(IN_UNLOADED_CHUNK));
        assertSame(Blocks.STONE, region.getChunkForCollisions(0, 0).getBlockStateIfLoaded(IN_STONE_SECTION).getBlock());
        assertNull(region.getBlockStateIfLoaded(IN_UNLOADED_CHUNK));
    }
}
