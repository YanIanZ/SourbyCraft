package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The route decision behind the owned {@code Entity#teleport}. Besides the answer, the order of the
 * questions is the region-safety property: the destination's chunk state is only read once the
 * caller is known to own the entity, the destination is in the same world, and the caller's region
 * owns the destination. A probe that answered out of order would read another region's state from
 * the wrong thread.
 */
class TeleportRouteTest {

    /** Records which questions were asked, in order. */
    private static final class Probe implements TeleportRoute.Probe {
        final boolean ownsEntity, sameWorld, destinationOwned, destinationLoaded;
        final List<String> asked = new ArrayList<>();

        Probe(final boolean ownsEntity, final boolean sameWorld, final boolean destinationOwned, final boolean destinationLoaded) {
            this.ownsEntity = ownsEntity;
            this.sameWorld = sameWorld;
            this.destinationOwned = destinationOwned;
            this.destinationLoaded = destinationLoaded;
        }

        @Override public boolean callerOwnsEntity() { this.asked.add("entity"); return this.ownsEntity; }
        @Override public boolean sameWorld() { this.asked.add("world"); return this.sameWorld; }
        @Override public boolean callerOwnsDestination() { this.asked.add("owned"); return this.destinationOwned; }
        @Override public boolean destinationLoaded() { this.asked.add("loaded"); return this.destinationLoaded; }
    }

    @Test
    void sameRegionLoadedDestinationRunsSynchronously() {
        final Probe probe = new Probe(true, true, true, true);
        assertEquals(TeleportRoute.Route.SYNC, TeleportRoute.decide(true, probe));
        assertEquals(List.of("entity", "world", "owned", "loaded"), probe.asked);
    }

    @Test
    void disabledRefusesWithoutAskingAnything() {
        final Probe probe = new Probe(true, true, true, true);
        assertEquals(TeleportRoute.Route.REFUSE, TeleportRoute.decide(false, probe));
        assertEquals(List.of(), probe.asked);
    }

    @Test
    void aCallerThatDoesNotOwnTheEntityIsScheduledAndNothingElseIsRead() {
        final Probe probe = new Probe(false, true, true, true);
        assertEquals(TeleportRoute.Route.ASYNC, TeleportRoute.decide(true, probe));
        assertEquals(List.of("entity"), probe.asked);
    }

    @Test
    void anotherWorldIsScheduledWithoutReadingTheDestination() {
        final Probe probe = new Probe(true, false, true, true);
        assertEquals(TeleportRoute.Route.ASYNC, TeleportRoute.decide(true, probe));
        assertEquals(List.of("entity", "world"), probe.asked);
    }

    @Test
    void anotherRegionIsScheduledWithoutReadingItsChunks() {
        final Probe probe = new Probe(true, true, false, true);
        assertEquals(TeleportRoute.Route.ASYNC, TeleportRoute.decide(true, probe));
        assertEquals(List.of("entity", "world", "owned"), probe.asked);
    }

    @Test
    void anOwnedButUnloadedDestinationIsScheduledSoTheChunkLoadsAsync() {
        final Probe probe = new Probe(true, true, true, false);
        assertEquals(TeleportRoute.Route.ASYNC, TeleportRoute.decide(true, probe));
    }

    @Test
    void theSwitchDefaultsOnAndOnlyAnExplicitFalseTurnsItOff() {
        assertEquals(true, TeleportRoute.enabledFrom(null));
        assertEquals(true, TeleportRoute.enabledFrom("true"));
        assertEquals(true, TeleportRoute.enabledFrom("yes-please"));
        assertEquals(false, TeleportRoute.enabledFrom("false"));
        assertEquals(false, TeleportRoute.enabledFrom(" FALSE "));
    }

    @Test
    void anOffThreadPlayerCallReadsNoPlayerStateAndIsLeftToTheRoute() {
        final boolean[] asked = {false};
        assertEquals(true, TeleportRoute.playerMayProceed(false, () -> { asked[0] = true; return false; }));
        assertEquals(false, asked[0], "the connection of a player another region owns is not read");
    }

    @Test
    void aPlayerWithoutConnectionFailsOnlyWhenTheCallerOwnsIt() {
        assertEquals(false, TeleportRoute.playerMayProceed(true, () -> false));
        assertEquals(true, TeleportRoute.playerMayProceed(true, () -> true));
    }

    // Relaxed thread checks (owner decision 2026-10-22): LightingLuminol's join semantics, bounded to threads
    // whose wait can never need their own tick.

    @Test
    void aThreadThatTicksNothingWaitsForTheMove() {
        final Probe probe = new Probe(false, true, false, false);
        assertTrue(TeleportRoute.joinsWhenRelaxed(probe, false));
        assertEquals(List.of(), probe.asked, "nothing of the entity is read off its region");
    }

    @Test
    void anOwningTickThreadWaitsOnlyForALoadedDestinationOwnedElsewhere() {
        assertTrue(TeleportRoute.joinsWhenRelaxed(new Probe(true, true, false, true), true));
        assertFalse(TeleportRoute.joinsWhenRelaxed(new Probe(true, true, false, false), true),
            "an unloaded destination may be merged into this region, whose tick is blocked (Folia#280): schedule instead");
        assertFalse(TeleportRoute.joinsWhenRelaxed(new Probe(true, true, true, true), true),
            "the caller owns the destination: that is the SYNC route, nothing to wait for");
        assertFalse(TeleportRoute.joinsWhenRelaxed(new Probe(false, true, false, true), true),
            "a tick thread that does not own the entity never blocks on another region's schedule");
    }

    @Test
    void relaxedQuestionsStopAtOwnershipWhenTheCallerDoesNotOwnTheEntity() {
        final Probe probe = new Probe(false, true, true, true);
        assertFalse(TeleportRoute.joinsWhenRelaxed(probe, true));
        assertEquals(List.of("entity"), probe.asked);
    }
}
