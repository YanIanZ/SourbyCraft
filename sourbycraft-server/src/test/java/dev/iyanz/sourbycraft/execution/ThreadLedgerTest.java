package dev.iyanz.sourbycraft.execution;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit ENG-9: the boot-time thread ledger. Formatting only; it changes no pool size. */
class ThreadLedgerTest {

    @Test
    void totalsConfiguredThreadsAgainstProcessors() {
        final List<String> lines = ThreadLedger.format(List.of(
            new ThreadLedger.Pool("Region tick", 8, "paper-global.yml threaded-regions.threads"),
            new ThreadLedger.Pool("Chunk workers", 2, "paper-global.yml chunk-system.worker-threads"),
            new ThreadLedger.Pool("Netty event loops", 4, "spigot.yml settings.netty-threads"),
            new ThreadLedger.Pool("Async pathfinding", 0, "aurora.entity.async-pathfinding")), 8);
        assertEquals("Thread ledger: 14 configured platform thread(s) in 4 pool(s) for 8 available processor(s)"
            + " (1.8x); configured ceilings, not measured load", lines.get(0));
        assertEquals("  Region tick          8  (paper-global.yml threaded-regions.threads)", lines.get(1));
        assertEquals("  Chunk workers        2  (paper-global.yml chunk-system.worker-threads)", lines.get(2));
        assertEquals("  Async pathfinding  off  (aurora.entity.async-pathfinding)", lines.get(4),
            "a pool that is off counts nothing");
        assertTrue(lines.get(lines.size() - 1).startsWith("  Not counted:"));
        assertEquals(6, lines.size());
    }

    @Test
    void aPoolNotSetUpYetIsUnknownAndNotCounted() {
        final List<String> lines = ThreadLedger.format(List.of(
            new ThreadLedger.Pool("Region tick", 4, "a"),
            new ThreadLedger.Pool("Chunk I/O", -1, "b")), 4);
        assertEquals("Thread ledger: 4 configured platform thread(s) in 2 pool(s) for 4 available processor(s)"
            + " (1.0x), 1 pool(s) not set up yet; configured ceilings, not measured load", lines.get(0));
        assertEquals("  Chunk I/O      ?  (b)", lines.get(2));
    }

    @Test
    void collectNeverThrowsOutsideAServer() {
        // No paper-global.yml and no chunk system in a unit test: those pools read as unknown.
        final List<ThreadLedger.Pool> pools = ThreadLedger.collect();
        assertEquals(8, pools.size());
        assertEquals(ThreadLedger.format(pools, 8).size(), pools.size() + 2);
    }
}
