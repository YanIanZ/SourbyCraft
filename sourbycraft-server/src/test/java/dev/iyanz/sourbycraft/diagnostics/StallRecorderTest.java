package dev.iyanz.sourbycraft.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The stall state machine, driven by a fake clock and fake tick markers: no thread, no sleeping. */
class StallRecorderTest {

    private static final long S = TimeUnit.SECONDS.toNanos(1L);
    private static final long BASE = 1_000L * S; // arbitrary nanoTime origin, well above zero

    private final AtomicLong now = new AtomicLong(BASE);
    private final AtomicLong globalEnd = new AtomicLong(BASE);
    private final AtomicLong globalActive = new AtomicLong(StallRecorder.NO_ACTIVE_TICK);
    private final List<StallRecorder.Reading> regions = new ArrayList<>();
    private final List<StallRecorder.DumpRequest> dumps = new ArrayList<>();
    private final List<String> warns = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private boolean failDump;

    private StallRecorder recorder(final AuroraConfig.StallRecorder settings) {
        return new StallRecorder(settings, out -> {
            out.accept(new StallRecorder.Reading("global tick", true, this.globalEnd.get(), this.globalActive.get()));
            this.regions.forEach(out);
        }, request -> {
            if (this.failDump) {
                throw new IOException("disk full");
            }
            this.dumps.add(request);
            return request.file() != null ? request.file() : Path.of("logs/stalls/stall-" + request.stallNumber() + ".txt");
        }, this.now::get, this.stopping::get, this.warns::add, this.infos::add);
    }

    private static AuroraConfig.StallRecorder settings(final int after, final int repeat, final int max) {
        return new AuroraConfig.StallRecorder(true, after, repeat, max, 20);
    }

    /** Advances the clock by {@code seconds} one second at a time, polling after each step. */
    private void run(final StallRecorder recorder, final int seconds) {
        for (int i = 0; i < seconds; ++i) {
            this.now.addAndGet(S);
            recorder.poll();
        }
    }

    @Test
    void aTickingServerWritesNothing() {
        final StallRecorder recorder = recorder(AuroraConfig.StallRecorder.DEFAULT);
        for (int i = 0; i < 60; ++i) {
            this.now.addAndGet(S);
            this.globalEnd.set(this.now.get() - S / 20); // ticked 50 ms ago, every second
            recorder.poll();
        }
        assertTrue(this.dumps.isEmpty());
        assertTrue(this.warns.isEmpty());
        assertFalse(recorder.stallOpen());
    }

