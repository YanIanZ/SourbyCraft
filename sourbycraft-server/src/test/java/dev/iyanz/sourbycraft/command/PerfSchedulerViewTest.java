package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.GlobalTick;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.LateTicks;
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
        return snapshot(global, new LateTicks(0L, 0L, 0L, 0L), worlds);
    }

    private static SchedulerQueueSnapshot snapshot(final GlobalTick global, final LateTicks late, final WorldRegions... worlds) {
        return new SchedulerQueueSnapshot(true, "", new Scheduler("AFFINITY", 4, 4, 4, 20.0, 4),
            new Runners(1, 2, 1, 0), global, List.of(worlds), late);
    }

    private static WorldRegions world(final String name, final int total, final int ready, final int ticking,
                                      final int overdue, final long worstOverdue, final int pending,
                                      final int inProgress, final long longest) {
        return world(name, total, ready, ticking, overdue, worstOverdue, pending, inProgress, longest, 0L, 0L, 0L);
    }

    private static WorldRegions world(final String name, final int total, final int ready, final int ticking,
                                      final int overdue, final long worstOverdue, final int pending,
                                      final int inProgress, final long longest, final long queuedTick,
                                      final long queuedChunk, final long largest) {
        return new WorldRegions(name, total, ready, ticking, 1, 0, 0, overdue, worstOverdue, pending, inProgress, longest,
            queuedTick, queuedChunk, largest);
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
        assertEquals("10 regions, 2 ticking, 2 late (worst 3.0ms), 4 with tasks, 0 queued", rows.get("  world"));
        assertEquals("3 regions, 1 ticking, 1 late (worst 9.0ms), 0 with tasks, 0 queued", rows.get("  world_nether"));
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
            new Runners(0, 0, 0, 3), GlobalTick.NONE, List.of(), LateTicks.NONE);
        final Map<String, String> rows = rows(snapshot);

        assertEquals("unknown", rows.get("Scheduler (threaded-regions.scheduler)"));
        assertEquals("unknown / unknown / unknown", rows.get("Threads configured / in pool / alive"));
        assertEquals("unknown / unknown", rows.get("Tick rate / grid-exponent"));
        assertEquals("0 / 0 / 0 (+3 unrecognised)", rows.get("Threads on region / global / idle (approximate)"));
        assertEquals("n/a (no scheduled start)", rows.get("Next tick late by (approximate)"));
        assertEquals("none", rows.get("Tick in progress"));
        assertEquals("n/a (engine counters not readable)", rows.get("Late tick starts"));
        assertNull(rows.get("Region ticks started > 1.0ms late"), "unknown counters are not printed as numbers");
    }

    @Test
    void queuedTasksAreSummedAcrossWorldsWithTheLargestSingleRegion() {
        final Map<String, String> rows = rows(snapshot(new GlobalTick(0L, -1L),
            world("world", 10, 7, 2, 0, 0L, 4, 0, 0L, 30L, 5L, 21L),
            world("world_nether", 3, 1, 1, 0, 0L, 1, 0, 0L, 2L, 40L, 40L)));

        assertEquals("32 / 45 (largest region 40; entries, incl. cancelled not yet polled)",
            rows.get("Queued region tasks tick / chunk"));
        assertEquals("10 regions, 2 ticking, 0 late, 4 with tasks, 35 queued", rows.get("  world"));
        assertEquals("3 regions, 1 ticking, 0 late, 1 with tasks, 42 queued", rows.get("  world_nether"));
    }

    @Test
    void lateTickStartsAreShownAsLateOfStartedWithTheThreshold() {
        final Map<String, String> rows = rows(snapshot(new GlobalTick(0L, -1L), new LateTicks(4000L, 37L, 200L, 0L)));

        assertEquals("37 of 4000 started (0.93%)", rows.get("Region ticks started > 1.0ms late"));
        assertEquals("0 of 200 started (0.00%)", rows.get("Global ticks started > 1.0ms late"));
        assertTrue(PerfSchedulerView.lines(snapshot(new GlobalTick(0L, -1L))).stream()
            .anyMatch(line -> line.isSection() && line.section().equals("Late tick starts since boot (approximate)")));
    }

    @Test
    void noTicksStartedYetIsNotADivisionByZero() {
        assertEquals("0 of 0 started", rows(snapshot(new GlobalTick(0L, -1L))).get("Region ticks started > 1.0ms late"));
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
        // Both used to be n/a; the feature patch exposes them now.
        assertNull(rows.get("Queued tasks per region"));
        assertNull(rows.get("Late ticks over time"));

        for (final String label : List.of("Scheduler run queue / idle threads", "Queued global tasks")) {
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
