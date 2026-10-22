package dev.iyanz.aurora.engine.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * ENG-3: a world unload drains what its regions still hold, and the AWF follow-up: a world
 * without regions still closes its storage.
 */
class WorldUnloadDrainTest {

    /** Records the order of steps; can be told to fail one. */
    private static final class Recorder implements WorldUnloadDrain.Steps<String> {
        final List<String> calls = new ArrayList<>();
        String failRetireFor;
        String entered;

        @Override public void enter(final String region) { this.entered = region; this.calls.add("enter " + region); }
        @Override public void exit() { this.calls.add("exit " + this.entered); this.entered = null; }

        @Override
        public int retireEntitySchedulers(final String region) {
            this.calls.add("retire " + region + " entered=" + region.equals(this.entered));
            if (region.equals(this.failRetireFor)) throw new IllegalStateException("retired callback threw");
            return 2;
        }

        @Override public int cancelRegionSchedulerTasks(final String region) { this.calls.add("scheduled " + region); return 1; }
        @Override public int cancelQueuedTasks(final String region) { this.calls.add("queued " + region); return 3; }
    }

    @Test
    void everyRegionIsDrainedWhileItIsEntered() {
        final Recorder steps = new Recorder();
        final WorldUnloadDrain.Result result = WorldUnloadDrain.drain(List.of("a", "b"), steps, (m, t) -> {});
        assertEquals(List.of(
            "enter a", "retire a entered=true", "scheduled a", "queued a", "exit a",
            "enter b", "retire b entered=true", "scheduled b", "queued b", "exit b"), steps.calls);
        assertEquals(new WorldUnloadDrain.Result(4, 2, 6, 0), result);
        assertTrue(result.anything());
    }

    @Test
    void aFailingStepIsReportedAndTheRestStillRun() {
        final Recorder steps = new Recorder();
        steps.failRetireFor = "a";
        final List<String> errors = new ArrayList<>();
        final WorldUnloadDrain.Result result = WorldUnloadDrain.drain(List.of("a", "b"), steps, (m, t) -> errors.add(m));
        assertEquals(1, result.failures());
        assertEquals(List.of("Failed to retire entity schedulers"), errors);
        assertTrue(steps.calls.contains("queued a"), "the region's other steps still ran");
        assertTrue(steps.calls.contains("exit a"));
        assertTrue(steps.calls.contains("retire b entered=true"));
    }

    @Test
    void anEmptyWorldDrainsNothing() {
        assertFalse(WorldUnloadDrain.drain(List.of(), new Recorder(), (m, t) -> {}).anything());
    }

    @Test
    void aWorldWithoutRegionsClosesItsStorageDirectly() {
        final AtomicInteger closed = new AtomicInteger();
        assertTrue(WorldUnloadDrain.closeStorageWithoutRegions(0, closed::incrementAndGet));
        assertEquals(1, closed.get());
        assertFalse(WorldUnloadDrain.closeStorageWithoutRegions(2, closed::incrementAndGet),
            "with regions, the last region's chunk save closes it");
        assertEquals(1, closed.get());
    }

    @Test
    void theShutdownThreadRunsTheDrainAndTheClose() throws IOException {
        // The wiring is in WorldShutdownThread (MC feature patch 0033), which needs a live world.
        final List<String> calls = new ArrayList<>();
        final String resource = "/" + WorldShutdownThread.class.getName().replace('.', '/') + ".class";
        try (InputStream input = WorldShutdownThread.class.getResourceAsStream(resource)) {
            assertTrue(input != null);
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!method.methodName().stringValue().equals("unload")) continue;
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().endsWith("/WorldUnloadDrain")) {
                        calls.add(invoke.name().stringValue());
                    }
                }
            }
        }
        assertEquals(List.of("closeStorageWithoutRegions", "drain"), calls);
    }
}
