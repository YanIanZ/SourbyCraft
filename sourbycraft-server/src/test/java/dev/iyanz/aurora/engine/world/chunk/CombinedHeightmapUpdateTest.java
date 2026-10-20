package dev.iyanz.aurora.engine.world.chunk;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine world/chunk: {@link CombinedHeightmapUpdate#updateHeightmaps} (the Lithium
 * four-heightmaps-at-once update). Whatever shortcuts it takes, afterwards each heightmap of the
 * column must hold what a from-scratch scan of the column gives: the highest block the heightmap's
 * predicate accepts, plus one, or the chunk's minimum Y when there is none.
 *
 * <p>The column is a fixed-size model behind mocked {@code Heightmap}/{@code LevelChunk} objects
 * (a real heightmap needs a real chunk). The random part uses a fixed seed and many single-block
 * edits, each starting from heights that were consistent with the column before the edit.</p>
 */
class CombinedHeightmapUpdateTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 40;
    private static final int X = 3;
    private static final int Z = 5;

    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState GLASS;
    private static BlockState DIRT;
    private static BlockState WATER;
    private static BlockState[] PALETTE;
    /** The four predicates differ on purpose: not air, blocks motion, stone only, glass or dirt. */
    private static final Predicate<BlockState>[] PREDICATES = predicates();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        GLASS = Blocks.GLASS.defaultBlockState();
        DIRT = Blocks.DIRT.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        PALETTE = new BlockState[] {AIR, AIR, AIR, STONE, GLASS, DIRT, WATER};
    }

    @SuppressWarnings("unchecked")
    private static Predicate<BlockState>[] predicates() {
        return new Predicate[] {
            (Predicate<BlockState>) state -> !state.isAir(),
            (Predicate<BlockState>) state -> state.blocksMotion(),
            (Predicate<BlockState>) state -> state.is(Blocks.STONE),
            (Predicate<BlockState>) state -> state.is(Blocks.GLASS) || state.is(Blocks.DIRT),
        };
    }

    /** One block column with four heightmaps over it. */
    private static final class Column {
        final BlockState[] states = new BlockState[HEIGHT];
        final int[] heights = new int[4];
        final Heightmap[] heightmaps = new Heightmap[4];
        final LevelChunk chunk = mock(LevelChunk.class);
        int blockReads;

        Column() {
            java.util.Arrays.fill(this.states, AIR);
            for (int i = 0; i < 4; i++) {
                final int index = i;
                final Heightmap heightmap = mock(Heightmap.class);
                setOpaque(heightmap, PREDICATES[i]);
                when(heightmap.getFirstAvailable(anyInt(), anyInt())).thenAnswer(call -> {
                    assertEquals(X, (int) call.getArgument(0));
                    assertEquals(Z, (int) call.getArgument(1));
                    return this.heights[index];
                });
                doAnswer(call -> {
                    assertEquals(X, (int) call.getArgument(0));
                    assertEquals(Z, (int) call.getArgument(1));
                    this.heights[index] = call.getArgument(2);
                    return null;
                }).when(heightmap).setHeight(anyInt(), anyInt(), anyInt());
                this.heightmaps[i] = heightmap;
            }
            when(this.chunk.getMinY()).thenReturn(MIN_Y);
            when(this.chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
                final BlockPos pos = call.getArgument(0);
                assertEquals(X, pos.getX());
                assertEquals(Z, pos.getZ());
                this.blockReads++;
                return this.states[pos.getY() - MIN_Y];
            });
            recompute();
        }

        void recompute() {
            for (int i = 0; i < 4; i++) {
                this.heights[i] = scan(i);
            }
        }

        int scan(final int predicate) {
            for (int index = HEIGHT - 1; index >= 0; index--) {
                if (PREDICATES[predicate].test(this.states[index])) {
                    return MIN_Y + index + 1;
                }
            }
            return MIN_Y;
        }

        /** Replaces the block at world height {@code y} and runs the update. */
        void edit(final int y, final BlockState state) {
            this.states[y - MIN_Y] = state;
            CombinedHeightmapUpdate.updateHeightmaps(
                this.heightmaps[0], this.heightmaps[1], this.heightmaps[2], this.heightmaps[3], this.chunk, X, y, Z, state
            );
        }

        void assertConsistent(final String context) {
            for (int i = 0; i < 4; i++) {
                assertEquals(scan(i), this.heights[i], "heightmap " + i + " after " + context);
            }
        }
    }

    private static void setOpaque(final Heightmap heightmap, final Predicate<BlockState> predicate) {
        try {
            final Field field = Heightmap.class.getField("isOpaque");
            field.setAccessible(true);
            field.set(heightmap, predicate);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theModelStartsFromTheDocumentedEmptyColumnHeight() {
        final Column column = new Column();
        for (final int height : column.heights) {
            assertEquals(MIN_Y, height);
        }
    }

    @Test
    void placingTheFirstOpaqueBlockRaisesTheHeightmapsThatAcceptIt() {
        final Column column = new Column();
        column.edit(MIN_Y + 10, STONE);

        assertEquals(MIN_Y + 11, column.heights[0], "not air");
        assertEquals(MIN_Y + 11, column.heights[1], "blocks motion");
        assertEquals(MIN_Y + 11, column.heights[2], "stone");
        assertEquals(MIN_Y, column.heights[3], "glass or dirt only: stone is no business of it");
        column.assertConsistent("placing stone");
    }

    @Test
    void removingTheTopBlockLowersEachHeightmapToItsNextBlockDown() {
        final Column column = new Column();
        column.edit(MIN_Y + 4, STONE);
        column.edit(MIN_Y + 8, GLASS);
        column.edit(MIN_Y + 12, WATER);      // not air, no motion block, not stone/glass/dirt
        assertEquals(MIN_Y + 13, column.heights[0]);
        assertEquals(MIN_Y + 9, column.heights[1]);
        assertEquals(MIN_Y + 5, column.heights[2]);
        assertEquals(MIN_Y + 9, column.heights[3]);

        column.edit(MIN_Y + 12, AIR);
        assertEquals(MIN_Y + 9, column.heights[0]);
        column.assertConsistent("removing the water");

        column.edit(MIN_Y + 8, AIR);
        assertEquals(MIN_Y + 5, column.heights[0]);
        assertEquals(MIN_Y + 5, column.heights[1]);
        assertEquals(MIN_Y, column.heights[3]);
        column.assertConsistent("removing the glass");
    }

    @Test
    void clearingTheLastBlockDropsEveryHeightmapToTheMinimum() {
        final Column column = new Column();
        column.edit(MIN_Y, STONE);
        assertEquals(MIN_Y + 1, column.heights[0]);
        column.edit(MIN_Y, AIR);
        for (final int height : column.heights) {
            assertEquals(MIN_Y, height);
        }
    }

    @Test
    void theHighestPossibleBlockIsHandled() {
        final Column column = new Column();
        column.edit(MIN_Y + HEIGHT - 1, STONE);
        assertEquals(MIN_Y + HEIGHT, column.heights[0]);
        column.edit(MIN_Y + HEIGHT - 1, AIR);
        assertEquals(MIN_Y, column.heights[0]);
    }

    @Test
    void anEditWellBelowTheSurfaceNeitherWritesNorScansTheColumn() {
        final Column column = new Column();
        column.edit(MIN_Y + 30, STONE);
        column.edit(MIN_Y + 30, GLASS);   // a different opaque block at the top, to have the heights settled
        final int[] before = column.heights.clone();
        final int readsBefore = column.blockReads;
        for (final Heightmap heightmap : column.heightmaps) {
            org.mockito.Mockito.clearInvocations(heightmap);
        }

        column.edit(MIN_Y + 10, DIRT);    // far under the top: cannot matter to any of the four

        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(column.heights));
        assertEquals(readsBefore, column.blockReads);
        for (final Heightmap heightmap : column.heightmaps) {
            verify(heightmap, never()).setHeight(anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void anEditAboveTheSurfaceThatTheHeightmapRejectsLeavesItAlone() {
        final Column column = new Column();
        column.edit(MIN_Y + 5, STONE);
        column.edit(MIN_Y + 20, WATER);   // not air only
        assertEquals(MIN_Y + 21, column.heights[0]);
        assertEquals(MIN_Y + 6, column.heights[1]);
        column.assertConsistent("water above stone");
    }

    @Test
    void manyRandomEditsAlwaysMatchAFromScratchScan() {
        final Random random = new Random(0x5EEDL);
        int scanningEdits = 0;
        for (int round = 0; round < 60; round++) {
            final Column column = new Column();
            for (int index = 0; index < HEIGHT; index++) {
                column.states[index] = PALETTE[random.nextInt(PALETTE.length)];
            }
            column.recompute();

            for (int step = 0; step < 400; step++) {
                // half of the edits aim at the surface of a random heightmap, where the shortcuts live
                final int y;
                if (random.nextBoolean()) {
                    final int surface = column.heights[random.nextInt(4)];
                    y = clamp(surface - 3 + random.nextInt(6));
                } else {
                    y = MIN_Y + random.nextInt(HEIGHT);
                }
                final BlockState state = PALETTE[random.nextInt(PALETTE.length)];
                final int readsBefore = column.blockReads;
                column.edit(y, state);
                if (column.blockReads > readsBefore) {
                    scanningEdits++;
                }
                column.assertConsistent("round " + round + " step " + step + ": " + state + " at y=" + y);
            }
        }
        // the downward scan is the branch most worth having exercised
        org.junit.jupiter.api.Assertions.assertTrue(scanningEdits > 100, "only " + scanningEdits + " edits needed a scan");
    }

    private static int clamp(final int y) {
        return Math.max(MIN_Y, Math.min(MIN_Y + HEIGHT - 1, y));
    }
}
