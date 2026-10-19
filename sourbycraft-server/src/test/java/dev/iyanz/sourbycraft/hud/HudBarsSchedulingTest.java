package dev.iyanz.sourbycraft.hud;

import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 follow-up: the toggle / preference / auto-show / updater-task paths of {@link HudBars}.
 *
 * <p>These run through the package-private {@code HudBars.installForTesting} seam: the global
 * region scheduler is replaced by a recorder (no running server), and the player's entity
 * scheduler is a queue the test flushes by hand, so "show on the player's region" and the
 * retired callback can be asserted without any sleeping. The boss bars are process-wide statics,
 * so each test restores nothing but calls {@link HudBars#close()} and compares bar state against
 * values it set itself.</p>
 */
class HudBarsSchedulingTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private RecordingScheduler scheduler;
    private AutoCloseable restore;
    private MetricsHolder metrics;

    /**
     * {@code HudBars} hands {@code MinecraftInternalPlugin.INSTANCE} to the entity scheduler, and that
     * class's static initialiser builds a {@code PluginLogger} from {@code Bukkit.getServer().getLogger()}.
     * A throw-away {@link Server} proxy that only answers {@code getLogger} is installed for exactly
     * that one initialisation, then the previous {@code Bukkit.server} is put back.
     */
    @BeforeAll
    static void initialiseThePluginOwner() throws Exception {
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        final Object previous = field.get(null);
        final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("HudBarsSchedulingTest");
        field.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getLogger")) return logger;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return zero(method.getReturnType());
            }));
        try {
            assertNotNull(MinecraftInternalPlugin.INSTANCE);
        } finally {
            field.set(null, previous);
        }
    }

    @BeforeEach
    void install() {
        HudBars.close();
        scheduler = new RecordingScheduler();
        metrics = new MetricsHolder();
        restore = HudBars.installForTesting(scheduler, metrics);
    }

    @AfterEach
    void uninstall() throws Exception {
        HudBars.close();
        restore.close();
    }

    // ------------------------------------------------------------------------------- toggling

    @Test
    void firstToggleShowsTheBarOnThePlayersRegionAndStartsTheUpdaterOnce() {
        final FakePlayer player = new FakePlayer();

        assertTrue(HudBars.toggle(player.proxy(), true));
        assertEquals(1, scheduler.scheduled, "updater scheduled lazily on the first toggle");
        assertTrue(player.shown.isEmpty(), "show/hide hops to the entity scheduler, nothing runs inline");

        player.flush();
        assertEquals(1, player.shown.size());
        assertEquals(List.of("TPS"), labels(player.shown), "the shared TPS bar");

        assertTrue(HudBars.toggle(player.proxy(), false));
        assertEquals(1, scheduler.scheduled, "a second bar does not start a second updater");
    }

    @Test
    void secondToggleHidesTheBarAndRecordsTheOptOut() {
        final FakePlayer player = new FakePlayer();
        HudBars.toggle(player.proxy(), true);
        player.flush();
        final BossBar tpsBar = player.shown.get(0);
        assertFalse(player.optedOut("hud_tps_optout"), "showing opts back in");

        assertFalse(HudBars.toggle(player.proxy(), true));
        player.flush();

        assertEquals(List.of(tpsBar), player.hidden);
        assertTrue(player.optedOut("hud_tps_optout"), "hiding persists the opt-out so join auto-show respects it");
        assertFalse(player.optedOut("hud_ram_optout"), "the other bar is untouched");
    }

    @Test
    void setPreferenceIsIdempotentAndWritesTheOptOutBothWays() {
        final FakePlayer player = new FakePlayer();

        assertTrue(HudBars.setPreference(player.proxy(), false, true));
        assertTrue(HudBars.setPreference(player.proxy(), false, true));
        player.flush();
        assertEquals(2, player.shown.size(), "each call re-shows (showBossBar is idempotent on the client)");
        assertSame(player.shown.get(0), player.shown.get(1));
        assertFalse(player.optedOut("hud_ram_optout"));

        assertFalse(HudBars.setPreference(player.proxy(), false, false));
        assertFalse(HudBars.setPreference(player.proxy(), false, false));
        player.flush();
        assertEquals(2, player.hidden.size());
        assertTrue(player.optedOut("hud_ram_optout"));
        assertEquals(1, scheduler.scheduled);
    }

    @Test
    void closeCancelsTheUpdaterClearsViewersAndAllowsRescheduling() {
        final FakePlayer player = new FakePlayer();
        HudBars.toggle(player.proxy(), true);
        assertEquals(1, scheduler.scheduled);

        HudBars.close();
        assertEquals(1, scheduler.cancelled, "close() cancels the running updater");

        // Viewers were cleared: the next toggle on the same player is a SHOW again, and restarts the updater.
        assertTrue(HudBars.toggle(player.proxy(), true));
        assertEquals(2, scheduler.scheduled);
    }

    // ---------------------------------------------------------------------------- perf bar

    @Test
    void setPerfTogglesPersistsAndShowsOrHidesThePerfBar() {
        final FakePlayer player = new FakePlayer();

        assertTrue(HudBars.setPerf(player.proxy(), null), "null toggles: off -> on");
        assertEquals((byte) 1, player.pdc.get(new NamespacedKey("sourbycraft", "hud_perf_enabled")));
        player.flush();
        assertEquals(List.of("PERF"), labels(player.shown));

        assertTrue(HudBars.setPerf(player.proxy(), true), "explicit values are idempotent");
        assertFalse(HudBars.setPerf(player.proxy(), null), "null toggles: on -> off");
        player.flush();
        // Show/hide bodies decide from the viewer set when they RUN, so both queued tasks hide.
        assertEquals(List.of("PERF", "PERF"), labels(player.hidden));
        assertEquals(List.of("PERF"), labels(player.shown));
        assertEquals((byte) 0, player.pdc.get(new NamespacedKey("sourbycraft", "hud_perf_enabled")));
        assertEquals(1, scheduler.scheduled);
    }

    @Test
    void aRetiredPlayerSchedulerDropsThePerfViewer() {
        final FakePlayer player = new FakePlayer();
        HudBars.setPerf(player.proxy(), true);
        player.retire();   // the entity was removed before the task could run

        // The viewer is gone, so an explicit toggle is a fresh ON (null would now mean off -> on).
        assertTrue(HudBars.setPerf(player.proxy(), null));
    }

    // ------------------------------------------------------------------------- auto show on join

    @Test
    void autoShowGivesPermittedPlayersBothBars() {
        final FakePlayer player = new FakePlayer().permit(HudBars.AUTO_PERMISSION);

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertEquals(Set.of("TPS", "RAM"), new HashSet<>(labels(player.shown)));
        assertEquals(2, player.shown.size());
        assertEquals(1, scheduler.scheduled);
    }

    @Test
    void autoShowSkipsABarTheAdminOptedOutOf() {
        final FakePlayer player = new FakePlayer().permit(HudBars.AUTO_PERMISSION);
        player.setOptOut("hud_tps_optout");

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertEquals(List.of("RAM"), labels(player.shown));
    }

    @Test
    void autoShowDoesNothingWithoutThePermission() {
        final FakePlayer player = new FakePlayer();

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertTrue(player.shown.isEmpty());
        assertEquals(0, scheduler.scheduled, "no viewer, so no updater is started");
    }

    @Test
    void autoShowRestoresAPersistedPerfBarForPerfbarHolders() {
        final FakePlayer player = new FakePlayer().permit("sourbycraft.command.perfbar");
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_perf_enabled"), (byte) 1);

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertEquals(List.of("PERF"), labels(player.shown), "perf bar only; no AUTO permission");
    }

    @Test
    void joinListenerAutoShowsAfterOneSecondOnThePlayersScheduler() {
        final FakePlayer player = new FakePlayer().permit(HudBars.AUTO_PERMISSION);

        new HudBars.QuitListener().onJoin(new PlayerJoinEvent(player.proxy(), Component.empty()));
        assertEquals(1, player.delayedTicks.size());
        assertEquals(20L, player.delayedTicks.get(0), "20 ticks = the 1s handshake grace");
        assertTrue(player.shown.isEmpty(), "nothing shown before the delay elapses");

        player.flush();   // runs the delayed autoShowOnJoin, then the show tasks it queued
        player.flush();
        assertEquals(Set.of("TPS", "RAM"), new HashSet<>(labels(player.shown)));
    }

    @Test
    void quitListenerEvictsTheViewerSoTheNextToggleShowsAgain() {
        final FakePlayer player = new FakePlayer();
        HudBars.toggle(player.proxy(), true);
        player.flush();

        new HudBars.QuitListener().onQuit(
            new PlayerQuitEvent(player.proxy(), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(1, player.hiddenNow.size(), "onQuit hides directly, not through the scheduler");

        assertTrue(HudBars.toggle(player.proxy(), true), "viewer was evicted, so this is a show");
    }

    // ---------------------------------------------------------------------------- updater task

    @Test
    void updaterTaskRendersTpsForTpsViewers() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 18.0, 30.0, 0L, 0L);
        HudBars.toggle(player.proxy(), true);
        player.flush();
        final BossBar tpsBar = player.shown.get(0);

        scheduler.runTask();

        final String text = PLAIN.serialize(tpsBar.name());
        assertTrue(text.contains("TPS 18.00/20.00") && text.contains("MSPT"), text);
        assertEquals(0.9F, tpsBar.progress(), 1.0E-5F);
        assertEquals(BossBar.Color.GREEN, tpsBar.color());

        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 10.0, 80.0, 0L, 0L);
        scheduler.runTask();
        assertEquals(0.5F, tpsBar.progress(), 1.0E-5F);
        assertEquals(BossBar.Color.RED, tpsBar.color());
    }

    @Test
    void updaterTaskRendersHeapForRamViewers() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 512L << 20, 1024L << 20);
        HudBars.toggle(player.proxy(), false);
        player.flush();
        final BossBar ramBar = player.shown.get(0);

        scheduler.runTask();

        assertTrue(PLAIN.serialize(ramBar.name()).startsWith("RAM "), PLAIN.serialize(ramBar.name()));
        assertTrue(PLAIN.serialize(ramBar.name()).contains("(50%)"), PLAIN.serialize(ramBar.name()));
        assertEquals(0.5F, ramBar.progress(), 1.0E-5F);
        assertEquals(BossBar.Color.GREEN, ramBar.color());

        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 900L << 20, 1000L << 20);
        scheduler.runTask();
        assertEquals(BossBar.Color.RED, ramBar.color(), "90% heap is critical");

        metrics.value = snapshot(MetricState.UNAVAILABLE, 20.0, 20.0, 10.0, -1L, -1L);
        scheduler.runTask();
        assertEquals("RAM UNAVAILABLE", PLAIN.serialize(ramBar.name()));
        assertEquals(0.0F, ramBar.progress(), 0.0F);
    }

    @Test
    void updaterTaskRendersCpuAndHeapForPerfViewers() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 256L << 20, 1024L << 20);
        HudBars.setPerf(player.proxy(), true);
        player.flush();
        final BossBar perfBar = player.shown.get(0);

        scheduler.runTask();

        final String text = PLAIN.serialize(perfBar.name());
        assertTrue(text.contains("TPS 20.00/20.00"), text);
        assertTrue(text.contains("RAM 25%"), text);
        assertTrue(text.contains("CPU 37%") || text.contains("CPU 38%"), text);
    }

    @Test
    void updaterTaskLeavesBarsAloneWhenNobodyWatches() {
        final FakePlayer player = new FakePlayer();
        HudBars.toggle(player.proxy(), true);
        player.flush();
        final BossBar tpsBar = player.shown.get(0);
        HudBars.toggle(player.proxy(), true);   // now hidden: no TPS viewers, updater still running
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 5.0, 90.0, 0L, 0L);
        final Component before = tpsBar.name();
        final float progressBefore = tpsBar.progress();

        scheduler.runTask();

        assertEquals(before, tpsBar.name());
        assertEquals(progressBefore, tpsBar.progress(), 0.0F);
    }

    @Test
    void updaterTaskSwallowsMetricFailures() {
        final FakePlayer player = new FakePlayer();
        HudBars.toggle(player.proxy(), true);
        metrics.failure = new IllegalStateException("collector gone");

        assertDoesNotThrow(scheduler::runTask, "the HUD must never break the global tick");
    }

    // ------------------------------------------------------------------------------- helpers

    /** Which shared bar each object is, by identity: the bars are process-wide and renamed by the updater. */
    private static List<String> labels(final List<BossBar> bars) {
        final List<String> out = new ArrayList<>();
        for (final BossBar bar : bars) {
            if (bar == sharedBar("TPS_BAR")) out.add("TPS");
            else if (bar == sharedBar("RAM_BAR")) out.add("RAM");
            else if (bar == sharedBar("PERF_BAR")) out.add("PERF");
            else out.add("?");
        }
        return out;
    }

    private static BossBar sharedBar(final String field) {
        try {
            final Field f = HudBars.class.getDeclaredField(field);
            f.setAccessible(true);
            return (BossBar) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static PerformanceSnapshot snapshot(final MetricState state, final double target, final double tps,
                                                final double mspt, final long heapUsed, final long heapMax) {
        final Map<String, Object> window = new HashMap<>();
        window.put("worstTps", tps);
        window.put("medianTps", tps);
        window.put("aggregateTps", tps);
        window.put("worstAverageMspt", mspt);
        final Map<String, Object> runtime = new HashMap<>();
        runtime.put("heapUsedBytes", heapUsed);
        runtime.put("heapMaxBytes", heapMax);
        runtime.put("processCpuPercent", 37.5);
        final Map<String, Object> freshness = new HashMap<>();
        freshness.put("state", state);
        freshness.put("diagnostic", "test");
        final Map<String, Object> values = new HashMap<>();
        values.put("targetTps", target);
        values.put("activeRegionCount", 1);
        values.put("freshness", proxy(Freshness.class, freshness));
        values.put("window", proxy(WindowMetrics.class, window));
        values.put("globalWindow", proxy(WindowMetrics.class, window));
        values.put("runtime", proxy(RuntimeMetrics.class, runtime));
        return proxy(PerformanceSnapshot.class, values);
    }

    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        final InvocationHandler handler = (proxy, method, args) -> {
            if (values.containsKey(method.getName())) return values.get(method.getName());
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            return zero(method.getReturnType());
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object zero(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        return (short) 0;
    }

    /** The injected updater scheduler: records scheduling and cancellation and holds the task body. */
    private static final class RecordingScheduler implements HudBars.UpdaterScheduler {
        int scheduled;
        int cancelled;
        private Runnable task;

        @Override
        public ScheduledTask schedule(final Runnable update) {
            scheduled++;
            task = update;
            return (ScheduledTask) Proxy.newProxyInstance(ScheduledTask.class.getClassLoader(),
                new Class<?>[] {ScheduledTask.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) {
                        cancelled++;
                        return ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return zero(method.getReturnType());
                });
        }

        void runTask() {
            assertNotEquals(null, task, "updater was never scheduled");
            task.run();
        }
    }

    /** What the updater reads each run; {@code failure} makes the snapshot throw. */
    private static final class MetricsHolder implements SourbyMetrics {
        volatile PerformanceSnapshot value;
        volatile RuntimeException failure;

        @Override
        public PerformanceSnapshot snapshot() {
            if (failure != null) throw failure;
            return value;
        }
    }

    /** A {@link Player} proxy with an in-memory PDC and an entity scheduler that queues until flushed. */
    private static final class FakePlayer {
        final UUID id = UUID.randomUUID();
        final Map<NamespacedKey, Object> pdc = new HashMap<>();
        final Set<String> permissions = new HashSet<>();
        final List<BossBar> shown = new ArrayList<>();
        final List<BossBar> hidden = new ArrayList<>();
        final List<BossBar> hiddenNow = new ArrayList<>();
        final List<Long> delayedTicks = new ArrayList<>();
        private final List<Runnable> queue = new ArrayList<>();
        private final List<Runnable> retired = new ArrayList<>();
        private final Player proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (p, method, args) -> handle(method, args));

        Player proxy() {
            return proxy;
        }

        FakePlayer permit(final String permission) {
            permissions.add(permission);
            return this;
        }

        void setOptOut(final String key) {
            pdc.put(new NamespacedKey("sourbycraft", key), (byte) 1);
        }

        boolean optedOut(final String key) {
            return Byte.valueOf((byte) 1).equals(pdc.get(new NamespacedKey("sourbycraft", key)));
        }

        /** Runs every queued region task once (tasks queued while flushing wait for the next flush). */
        void flush() {
            final List<Runnable> now = new ArrayList<>(queue);
            queue.clear();
            now.forEach(Runnable::run);
        }

        /** Simulates the entity being removed: every task's retired callback runs instead of the task. */
        void retire() {
            queue.clear();
            final List<Runnable> now = new ArrayList<>(retired);
            retired.clear();
            now.forEach(Runnable::run);
        }

        @SuppressWarnings("unchecked")
        private Object handle(final Method method, final Object[] args) {
            switch (method.getName()) {
                case "getUniqueId": return id;
                case "hasPermission": return permissions.contains((String) args[0]);
                case "getPersistentDataContainer": return pdcProxy();
                case "getScheduler": return schedulerProxy();
                case "showBossBar": shown.add((BossBar) args[0]); return null;
                case "hideBossBar":
                    // Called directly from the task bodies (deferred) and from onQuit (immediate).
                    if (flushing) hidden.add((BossBar) args[0]); else hiddenNow.add((BossBar) args[0]);
                    return null;
                case "hashCode": return id.hashCode();
                case "equals": return args[0] == proxy;
                case "toString": return "FakePlayer[" + id + "]";
                default: throw new AssertionError("unexpected Player call: " + method.getName());
            }
        }

        private volatile boolean flushing;

        private PersistentDataContainer pdcProxy() {
            return (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "get": return pdc.get((NamespacedKey) args[0]);
                        case "set": pdc.put((NamespacedKey) args[0], args[2]); return null;
                        case "remove": pdc.remove((NamespacedKey) args[0]); return null;
                        case "has": return pdc.containsKey((NamespacedKey) args[0]);
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == args[0];
                        default: throw new AssertionError("unexpected PDC call: " + method.getName());
                    }
                });
        }

        private EntityScheduler schedulerProxy() {
            return (EntityScheduler) Proxy.newProxyInstance(EntityScheduler.class.getClassLoader(),
                new Class<?>[] {EntityScheduler.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "run": {
                            final Consumer<ScheduledTask> body = (Consumer<ScheduledTask>) args[1];
                            final Runnable onRetired = (Runnable) args[2];
                            queue.add(() -> { flushing = true; try { body.accept(null); } finally { flushing = false; } });
                            if (onRetired != null) retired.add(onRetired);
                            return null;
                        }
                        case "runDelayed": {
                            final Consumer<ScheduledTask> body = (Consumer<ScheduledTask>) args[1];
                            delayedTicks.add((Long) args[3]);
                            queue.add(() -> { flushing = true; try { body.accept(null); } finally { flushing = false; } });
                            return null;
                        }
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == args[0];
                        default: throw new AssertionError("unexpected EntityScheduler call: " + method.getName());
                    }
                });
        }
    }
}
