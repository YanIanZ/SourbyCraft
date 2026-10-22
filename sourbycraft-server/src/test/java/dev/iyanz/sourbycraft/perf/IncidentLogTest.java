package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.perf.IncidentLog.Breach;
import dev.iyanz.sourbycraft.perf.IncidentLog.Incident;
import dev.iyanz.sourbycraft.perf.IncidentLog.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentLogTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long GAP = IncidentLog.MERGE_GAP_MILLIS;

    private static Breach region(final double value) {
        return new Breach(Kind.REGION_OVER_BUDGET, "world #1 region #7", value, 50.0);
    }

    private static Incident only(final IncidentLog log) {
        final List<Incident> incidents = log.view().incidents();
        assertEquals(1, incidents.size(), incidents.toString());
        return incidents.get(0);
    }

    @Test
    void aSingleBreachIsOneOngoingIncidentSeenOnce() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(80.0)));

        final Incident incident = only(log);
        assertTrue(incident.ongoing());
        assertEquals(T0, incident.startEpochMillis());
        assertEquals(T0, incident.endEpochMillis());
        assertEquals(1, incident.observations());
        assertEquals(80.0, incident.peak());
        assertEquals(50.0, incident.threshold());
    }

    @Test
    void consecutiveBreachesMergeIntoOneIncidentWithADurationAndThePeak() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(60.0)));
        log.pass(T0 + 1_000, List.of(region(95.0)));
        log.pass(T0 + 2_000, List.of(region(70.0)));

        final Incident incident = only(log);
        assertEquals(T0, incident.startEpochMillis());
        assertEquals(T0 + 2_000, incident.endEpochMillis());
        assertEquals(2_000, incident.durationMillis());
        assertEquals(95.0, incident.peak(), "peak is the largest, not the latest");
        assertEquals(3, incident.observations());
    }

    @Test
    void aBreachWithinTheMergeGapExtendsTheSameIncident() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(60.0)));
        log.pass(T0 + 1_000, List.of());
        log.pass(T0 + GAP, List.of(region(61.0)));   // exactly the gap: still the same incident

        final Incident incident = only(log);
        assertEquals(T0, incident.startEpochMillis());
        assertEquals(T0 + GAP, incident.endEpochMillis());
        assertEquals(2, incident.observations());
    }

    @Test
    void anIncidentClosesOnlyAfterTheGapHasPassedWithoutABreach() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(60.0)));

        log.pass(T0 + GAP, List.of());
        assertTrue(only(log).ongoing(), "at exactly the gap it is still open");

        log.pass(T0 + GAP + 1, List.of());
        final Incident closed = only(log);
        assertFalse(closed.ongoing());
        assertEquals(T0, closed.endEpochMillis(), "the end is the last breach, not the closing pass");
        assertEquals(1L, log.view().closedTotal());
    }

    @Test
    void aBreachAfterALongerGapStartsANewIncidentEvenWithoutAnEmptyPassBetween() {
        // A stalled collector means no pass in between; the old incident must not stretch over it.
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(60.0)));
        log.pass(T0 + GAP + 1, List.of(region(70.0)));

        final List<Incident> incidents = log.view().incidents();
        assertEquals(2, incidents.size());
        assertEquals(T0 + GAP + 1, incidents.get(0).startEpochMillis(), "newest first");
        assertTrue(incidents.get(0).ongoing());
        assertEquals(T0, incidents.get(1).startEpochMillis());
        assertFalse(incidents.get(1).ongoing());
    }

    @Test
    void differentSubjectsOrKindsAreSeparateIncidents() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(region(60.0),
            new Breach(Kind.REGION_OVER_BUDGET, "world #1 region #8", 55.0, 50.0),
            new Breach(Kind.HEAP_PRESSURE, "world #1 region #7", 95.0, 90.0)));

        assertEquals(3, log.view().incidents().size());
    }

    @Test
    void theRingKeepsTheNewestClosedIncidentsAndCountsTheOverwritten() {
        final IncidentLog log = new IncidentLog();
        final int total = IncidentLog.CAPACITY + 5;
        for (int i = 0; i < total; i++) {
            final long at = T0 + i * (GAP + 1_000);
            log.pass(at, List.of(new Breach(Kind.LANE_REJECTED, "lane " + i, 1.0, 1.0)));
        }
        log.pass(T0 + total * (GAP + 1_000), List.of());   // closes the last one

        final IncidentLog.View view = log.view();
        assertEquals(IncidentLog.CAPACITY, view.incidents().size());
        assertEquals(total, view.closedTotal());
        assertEquals(5, view.overwritten());
        assertEquals("lane " + (total - 1), view.incidents().get(0).subject(), "newest first");
        assertEquals("lane 5", view.incidents().get(IncidentLog.CAPACITY - 1).subject(), "lanes 0-4 overwritten");
        assertTrue(view.incidents().stream().noneMatch(Incident::ongoing));
    }

    @Test
    void openIncidentsAreBoundedAndTheExcessIsCounted() {
        final IncidentLog log = new IncidentLog();
        final List<Breach> many = new java.util.ArrayList<>();
        for (int i = 0; i <= IncidentLog.MAX_OPEN; i++) {
            many.add(new Breach(Kind.STORAGE_BACKLOG, "world " + i, 2_000, 1_000));
        }
        log.pass(T0, many);

        assertEquals(IncidentLog.MAX_OPEN, log.view().incidents().size());
        assertEquals(1L, log.view().untrackedBreaches());
    }

    @Test
    void theThresholdShownIsTheOneInForceAtTheLastBreach() {
        final IncidentLog log = new IncidentLog();
        log.pass(T0, List.of(new Breach(Kind.REGION_OVER_BUDGET, "r", 60.0, 50.0)));
        log.pass(T0 + 1_000, List.of(new Breach(Kind.REGION_OVER_BUDGET, "r", 60.0, 40.0)));

        assertEquals(40.0, only(log).threshold());
    }
}
