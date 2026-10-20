package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.perf.RegionLocator;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class PerfRegionViewTest {

    private static final RegionTickMetrics.WindowSnapshot WINDOW = new RegionTickMetrics.WindowSnapshot(
        100L, 5_000_000_000L, 800_000_000L, 4_000_000L, 8.0, 16_000_000L, 0L,
        19.99, 8.0, 0.4, 12.0, 14.0, false, false);

    private static final RegionTickMetrics.Snapshot SNAPSHOT = new RegionTickMetrics.Snapshot(
        1L, 1_000_000_000L, RegionTickMetrics.INACTIVE,
        WINDOW, WINDOW, WINDOW, WINDOW, WINDOW, WINDOW);

    private static SourbyMetrics metrics() {
        return () -> CommandTestSupport.healthy(20.0, 8.0);
    }

    private static String plain(final List<Component> lines) {
        return CommandTestSupport.plain(lines);
    }

    @Test
    void aFoundRegionShowsItsCoordinatesIdentityAndWindows() {
        final RegionLocator.Result result = new RegionLocator.Result(
            RegionLocator.Status.FOUND, 3L, 7L, 11L, true, SNAPSHOT, 1, -2);

        final String text = plain(PerfCommand.renderRegion(metrics(), "world", 16, -32, result));

        assertTrue(text.contains("World / block: world / 16, -32"), text);
        assertTrue(text.contains("Chunk: 1, -2"), text);
        assertTrue(text.contains("world #3 region #7 gen 11"), text);
        assertFalse(text.contains("(inactive)"), text);
        assertTrue(text.contains("TPS (5s / 1m): 19.99 / 19.99"), text);
        assertTrue(text.contains("Average MSPT (5s / 1m): 8.00ms / 8.00ms"), text);
        assertTrue(text.contains("Maximum MSPT (5s): 16.0ms"), text);
        assertTrue(text.contains("Samples (5s): 100 ticks"), text);
    }

    @Test
    void anInactiveRegionIsMarkedAsSuch() {
        final RegionLocator.Result result = new RegionLocator.Result(
            RegionLocator.Status.FOUND, 3L, 7L, 11L, false, SNAPSHOT, 1, -2);

        final String text = plain(PerfCommand.renderRegion(metrics(), "world", 16, -32, result));
        assertTrue(text.contains("gen 11 (inactive)"), text);
    }

    @Test
    void eachUnresolvableStatusIsNamedHonestly() {
        final String unknown = plain(PerfCommand.renderRegion(metrics(), "world", 0, 0,
            new RegionLocator.Result(RegionLocator.Status.UNKNOWN_WORLD, 0L, 0L, 0L, false, null, 0, 0)));
        assertTrue(unknown.contains("unknown world 'world'"), unknown);

        final String noServer = plain(PerfCommand.renderRegion(metrics(), "world", 0, 0,
            new RegionLocator.Result(RegionLocator.Status.NO_SERVER, 0L, 0L, 0L, false, null, 0, 0)));
        assertTrue(noServer.contains("no server running"), noServer);

        final String noTelemetry = plain(PerfCommand.renderRegion(metrics(), "world", 0, 0,
            new RegionLocator.Result(RegionLocator.Status.NO_TELEMETRY, 3L, 7L, 0L, false, null, 0, 0)));
        assertTrue(noTelemetry.contains("world #3 region #7 has no recorded tick metrics yet"), noTelemetry);
    }

    @Test
    void regionWithCoordinatesRunsWithoutAServerAndReportsIt() {
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender sender = probe.sender(CommandSender.class, true);
        final PerfCommand command = CommandTestSupport.quietDenial(new PerfCommand("perf"));

        command.execute(sender, "perf", new String[]{"region", "world", "16", "-32"});

        final String text = CommandTestSupport.plainReceived(probe.received());
        assertTrue(text.contains("World / block: world / 16, -32"), text);
        assertTrue(text.contains("no server running"), text);
    }

    private static String reply(final String... args) {
        // A fresh probe per invocation, so each assertion sees only its own reply.
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender sender = probe.sender(CommandSender.class, true);
        CommandTestSupport.quietDenial(new PerfCommand("perf")).execute(sender, "perf", args);
        return CommandTestSupport.plainReceived(probe.received());
    }

    @Test
    void everyMalformedLocationFormShowsTheRegionUsage() {
        for (final String[] args : List.of(
                new String[]{"region", "world"},
                new String[]{"region", "world", "16"},
                new String[]{"region", "world", "16", "-32", "extra"},
                new String[]{"region", "world", "x", "-32"},
                new String[]{"region", "world", "16", "z"})) {
            final String text = reply(args);
            assertTrue(text.contains("/perf region <world> <x> <z>"), String.join(" ", args) + " -> " + text);
            assertFalse(text.contains("/perf ["), String.join(" ", args) + " fell through to the view list: " + text);
        }
    }

    @Test
    void bareRegionStaysTheOverview() {
        final String text = reply("region");
        assertTrue(text.contains("Performance / region"), text);
        assertTrue(text.contains("Active regions / retained generations"), text);
    }

    @Test
    void anUnresolvedChunkIsDescribedAsAnApproximateLookup() {
        // During a merge or split the section owner is briefly unpublished, so "not loaded" alone
        // would be wrong for a loaded chunk.
        final String text = plain(PerfCommand.renderRegion(metrics(), "world", 16, -32,
            new RegionLocator.Result(RegionLocator.Status.NO_REGION_AT_CHUNK, 3L, 0L, 0L, false, null, 1, -2)));
        assertTrue(text.contains("no region resolved for chunk 1, -2"), text);
        assertTrue(text.contains("merging or splitting"), text);
        assertFalse(text.contains("no region owns chunk"), text);
    }

    @Test
    void tabCompletionOffersWorldNamesAfterRegion() throws Exception {
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final World nether = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(nether.getName()).thenReturn("world_nether");
        when(server.getWorlds()).thenReturn(List.of(world, nether));

        final PerfCommand command = CommandTestSupport.quietDenial(new PerfCommand("perf"));
        final CommandSender sender = new ReplyProbe().sender(CommandSender.class, true);

        CommandTestSupport.withServer(server, () -> {
            assertEquals(List.of("region"), command.tabComplete(sender, "perf", new String[]{"reg"}));
            assertEquals(List.of("world", "world_nether"),
                command.tabComplete(sender, "perf", new String[]{"region", "wor"}));
            assertEquals(List.of("world_nether"),
                command.tabComplete(sender, "perf", new String[]{"region", "world_n"}));
        });
    }
}
