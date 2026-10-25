package dev.iyanz.aurora.engine.spark.provider;

import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionScheduleHandlePinner;
import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.datapack.DatapackManager;
import io.papermc.paper.datapack.DatapackSource;
import java.util.Collection;
import java.util.List;
import me.lucko.spark.paper.common.platform.world.WorldInfoProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaWorldInfoProvider} on a server that is not running a region profile
 * (the global figures) and on the global tick during one. Reading a pinned region's chunks, entities
 * and tile entities needs a live region scheduler and loaded chunks and is not covered, nor are the
 * per-region chunk reports ({@code pollChunks} on loaded worlds) or game rules (the game rule
 * registry is only present on a booted server).
 */
class FoliaWorldInfoProviderTest {

    private RegionProfiler.ProfilingState previousState;

    @BeforeEach
    void noRegionProfileRuns() {
        this.previousState = RegionProfiler.STATE.getAndSet(null);
    }

    @AfterEach
    void restoreProfilingState() {
        RegionProfiler.STATE.set(this.previousState);
    }

    private static World world(final int entities, final int chunks) {
        final World world = mock(World.class);
        when(world.getEntityCount()).thenReturn(entities);
        when(world.getChunkCount()).thenReturn(chunks);
        return world;
    }

    private static Server server(final int players, final World... worlds) {
        final Server server = mock(Server.class);
        when(server.getOnlinePlayers()).thenAnswer(call -> java.util.Collections.nCopies(players, mock(org.bukkit.entity.Player.class)));
        when(server.getWorlds()).thenReturn(List.of(worlds));
        return server;
    }

    @Test
    void globalCountsAddUpPlayersAndEveryWorldsEntitiesAndChunks() {
        final WorldInfoProvider.CountsResult counts = new FoliaWorldInfoProvider(server(3, world(10, 100), world(5, 40), world(0, 7))).pollCounts();

        assertEquals(3, counts.players());
        assertEquals(15, counts.entities());
        assertEquals(147, counts.chunks());
        assertEquals(0, counts.tileEntities(), "the mocked worlds publish no tickable block entities");
    }

    @Test
    void anEmptyServerCountsZeros() {
        final WorldInfoProvider.CountsResult counts = new FoliaWorldInfoProvider(server(0)).pollCounts();
        assertEquals(0, counts.players() + counts.entities() + counts.chunks() + counts.tileEntities());
    }

    @Test
    void duringAProfileOfTheGlobalTickThereAreNoRegionCountsAndNoChunks() {
        // a pinner that is not a region pinner is the global tick, which owns no world data
        RegionProfiler.STATE.set(new RegionProfiler.ProfilingState(null, mock(RegionScheduleHandlePinner.class), null));
        final FoliaWorldInfoProvider provider = new FoliaWorldInfoProvider(server(9, world(10, 100)));

        final WorldInfoProvider.CountsResult counts = provider.pollCounts();
        assertEquals(0, counts.players() + counts.entities() + counts.chunks() + counts.tileEntities());
        assertTrue(provider.pollChunks().getWorlds().isEmpty());
    }

    @Test
    void withNoWorldsThereAreNoChunkReports() {
        assertTrue(new FoliaWorldInfoProvider(server(0)).pollChunks().getWorlds().isEmpty());
    }

    private static Datapack pack(final String name, final Component description, final DatapackSource source) {
        final Datapack pack = mock(Datapack.class);
        when(pack.getName()).thenReturn(name);
        when(pack.getDescription()).thenReturn(description);
        when(pack.getSource()).thenReturn(source);
        return pack;
    }

    private static Server serverWithPacks(final Datapack... packs) {
        final Server server = mock(Server.class);
        final DatapackManager manager = mock(DatapackManager.class);
        when(manager.getEnabledPacks()).thenAnswer(call -> List.of(packs));
        when(server.getDatapackManager()).thenReturn(manager);
        return server;
    }

    @Test
    void dataPacksReportNameSourceAndTheirDescriptionText() {
        final Collection<WorldInfoProvider.DataPackInfo> packs = new FoliaWorldInfoProvider(serverWithPacks(
            pack("vanilla", Component.text("The default data for Minecraft"), DatapackSource.BUILT_IN),
            pack("file/my-pack.zip", Component.text("Hello ").append(Component.text("world", NamedTextColor.RED)), DatapackSource.WORLD)
        )).pollDataPacks();

        final List<WorldInfoProvider.DataPackInfo> list = List.copyOf(packs);
        assertEquals(2, list.size());
        assertEquals("vanilla", list.get(0).name());
        assertEquals("builtin", list.get(0).source(), "underscores are dropped from the source name");
        assertEquals("The default data for Minecraft", list.get(0).description());
        assertEquals("file/my-pack.zip", list.get(1).name());
        assertEquals("world", list.get(1).source());
        assertEquals("Hello world", list.get(1).description(), "the plain text of the whole component, styled children included");
    }

    @Test
    void noEnabledDataPacksIsAnEmptyList() {
        assertFalse(new FoliaWorldInfoProvider(serverWithPacks()).pollDataPacks().iterator().hasNext());
    }
}
