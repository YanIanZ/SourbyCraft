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

    /**
     * Every player created by a test, so the injected player resolver (production: {@code Bukkit::getPlayer})
     * can find them by UUID. Cleared before each test.
     */
    private static final List<FakePlayer> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    private RecordingScheduler scheduler;
    private AutoCloseable restore;
    private MetricsHolder metrics;
    /** Every UUID the updater asked the player resolver for, in order. */
    private final List<UUID> resolved = new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * One-shot hook run inside the resolver, i.e. after the updater decided to refresh a viewer and
     * before it queues the send. Lets a test land a player transition exactly in that window.
     */
    private volatile Consumer<UUID> onResolve;

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
        LIVE.clear();
        scheduler = new RecordingScheduler();
        metrics = new MetricsHolder();
        resolved.clear();
        onResolve = null;
        restore = HudBars.installForTesting(scheduler, metrics, id -> {
            resolved.add(id);
            final Consumer<UUID> hook = onResolve;
            onResolve = null;
            if (hook != null) hook.accept(id);
            return LIVE.stream().filter(p -> p.id.equals(id)).map(FakePlayer::proxy).findFirst().orElse(null);
        });
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

    // ------------------------------------------------------------------------- actionbar mode

    @Test
    void actionbarModeNeverShowsTheBossbarAndSendsTheLineOnThePlayersScheduler() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 18.0, 30.0, 0L, 0L);

        assertTrue(HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR));
        assertEquals("actionbar", player.pdc.get(new NamespacedKey("sourbycraft", "hud_tps_mode")));
        player.flush();
        assertTrue(player.shown.isEmpty(), "actionbar mode never shows the shared boss bar");
        assertTrue(player.hidden.contains(sharedBar("TPS_BAR")), "a bar left over from bossbar mode is hidden");

        player.actionbars.clear();
        scheduler.runTask();
        player.flush();

        assertEquals(1, player.actionbars.size(), "the line is sent on the player's own scheduler");
        final String text = PLAIN.serialize(player.actionbars.get(0));
        assertTrue(text.contains("TPS 18.00/20.00") && text.contains("MSPT"), text);
    }

    @Test
    void theActionbarIsResentOnEveryRefreshBecauseTheClientExpiresIt() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();

        scheduler.runTask();
        scheduler.runTask();
        scheduler.runTask();
        player.flush();

        assertEquals(3, player.actionbars.size(), "one refresh = one resend (client drops an actionbar after ~2-3s)");
    }

    @Test
    void switchingBackToBossbarShowsTheSharedBarAndClearsTheLingeringActionbar() {
        final FakePlayer player = new FakePlayer();
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();
        player.hidden.clear();

        assertTrue(HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.BOSSBAR));
        player.flush();

        assertEquals(List.of("TPS"), labels(player.shown), "bossbar mode is the default again");
        assertEquals(List.of(Component.empty()), player.actionbars, "an empty actionbar clears the leftover line");
        assertFalse(player.pdc.containsKey(new NamespacedKey("sourbycraft", "hud_tps_mode")),
            "the stored mode is the default (absent), not a leftover");
    }

    @Test
    void turningAnActionbarOffClearsItAndStopsRefreshing() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();

        assertFalse(HudBars.setPreference(player.proxy(), true, false));
        player.flush();

        assertEquals(List.of(Component.empty()), player.actionbars, "toggle-off clears the line instead of leaving it to expire");
        player.actionbars.clear();
        scheduler.runTask();
        assertTrue(resolved.isEmpty(), "a turned-off viewer is not even resolved");
        player.flush();
        assertTrue(player.actionbars.isEmpty(), "no viewer left, so nothing is refreshed");
    }

    @Test
    void quittingEvictsTheActionbarViewer() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();

        HudBars.onQuit(player.proxy());
        scheduler.runTask();
        player.flush();

        assertTrue(resolved.isEmpty(), "a disconnected player is never resolved");
        assertTrue(player.actionbars.isEmpty(), "and never sent to");
        assertTrue(HudBars.toggle(player.proxy(), true), "the viewer set was evicted, so the next toggle is a show");
    }

    @Test
    void anActionbarModeIsRestoredFromPlayerDataOnJoin() {
        final FakePlayer player = new FakePlayer().permit(HudBars.AUTO_PERMISSION);
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_tps_mode"), "actionbar");
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertEquals(List.of("RAM"), labels(player.shown), "only the RAM bar is a bossbar");
        assertEquals(1, player.actionbars.size(), "the TPS bar is delivered as an actionbar");
        assertEquals(Component.empty(), player.actionbars.get(0), "nothing rendered yet; the updater fills it in");
        scheduler.runTask();
        player.flush();
        assertTrue(PLAIN.serialize(player.actionbars.get(player.actionbars.size() - 1)).contains("TPS"));
    }

    @Test
    void perfModeSendsTheCombinedLineAsAnActionbar() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 256L << 20, 1024L << 20);
        HudBars.setPerfMode(player.proxy(), HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        assertEquals("actionbar", player.pdc.get(new NamespacedKey("sourbycraft", "hud_perf_mode")));
        assertTrue(player.shown.isEmpty(), "perf actionbar mode shows no boss bar");
        player.actionbars.clear();

        scheduler.runTask();
        player.flush();

        assertEquals(1, player.actionbars.size());
        final String text = PLAIN.serialize(player.actionbars.get(0));
        assertTrue(text.contains("TPS 20.00/20.00") && text.contains("RAM 25%"), text);
    }

    @Test
    void actionbarViewersDoNotMutateTheSharedBossbar() {
        final FakePlayer player = new FakePlayer();
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        final BossBar shared = sharedBar("TPS_BAR");
        final Component before = shared.name();
        final float progressBefore = shared.progress();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 5.0, 90.0, 0L, 0L);
        player.actionbars.clear();

        scheduler.runTask();
        player.flush();

        assertEquals(before, shared.name(), "no bossbar viewer, so the shared bar is left untouched");
        assertEquals(progressBefore, shared.progress(), 0.0F);
        assertEquals(1, player.actionbars.size(), "the actionbar viewer still got the line");
    }

    @Test
    void aVanishedPlayerIsSkippedWithoutBreakingTheRefresh() {
        final FakePlayer player = new FakePlayer();
        // Without a snapshot the updater stops at the render and never reaches the resolver.
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();
        LIVE.remove(player);   // the resolver can no longer find the player

        assertDoesNotThrow(scheduler::runTask);
        assertEquals(List.of(player.id), resolved);
        player.flush();
        assertTrue(player.actionbars.isEmpty());
    }

    @Test
    void severalBarsInActionbarModeAreAllSentInUpdateOrderSoTheLastOneWins() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 512L << 20, 1024L << 20);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        HudBars.setMode(player.proxy(), false, HudBars.DisplayMode.ACTIONBAR);
        HudBars.setPerfMode(player.proxy(), HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        assertTrue(player.shown.isEmpty(), "no bar is a boss bar");
        player.actionbars.clear();

        scheduler.runTask();
        assertEquals(List.of(player.id, player.id, player.id), resolved, "one resolve per bar per refresh");
        player.flush();

        assertEquals(3, player.actionbars.size(), "one send per bar; the client keeps only the last");
        assertTrue(PLAIN.serialize(player.actionbars.get(0)).startsWith("TPS "), PLAIN.serialize(player.actionbars.get(0)));
        assertTrue(PLAIN.serialize(player.actionbars.get(1)).startsWith("RAM "), PLAIN.serialize(player.actionbars.get(1)));
        assertTrue(PLAIN.serialize(player.actionbars.get(2)).contains("CPU"), PLAIN.serialize(player.actionbars.get(2)));

        // Turning one of them off clears the shared line once; the others keep refreshing.
        player.actionbars.clear();
        resolved.clear();
        HudBars.setPreference(player.proxy(), true, false);
        player.flush();
        assertEquals(List.of(Component.empty()), player.actionbars);
        player.actionbars.clear();
        scheduler.runTask();
        player.flush();
        assertEquals(List.of(player.id, player.id), resolved);
        assertEquals(2, player.actionbars.size());
        assertTrue(PLAIN.serialize(player.actionbars.get(0)).startsWith("RAM "));
    }

    @Test
    void anUnknownOrEmptyStoredModeFallsBackToTheBossbar() {
        final FakePlayer player = new FakePlayer().permit(HudBars.AUTO_PERMISSION).permit("sourbycraft.command.perfbar");
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_tps_mode"), "sideways");
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_ram_mode"), "");
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_perf_mode"), "action bar");
        player.pdc.put(new NamespacedKey("sourbycraft", "hud_perf_enabled"), (byte) 1);

        HudBars.autoShowOnJoin(player.proxy());
        player.flush();

        assertEquals(Set.of("TPS", "RAM", "PERF"), new HashSet<>(labels(player.shown)), "all three are boss bars");
        assertTrue(player.actionbars.isEmpty());
        scheduler.runTask();
        player.flush();
        assertTrue(resolved.isEmpty(), "no actionbar viewer, so nobody is resolved");
        assertTrue(player.actionbars.isEmpty());

        assertEquals(HudBars.DisplayMode.BOSSBAR, HudBars.DisplayMode.of(null));
        assertEquals(HudBars.DisplayMode.BOSSBAR, HudBars.DisplayMode.of("bossbar"));
        assertEquals(HudBars.DisplayMode.ACTIONBAR, HudBars.DisplayMode.of("ActionBar"), "stored values are case-insensitive");
        assertEquals(null, HudBars.DisplayMode.parse("sideways"), "the command parser rejects unknown keywords");
    }

    // ------------------------------------------------------- refresh vs lifecycle interleavings
    //
    // The updater (global region) decides to refresh a viewer, then queues the send on the player's
    // scheduler. A player transition (off, mode switch, quit) or close() can land in between, or after
    // the send is queued. These drive both fake schedulers by hand to put the transition exactly there.

    /** Puts a player in TPS actionbar mode with a rendered line, then forgets what was sent so far. */
    private FakePlayer tpsActionbarViewer() {
        final FakePlayer player = new FakePlayer();
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        player.flush();
        player.actionbars.clear();
        player.shown.clear();
        player.hidden.clear();
        return player;
    }

    @Test
    void aRefreshRacingToggleOffCannotRestoreTheClearedLine() {
        final FakePlayer player = tpsActionbarViewer();
        onResolve = id -> HudBars.setPreference(player.proxy(), true, false);   // off, between check and enqueue

        scheduler.runTask();
        assertEquals(List.of(player.id), resolved);
        player.flush();   // runs the clear, then the stale refresh

        assertEquals(List.of(Component.empty()), player.actionbars, "the clear is the last thing the client sees");
    }

    @Test
    void aRefreshRacingASwitchToBossbarCannotRestoreTheClearedLine() {
        final FakePlayer player = tpsActionbarViewer();
        onResolve = id -> HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.BOSSBAR);

        scheduler.runTask();
        assertEquals(List.of(player.id), resolved);
        player.flush();

        assertEquals(List.of("TPS"), labels(player.shown));
        assertEquals(List.of(Component.empty()), player.actionbars, "no actionbar line after the switch");
    }

    @Test
    void aRefreshQueuedBeforeQuitIsDropped() {
        final FakePlayer player = tpsActionbarViewer();
        scheduler.runTask();   // refresh queued on the player's scheduler
        HudBars.onQuit(player.proxy());

        player.flush();

        assertTrue(player.actionbars.isEmpty(), "a refresh from before the quit is not sent");
    }

    @Test
    void aRefreshRacingQuitIsDropped() {
        final FakePlayer player = tpsActionbarViewer();
        onResolve = id -> HudBars.onQuit(player.proxy());

        scheduler.runTask();
        assertEquals(List.of(player.id), resolved);
        player.flush();

        assertTrue(player.actionbars.isEmpty());
    }

    @Test
    void aRefreshQueuedBeforeCloseIsDropped() {
        final FakePlayer player = tpsActionbarViewer();
        scheduler.runTask();
        HudBars.close();

        player.flush();

        assertTrue(player.actionbars.isEmpty(), "close() invalidates refreshes it could not cancel");
    }

    @Test
    void aRefreshFromBeforeCloseIsDroppedEvenWhenThePlayerReenablesTheActionbar() {
        final FakePlayer player = tpsActionbarViewer();
        scheduler.runTask();   // stale refresh queued (old session)
        HudBars.close();
        HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);   // new session, same player

        player.flush();

        // Only the new session's replay (empty: close() reset the rendered line) reaches the client.
        assertEquals(List.of(Component.empty()), player.actionbars);
    }

    @Test
    void aRefreshFromAnOlderSessionIsDroppedAfterOffAndOnAgain() {
        final FakePlayer player = tpsActionbarViewer();
        onResolve = id -> {
            HudBars.setPreference(player.proxy(), true, false);
            HudBars.setMode(player.proxy(), true, HudBars.DisplayMode.ACTIONBAR);
        };

        scheduler.runTask();
        player.flush();

        assertEquals(2, player.actionbars.size(), "clear + the new session's replay, no stale third send");
        assertEquals(Component.empty(), player.actionbars.get(0));
        assertTrue(PLAIN.serialize(player.actionbars.get(1)).startsWith("TPS "));
    }

    @Test
    void theUpdaterNeverEvictsASubscription() throws Exception {
        // Mid-transition state as the updater can observe it on another thread: the player is no longer
        // a viewer, but the actionbar entry is not yet gone (or, after a re-enable, is back). Only the
        // player-owned transitions may remove it; the updater evicting here is what deleted a re-enabled
        // subscription in the Codex review's race.
        metrics.value = snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0, 0L, 0L);   // render must succeed
        final FakePlayer bossbarViewer = new FakePlayer();
        HudBars.toggle(bossbarViewer.proxy(), true);   // starts the updater; the TPS bar has a viewer
        final FakePlayer player = new FakePlayer();
        subscribeActionbarOnly("TPS_ACTIONBAR", player.id);

        scheduler.runTask();
        player.flush();

        assertTrue(actionbarSubscribed("TPS_ACTIONBAR", player.id), "the updater left the entry alone");
    }

    @SuppressWarnings("unchecked")
    private static void subscribeActionbarOnly(final String field, final UUID id) throws Exception {
        final Field f = HudBars.class.getDeclaredField(field);
        f.setAccessible(true);
        final Object subs = f.get(null);
        if (subs instanceof Set<?> set) ((Set<UUID>) set).add(id);
        else ((Map<UUID, Long>) subs).put(id, Long.MIN_VALUE);
    }

    private static boolean actionbarSubscribed(final String field, final UUID id) throws Exception {
        final Field f = HudBars.class.getDeclaredField(field);
        f.setAccessible(true);
        final Object subs = f.get(null);
        return subs instanceof Set<?> set ? set.contains(id) : ((Map<?, ?>) subs).containsKey(id);
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
        final List<Component> actionbars = new ArrayList<>();
        final List<Long> delayedTicks = new ArrayList<>();
        private final List<Runnable> queue = new ArrayList<>();
        private final List<Runnable> retired = new ArrayList<>();
        private final Player proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (p, method, args) -> handle(method, args));

        FakePlayer() {
            LIVE.add(this);
        }

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
                case "sendActionBar": actionbars.add((Component) args[0]); return null;
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
