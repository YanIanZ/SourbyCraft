package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.iyanz.sourbycraft.execution.Admission;
import dev.iyanz.sourbycraft.execution.OwnerHandoff;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

@AllFeatures
class WanderingTraderCoordinatorTest {
    private static final WanderingTraderCoordinator.Settings SETTINGS =
        new WanderingTraderCoordinator.Settings(2, 4, 25, 75, 25);

    private static final class Backend implements WanderingTraderCoordinator.Backend<String> {
        long clock;
        boolean enabled = true;
        boolean present = true;
        boolean ownerEnabled = true;
        boolean refused;
        int attempts;
        int draws;
        int selection;
        int chanceRoll;
        boolean onOwner;
        String target;
        final Queue<Runnable> owners = new ArrayDeque<>();
        final Queue<Runnable> global = new ArrayDeque<>();
        public long tick() { return clock; }
        public boolean enabled() { return enabled; }
        public List<String> players() { return List.of("region-a", "region-b", "region-c"); }
        public int random(int bound) { draws++; return bound == 3 ? selection : chanceRoll; }
        public OwnerHandoff<String> handoff(String player) {
            target = player;
            return (delivery, retirement) -> {
                if (refused) return Admission.REJECTED;
                owners.add(() -> {
                    onOwner = true;
                    try { if (present) delivery.accept(player); else retirement.run(); }
                    finally { onOwner = false; }
                });
                return Admission.ACCEPTED;
            };
        }
        public boolean eligible(String player) { assertTrue(onOwner); return present; }
        public boolean ownerEnabled(String player) { assertTrue(onOwner); return ownerEnabled; }
        public boolean spawn(String player) { assertTrue(onOwner); attempts++; return false; }
        public void onGlobal(Runnable task) { global.add(task); }
        void deliver() { while (!owners.isEmpty()) owners.remove().run(); while (!global.isEmpty()) global.remove().run(); }
    }

    private WanderingTraderData due() {
        WanderingTraderData data = new WanderingTraderData(2, 25);
        data.setTickDelay(1);
        return data;
    }

    @Test
    void oneCountdownForThreeRegionsAndOnePendingHandoff() {
        Backend backend = new Backend();
        WanderingTraderData data = due();
        data.coordinator.tick(data, SETTINGS, 1, backend);
        data.coordinator.tick(data, SETTINGS, 1, backend); // same fake clock tick
        assertEquals(1, backend.owners.size());
        backend.clock++;
        data.coordinator.tick(data, SETTINGS, 1, backend); // slow region must not build a queue
        assertEquals(1, backend.owners.size());
        backend.deliver();
        assertEquals(1, backend.attempts);
        assertEquals(4, data.spawnDelay());
        assertEquals(50, data.spawnChance());
    }

    @Test
    void chosenPlayerDeterminesHandoffOwner() {
        Backend backend = new Backend();
        backend.selection = 2;
        WanderingTraderData data = due();
        data.coordinator.tick(data, SETTINGS, 1, backend);
        assertEquals("region-c", backend.target);
        assertEquals(0, backend.attempts, "spawn cannot run on the global submitter");
        backend.deliver();
        assertEquals(1, backend.attempts);
    }

    @Test
    void retiredPlayerConsumesIntervalAndResetsChanceLikeVanillaNoPlayer() {
        Backend backend = new Backend();
        WanderingTraderData data = due();
        data.coordinator.tick(data, SETTINGS, 1, backend);
        backend.present = false;
        backend.deliver();
        assertEquals(0, backend.attempts);
        assertEquals(4, data.spawnDelay());
        assertEquals(2, data.tickDelay);
        assertEquals(25, data.spawnChance());
    }

    @Test
    void codecReloadPreservesAllCountdownStateWithoutReinitializingDelayOrChance() {
        WanderingTraderData data = new WanderingTraderData(1234, 65);
        data.setTickDelay(37);
        var encoded = WanderingTraderData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        WanderingTraderData loaded = WanderingTraderData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(1234, loaded.spawnDelay());
        assertEquals(65, loaded.spawnChance());
        assertEquals(37, loaded.tickDelay);
        loaded.coordinator.tick(loaded, SETTINGS, 1, new Backend());
        assertEquals(36, loaded.tickDelay);
        assertEquals(1234, loaded.spawnDelay());
        assertEquals(65, loaded.spawnChance());
    }

    @Test
    void gameruleOffFreezesCountdownAndOwnerRechecksIt() {
        Backend backend = new Backend();
        WanderingTraderData data = due();
        backend.enabled = false;
        data.coordinator.tick(data, SETTINGS, 1, backend);
        assertTrue(backend.owners.isEmpty());
        assertEquals(1, data.tickDelay);
        backend.enabled = true;
        backend.clock++;
        data.coordinator.tick(data, SETTINGS, 1, backend);
        backend.ownerEnabled = false;
        backend.deliver();
        assertEquals(0, backend.attempts);
    }

    @Test
    void entryRequiresGlobalOwnershipBeforeAccessingTheWorld() {
        RegionOwnership fake = (RegionOwnership) Proxy.newProxyInstance(
            RegionOwnership.class.getClassLoader(), new Class<?>[] {RegionOwnership.class},
            (proxy, method, args) -> {
                assertEquals("ensureGlobalTickThread", method.getName());
                throw new IllegalStateException("fake non-global caller");
            });
        RegionOwnership previous = RegionOwnership.replace(fake);
        try {
            assertEquals("fake non-global caller", assertThrows(IllegalStateException.class,
                () -> WanderingTraderCoordinator.tick(null, 1)).getMessage());
        } finally {
            RegionOwnership.replace(previous);
        }
    }

