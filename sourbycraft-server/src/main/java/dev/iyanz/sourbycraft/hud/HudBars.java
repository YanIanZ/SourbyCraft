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
import net.kyori.adventure.text.format.NamedTextColor;
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
 * task; updates are skipped entirely while nobody watches). Viewers are evicted on quit.
 */
public final class HudBars {

    private static final Set<UUID> TPS_VIEWERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RAM_VIEWERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Boolean> ANY = new ConcurrentHashMap<>(); // quick "has any bar" check

    private static final BossBar TPS_BAR =
        BossBar.bossBar(Component.text("TPS"), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
    private static final BossBar RAM_BAR =
        BossBar.bossBar(Component.text("RAM"), 0.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);

    private static volatile boolean taskStarted;

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

    /** Toggle a bar for the player; returns true when the bar is now SHOWN. */
    public static boolean toggle(final Player player, final boolean tps) {
        final boolean show = setShown(player, tps, !(tps ? TPS_VIEWERS : RAM_VIEWERS).contains(player.getUniqueId()));
        // Manual toggle records the choice so the admin auto-HUD respects it on the next join: hiding
        // opts out (stays hidden), showing opts back in (auto-shows again).
        setOptedOut(player, tps, !show);
        return show;
    }

    /** Force a bar shown/hidden without touching the opt-out flag. Returns whether it is now shown. */
    private static boolean setShown(final Player player, final boolean tps, final boolean show) {
        final Set<UUID> viewers = tps ? TPS_VIEWERS : RAM_VIEWERS;
        final BossBar bar = tps ? TPS_BAR : RAM_BAR;
        final UUID id = player.getUniqueId();
        if (show) viewers.add(id); else viewers.remove(id);
        ANY.compute(id, (k, v) -> TPS_VIEWERS.contains(k) || RAM_VIEWERS.contains(k) ? Boolean.TRUE : null);
        // Show/hide on the player's owning region thread (packet-adjacent audience op).
        player.getScheduler().run(MinecraftInternalPlugin.INSTANCE,
            task -> { if (show) player.showBossBar(bar); else player.hideBossBar(bar); }, null);
        ensureTask();
        return show;
    }

    /**
     * Admin auto-HUD: on join, show both bars to a player with {@link #AUTO_PERMISSION} UNLESS they
     * have opted a given bar out (persisted). Non-admins are untouched (they can still toggle manually
     * with /tpsbar /rambar). Regular players never get the bars automatically.
     */
    public static void autoShowOnJoin(final Player player) {
        if (!player.hasPermission(AUTO_PERMISSION)) return;
        if (!isOptedOut(player, true)) setShown(player, true, true);
        if (!isOptedOut(player, false)) setShown(player, false, true);
    }

    /** Evicts a departing player from both viewer sets. No-op if they had no bar shown. */
    public static void onQuit(final Player player) {
        final UUID id = player.getUniqueId();
        if (ANY.remove(id) == null) return;
        TPS_VIEWERS.remove(id);
        RAM_VIEWERS.remove(id);
        // Viewer-side state dies with the connection; hiding explicitly is unnecessary.
    }

    private static synchronized void ensureTask() {
        if (taskStarted) return;
        taskStarted = true;
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            MinecraftInternalPlugin.INSTANCE,
            task -> update(), 20L, 20L);
    }

    private static void update() {
        try {
            final PerformanceSnapshot snapshot = MetricsRuntime.provider().snapshot();
            if (!TPS_VIEWERS.isEmpty()) applyTps(renderTps(snapshot));
            if (!RAM_VIEWERS.isEmpty()) updateRam(snapshot);
        } catch (Throwable ignored) {
            // HUD must never break the global tick.
        }
    }

