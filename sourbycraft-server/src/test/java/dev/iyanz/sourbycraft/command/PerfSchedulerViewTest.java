package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.GlobalTick;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Runners;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Scheduler;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.WorldRegions;
import java.util.List;
import org.bukkit.command.CommandSender;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PerfSchedulerViewTest {

    private static final long MS = 1_000_000L;

    private static SchedulerQueueSnapshot snapshot(final GlobalTick global, final WorldRegions... worlds) {
        return new SchedulerQueueSnapshot(true, "", new Scheduler("AFFINITY", 4, 4, 4, 20.0, 4),
            new Runners(1, 2, 1, 0), global, List.of(worlds));
    }

    private static WorldRegions world(final String name, final int total, final int ready, final int ticking,
                                      final int overdue, final long worstOverdue, final int pending,
                                      final int inProgress, final long longest) {
        return new WorldRegions(name, total, ready, ticking, 1, 0, 0, overdue, worstOverdue, pending, inProgress, longest);
    }

    private static Map<String, String> rows(final SchedulerQueueSnapshot snapshot) {
        return PerfSchedulerView.lines(snapshot).stream().filter(line -> !line.isSection())
            .collect(Collectors.toMap(PerfSchedulerView.Line::label, PerfSchedulerView.Line::value, (a, b) -> a + " | " + b));
    }

    @Test
    void anUnavailableSchedulerSaysWhyAndShowsNoFigures() {
        final List<PerfSchedulerView.Line> lines =
            PerfSchedulerView.lines(SchedulerQueueSnapshot.unavailable("region scheduler not started"));

        assertEquals(List.of("Region scheduler", "Region scheduler: unavailable — region scheduler not started"),
            lines.stream().map(PerfSchedulerView.Line::plain).toList());
    }

    @Test
    void regionTotalsAreSummedAcrossWorldsAndTheWorstIsTheWorstOfAll() {
        final Map<String, String> rows = rows(snapshot(new GlobalTick(0L, -1L),
            world("world", 10, 7, 2, 2, 3 * MS, 4, 2, 12 * MS),
            world("world_nether", 3, 1, 1, 1, 9 * MS, 0, 1, 2 * MS)));

        assertEquals("13 / 8 / 3", rows.get("Regions total / ready / ticking"));
        assertEquals("2 / 0 / 0", rows.get("Merging-splitting / dead / descheduled"));
        assertEquals("3 (worst 9.0ms late)", rows.get("Ready but past their tick start"));
        assertEquals("4", rows.get("With pending tasks or packets"));
        assertEquals("3 (longest 12.0ms)", rows.get("Ticks in progress"));
        assertEquals("10 regions, 2 ticking, 2 late (worst 3.0ms), 4 with tasks", rows.get("  world"));
        assertEquals("3 regions, 1 ticking, 1 late (worst 9.0ms), 0 with tasks", rows.get("  world_nether"));
    }

    @Test
    void schedulerAndThreadFiguresAreShownAndRacyOnesAreMarkedApproximate() {
        final Map<String, String> rows = rows(snapshot(new GlobalTick(0L, -1L)));

        assertEquals("AFFINITY", rows.get("Scheduler (threaded-regions.scheduler)"));
        assertEquals("4 / 4 / 4", rows.get("Threads configured / in pool / alive"));
        assertEquals("20.0 TPS / 4", rows.get("Tick rate / grid-exponent"));
        assertEquals("2 / 1 / 1", rows.get("Threads on region / global / idle (approximate)"));
        assertTrue(PerfSchedulerView.lines(snapshot(new GlobalTick(0L, -1L))).stream()
            .anyMatch(line -> line.isSection() && line.section().contains("approximate")),
            "the region counts are read without locks and must say so");
    }

    @Test
    void unknownSchedulerFiguresAreNotPrintedAsNumbers() {
        final SchedulerQueueSnapshot snapshot = new SchedulerQueueSnapshot(true, "", Scheduler.UNKNOWN,
            new Runners(0, 0, 0, 3), GlobalTick.NONE, List.of());
        final Map<String, String> rows = rows(snapshot);

        assertEquals("unknown", rows.get("Scheduler (threaded-regions.scheduler)"));
        assertEquals("unknown / unknown / unknown", rows.get("Threads configured / in pool / alive"));
        assertEquals("unknown / unknown", rows.get("Tick rate / grid-exponent"));
        assertEquals("0 / 0 / 0 (+3 unrecognised)", rows.get("Threads on region / global / idle (approximate)"));
        assertEquals("n/a (no scheduled start)", rows.get("Next tick late by (approximate)"));
        assertEquals("none", rows.get("Tick in progress"));
    }

    @Test
    void theGlobalTickIsOnTimeLateOrRunning() {
        assertEquals("on time", rows(snapshot(new GlobalTick(MS / 2, -1L))).get("Next tick late by (approximate)"));
        assertEquals("25.0ms", rows(snapshot(new GlobalTick(25 * MS, -1L))).get("Next tick late by (approximate)"));
        assertEquals("running for 48.5ms", rows(snapshot(new GlobalTick(0L, 48_500_000L))).get("Tick in progress"));
    }

    @Test
    void whatTheEngineDoesNotExposeIsNotAvailableWithAReasonNeverAZero() {
        final Map<String, String> rows = rows(snapshot(GlobalTick.NONE));

        for (final String label : List.of("Queued tasks per region", "Scheduler run queue / idle threads",
            "Late ticks over time", "Queued global tasks")) {
            final String value = rows.get(label);
            assertNotNull(value, label);
            assertTrue(value.startsWith("n/a (") && value.endsWith(")"), label + " -> " + value);
        }
    }

    @Test
    void componentsCarryTheSameTextAsTheLines() {
        final SchedulerQueueSnapshot snapshot = snapshot(new GlobalTick(3 * MS, -1L),
            world("world", 1, 1, 0, 0, 0L, 0, 0, 0L));
        final Function<net.kyori.adventure.text.Component, String> plain = PlainTextComponentSerializer.plainText()::serialize;

        final List<String> rendered = PerfSchedulerView.components(snapshot).stream().map(plain).map(String::strip).toList();
        final List<String> expected = PerfSchedulerView.lines(snapshot).stream()
            .map(PerfSchedulerView.Line::plain).map(String::strip).toList();

        assertEquals(expected, rendered);
    }

    @Test
    void schedulerIsAcceptedOfferedAndListedInTheUsage() {
        assertTrue(PerfCommand.VIEWS.contains("scheduler"));
        final PerfCommand command = CommandTestSupport.quietDenial(new PerfCommand("perf"));
        final CommandSender sender = new ReplyProbe().sender(CommandSender.class, true);

        assertEquals(List.of("scheduler"), command.tabComplete(sender, "perf", new String[]{"sch"}));
        assertTrue(command.getUsage().contains("|scheduler|"), command.getUsage());
        // /ram is the memory alias: it takes no view and offers none.
        assertEquals(List.of(), CommandTestSupport.quietDenial(new PerfCommand("ram"))
            .tabComplete(sender, "ram", new String[]{"sch"}));
    }
}
