package dev.iyanz.aurora.engine.world.chunk;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine world/chunk: {@link NatureSpawnChunkMap} marks, per spawn radius, the chunks around
 * each spawning player. Its lookup tables are a 1 000-odd-entry list of packed offsets, so the tests
 * compare the result with a plain formula instead of repeating the tables: a chunk is a spawn chunk of
 * a player with radius {@code r} exactly when {@code dx*dx + dz*dz <= r*r}. (The tables were decoded
 * once and found to match that circle for every radius 0..8; this keeps them honest.)
 */
class NatureSpawnChunkMapTest {

    private static final int WINDOW = 20;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ServerPlayer player(final int chunkX, final int chunkZ, final int radius) {
        return player(chunkX, chunkZ, radius, false, false);
    }

    private static ServerPlayer player(final int chunkX, final int chunkZ, final int radius, final boolean spectator, final boolean cancelled) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.isSpectator()).thenReturn(spectator);
        when(player.chunkPosition()).thenReturn(new ChunkPos(chunkX, chunkZ));
        final PlayerNaturallySpawnCreaturesEvent event = new PlayerNaturallySpawnCreaturesEvent(mock(Player.class), (byte) radius);
        event.setCancelled(cancelled);
        player.playerNaturallySpawnedEvent = event;
        return player;
    }

    private static List<long[]> covered(final NatureSpawnChunkMap map, final int centerX, final int centerZ) {
        final List<long[]> out = new ArrayList<>();
        for (int dx = -WINDOW; dx <= WINDOW; dx++) {
            for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                if (map.contains(centerX + dx, centerZ + dz)) {
                    out.add(new long[] {dx, dz});
                }
            }
        }
        return out;
    }

    private static void assertCircle(final NatureSpawnChunkMap map, final int centerX, final int centerZ, final int radius) {
        int expected = 0;
        for (int dx = -WINDOW; dx <= WINDOW; dx++) {
            for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                final boolean inside = dx * dx + dz * dz <= radius * radius;
                if (inside) expected++;
                assertEquals(inside, map.contains(centerX + dx, centerZ + dz),
                    "radius " + radius + " around (" + centerX + "," + centerZ + ") at offset (" + dx + "," + dz + ")");
            }
        }
        assertEquals(expected, covered(map, centerX, centerZ).size());
    }

    @Test
    void anEmptyMapContainsNothing() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.build();
        assertFalse(map.contains(0, 0));
        assertFalse(map.contains(-1, -1));
    }

    @Test
    void everyRadiusCoversExactlyTheChunksWithinThatDistance() {
        for (int radius = 0; radius <= 8; radius++) {
            final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
            map.addPlayer(player(100, 200, radius));
            map.build();
            assertCircle(map, 100, 200, radius);
        }
    }

    @Test
    void theShapeIsTheSameAcrossRegionAndSignBoundaries() {
        // the map keeps one 64-bit mask per 8x8 chunk region, so centers on region edges and around zero matter
        final int[][] centers = {{0, 0}, {7, 7}, {8, 8}, {-1, -1}, {-8, -8}, {-9, 7}, {1_000_000, -1_000_000}, {-3_000_000, 3_000_001}};
        for (final int[] center : centers) {
            final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
            map.addPlayer(player(center[0], center[1], 5));
            map.build();
            assertCircle(map, center[0], center[1], 5);
        }
    }

    @Test
    void severalPlayersFormTheUnionAndDuplicatesChangeNothing() {
        final NatureSpawnChunkMap together = new NatureSpawnChunkMap();
        together.addPlayer(player(0, 0, 3));
        together.addPlayer(player(0, 0, 3));
        together.addPlayer(player(10, 0, 2));
        together.addPlayer(player(10, 0, 2));
        together.addPlayer(player(-5, -5, 0));
        together.build();

        for (int x = -15; x <= 20; x++) {
            for (int z = -15; z <= 15; z++) {
                final boolean expected = x * x + z * z <= 9
                    || (x - 10) * (x - 10) + z * z <= 4
                    || (x + 5) * (x + 5) + (z + 5) * (z + 5) <= 0;
                assertEquals(expected, together.contains(x, z), "(" + x + "," + z + ")");
            }
        }
    }

    @Test
    void playersOfDifferentRadiiAtTheSameChunkKeepTheLargest() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(4, 4, 1));
        map.addPlayer(player(4, 4, 6));
        map.build();
        assertCircle(map, 4, 4, 6);
    }

    @Test
    void spectatorsCancelledEventsMissingEventsAndNegativeRadiiAddNothing() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(0, 0, 4, true, false));
        map.addPlayer(player(0, 0, 4, false, true));
        map.addPlayer(player(0, 0, -1));
        final ServerPlayer noEvent = player(0, 0, 4);
        noEvent.playerNaturallySpawnedEvent = null;
        map.addPlayer(noEvent);
        map.build();

        assertEquals(List.of(), covered(map, 0, 0));
    }

    @Test
    void aRadiusAboveTheTableIsClampedToEight() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(0, 0, 100));
        map.build();
        assertCircle(map, 0, 0, 8);
        assertTrue(map.contains(8, 0));
        assertFalse(map.contains(9, 0));
    }

    @Test
    void clearForgetsEverythingAndTheMapCanBeBuiltAgain() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(0, 0, 3));
        map.build();
        assertTrue(map.contains(0, 0));

        map.clear();
        assertFalse(map.contains(0, 0));
        map.build();
        assertFalse(map.contains(0, 0), "building an emptied map stays empty");

        map.addPlayer(player(50, 50, 1));
        map.build();
        assertFalse(map.contains(0, 0));
        assertCircle(map, 50, 50, 1);
    }

    @Test
    void buildingTwiceWithoutNewPlayersIsHarmless() {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(3, -3, 4));
        map.build();
        map.build();
        assertCircle(map, 3, -3, 4);
    }

    @Test
    void collectSpawningChunksKeepsOnlyChunksInsideAMarkedArea() throws Exception {
        final NatureSpawnChunkMap map = new NatureSpawnChunkMap();
        map.addPlayer(player(0, 0, 1));
        map.build();

        final LevelChunk inside = chunk(0, 1);
        final LevelChunk alsoInside = chunk(-1, 0);
        final LevelChunk outside = chunk(1, 1);
        final LevelChunk far = chunk(40, -40);
        final ReferenceList<LevelChunk> chunks = new ReferenceList<>(new LevelChunk[0]);
        chunks.add(far);
        chunks.add(inside);
        chunks.add(outside);
        chunks.add(alsoInside);

        final List<LevelChunk> out = new ArrayList<>();
        map.collectSpawningChunks(chunks, out);

        assertEquals(List.of(inside, alsoInside), out, "order of the input is kept");
    }

    private static LevelChunk chunk(final int x, final int z) throws Exception {
        final LevelChunk chunk = mock(LevelChunk.class);
        setFinalInt(chunk, "locX", x);
        setFinalInt(chunk, "locZ", z);
        return chunk;
    }

    private static void setFinalInt(final Object target, final String name, final int value) throws Exception {
        final Field field = LevelChunk.class.getField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
