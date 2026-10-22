package io.papermc.paper.threadedregions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Feature patch 0025 ("disable region watchdog by default"): with the JVM property
 * {@code sourbycraft.region-watchdog.enabled} unset, the Folia diagnostic watchdog must be inert.
 * This is the unit-level half of {@code docs/development/REGION-WATCHDOG.md} acceptance case 3
 * (both disabled paths return early) and the thread-name check of case 1, on the real patched
 * {@link FoliaWatchdogThread}. It does not replace the live-server runs cases 1, 2 and 4 ask for.
 *
 * <p>{@code ENABLED} is a static final read once at class load, so the flag-on path cannot be
 * exercised in this JVM; every test here assumes the default (unset) flag and skips otherwise.
 */
@org.bukkit.support.environment.Normal
class FoliaWatchdogDisabledTest {

    private static final String PROPERTY = "sourbycraft.region-watchdog.enabled";
    private static final String THREAD_NAME = "Folia Watchdog Thread";

    private static void assumeDefaultFlag() {
        assumeFalse(Boolean.getBoolean(PROPERTY), "test JVM sets " + PROPERTY + "=true");
        assumeFalse(FoliaWatchdogThread.isEnabled(), "watchdog class loaded with the flag on");
    }

    @SuppressWarnings("unchecked")
    private static Set<FoliaWatchdogThread.RunningTick> ticksOf(final FoliaWatchdogThread watchdog) throws Exception {
        final Field field = FoliaWatchdogThread.class.getDeclaredField("ticks");
        field.setAccessible(true);
        return (Set<FoliaWatchdogThread.RunningTick>) field.get(watchdog);
    }

    @Test
    void defaultFlagIsOff() {
        assertNull(System.getProperty(PROPERTY), "the test JVM must not pre-set the watchdog flag");
        assertFalse(FoliaWatchdogThread.isEnabled(), "patch 0025: watchdog is opt-in, default false");
    }

    @Test
    void disabledWatchdogRetainsNoTicks() throws Exception {
        assumeDefaultFlag();
        final FoliaWatchdogThread watchdog = new FoliaWatchdogThread();
        final FoliaWatchdogThread.RunningTick tick =
            new FoliaWatchdogThread.RunningTick(System.nanoTime(), null, Thread.currentThread());

        watchdog.addTick(tick);
        assertTrue(ticksOf(watchdog).isEmpty(), "addTick must return early while disabled");

        // The scheduler passes null while disabled (patch 0025 TickRegionScheduler hunks); both paths tolerate it.
        assertDoesNotThrow(() -> watchdog.addTick(null));
        assertDoesNotThrow(() -> watchdog.removeTick(null));
        assertDoesNotThrow(() -> watchdog.removeTick(tick));
        assertTrue(ticksOf(watchdog).isEmpty());
    }

    @Test
    void disabledRunReturnsImmediatelyOnCallingThread() {
        assumeDefaultFlag();
        final FoliaWatchdogThread watchdog = new FoliaWatchdogThread();
        // Enabled, run() is an endless 1 s poll loop; disabled, it must return before touching the scheduler.
        // Calling it inline makes a regression a hang of this test rather than a false pass.
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) watchdog::run);
        assertFalse(watchdog.isAlive(), "run() on the calling thread never starts the Thread");
    }

    @Test
    void noWatchdogThreadIsLiveWhileDisabled() {
        assumeDefaultFlag();
        // Whether or not another test already loaded TickRegionScheduler (whose static initialiser is the
        // only start() call), a flag-off JVM must never have a live thread with the watchdog's name.
        final boolean live = Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> THREAD_NAME.equals(thread.getName()) && thread.isAlive());
        assertFalse(live, "no '" + THREAD_NAME + "' may be alive with " + PROPERTY + " unset");
    }
}
