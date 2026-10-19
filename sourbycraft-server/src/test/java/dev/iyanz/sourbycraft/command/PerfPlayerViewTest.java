package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@code /perf player <name>}: the view is gathered on the player's entity thread, and from there
 * it must reach the sender on the sender's own thread (OBS-8).
 */
class PerfPlayerViewTest {

    private final Plugin owner = mock(Plugin.class);

    /** What the player's entity scheduler does with the task. */
    private enum Scheduler { RUNS_IT, RETIRES_IT, REFUSES_IT }

    /** A player whose scheduler acts at once, the way the entity thread would. */
    private static Player player(final ReplyProbe probe, final boolean asSender, final Scheduler mode) {
        final Player player = asSender ? probe.sender(Player.class, true) : mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        final World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getLocation()).thenReturn(new Location(world, 17.5, 64.0, -33.2));
        when(player.getPing()).thenReturn(42);
        when(player.getViewDistance()).thenReturn(10);
        when(player.getSimulationDistance()).thenReturn(8);
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            final Consumer<ScheduledTask> task = invocation.getArgument(1);
            final Runnable retired = invocation.getArgument(2);
            switch (mode) {
                case RUNS_IT -> task.accept(mock(ScheduledTask.class));
                case RETIRES_IT -> retired.run();
                case REFUSES_IT -> {
                    return null;
                }
            }
            return mock(ScheduledTask.class);
        });
        return player;
    }

    @Test
    void aCommandBlockGetsTheViewThroughItsRegionHop() {
        final ReplyProbe probe = new ReplyProbe();
        final BlockCommandSender block = probe.sender(BlockCommandSender.class, true);
        assertEquals(SourbyReply.Hop.REGION, SourbyReply.hopFor(block));

        PerfCommand.renderPlayer(block, player(probe, false, Scheduler.RUNS_IT), this.owner, probe);

        final List<ReplyProbe.Received> lines = probe.received();
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(ReplyProbe.Received::throughHop), lines.toString());
        assertEquals(1, probe.hops(), "the whole view is one hop, not one per line");
        final String text = String.join("\n", lines.stream().map(ReplyProbe.Received::text).toList());
        assertTrue(text.contains("Player: Steve"), text);
        assertTrue(text.contains("World / block: world / 17, 64, -34"), text);
        assertTrue(text.contains("Chunk: 1, -3"), text);
        assertTrue(text.contains("Ping: 42 ms"), text);
        assertTrue(text.contains("View / simulation distance: 10 / 8"), text);
    }

    @Test
    void thePlayerLookingAtThemselvesIsAlreadyOnTheRightThread() {
        final ReplyProbe probe = new ReplyProbe();
        final Player player = player(probe, true, Scheduler.RUNS_IT);

        PerfCommand.renderPlayer(player, player, this.owner, probe);

        final List<ReplyProbe.Received> lines = probe.received();
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().noneMatch(ReplyProbe.Received::throughHop), "no second hop onto the same thread");
        assertEquals(0, probe.hops());
    }

    @Test
    void aPlayerWhoLeftIsReportedThroughTheHop() {
        final ReplyProbe probe = new ReplyProbe();
        final BlockCommandSender block = probe.sender(BlockCommandSender.class, true);

        PerfCommand.renderPlayer(block, player(probe, false, Scheduler.RETIRES_IT), this.owner, probe);

        final List<ReplyProbe.Received> lines = probe.received();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals("Steve left before the view was taken", lines.getFirst().text());
        assertTrue(lines.getFirst().throughHop());
    }

    @Test
    void aPlayerBeingRemovedIsReportedOnTheCommandThread() {
        final ReplyProbe probe = new ReplyProbe();
        final BlockCommandSender block = probe.sender(BlockCommandSender.class, true);
        PerfCommand.renderPlayer(block, player(probe, false, Scheduler.REFUSES_IT), this.owner, probe);

        final List<ReplyProbe.Received> lines = probe.received();
        assertEquals(List.of("Steve is being removed"), lines.stream().map(ReplyProbe.Received::text).toList());
        assertFalse(lines.getFirst().throughHop(), "execute has not returned yet: the command thread is the right one");
    }
}
