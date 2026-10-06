package dev.yanianz.intave.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** Server-owned adapter test (private profile): racy loaded-chunk reads, never a chunk load. */
@Normal
final class NativeBlockViewTest {
    @Test void unloadedChunkReadsAsUnknownAndNeverLoads() {
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        NativeBlockView view = new NativeBlockView(NativeBlockView.loadedStatesOf(level), -64, 384);
        BlockPos pos = new BlockPos(100, 64, -200);

        assertNull(view.stateIfLoaded(pos), "unloaded chunk is unknown");
        assertSame(Blocks.AIR.defaultBlockState(), view.getBlockState(pos), "unknown maps to AIR like upstream");
        assertFalse(view.isLoaded(100, -200));
        assertTrue(view.getFluidState(pos).isEmpty());
        assertNull(view.getBlockEntity(pos));

        // Only the concurrent full-chunk map lookup is used: no getChunk, ticket or scheduling call.
        verify(level, times(5)).getChunkSource();
        verify(chunks, times(5)).getChunkAtIfLoadedImmediately(100 >> 4, -200 >> 4);
        verifyNoMoreInteractions(chunks, level);
    }

    @Test void loadedChunkReadsItsSectionState() {
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        BlockPos pos = new BlockPos(1, 70, 2);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkAtIfLoadedImmediately(0, 0)).thenReturn(chunk);
        when(chunk.getBlockState(pos)).thenReturn(Blocks.STONE.defaultBlockState());
        NativeBlockView view = new NativeBlockView(NativeBlockView.loadedStatesOf(level), -64, 384);
        assertSame(Blocks.STONE.defaultBlockState(), view.getBlockState(pos));
        assertTrue(view.isLoaded(1, 2));
        verify(chunks, times(2)).getChunkAtIfLoadedImmediately(0, 0);
        verifyNoMoreInteractions(chunks);
    }

    @Test void outsideBuildHeightIsAirWithoutAnyLookup() {
        List<BlockPos> lookups = new ArrayList<>();
        NativeBlockView view = new NativeBlockView(new NativeBlockView.LoadedStates() {
            @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return true; }
            @Override public BlockState stateIfLoaded(BlockPos pos) { lookups.add(pos); return Blocks.STONE.defaultBlockState(); }
        }, -64, 384);
        assertSame(Blocks.AIR.defaultBlockState(), view.getBlockState(new BlockPos(0, 400, 0)));
        assertSame(Blocks.AIR.defaultBlockState(), view.getBlockState(new BlockPos(0, -65, 0)));
        assertEquals(List.of(), lookups);
    }

    @Test void blockEntitiesAreNeverRead() {
        NativeBlockView view = new NativeBlockView(new NativeBlockView.LoadedStates() {
            @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return true; }
            @Override public BlockState stateIfLoaded(BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
        }, -64, 384);
        assertThrows(UnsupportedOperationException.class, () -> view.getBlockEntity(BlockPos.ZERO));
    }
}
