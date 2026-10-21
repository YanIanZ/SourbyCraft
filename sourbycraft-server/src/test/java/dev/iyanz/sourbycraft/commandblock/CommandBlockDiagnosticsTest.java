package dev.iyanz.sourbycraft.commandblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CommandBlockDiagnosticsTest {

    @Test
    void logsOncePerBlockAndCommandWithBoth() {
        final List<String> lines = new ArrayList<>();
        final CommandBlockDiagnostics diagnostics = new CommandBlockDiagnostics(lines::add);
        assertTrue(diagnostics.report("minecraft:overworld 0,120,0", "fill 1 2 3 4 5 6 stone", "No blocks were filled"));
        assertFalse(diagnostics.report("minecraft:overworld 0,120,0", "fill 1 2 3 4 5 6 stone", "No blocks were filled"));
        assertTrue(diagnostics.report("minecraft:overworld 0,121,0", "fill 1 2 3 4 5 6 stone", "No blocks were filled"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("0,120,0") && lines.get(0).contains("fill 1 2 3 4 5 6 stone")
            && lines.get(0).contains("No blocks were filled"));
        assertEquals(1, diagnostics.suppressed());
    }

    @Test
    void keysAreBounded() {
        final List<String> lines = new ArrayList<>();
        final CommandBlockDiagnostics diagnostics = new CommandBlockDiagnostics(lines::add);
        for (int i = 0; i < CommandBlockDiagnostics.MAX_KEYS + 10; i++) {
            diagnostics.report("block " + i, "say", "m");
        }
        assertEquals(CommandBlockDiagnostics.MAX_KEYS, lines.size());
        assertEquals(10, diagnostics.suppressed());
    }

    @Test
    void concurrentReportersLogOnce() throws Exception {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final CommandBlockDiagnostics diagnostics = new CommandBlockDiagnostics(lines::add);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                diagnostics.report("b", "c", "m");
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, lines.size());
        assertEquals(7, diagnostics.suppressed());
    }
}
