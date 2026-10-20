package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** The {@code /perf} view registry: one class per view, one registration line, visibility honoured. */
class PerfViewRegistryTest {

    /** A view only senders holding {@code perf.secret} may see. */
    private static final PerfView SECRET = new PerfView() {
        @Override public String name() { return "secret"; }
        @Override public boolean visibleTo(final CommandSender sender) { return sender.hasPermission("perf.secret"); }
        @Override public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
            PerfRows.add(lines, "Secret", "shown");
        }
    };

    private static PerfCommand command() {
        return CommandTestSupport.quietDenial(new PerfCommand("perf",
            new PerfViews(List.of(PerfView.of("open", lines -> PerfRows.add(lines, "Open", "shown")), SECRET))));
    }

    /** May use the command, but not the secret view. */
    private static CommandSender withoutSecret(final ReplyProbe probe) {
        final CommandSender sender = probe.sender(CommandSender.class, true);
        org.mockito.Mockito.when(sender.hasPermission("perf.secret")).thenReturn(false);
        return sender;
    }

    @Test
    void aViewTheSenderMayNotSeeIsNeitherCompletedNorRendered() {
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender sender = withoutSecret(probe);
        assertEquals(List.of("open"), command().tabComplete(sender, "perf", new String[]{""}));
        command().execute(sender, "perf", new String[]{"secret"});
        final String text = CommandTestSupport.plainReceived(probe.received());
        assertEquals("/perf [open|secret]\n", text, "an invisible view answers with the usage text");
    }

    @Test
    void aVisibleViewIsCompletedAndRenderedInsideTheFrame() {
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender sender = probe.sender(CommandSender.class, true);
        assertEquals(List.of("open", "secret"), command().tabComplete(sender, "perf", new String[]{""}));
        command().execute(sender, "perf", new String[]{"secret"});
        final String text = CommandTestSupport.plainReceived(probe.received());
        assertTrue(text.contains("Performance / secret"), text);
        assertTrue(text.contains("Secret: shown"), text);
    }

    @Test
    void twoViewsMayNotShareAName() {
        assertThrows(IllegalArgumentException.class, () -> new PerfViews(List.of(
            PerfView.of("tick", lines -> {}), PerfView.of("tick", lines -> {}))));
    }

    @Test
    void theDefaultRegistryListsEveryViewOnceAndHidesOnlyTheOverview() {
        assertEquals(19, PerfCommand.VIEWS.size());
        assertTrue(PerfViews.DEFAULT.find("overview") != null && !PerfCommand.VIEWS.contains("overview"));
        for (final String view : PerfCommand.VIEWS) {
            assertTrue(PerfViews.DEFAULT.find(view) != null, view);
        }
    }
}
