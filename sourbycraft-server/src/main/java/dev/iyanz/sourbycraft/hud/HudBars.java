package dev.iyanz.sourbycraft.hud;

import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
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
 * <p>TPS reads the perf sensor's aggregate snapshot (min-region TPS / max-region MSPT — the same
 * numbers self-tune reacts to). RAM shows heap used/max, plus the container/panel allocation when
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

    /** Toggle a bar for the player; returns true when the bar is now SHOWN. */
    public static boolean toggle(final Player player, final boolean tps) {
        final Set<UUID> viewers = tps ? TPS_VIEWERS : RAM_VIEWERS;
        final BossBar bar = tps ? TPS_BAR : RAM_BAR;
        final UUID id = player.getUniqueId();
        final boolean show = viewers.add(id);
        if (!show) viewers.remove(id);
        ANY.compute(id, (k, v) -> TPS_VIEWERS.contains(k) || RAM_VIEWERS.contains(k) ? Boolean.TRUE : null);
        // Show/hide on the player's owning region thread (packet-adjacent audience op).
        player.getScheduler().run(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> { if (show) player.showBossBar(bar); else player.hideBossBar(bar); }, null);
        ensureTask();
        return show;
    }

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
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> update(), 20L, 20L);
    }

    private static void update() {
        try {
            if (!TPS_VIEWERS.isEmpty()) updateTps();
            if (!RAM_VIEWERS.isEmpty()) updateRam();
        } catch (Throwable ignored) {
            // HUD must never break the global tick.
        }
    }

    private static void updateTps() {
        final SensorSnapshot s = PerfSensor.snapshot();
        final double tps = Math.min(20.0, s.tps1s());
        final double mspt = s.msptAvg();
        final BossBar.Color color = s.tier() == Tier.GREEN ? BossBar.Color.GREEN
            : s.tier() == Tier.YELLOW ? BossBar.Color.YELLOW : BossBar.Color.RED;
        final TextColor valueColor = tps >= 18 ? NamedTextColor.GREEN
            : tps >= 15 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        TPS_BAR.name(Component.text()
            .append(Component.text("TPS ", NamedTextColor.GRAY))
            .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", tps), valueColor))
            .append(Component.text("   MSPT ", NamedTextColor.GRAY))
            .append(Component.text((mspt >= 10.0 ? String.format(java.util.Locale.ROOT, "%.1fms", mspt)
                    : mspt >= 0.1 ? String.format(java.util.Locale.ROOT, "%.2fms", mspt)
                    : String.format(java.util.Locale.ROOT, "%.3fms", mspt)),
                mspt < 40 ? NamedTextColor.GREEN : mspt < 60 ? NamedTextColor.YELLOW : NamedTextColor.RED))
            .build());
        TPS_BAR.progress((float) Math.max(0.0, Math.min(1.0, tps / 20.0)));
        TPS_BAR.color(color);
    }

    private static void updateRam() {
        final Runtime rt = Runtime.getRuntime();
        final long max = rt.maxMemory();
        final long used = rt.totalMemory() - rt.freeMemory();
        final double pct = max > 0 ? (double) used / max : 0.0;
        final long container = ContainerMemory.limitBytes();
        final var name = Component.text()
            .append(Component.text("RAM ", NamedTextColor.GRAY))
            .append(Component.text(ContainerMemory.fmt(used) + " / " + ContainerMemory.fmt(max),
                pct < 0.60 ? NamedTextColor.GREEN : pct < 0.85 ? NamedTextColor.YELLOW : NamedTextColor.RED))
            .append(Component.text(String.format(java.util.Locale.ROOT, "  (%.0f%%)", pct * 100), NamedTextColor.GRAY));
        // Panel allocation differs meaningfully from the heap -> show it (the missing-Xmx case).
        if (container > 0 && max > 0 && container > max * 2) {
            name.append(Component.text("   panel " + ContainerMemory.fmt(container), NamedTextColor.GOLD))
                .append(Component.text(" (heap not sized to it — see boot log)", NamedTextColor.DARK_GRAY));
        }
        RAM_BAR.name(name.build());
        RAM_BAR.progress((float) Math.max(0.0, Math.min(1.0, pct)));
        RAM_BAR.color(pct < 0.60 ? BossBar.Color.GREEN : pct < 0.85 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    /** Bukkit listener holder — registered once by the command registrar. */
    public static final class QuitListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
            HudBars.onQuit(e.getPlayer());
        }
    }
}
