package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a synchronous teleport does after the Bukkit teleport event, whose listeners may have
 * removed the entity, started another (async) transfer of it, changed the destination or the
 * passenger tree. An obsolete outer teleport is aborted, never resumed against the moved entity.
 */
class AfterEventsRouteTest {

    private static final class Probe implements TeleportRoute.AfterEventsProbe {
        boolean removed, owned = true, transferring, sameWorld = true, destinationOwned = true, loaded = true, passenger;
        final List<String> asked = new ArrayList<>();

        @Override public boolean removed() { asked.add("removed"); return removed; }
        @Override public boolean callerOwnsEntity() { asked.add("owned"); return owned; }
        @Override public boolean inRegionTransfer() { asked.add("transfer"); return transferring; }
        @Override public boolean asPassenger() { return passenger; }
        @Override public boolean sameWorld() { asked.add("world"); return sameWorld; }
        @Override public boolean callerOwnsDestination() { asked.add("dest"); return destinationOwned; }
        @Override public boolean destinationLoaded() { asked.add("loaded"); return loaded; }
    }

    @Test
    void anUnchangedSafeTeleportContinues() {
        final Probe probe = new Probe();
        assertEquals(TeleportRoute.AfterEvents.CONTINUE, TeleportRoute.afterEvents(probe));
        assertEquals(List.of("removed", "owned", "transfer", "world", "dest", "loaded"), probe.asked);
    }

    @Test
    void aListenerThatRemovedTheEntityAbortsWithoutReadingMore() {
        final Probe probe = new Probe();
        probe.removed = true;
        assertEquals(TeleportRoute.AfterEvents.ABORT, TeleportRoute.afterEvents(probe));
        assertEquals(List.of("removed"), probe.asked);
    }

    @Test
    void aListenerThatStartedAnotherTransferAbortsTheOuterTeleport() {
        final Probe probe = new Probe();
        probe.transferring = true;
        assertEquals(TeleportRoute.AfterEvents.ABORT, TeleportRoute.afterEvents(probe));
        assertEquals(List.of("removed", "owned", "transfer"), probe.asked);
    }

    @Test
    void anEntityThatLeftThisRegionDuringTheEventAbortsAndIsNotRead() {
        final Probe probe = new Probe();
        probe.owned = false;
        assertEquals(TeleportRoute.AfterEvents.ABORT, TeleportRoute.afterEvents(probe));
        assertEquals(List.of("removed", "owned"), probe.asked);
    }

    @Test
    void aDestinationMovedOffTheRegionIsReroutedAndItsChunksAreNotRead() {
        final Probe probe = new Probe();
        probe.destinationOwned = false;
        assertEquals(TeleportRoute.AfterEvents.REROUTE, TeleportRoute.afterEvents(probe));
        assertEquals(List.of("removed", "owned", "transfer", "world", "dest"), probe.asked);
    }

    @Test
    void aDestinationInAnotherWorldOrUnloadedIsRerouted() {
        final Probe world = new Probe();
        world.sameWorld = false;
        assertEquals(TeleportRoute.AfterEvents.REROUTE, TeleportRoute.afterEvents(world));
        final Probe unloaded = new Probe();
        unloaded.loaded = false;
        assertEquals(TeleportRoute.AfterEvents.REROUTE, TeleportRoute.afterEvents(unloaded));
    }

    @Test
    void aPassengerWhoseDestinationLeftTheRegionStaysSeated() {
        final Probe probe = new Probe();
        probe.passenger = true;
        probe.destinationOwned = false;
        assertEquals(TeleportRoute.AfterEvents.ABORT, TeleportRoute.afterEvents(probe));
    }
}