    /** Reads the provider once and builds the TPS bar from one immutable generation. */
    public static TpsDisplay renderTps(final SourbyMetrics metrics) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        return renderTps(snapshot);
    }

    private static TpsDisplay renderTps(final PerformanceSnapshot snapshot) {
        final WindowMetrics recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        final double target = snapshot.targetTps();
        final double tps = TpsCommand.cappedTps(recent.worstTps(), target);
        final double mspt = recent.worstAverageMspt();
        if (!TpsCommand.available(tps)
            || snapshot.freshness().state() == MetricState.UNAVAILABLE) {
            return new TpsDisplay(Component.text("TPS " + snapshot.freshness().state().name(), NamedTextColor.GRAY),
                0.0F, BossBar.Color.YELLOW);
        }
        final double ratio = tps / target;
        final BossBar.Color color = ratio >= 0.9 ? BossBar.Color.GREEN
            : ratio >= 0.75 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        final TextColor valueColor = ratio >= 0.9 ? NamedTextColor.GREEN
            : ratio >= 0.75 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        final double budget = 1_000.0 / target;
        final TextColor msptColor = !TpsCommand.available(mspt) ? NamedTextColor.GRAY
            : mspt < budget * 0.8 ? NamedTextColor.GREEN
            : mspt < budget * 1.2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        final String stale = snapshot.freshness().state() == MetricState.STALE ? "  STALE" : "";
        final Component name = Component.text()
            .append(Component.text("TPS ", NamedTextColor.GRAY))
            .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f/%.2f", tps, target), valueColor))
            .append(Component.text("   MSPT ", NamedTextColor.GRAY))
            .append(Component.text(TpsCommand.ms(mspt), msptColor))
            .append(Component.text(stale, NamedTextColor.GRAY))
            .build();
        return new TpsDisplay(name, (float)ratio, color);
    }

    private static void applyTps(final TpsDisplay display) {
        TPS_BAR.name(display.name());
        TPS_BAR.progress(display.progress());
        TPS_BAR.color(display.color());
    }

    private static void updateRam(final PerformanceSnapshot snapshot) {
        final RuntimeMetrics runtime = snapshot.runtime();
        final long max = runtime.heapMaxBytes();
        final long used = runtime.heapUsedBytes();
        if (max <= 0L || used < 0L) {
            RAM_BAR.name(Component.text("RAM " + snapshot.freshness().state().name(), NamedTextColor.GRAY));
            RAM_BAR.progress(0.0F);
            RAM_BAR.color(BossBar.Color.YELLOW);
            return;
        }
        final double pct = max > 0 ? (double) used / max : 0.0;
        final var name = Component.text()
            .append(Component.text("RAM ", NamedTextColor.GRAY))
            .append(Component.text(ContainerMemory.fmt(used) + "/" + ContainerMemory.fmt(max),
                pct < 0.60 ? NamedTextColor.GREEN : pct < 0.85 ? NamedTextColor.YELLOW : NamedTextColor.RED))
            .append(Component.text(String.format(java.util.Locale.ROOT, " (%.0f%%)", pct * 100), NamedTextColor.GRAY));
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
                    swapValue = Component.text(ContainerMemory.fmt(swapTotal) + " total (used n/a)", NamedTextColor.GRAY);
                } else {
                    final double swapPct = (double) swapUsed / swapTotal;
                    swapValue = Component.text(ContainerMemory.fmt(swapUsed) + "/" + ContainerMemory.fmt(swapTotal),
                        swapPct < 0.30 ? NamedTextColor.GREEN : swapPct < 0.70 ? NamedTextColor.YELLOW : NamedTextColor.RED);
                }
                name.append(Component.text("  Swap ", NamedTextColor.GRAY)).append(swapValue);
            }
        } catch (Throwable ignored) { /* swap metrics unavailable on this JVM/OS */ }
        RAM_BAR.name(name.build());
        RAM_BAR.progress((float) Math.max(0.0, Math.min(1.0, pct)));
        RAM_BAR.color(pct < 0.60 ? BossBar.Color.GREEN : pct < 0.85 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    public record TpsDisplay(Component name, float progress, BossBar.Color color) {}

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
