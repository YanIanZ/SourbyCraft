package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.bridge.ViolationClassifier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionRefusalTest {

    /** The alternatives each refusal must name: all exist in this build (see the javadoc of RegionRefusal). */
    private static final Map<RegionRefusal, List<String>> MUST_NAME = new EnumMap<>(RegionRefusal.class);

    static {
        MUST_NAME.put(RegionRefusal.PLUGIN_RELOAD, List.of("Server#reload()", "/sourbycraft reload", "Restart the server"));
        MUST_NAME.put(RegionRefusal.DATA_RELOAD, List.of("Server#reloadData()", "datapack", "Restart the server"));
        MUST_NAME.put(RegionRefusal.DATAPACK_TOGGLE, List.of("Datapack#setEnabled", "restart the server"));
        MUST_NAME.put(RegionRefusal.UNLOAD_WORLD, List.of("Server#unloadWorld", "unloadWorldAsync", "AuroraWorlds#unload"));
        MUST_NAME.put(RegionRefusal.RESTART, List.of("Server#restart()", "/restart", "/stop"));
        MUST_NAME.put(RegionRefusal.SERVER_QUEUE, List.of("MinecraftServer#submit", "getRegionScheduler", "getGlobalRegionScheduler", "getAsyncScheduler"));
        MUST_NAME.put(RegionRefusal.UNDRAINED_QUEUE, List.of("processQueue", "never run", "getRegionScheduler", "getGlobalRegionScheduler"));
        MUST_NAME.put(RegionRefusal.TICK_COUNT, List.of("getTickCount()", "Server#getCurrentTick()"));
        MUST_NAME.put(RegionRefusal.TICK_LOOP, List.of("tick loop", "getRegionScheduler", "getGlobalRegionScheduler"));
        MUST_NAME.put(RegionRefusal.ADD_TICKABLE, List.of("addTickable", "getGlobalRegionScheduler"));
        MUST_NAME.put(RegionRefusal.ENSURE_MAIN, List.of("MCUtil#ensureMain", "Entity#getScheduler", "getGlobalRegionScheduler"));
        MUST_NAME.put(RegionRefusal.TICK_MANAGER, List.of("ServerTickManager", "getRegionThreadingTickRateManager", "getTPS"));
        MUST_NAME.put(RegionRefusal.BUKKIT_SCHEDULER, List.of("BukkitScheduler", "getGlobalRegionScheduler", "Entity#getScheduler", "Aurora Bridge"));
    }

    @Test
    void everyRefusalIsCoveredByTheTableAndNamesItsAlternative() {
        assertEquals(RegionRefusal.values().length, MUST_NAME.size(), "a new refusal needs an entry here");
        for (final RegionRefusal refusal : RegionRefusal.values()) {
            final String message = refusal.message();
            for (final String needle : MUST_NAME.get(refusal)) {
                assertTrue(message.contains(needle), refusal + " must mention '" + needle + "': " + message);
            }
        }
    }

    @Test
    void everyMessageKeepsTheLegacyPrefixAndSaysMoreThanItDid() {
        for (final RegionRefusal refusal : RegionRefusal.values()) {
            final String message = refusal.message();
            assertTrue(message.startsWith("Unsupported in region threading: "), message);
            assertFalse(message.equals("Unsupported in region threading"), "bare legacy text is the thing being replaced");
            assertTrue(message.endsWith("."), message);
            assertTrue(message.length() > 80, "an explanation, not a label: " + message);
        }
    }

    @Test
    void theExceptionIsAnUnsupportedOperationWithTheMessage() {
        final UnsupportedOperationException error = RegionRefusal.PLUGIN_RELOAD.error();
        assertEquals(RegionRefusal.PLUGIN_RELOAD.message(), error.getMessage());
        assertEquals(RegionRefusal.TICK_COUNT.message() + " (from here)", RegionRefusal.TICK_COUNT.error("from here").getMessage());
    }

    @Test
    void theBridgeStillCountsEveryOwnedRefusalAsARegionViolation() {
        // A bridged plugin's refused call must keep counting toward its quarantine, which the classifier
        // decides from the message: the owned text must not lose the phrase it matches on.
        for (final RegionRefusal refusal : RegionRefusal.values()) {
            assertTrue(ViolationClassifier.isRegionViolation(refusal.error()), refusal.toString());
            assertTrue(ViolationClassifier.isRegionViolation(new RuntimeException("task failed", refusal.error("detail"))), refusal.toString());
        }
    }
}