    @Test
    void firstDumpAtTheThresholdAndOnlyOnceBeforeTheRepeat() {
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 9);
        assertTrue(this.dumps.isEmpty(), "9 s without a tick is below the 10 s threshold");
        run(recorder, 1);
        assertEquals(1, this.dumps.size());
        assertEquals(StallRecorder.Kind.FIRST, this.dumps.get(0).kind());
        assertEquals(1, this.dumps.get(0).index());
        assertEquals(1, this.warns.size(), "exactly one WARN per stall");
        assertTrue(this.warns.get(0).contains("stall-1.txt"), this.warns.get(0));
        assertTrue(this.warns.get(0).contains("global tick"), this.warns.get(0));
        run(recorder, 9);
        assertEquals(1, this.dumps.size(), "no second dump before the repeat interval");
    }

    @Test
    void repeatsEveryIntervalAndStopsAtTheCap() {
        final StallRecorder recorder = recorder(settings(10, 10, 3));
        run(recorder, 10);   // t=10 dump 1
        run(recorder, 10);   // t=20 dump 2
        run(recorder, 10);   // t=30 dump 3
        assertEquals(3, this.dumps.size());
        assertEquals(List.of(1, 2, 3), this.dumps.stream().map(StallRecorder.DumpRequest::index).toList());
        assertEquals(StallRecorder.Kind.REPEAT, this.dumps.get(2).kind());
        assertNotNull(this.dumps.get(1).file(), "later dumps append to the file the first one created");
        assertNull(this.dumps.get(0).file());
        assertEquals(Path.of("logs/stalls/stall-1.txt"), this.dumps.get(1).file());
        run(recorder, 60);
        assertEquals(3, this.dumps.size(), "the cap holds however long the stall lasts");
        assertEquals(1, this.warns.size());
        assertTrue(recorder.stallOpen());
        assertEquals(3, recorder.dumpsInOpenStall());
    }

    @Test
    void recoveryEndsTheStallAndTheNextOneStartsFresh() {
        final StallRecorder recorder = recorder(settings(5, 5, 6));
        run(recorder, 12); // dumps at 5 and 10
        assertEquals(2, this.dumps.size());
        this.globalEnd.set(this.now.get()); // the tick completes
        this.now.addAndGet(S / 20);
        recorder.poll();
        assertFalse(recorder.stallOpen());
        assertEquals(1, this.infos.size());
        assertTrue(this.infos.get(0).contains("stall #1 ended"), this.infos.get(0));
        assertTrue(this.infos.get(0).contains("2 dump(s)"), this.infos.get(0));

        run(recorder, 6); // a second stall
        assertEquals(3, this.dumps.size());
        assertEquals(2, this.dumps.get(2).stallNumber());
        assertEquals(1, this.dumps.get(2).index(), "the dump count restarts");
        assertNull(this.dumps.get(2).file(), "a new stall gets its own file");
        assertEquals(2, this.warns.size());
    }

    @Test
    void aRegionStallIsDetectedWhileTheGlobalTickIsHealthy() {
        this.regions.add(new StallRecorder.Reading("region #7 (world id 1)", false, BASE, StallRecorder.NO_ACTIVE_TICK));
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        for (int i = 0; i < 12; ++i) {
            this.now.addAndGet(S);
            this.globalEnd.set(this.now.get());
            recorder.poll();
        }
        assertEquals(1, this.dumps.size());
        final List<StallRecorder.Stalled> stalled = this.dumps.get(0).stalled();
        assertEquals(1, stalled.size());
        assertEquals("region #7 (world id 1)", stalled.get(0).label());
        assertFalse(stalled.get(0).global());
        assertTrue(this.warns.get(0).contains("region #7"), this.warns.get(0));
    }

    @Test
    void theGlobalTickIsListedBeforeRegionsAndLongestFirst() {
        this.regions.add(new StallRecorder.Reading("region #1", false, BASE - 5 * S, StallRecorder.NO_ACTIVE_TICK));
        this.regions.add(new StallRecorder.Reading("region #2", false, BASE - 30 * S, StallRecorder.NO_ACTIVE_TICK));
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 11);
        assertEquals(List.of("region #2"), this.dumps.get(0).stalled().stream().map(StallRecorder.Stalled::label).toList(),
            "only region #2 had crossed the threshold at the first poll");
        final List<String> order = this.dumps.get(1).stalled().stream().map(StallRecorder.Stalled::label).toList();
        assertEquals(List.of("global tick", "region #2", "region #1"), order);
    }

    @Test
    void aSourceThatNeverCompletedATickIsOnlyAStallWhileATickIsRunning() {
        this.globalEnd.set(0L); // boot: no tick has completed yet
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 120);
        assertTrue(this.dumps.isEmpty(), "a slow boot is not a hang");

        this.globalActive.set(this.now.get()); // the first tick starts and never ends
        run(recorder, 10);
        assertEquals(1, this.dumps.size());
        assertEquals(10 * S, this.dumps.get(0).stalled().get(0).activeForNanos());
    }

    @Test
    void aLongTickInProgressReportsItsRunningTime() {
        this.globalActive.set(BASE + S);
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 10);
        final StallRecorder.Stalled stalled = this.dumps.get(0).stalled().get(0);
        assertEquals(10 * S, stalled.sinceLastTickNanos());
        assertEquals(9 * S, stalled.activeForNanos());
        assertTrue(this.warns.get(0).contains("a tick has been running for 9.0 s"), this.warns.get(0));
    }

    @Test
    void noNewStallOpensWhileTheServerIsStoppingButAnOpenOneContinues() {
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        this.stopping.set(true); // ticks stop by design during shutdown
        run(recorder, 120);
        assertTrue(this.dumps.isEmpty(), "a long final save is not a stall");

        this.stopping.set(false);
        run(recorder, 1);
        assertEquals(1, this.dumps.size());
        this.stopping.set(true); // the operator stops a hung server
        run(recorder, 10);
        assertEquals(2, this.dumps.size(), "an already open stall keeps being recorded");
    }

    @Test
    void aShutdownDuringAnOpenStallWritesOneFinalDumpToTheSameFile() {
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 10);
        run(recorder, 3);
        recorder.onShutdown();
        recorder.onShutdown();
        assertEquals(2, this.dumps.size(), "once only");
        final StallRecorder.DumpRequest last = this.dumps.get(1);
        assertEquals(StallRecorder.Kind.FINAL, last.kind());
        assertEquals(2, last.index());
        assertEquals(Path.of("logs/stalls/stall-1.txt"), last.file());
    }

    @Test
    void aShutdownWithNoStallWritesNothing() {
        final StallRecorder recorder = recorder(settings(10, 10, 6));
        run(recorder, 3);
        this.globalEnd.set(this.now.get());
        recorder.onShutdown();
        assertTrue(this.dumps.isEmpty());
    }

    @Test
    void aFailingDumperCountsTheAttemptWarnsOnceAndDoesNotLoop() {
        this.failDump = true;
        final StallRecorder recorder = recorder(settings(5, 5, 6));
        run(recorder, 30);
        assertTrue(this.dumps.isEmpty());
        assertEquals(1, this.warns.size(), "one line about the failure, not one per attempt: " + this.warns);
        assertTrue(this.warns.get(0).contains("could not write"), this.warns.get(0));
        assertEquals(6, recorder.dumpsInOpenStall(), "attempts count toward the cap, so a broken disk is not retried forever");
    }

    @Test
    void sourcesThatThrowBeforeTheEngineIsUpAreNotAStall() {
        final StallRecorder recorder = new StallRecorder(settings(1, 1, 6), out -> {
            throw new IllegalStateException("engine not started");
        }, request -> Path.of("x"), this.now::get, () -> false, this.warns::add, this.infos::add);
        run(recorder, 30);
        assertFalse(recorder.stallOpen());
        assertTrue(this.warns.isEmpty());
    }

    @Test
    void aThrowingStoppingSupplierIsTreatedAsNotStopping() {
        final StallRecorder recorder = new StallRecorder(settings(5, 5, 6), out ->
            out.accept(new StallRecorder.Reading("global tick", true, BASE, StallRecorder.NO_ACTIVE_TICK)),
            request -> Path.of("x"), this.now::get, () -> {
                throw new IllegalStateException("no server yet");
            }, this.warns::add, this.infos::add);
        run(recorder, 5);
        assertTrue(recorder.stallOpen());
    }

    @Test
    void enabledStartsADaemonWatcherThatCloseStops() throws Exception {
        final StallRecorder recorder = recorder(AuroraConfig.StallRecorder.DEFAULT);
        final long before = namedThreads();
        recorder.start();
        assertEquals(before + 1, namedThreads());
        final Thread thread = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("SourbyCraft-StallRecorder")).findFirst().orElseThrow();
        assertTrue(thread.isDaemon(), "must not keep the JVM alive");
        recorder.close();
        thread.join(5_000L);
        assertFalse(thread.isAlive());
    }

    @Test
    void disabledRecorderStartsNoThreadAtAll() {
        final long before = namedThreads();
        final StallRecorder recorder = recorder(new AuroraConfig.StallRecorder(false, 10, 10, 6, 20));
        recorder.start();
        assertEquals(before, namedThreads());
        recorder.close();
    }

    private static long namedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("SourbyCraft-StallRecorder") && t.isAlive()).count();
    }
}