    @Test
    void failedChanceRollAndRefusedHandoffConsumeTheIntervalWithoutSpawning() {
        for (boolean refused : List.of(false, true)) {
            Backend backend = new Backend();
            backend.refused = refused;
            backend.chanceRoll = 99;
            WanderingTraderData data = due();
            data.coordinator.tick(data, SETTINGS, 1, backend);
            backend.deliver();
            assertEquals(0, backend.attempts);
            assertEquals(4, data.spawnDelay());
            assertEquals(50, data.spawnChance());
            backend.clock++;
            data.coordinator.tick(data, SETTINGS, 1, backend);
            assertEquals(1, data.tickDelay, "refusal must release pending bookkeeping");
        }
    }

    @Test
    void eachSnapshotIndexCanSelectItsPlayerWithoutWeightingByRegionPopulation() {
        for (int index = 0; index < 3; index++) {
            Backend backend = new Backend();
            backend.selection = index;
            WanderingTraderData data = due();
            data.coordinator.tick(data, SETTINGS, 1, backend);
            assertEquals(List.of("region-a", "region-b", "region-c").get(index), backend.target);
        }
    }

    @Test
    void legacyCodecWithoutCountdownPreservesSavedDelayAndChance() {
        var legacy = com.google.gson.JsonParser.parseString("{\"spawn_delay\":1234,\"spawn_chance\":65}");
        WanderingTraderData loaded = WanderingTraderData.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
        loaded.coordinator.tick(loaded, SETTINGS, 1, new Backend());
        assertEquals(1, loaded.tickDelay);
        assertEquals(1234, loaded.spawnDelay());
        assertEquals(65, loaded.spawnChance());
    }

    @Test
    void patchLivesInFeaturesAndContainsBothTickCallSitesAndPersistence() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sourbycraft-server/minecraft-patches/features"))) root = root.getParent();
        assertNotNull(root);
        Path patch = root.resolve("sourbycraft-server/minecraft-patches/features/0068-SourbyCraft-one-wandering-trader-spawner-per-world-on-the-global-tick.patch");
        String text = Files.readString(patch);
        assertTrue(text.contains("WanderingTraderCoordinator.tick(world, tickCount)"));
        // Subject must match the file name, so rebuildMinecraftFeaturePatches keeps this path
        // (build-data/inherited-guards.json names it).
        // git wraps long subjects onto an indented continuation line; unfold it first.
        assertTrue(text.replace("\n ", " ").contains("Subject: [PATCH] SourbyCraft - one wandering trader spawner per world on the global tick\n"));
        assertTrue(text.contains("WanderingTraderCoordinator.completeSpawn(level, trader"));
        assertFalse(text.contains("ownsSpawnedTrader(level, trader)) return false"),
            "a trader already in the world must never be reported as a failed spawn");
        assertTrue(text.contains("-    private WanderingTraderData getTraderData() {"));
        assertTrue(text.contains("-    public WanderingTraderData wanderingTraderData;"));
        assertTrue(text.contains("global coordinator owns trader ticking"));
        assertTrue(text.contains("tick_delay"));
        assertTrue(text.contains("spawnForPlayer"));
    }

    @Test
    void catchUpTickCountsDownByTheElapsedGameTicks() {
        WanderingTraderCoordinator.Settings settings = new WanderingTraderCoordinator.Settings(20, 4, 25, 75, 25);
        Backend backend = new Backend();
        WanderingTraderData data = new WanderingTraderData(100, 25);
        data.setTickDelay(10);
        data.coordinator.tick(data, settings, 3, backend);
        assertEquals(7, data.tickDelay, "one global tick that advanced game time by 3 counts 3");
        backend.clock++;
        data.coordinator.tick(data, settings, 7, backend);
        assertEquals(20, data.tickDelay, "reaching the minute fires and restarts the countdown");
        assertEquals(80, data.spawnDelay());
    }

    @Test
    void spawnedTraderAlwaysCountsAndIsOnlySetUpWhileOwned() {
        int[] home = {0};
        int[] llamas = {0};
        // A spawn-event plugin moved it away: still the spawn (chance resets), nothing written cross-region.
        assertTrue(WanderingTraderCoordinator.completeSpawn(() -> false, () -> home[0]++, () -> llamas[0]++));
        assertEquals(0, home[0]);
        assertEquals(0, llamas[0]);
        // Owned throughout: home first, then both llamas.
        assertTrue(WanderingTraderCoordinator.completeSpawn(() -> true, () -> home[0]++, () -> llamas[0]++));
        assertEquals(1, home[0]);
        assertEquals(2, llamas[0]);
        // The first llama's spawn event moved the trader: home already set, no second llama.
        boolean[] owned = {true};
        assertTrue(WanderingTraderCoordinator.completeSpawn(() -> owned[0], () -> home[0]++, () -> { llamas[0]++; owned[0] = false; }));
        assertEquals(2, home[0]);
        assertEquals(3, llamas[0]);
    }
}
