package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

/**
 * V19: each broadcast recipient must observe range changes made by earlier callbacks.
 * Exercises the real broadcast loops and range calculation; the per-player operation is
 * replaced with a synchronous callback so no running world or network connection is needed.
 */
@org.bukkit.support.environment.Normal
class TrackerBroadcastTest {
    private final List<Integer> observed = new ArrayList<>();
    private final AtomicInteger scale = new AtomicInteger(16);
    private final MinecraftServer server = mock(MinecraftServer.class);

    private ChunkMap.TrackedEntity tracker() throws Exception {
        final Entity entity = mock(Entity.class);
        when(entity.getPassengers()).thenReturn(ImmutableList.of());
        final ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(server.getScaledTrackingDistance(anyInt())).thenAnswer(call -> scale.get());
        final ChunkMap map = mock(ChunkMap.class);
        set(map, "level", level);
        final var range = ChunkMap.TrackedEntity.class.getDeclaredMethod("getEffectiveRange");
        range.setAccessible(true);
        final ChunkMap.TrackedEntity tracker = mock(ChunkMap.TrackedEntity.class, call -> {
            if (call.getMethod().getName().equals("updatePlayer")) {
                // Also recognizes the old cached-range overload, reproducing its stale value.
                final int current = call.getArguments().length == 2
                    ? (int) call.getArgument(1) : (int) range.invoke(call.getMock());
                observed.add(current);
                scale.set(64); // Models a synchronous tracking callback changing range inputs.
                return null;
            }
            return call.callRealMethod();
        });
        set(tracker, "this$0", map);
        set(tracker, "entity", entity);
        set(tracker, "range", 16);
        set(tracker, "seenBy", new HashSet<>());
        return tracker;
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void v19ListBroadcastRecomputesRangeAfterCallback() throws Exception {
        tracker().updatePlayers(List.of(mock(ServerPlayer.class), mock(ServerPlayer.class)));
        assertEquals(List.of(16, 64), observed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void v19RegionBroadcastRecomputesRangeAfterCallback() throws Exception {
        final ServerPlayer[] players = {mock(ServerPlayer.class), mock(ServerPlayer.class)};
        final ReferenceList<ServerPlayer> list = mock(ReferenceList.class);
        when(list.getRawDataUnchecked()).thenReturn(players);
        when(list.size()).thenReturn(players.length);
        final NearbyPlayers.TrackedChunk chunk = mock(NearbyPlayers.TrackedChunk.class);
        when(chunk.getPlayers(NearbyPlayers.NearbyMapType.VIEW_DISTANCE)).thenReturn(list);
        tracker().moonrise$tick(chunk);
        assertEquals(List.of(16, 64), observed);
    }

    @Test
    void v19EmptyBroadcastDoesNotReadEntityRange() throws Exception {
        final var tracker = tracker();
        clearInvocations(server);
        tracker.updatePlayers(List.of());
        verifyNoInteractions(server);
    }
}
