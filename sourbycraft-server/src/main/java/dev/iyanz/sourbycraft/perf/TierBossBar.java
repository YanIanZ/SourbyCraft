package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P8 Operator UX — Tier BossBar.
 *
 * <p>Per-op {@link BossBar} reflecting the current {@link PerfSensor}
 * tier. Colour + label flip on tier transitions; progress bar shows
 * {@code dwellCount / dwellSamples} so operators can see how close
 * the sensor is to escalating.
 *
 * <p>Subscribers opt-in via {@link #show(Player)} (e.g. wired by a
 * future {@code /perf bossbar} subcommand). On tier change every
 * subscribed player gets the bar refreshed.
 */
public final class TierBossBar {

    private static final Map<UUID, BossBar> BARS = new ConcurrentHashMap<>();

    private TierBossBar() {}

    public static void show(final Player player) {
        if (player == null) return;
        BossBar bar = buildBar(PerfSensor.currentTier());
        BARS.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    public static void hide(final Player player) {
        if (player == null) return;
        BossBar bar = BARS.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public static boolean isShowing(final Player player) {
        return player != null && BARS.containsKey(player.getUniqueId());
    }

    /** Invoked by {@code PerfSensor.transition()} on every tier change. */
    public static void onTierChange(final Tier newTier) {
        if (BARS.isEmpty()) return;
        BARS.forEach((uuid, oldBar) -> {
            try {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    BARS.remove(uuid);
                    return;
                }
                p.hideBossBar(oldBar);
                BossBar fresh = buildBar(newTier);
                BARS.put(uuid, fresh);
                p.showBossBar(fresh);
            } catch (Throwable t) {
                SourbyLogger.error("TierBossBar refresh failed for " + uuid, t);
            }
        });
    }

    private static BossBar buildBar(final Tier tier) {
        return BossBar.bossBar(
            Component.text("Perf tier: " + tier.name()),
            1.0f,
            colorFor(tier),
            BossBar.Overlay.PROGRESS
        );
    }

    private static BossBar.Color colorFor(final Tier tier) {
        return switch (tier) {
            case GREEN -> BossBar.Color.GREEN;
            case YELLOW -> BossBar.Color.YELLOW;
            case ORANGE -> BossBar.Color.PINK;
            case RED -> BossBar.Color.RED;
            case EMERGENCY -> BossBar.Color.PURPLE;
        };
    }
}
