package dev.iyanz.sourbycraft.hud;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.command.TpsCommand;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourbyCraft HUD boss bars: {@code /tpsbar} + {@code /rambar}.
 *
 * <p><b>One shared bar per metric</b> — adventure {@link BossBar} is mutable and propagates
 * {@code name}/{@code progress}/{@code color} changes to every viewer, so the whole HUD is two
 * bar objects mutated by ONE global-region task (no per-player bar churn, no per-player timers).
 * Show/hide hops to the player's owning region via the entity scheduler (Folia rule 1).
 *
 * <p>TPS and RAM read the immutable metrics cache once per update. RAM shows heap used/max, plus the container/panel allocation when
 * it is meaningfully larger than the heap (the "panel gave me 10G, spark shows 2G" case — that
 * gap is a JVM flag issue, and the bar makes it visible).
 *
 * <p>The updater task is scheduled lazily on the first toggle and keeps running (single cheap
 * task; while nobody watches it still reads the cached snapshot once a second but leaves every bar untouched). Viewers are evicted on quit.
 *
 * <p><b>Display modes.</b> Each player's bar can be a {@link DisplayMode#BOSSBAR} (default: the
 * shared bar above) or an {@link DisplayMode#ACTIONBAR}. An actionbar view is rendered from the same
 * snapshot but delivered per player, on that player's own scheduler, and re-sent on every refresh
 * because the client drops an actionbar after ~2-3 s. The mode is stored in the player's data
 * ({@code hud_<bar>_mode}) and survives a rejoin; turning the bar off sends an empty actionbar so no
 * stale line lingers. The actionbar has a single line, so if a player enables more than one bar in
 * actionbar mode the refreshes overwrite each other (the last rendered bar wins).
 *
 * <p><b>Actionbar sessions.</b> Each actionbar subscription maps the player to a session token drawn
 * from one process-wide counter that is never reset, so a token is never reused, not even after
 * {@link #close()}. Every player-owned transition (enable or re-select a mode, off, mode switch, quit,
 * close, a retired perf task) replaces or removes the entry, which invalidates all refreshes queued
 * under the old token. The updater only reads the maps: it captures the token when it queues a
 * refresh, and the refresh, running on the player's own scheduler, sends only if the player is still a
 * viewer and the entry still holds that exact token. That check runs on the same thread as the
 * player's commands and quit, so a refresh can no longer restore a line after the transition that
 * cleared it. {@code close()} runs elsewhere; it removes every entry, so refreshes still queued at
 * that point fail the check when they run.
 */
public final class HudBars {

    private static final Set<UUID> TPS_VIEWERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RAM_VIEWERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> PERF_VIEWERS = ConcurrentHashMap.newKeySet();
    // Viewers currently delivered as an actionbar instead of the shared boss bar, mapped to their
    // actionbar session token (see the class javadoc). Mutated only by player-owned transitions and
    // close(); the updater only reads them.
    private static final Map<UUID, Long> TPS_ACTIONBAR = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RAM_ACTIONBAR = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PERF_ACTIONBAR = new ConcurrentHashMap<>();
    /** Source of actionbar session tokens. Never reset, so a token is never reused. */
    private static final java.util.concurrent.atomic.AtomicLong SESSIONS = new java.util.concurrent.atomic.AtomicLong();
    private static final Map<UUID, Boolean> ANY = new ConcurrentHashMap<>(); // quick "has any bar" check

    private static final BossBar TPS_BAR =
        BossBar.bossBar(Component.text("TPS"), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
    private static final BossBar RAM_BAR =
        BossBar.bossBar(Component.text("RAM"), 0.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);
    private static final BossBar PERF_BAR =
        BossBar.bossBar(Component.text("Performance"), 0.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
    private static final org.bukkit.NamespacedKey PERF_ENABLED =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_perf_enabled");
    // Per-player display mode, persisted next to the enable/opt-out flags. Absent = BOSSBAR, so existing
    // players keep exactly the behaviour they had before the actionbar mode existed.
    private static final org.bukkit.NamespacedKey MODE_TPS =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_tps_mode");
    private static final org.bukkit.NamespacedKey MODE_RAM =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_ram_mode");
    private static final org.bukkit.NamespacedKey MODE_PERF =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_perf_mode");

    private static volatile boolean taskStarted;
    private static io.papermc.paper.threadedregions.scheduler.ScheduledTask updater;

    /**
     * How a player's HUD bar is delivered. {@link #BOSSBAR} is the default and the pre-existing
     * behaviour: one shared adventure bar per metric. {@link #ACTIONBAR} sends the same rendered line
     * over the actionbar instead; the client drops an actionbar after ~2-3 s, so it is re-sent on
     * every updater refresh (one per 20 global ticks).
     */
    public enum DisplayMode {
        BOSSBAR,
        ACTIONBAR;

        /** Command-argument parser: recognised keywords only, null otherwise. */
        public static DisplayMode parse(final String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "bossbar" -> BOSSBAR;
                case "actionbar" -> ACTIONBAR;
                default -> null;
            };
        }

        /** Stored-value parser: anything absent or unknown means the default, {@link #BOSSBAR}. */
        static DisplayMode of(final String stored) {
            return stored != null && stored.equalsIgnoreCase("actionbar") ? ACTIONBAR : BOSSBAR;
        }
    }

    /**
     * Starts the repeating updater. Production schedules {@code update} on the global region
     * scheduler every second; tests inject a scheduler through {@link #installForTesting} so the
     * task can be captured and run by hand.
     */
    @FunctionalInterface
    interface UpdaterScheduler {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask schedule(Runnable update);
    }

    private static final UpdaterScheduler GLOBAL_REGION_SCHEDULER = update ->
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            MinecraftInternalPlugin.INSTANCE, task -> update.run(), 20L, 20L);

    private static volatile UpdaterScheduler updaterScheduler = GLOBAL_REGION_SCHEDULER;
    private static volatile java.util.function.Supplier<SourbyMetrics> metricsSource = MetricsRuntime::provider;
    /**
     * Resolves an actionbar viewer's UUID to a player. The updater runs on the global region (the
     * owner of the player list), which is where {@code Bukkit.getPlayer} may be called; tests inject
     * their own map. Never stores a {@link Player} reference past the call.
     */
    private static volatile java.util.function.Function<UUID, Player> playerSource = Bukkit::getPlayer;

    // Last rendered line per bar, replayed when a player switches to (or joins in) actionbar mode so
    // the first client frame is not blank while waiting for the next refresh.
    private static volatile Component lastTpsText = Component.empty();
    private static volatile Component lastRamText = Component.empty();
    private static volatile Component lastPerfText = Component.empty();

    /**
     * Test seam: replaces the updater scheduler and the metrics source for the duration of the
     * returned handle. Package-private; production never calls it, so the defaults (the global
     * region scheduler, {@link MetricsRuntime#provider()} and {@code Bukkit::getPlayer}) are the only
     * production path. Closing the handle cancels nothing and clears nothing except to restore the
     * hooks; tests that started the updater call {@link #close()}.
     *
     * @param scheduler the scheduler {@code ensureTask} should use
     * @param metrics   the metrics the updater task reads each run
     * @return a handle that restores the previous hooks
     */
    static AutoCloseable installForTesting(final UpdaterScheduler scheduler, final SourbyMetrics metrics) {
        return installForTesting(scheduler, metrics, Bukkit::getPlayer);
    }

    /**
     * Test seam overload that also replaces the player resolver used to deliver actionbars. The
     * production path is {@link #installForTesting(UpdaterScheduler, SourbyMetrics)}, which keeps
     * {@code Bukkit::getPlayer}.
     *
     * @param scheduler the scheduler {@code ensureTask} should use
     * @param metrics   the metrics the updater task reads each run
     * @param players   how an actionbar viewer UUID becomes a player (may return null)
     * @return a handle that restores the previous hooks
     */
    static AutoCloseable installForTesting(final UpdaterScheduler scheduler, final SourbyMetrics metrics,
                                           final java.util.function.Function<UUID, Player> players) {
        final UpdaterScheduler previousScheduler = updaterScheduler;
        final java.util.function.Supplier<SourbyMetrics> previousSource = metricsSource;
        final java.util.function.Function<UUID, Player> previousPlayers = playerSource;
        updaterScheduler = scheduler;
        metricsSource = () -> metrics;
        playerSource = players;
        return () -> {
            updaterScheduler = previousScheduler;
            metricsSource = previousSource;
            playerSource = previousPlayers;
        };
    }

    private HudBars() {}

    // Permission gating the admin auto-HUD on join. Ops get it by default (unregistered permission
    // defaults to OP); grant it to non-op staff to give them the bars too.
    public static final String AUTO_PERMISSION = "sourbycraft.hud.auto";

    // Per-player, per-bar OPT-OUT flag persisted in the player's PDC so that turning a bar off STICKS
    // across rejoins/restarts (otherwise the join auto-show would re-enable it every time). PDC value
    // (byte) 1 = opted out (do not auto-show); absent/0 = auto-show for admins.
    private static final org.bukkit.NamespacedKey OPTOUT_TPS =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_tps_optout");
    private static final org.bukkit.NamespacedKey OPTOUT_RAM =
        new org.bukkit.NamespacedKey("sourbycraft", "hud_ram_optout");

    private static boolean isOptedOut(final Player player, final boolean tps) {
        final Byte v = player.getPersistentDataContainer().get(
            tps ? OPTOUT_TPS : OPTOUT_RAM, org.bukkit.persistence.PersistentDataType.BYTE);
        return v != null && v != 0;
    }

    private static void setOptedOut(final Player player, final boolean tps, final boolean out) {
        final var pdc = player.getPersistentDataContainer();
        final var key = tps ? OPTOUT_TPS : OPTOUT_RAM;
        if (out) pdc.set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        else pdc.remove(key);
    }

    private static org.bukkit.NamespacedKey modeKey(final boolean tps) {
        return tps ? MODE_TPS : MODE_RAM;
    }

    private static DisplayMode storedMode(final Player player, final org.bukkit.NamespacedKey key) {
        return DisplayMode.of(player.getPersistentDataContainer().get(
            key, org.bukkit.persistence.PersistentDataType.STRING));
    }

    private static void storeMode(final Player player, final org.bukkit.NamespacedKey key, final DisplayMode mode) {
        final var pdc = player.getPersistentDataContainer();
        if (mode == DisplayMode.ACTIONBAR) {
            pdc.set(key, org.bukkit.persistence.PersistentDataType.STRING, "actionbar");
        } else {
            pdc.remove(key); // bossbar is the default, so nothing needs to be stored
        }
    }

    /**
     * Chooses the delivery mode for one bar and ensures it is shown. {@code bossbar} returns the bar
     * to the default. The choice is persisted in the player's data, so it survives a rejoin.
     */
    public static boolean setMode(final Player player, final boolean tps, final DisplayMode mode) {
        storeMode(player, modeKey(tps), mode);
        setOptedOut(player, tps, false); // an explicit mode choice is also an opt-in
        return applyShown(player, tps, true, mode);
    }

    /** Toggle a bar for the player; returns true when the bar is now SHOWN. */
    public static boolean toggle(final Player player, final boolean tps) {
        return setPreference(player, tps, !(tps ? TPS_VIEWERS : RAM_VIEWERS).contains(player.getUniqueId()));
    }

    public static boolean setPreference(final Player player, final boolean tps, final boolean enabled) {
        final boolean show = setShown(player, tps, enabled);
        // Manual toggle records the choice so the admin auto-HUD respects it on the next join: hiding
        // opts out (stays hidden), showing opts back in (auto-shows again).
        setOptedOut(player, tps, !show);
        return show;
    }

    /** Called by commands on the player region; null toggles, explicit values are idempotent. */
    public static boolean setPerf(final Player player, final Boolean enabled) {
        final UUID id = player.getUniqueId();
        final boolean show = enabled == null ? !PERF_VIEWERS.contains(id) : enabled;
        applyPerf(player, show, show ? storedMode(player, MODE_PERF) : DisplayMode.BOSSBAR);
        player.getPersistentDataContainer().set(PERF_ENABLED, org.bukkit.persistence.PersistentDataType.BYTE,
            (byte)(show ? 1 : 0));
        return show;
    }

    /** Chooses the perf bar's delivery mode and shows it; persisted like {@link #setMode}. */
    public static boolean setPerfMode(final Player player, final DisplayMode mode) {
        storeMode(player, MODE_PERF, mode);
        player.getPersistentDataContainer().set(PERF_ENABLED, org.bukkit.persistence.PersistentDataType.BYTE,
            (byte) 1);
        applyPerf(player, true, mode);
        return true;
    }

    private static void applyPerf(final Player player, final boolean show, final DisplayMode mode) {
        final UUID id = player.getUniqueId();
        final boolean wasActionbar = PERF_ACTIONBAR.containsKey(id);
        if (show) {
            PERF_VIEWERS.add(id);
            if (mode == DisplayMode.ACTIONBAR) PERF_ACTIONBAR.put(id, SESSIONS.incrementAndGet());
            else PERF_ACTIONBAR.remove(id);
        } else {
            PERF_VIEWERS.remove(id);
            PERF_ACTIONBAR.remove(id);
        }
        player.getScheduler().run(MinecraftInternalPlugin.INSTANCE, task -> {
            // The body reads the live sets: a task queued by an earlier toggle may run after a later
            // one, and the last call wins (pre-existing behaviour of this bar).
            if (PERF_VIEWERS.contains(id) && PERF_ACTIONBAR.containsKey(id)) {
                player.hideBossBar(PERF_BAR);
                player.sendActionBar(lastPerfText);
            } else if (PERF_VIEWERS.contains(id)) {
                player.showBossBar(PERF_BAR);
                if (wasActionbar) player.sendActionBar(Component.empty());
            } else {
                player.hideBossBar(PERF_BAR);
                if (wasActionbar) player.sendActionBar(Component.empty());
            }
        }, () -> {
            PERF_VIEWERS.remove(id);
            PERF_ACTIONBAR.remove(id);
        });
        ensureTask();
    }

    /** Force a bar shown/hidden without touching the opt-out flag. Returns whether it is now shown. */
    private static boolean setShown(final Player player, final boolean tps, final boolean show) {
        // The stored mode only applies while the bar is shown; hiding always drops the actionbar.
        return applyShown(player, tps, show, show ? storedMode(player, modeKey(tps)) : DisplayMode.BOSSBAR);
    }

    private static boolean applyShown(final Player player, final boolean tps, final boolean show,
                                      final DisplayMode mode) {
        final Set<UUID> viewers = tps ? TPS_VIEWERS : RAM_VIEWERS;
        final Map<UUID, Long> actionbars = tps ? TPS_ACTIONBAR : RAM_ACTIONBAR;
        final BossBar bar = tps ? TPS_BAR : RAM_BAR;
        final UUID id = player.getUniqueId();
        final boolean wasActionbar = actionbars.containsKey(id);
        if (show) {
            viewers.add(id);
            if (mode == DisplayMode.ACTIONBAR) actionbars.put(id, SESSIONS.incrementAndGet());
            else actionbars.remove(id);
        } else {
            viewers.remove(id);
            actionbars.remove(id);
        }
        ANY.compute(id, (k, v) -> TPS_VIEWERS.contains(k) || RAM_VIEWERS.contains(k) ? Boolean.TRUE : null);
        final boolean actionbarNow = show && mode == DisplayMode.ACTIONBAR;
        // Show/hide and the mode switch run on the player's owning region thread (packet-adjacent op).
        player.getScheduler().run(MinecraftInternalPlugin.INSTANCE, task -> {
            if (actionbarNow) {
                player.hideBossBar(bar);
                player.sendActionBar(tps ? lastTpsText : lastRamText);
            } else {
                if (show) player.showBossBar(bar); else player.hideBossBar(bar);
                // A line left over from actionbar mode outlives the toggle by ~2-3 s; clear it now.
                if (wasActionbar) player.sendActionBar(Component.empty());
            }
        }, null);
        ensureTask();
        return show;
    }

    /**
     * Admin auto-HUD: on join, show both bars to a player with {@link #AUTO_PERMISSION} UNLESS they
     * have opted a given bar out (persisted). Non-admins are untouched (they can still toggle manually
     * with /tpsbar /rambar). Regular players never get the bars automatically.
     */
    public static void autoShowOnJoin(final Player player) {
        if (player.hasPermission("sourbycraft.command.perfbar")
            && Byte.valueOf((byte)1).equals(player.getPersistentDataContainer().get(
                PERF_ENABLED, org.bukkit.persistence.PersistentDataType.BYTE))) setPerf(player, true);
        if (!player.hasPermission(AUTO_PERMISSION)) return;
        if (!isOptedOut(player, true)) setShown(player, true, true);
        if (!isOptedOut(player, false)) setShown(player, false, true);
    }

    /** Evicts a departing player from both viewer sets. No-op if they had no bar shown. */
    public static void onQuit(final Player player) {
        final UUID id = player.getUniqueId();
        TPS_ACTIONBAR.remove(id);
        RAM_ACTIONBAR.remove(id);
        PERF_ACTIONBAR.remove(id);
        if (PERF_VIEWERS.remove(id)) player.hideBossBar(PERF_BAR);
        if (ANY.remove(id) == null) return;
        if (TPS_VIEWERS.remove(id)) player.hideBossBar(TPS_BAR);
        if (RAM_VIEWERS.remove(id)) player.hideBossBar(RAM_BAR);
        // Viewer-side state dies with the connection; hiding explicitly is unnecessary.
    }

    private static synchronized void ensureTask() {
        if (taskStarted) return;
        updater = updaterScheduler.schedule(HudBars::update);
        taskStarted = true;
    }

    public static synchronized void close() {
        if (updater != null) updater.cancel();
        updater = null;
        taskStarted = false;
        TPS_VIEWERS.clear();
        RAM_VIEWERS.clear();
        PERF_VIEWERS.clear();
        TPS_ACTIONBAR.clear();
        RAM_ACTIONBAR.clear();
        PERF_ACTIONBAR.clear();
        ANY.clear();
        lastTpsText = Component.empty();
        lastRamText = Component.empty();
        lastPerfText = Component.empty();
    }

    private static void update() {
        try {
            final PerformanceSnapshot snapshot = metricsSource.get().snapshot();
            if (!TPS_VIEWERS.isEmpty()) {
                final TpsDisplay display = renderTps(snapshot);
                lastTpsText = display.name();
                if (hasBossbarViewer(TPS_VIEWERS, TPS_ACTIONBAR)) applyTps(display);
                sendActionbars(TPS_VIEWERS, TPS_ACTIONBAR, display.name());
            }
            if (!RAM_VIEWERS.isEmpty()) {
                final RamDisplay display = renderRam(snapshot);
                lastRamText = display.name();
                if (hasBossbarViewer(RAM_VIEWERS, RAM_ACTIONBAR)) applyRam(display);
                sendActionbars(RAM_VIEWERS, RAM_ACTIONBAR, display.name());
            }
            if (!PERF_VIEWERS.isEmpty()) {
                final TpsDisplay display = renderPerf(snapshot);
                lastPerfText = display.name();
                if (hasBossbarViewer(PERF_VIEWERS, PERF_ACTIONBAR)) applyPerfBar(display);
                sendActionbars(PERF_VIEWERS, PERF_ACTIONBAR, display.name());
            }
        } catch (Throwable ignored) {
            // HUD must never break the global tick.
        }
    }

    /** True when at least one viewer of this bar still wants a boss bar. */
    private static boolean hasBossbarViewer(final Set<UUID> viewers, final Map<UUID, Long> actionbars) {
        for (final UUID id : viewers) {
            if (!actionbars.containsKey(id)) return true;
        }
        return false;
    }

    /**
     * Re-sends the rendered line over the actionbar to every actionbar viewer. The client drops an
     * actionbar after ~2-3 s, so this runs on every updater refresh (20 global ticks); the send hops
     * to each player's owning region, exactly like the bossbar show/hide path.
     *
     * <p>Read-only on the subscription maps: an entry is only ever removed by the player's own
     * transitions (or {@link #close()}), never here, so a check-then-remove here cannot delete a
     * subscription the player re-enabled in between. Each queued send carries the session token it
     * was queued under and is dropped by {@link #stillSubscribed} if the session changed meanwhile.
     */
    private static void sendActionbars(final Set<UUID> viewers, final Map<UUID, Long> actionbars, final Component text) {
        if (actionbars.isEmpty()) return;
        for (final Map.Entry<UUID, Long> entry : actionbars.entrySet()) {
            final UUID id = entry.getKey();
            final Long session = entry.getValue();
            if (!viewers.contains(id)) continue; // mid-transition; the player's own transition cleans up
            try {
                final Player player = playerSource.apply(id);
                if (player == null) continue; // disconnecting or already gone
                player.getScheduler().run(MinecraftInternalPlugin.INSTANCE, task -> {
                    if (stillSubscribed(viewers, actionbars, id, session)) player.sendActionBar(text);
                }, null);
            } catch (Throwable ignored) {
                // A single player's scheduler must not stop the other viewers' refresh.
            }
        }
    }

    /**
     * Runs on the player's own scheduler right before a refresh is sent: the player must still be a
     * viewer, still be in actionbar mode, and still be in the same actionbar session the refresh was
     * queued under. Off, a switch to bossbar, quit and {@link #close()} remove the entry; re-enabling
     * puts a fresh token, so a refresh from an older session fails the comparison.
     */
    private static boolean stillSubscribed(final Set<UUID> viewers, final Map<UUID, Long> actionbars,
                                           final UUID id, final Long session) {
        return viewers.contains(id) && session.equals(actionbars.get(id));
    }

    /** Reads the provider once and builds the TPS bar from one immutable generation. */
    public static TpsDisplay renderTps(final SourbyMetrics metrics) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        return renderTps(snapshot);
    }

    public static TpsDisplay renderPerf(final SourbyMetrics metrics) {
        return renderPerf(metrics.snapshot());
    }

    private static TpsDisplay renderPerf(final PerformanceSnapshot snapshot) {
        final TpsDisplay tick = renderTps(snapshot);
        final RuntimeMetrics runtime = snapshot.runtime();
        final String heap = runtime.heapUsedBytes() < 0 || runtime.heapMaxBytes() <= 0 ? "unavailable"
            : TpsCommand.value(100.0 * runtime.heapUsedBytes() / runtime.heapMaxBytes(), 0) + "%";
        final String cpu = Double.isFinite(runtime.processCpuPercent())
            ? TpsCommand.value(runtime.processCpuPercent(), 0) + "%" : "unavailable";
        return new TpsDisplay(tick.name().append(Component.text("  RAM " + heap + "  CPU " + cpu,
            SourbyCraftColors.DIM)), tick.progress(), tick.color());
    }

    private static TpsDisplay renderTps(final PerformanceSnapshot snapshot) {
        final WindowMetrics recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        final double target = snapshot.targetTps();
        final double tps = TpsCommand.cappedTps(recent.worstTps(), target);
        final double mspt = recent.worstAverageMspt();
        if (!TpsCommand.available(tps)
            || snapshot.freshness().state() == MetricState.UNAVAILABLE) {
            return new TpsDisplay(Component.text("TPS " + snapshot.freshness().state().name(), SourbyCraftColors.DIM),
                0.0F, BossBar.Color.YELLOW);
        }
        final double ratio = tps / target;
        final BossBar.Color color = ratio >= 0.9 ? BossBar.Color.GREEN
            : ratio >= 0.75 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        final TextColor valueColor = ratio >= 0.9 ? SourbyCraftColors.HEALTHY
            : ratio >= 0.75 ? SourbyCraftColors.PRESSURE : SourbyCraftColors.CRITICAL;
        final double budget = 1_000.0 / target;
        final TextColor msptColor = !TpsCommand.available(mspt) ? SourbyCraftColors.DIM
            : mspt < budget * 0.8 ? SourbyCraftColors.HEALTHY
            : mspt < budget * 1.2 ? SourbyCraftColors.PRESSURE : SourbyCraftColors.CRITICAL;
        final String stale = snapshot.freshness().state() == MetricState.STALE ? "  STALE" : "";
        final Component name = Component.text()
            .append(Component.text("TPS ", SourbyCraftColors.DIM))
            .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f/%.2f", tps, target), valueColor))
            .append(Component.text("   MSPT ", SourbyCraftColors.DIM))
            .append(Component.text(TpsCommand.ms(mspt), msptColor))
            .append(Component.text(stale, SourbyCraftColors.DIM))
            .build();
        return new TpsDisplay(name, (float)ratio, color);
    }

    private static void applyTps(final TpsDisplay display) {
        TPS_BAR.name(display.name());
        TPS_BAR.progress(display.progress());
        TPS_BAR.color(display.color());
    }

    private static void applyPerfBar(final TpsDisplay display) {
        PERF_BAR.name(display.name());
        PERF_BAR.progress(display.progress());
        PERF_BAR.color(display.color());
    }

    /** Builds the RAM line (and the bossbar progress/colour) from one snapshot, touching nothing. */
    private static RamDisplay renderRam(final PerformanceSnapshot snapshot) {
        final RuntimeMetrics runtime = snapshot.runtime();
        final long max = runtime.heapMaxBytes();
        final long used = runtime.heapUsedBytes();
        if (max <= 0L || used < 0L) {
            return new RamDisplay(Component.text("RAM " + snapshot.freshness().state().name(), SourbyCraftColors.DIM),
                0.0F, BossBar.Color.YELLOW);
        }
        final double pct = max > 0 ? (double) used / max : 0.0;
        final var name = Component.text()
            .append(Component.text("RAM ", SourbyCraftColors.DIM))
            .append(Component.text(ContainerMemory.fmt(used) + "/" + ContainerMemory.fmt(max),
                pct < 0.60 ? SourbyCraftColors.HEALTHY : pct < 0.85 ? SourbyCraftColors.PRESSURE : SourbyCraftColors.CRITICAL))
            .append(Component.text(String.format(java.util.Locale.ROOT, " (%.0f%%)", pct * 100), SourbyCraftColors.DIM));
        // Swap usage (host/container-level), shown like the RAM readout: used/total. Only when swap
        // exists. Robust against the common container bug where the platform bean's free-swap figure
        // is unreliable (negative, or larger than the total) while the total itself is fine — see
        // ContainerMemory#swapUsedBytes. Never renders "?": an untrustworthy used figure falls back
        // to a graceful "n/a" instead of a bogus/clamped number.
        try {
            final long swapTotal = ContainerMemory.swapTotalBytes();
            if (swapTotal > 0) {
                final long swapUsed = ContainerMemory.swapUsedBytes(swapTotal);
                final Component swapValue;
                if (swapUsed < 0) {
                    swapValue = Component.text(ContainerMemory.fmt(swapTotal) + " total (used n/a)", SourbyCraftColors.DIM);
                } else {
                    final double swapPct = (double) swapUsed / swapTotal;
                    swapValue = Component.text(ContainerMemory.fmt(swapUsed) + "/" + ContainerMemory.fmt(swapTotal),
                        swapPct < 0.30 ? SourbyCraftColors.HEALTHY : swapPct < 0.70 ? SourbyCraftColors.PRESSURE : SourbyCraftColors.CRITICAL);
                }
                name.append(Component.text("  Swap ", SourbyCraftColors.DIM)).append(swapValue);
            }
        } catch (Throwable ignored) { /* swap metrics unavailable on this JVM/OS */ }
        return new RamDisplay(name.build(), (float) Math.max(0.0, Math.min(1.0, pct)),
            pct < 0.60 ? BossBar.Color.GREEN : pct < 0.85 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    private static void applyRam(final RamDisplay display) {
        RAM_BAR.name(display.name());
        RAM_BAR.progress(display.progress());
        RAM_BAR.color(display.color());
    }

    public record TpsDisplay(Component name, float progress, BossBar.Color color) {}

    /** The RAM line plus the two bossbar-only attributes (an actionbar has no progress/colour). */
    private record RamDisplay(Component name, float progress, BossBar.Color color) {}

    /** Bukkit listener holder — registered once by the command registrar. Handles the admin
     *  auto-HUD on join and viewer cleanup on quit. */
    public static final class QuitListener implements org.bukkit.event.Listener {
        /** Delays 1s, then runs {@link HudBars#autoShowOnJoin} on the player's own region scheduler. */
        @org.bukkit.event.EventHandler
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
            // Hop to the player's region for the audience/bossbar op (Folia). A small delay lets the
            // client finish the join handshake before the bar is added.
            final Player p = e.getPlayer();
            p.getScheduler().runDelayed(MinecraftInternalPlugin.INSTANCE,
                task -> { try { HudBars.autoShowOnJoin(p); } catch (Throwable ignored) {} }, null, 20L);
        }

        /** Forwards to {@link HudBars#onQuit} to evict the departing viewer. */
        @org.bukkit.event.EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
            HudBars.onQuit(e.getPlayer());
        }
    }
}
