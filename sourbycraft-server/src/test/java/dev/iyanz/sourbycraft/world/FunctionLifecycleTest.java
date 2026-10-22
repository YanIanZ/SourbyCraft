package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FunctionLifecycleTest {
    private final FunctionLifecycle lifecycle = new FunctionLifecycle();
    private final Map<String, List<String>> tags = new HashMap<>();
    private final List<String> ran = new ArrayList<>();
    private final List<String> queried = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    private final FunctionLifecycle.Target<String> target = new FunctionLifecycle.Target<>() {
        @Override
        public Collection<String> tag(final String tag) {
            queried.add(tag);
            return tags.getOrDefault(tag, List.of());
        }

        @Override
        public void execute(final String function) {
            ran.add(function);
            if (function.startsWith("throw")) {
                throw new IllegalStateException(function);
            }
        }
    };

    private FunctionLifecycle.Ran tick(final boolean runs, final boolean tickFunctions) {
        return lifecycle.tick(runs, tickFunctions, target, (function, failure) -> failed.add(function));
    }

    @Test
    void loadRunsOncePerPublishedLibraryAndBeforeTick() {
        tags.put(FunctionLifecycle.LOAD_TAG, List.of("load:a", "load:b"));
        tags.put(FunctionLifecycle.TICK_TAG, List.of("tick:a"));
        lifecycle.published(); // boot
        assertEquals(new FunctionLifecycle.Ran(2, 1), tick(true, true));
        assertEquals(List.of("load:a", "load:b", "tick:a"), ran);
        tick(true, true);
        tick(true, true);
        assertEquals(List.of("load:a", "load:b", "tick:a", "tick:a", "tick:a"), ran, "load not repeated");

        lifecycle.published(); // a successful reload
        ran.clear();
        tick(true, true);
        assertEquals(List.of("load:a", "load:b", "tick:a"), ran, "a new generation runs load once more");
        assertEquals(2, lifecycle.generation());
    }

    @Test
    void nothingRunsBeforeTheFirstPublishAndAFailedReloadStartsNoGeneration() {
        tags.put(FunctionLifecycle.LOAD_TAG, List.of("load:a"));
        assertEquals(FunctionLifecycle.Ran.NOTHING, tick(true, false));
        lifecycle.published();
        tick(true, false);
        // A reload that fails never reaches ServerFunctionManager.replaceLibrary, so no publish:
        tick(true, false);
        tick(true, false);
        assertEquals(List.of("load:a"), ran);
    }

    @Test
    void tickTagIsNeverReadWhileDisabled() {
        tags.put(FunctionLifecycle.TICK_TAG, List.of("tick:a"));
        lifecycle.published();
        for (int i = 0; i < 5; i++) {
            tick(true, false);
        }
        assertTrue(ran.isEmpty());
        assertFalse(queried.contains(FunctionLifecycle.TICK_TAG), "default off: the tick tag is not even looked up");
        assertEquals(List.of(FunctionLifecycle.LOAD_TAG), queried);

        tick(true, true); // LIVE: the next tick after switching on
        tick(true, true);
        assertEquals(List.of("tick:a", "tick:a"), ran);
        tick(true, false);
        assertEquals(2, ran.size(), "switching off stops it on the next tick");
    }

    @Test
    void frozenTicksDeferLoadAndTickLikeVanilla() {
        tags.put(FunctionLifecycle.LOAD_TAG, List.of("load:a"));
        tags.put(FunctionLifecycle.TICK_TAG, List.of("tick:a"));
        lifecycle.published();
        assertEquals(FunctionLifecycle.Ran.NOTHING, tick(false, true));
        assertEquals(FunctionLifecycle.Ran.NOTHING, tick(false, true));
        assertTrue(ran.isEmpty() && queried.isEmpty());
        tick(true, true);
        assertEquals(List.of("load:a", "tick:a"), ran);
    }

    @Test
    void aThrowingFunctionIsReportedTheRestStillRunAndLoadIsNotRetried() {
        tags.put(FunctionLifecycle.LOAD_TAG, List.of("throw:load", "load:b"));
        tags.put(FunctionLifecycle.TICK_TAG, List.of("throw:tick", "tick:b"));
        lifecycle.published();
        assertEquals(new FunctionLifecycle.Ran(2, 2), tick(true, true));
        assertEquals(List.of("throw:load", "load:b", "throw:tick", "tick:b"), ran);
        assertEquals(List.of("throw:load", "throw:tick"), failed);
        ran.clear();
        tick(true, true);
        assertEquals(List.of("throw:tick", "tick:b"), ran, "load ran once even though one of its functions threw");
    }

    @Test
    void publishFromAnotherThreadIsSeenByTheNextTick() throws InterruptedException {
        tags.put(FunctionLifecycle.LOAD_TAG, List.of("load:a"));
        final Thread reload = new Thread(lifecycle::published);
        reload.start();
        reload.join();
        tick(true, false);
        assertEquals(List.of("load:a"), ran);
    }
}
