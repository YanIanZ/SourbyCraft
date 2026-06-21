package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.kyori.adventure.text.Component.text;

/**
 * Per-player BossBar toggle + refresh driver shared by {@code /tpsbar}
 * and {@code /rambar}.
 *
 * <p>The previous behaviour scheduled a 10-second hide on every command
 * invocation, so the bossbar disappeared a few seconds after toggle.
 * Operators expected toggle semantics: first invocation shows the bar
 * and starts a periodic refresh, second invocation hides it. This
 * manager owns the persistent BossBar instances per (player, kind),
 * runs a 20-tick refresh timer that recomputes title + colour + fill,
 * and cleans up on player quit + bar disable.
 */
public final class BarToggleManager implements Listener {

    public enum Kind { TPS, RAM }

    private static final ConcurrentHashMap<UUID, BossBar> TPS_BARS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, BossBar> RAM_BARS = new ConcurrentHashMap<>();

    private static volatile int refreshTaskId = -1;
    private static volatile boolean listenerRegistered = false;

    private BarToggleManager() {}

    /** Toggle the bar of {@code kind} for {@code player}. Returns {@code true} when shown after this call. */
    public static boolean toggle(Player player, Kind kind) {
        if (player == null) return false;
        ensureScheduler();
        ConcurrentHashMap<UUID, BossBar> map = mapFor(kind);
        UUID id = player.getUniqueId();
        BossBar existing = map.remove(id);
        if (existing != null) {
            player.hideBossBar(existing);
            return false;
        }
        BossBar bar = BossBar.bossBar(
                text(kind == Kind.TPS ? "TPS" : "RAM", SourbyCraftColors.HEADER),
                0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        refresh(player, kind, bar);
        map.put(id, bar);
        player.showBossBar(bar);
        return true;
    }

    public static boolean isActive(Player player, Kind kind) {
        return player != null && mapFor(kind).containsKey(player.getUniqueId());
    }

    private static void ensureScheduler() {
        if (refreshTaskId != -1) return;
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner == null) {
            SourbyLogger.warn("[BarToggleManager] no enabled plugin available for scheduler; bars will be static.");
            return;
        }
        synchronized (BarToggleManager.class) {
            if (refreshTaskId != -1) return;
            refreshTaskId = Bukkit.getScheduler().runTaskTimer(owner, BarToggleManager::tickAll, 20L, 20L).getTaskId();
            if (!listenerRegistered) {
                Bukkit.getPluginManager().registerEvents(new BarToggleManager(), owner);
                listenerRegistered = true;
            }
        }
    }

    private static void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar tps = TPS_BARS.get(p.getUniqueId());
            if (tps != null) refresh(p, Kind.TPS, tps);
            BossBar ram = RAM_BARS.get(p.getUniqueId());
            if (ram != null) refresh(p, Kind.RAM, ram);
        }
    }

    private static void refresh(Player player, Kind kind, BossBar bar) {
        try {
            if (kind == Kind.TPS) {
                refreshTps(bar);
            } else {
                refreshRam(bar);
            }
        } catch (Throwable t) {
            SourbyLogger.warn("[BarToggleManager] refresh failed for " + player.getName() + " / " + kind + ": " + t.getMessage());
        }
    }

    private static void refreshTps(BossBar bar) {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        double pct = Math.clamp(tps[0] / 20.0, 0.0, 1.0);
        TextColor color = tps[0] > 18 ? SourbyCraftColors.SUCCESS
                : tps[0] > 15 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        bar.progress((float) pct);
        bar.color(tps[0] > 18 ? BossBar.Color.GREEN : tps[0] > 15 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        int warmupTicks = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.warmupRemainingTicks();
        var line = text()
                .append(text("TPS ", SourbyCraftColors.HEADER))
                .append(text(BarUtil.bar(pct * 100.0, 20), color))
                .append(text(" " + String.format(Locale.ROOT, "%.1f", tps[0]), color))
                .append(text("  MSPT ", SourbyCraftColors.LABEL))
                .append(text(String.format(Locale.ROOT, "%.1fms", mspt), SourbyCraftColors.VALUE));
        if (warmupTicks > 0) {
            line.append(text("  warmup " + (int) Math.ceil(warmupTicks / 20.0) + "s", SourbyCraftColors.WARNING));
        }
        bar.name(line.build());
    }

    private static void refreshRam(BossBar bar) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long used = total - rt.freeMemory();
        double frac = max == Long.MAX_VALUE ? (double) used / total : (double) used / max;
        double pct = frac * 100.0;
        String lbl = max == Long.MAX_VALUE ? BarUtil.INFINITY : (max / 1048576) + "MB";
        TextColor color = pct < 50 ? SourbyCraftColors.SUCCESS
                : pct < 80 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        bar.progress((float) Math.min(frac, 1.0));
        bar.color(pct < 50 ? BossBar.Color.GREEN : pct < 80 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(text()
                .append(text("RAM ", SourbyCraftColors.HEADER))
                .append(text(BarUtil.bar(pct, 20), color))
                .append(text(" " + (int) Math.round(pct) + "%", color))
                .append(text("  " + (used / 1048576) + "/" + lbl, SourbyCraftColors.VALUE))
                .build());
    }

    private static ConcurrentHashMap<UUID, BossBar> mapFor(Kind k) {
        return k == Kind.TPS ? TPS_BARS : RAM_BARS;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BossBar tps = TPS_BARS.remove(id);
        if (tps != null) event.getPlayer().hideBossBar(tps);
        BossBar ram = RAM_BARS.remove(id);
        if (ram != null) event.getPlayer().hideBossBar(ram);
    }
}
