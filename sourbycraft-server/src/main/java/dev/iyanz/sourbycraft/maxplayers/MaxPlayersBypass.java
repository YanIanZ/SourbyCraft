package dev.iyanz.sourbycraft.maxplayers;

import dev.iyanz.sourbycraft.lang.SourbyMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

/**
 * Full-server join bypass (F1-6 feature 2).
 *
 * <p>A player holding {@code sourbycraft.maxplayers.bypass} — or a server op — may connect even
 * when the server is at/over its max-player cap. Implemented as a {@link PlayerLoginEvent}
 * listener: when the login result is {@link PlayerLoginEvent.Result#KICK_FULL KICK_FULL} and the
 * joining player has the bypass permission (or is op), the result is flipped to
 * {@link PlayerLoginEvent.Result#ALLOWED ALLOWED}. Any other kick reason (banned, whitelist, …)
 * is left untouched.
 *
 * <p><b>F1-7 (varied lang).</b> When the player is NOT bypassing and the kick stands, the vanilla
 * "Server is full" kick message is replaced with a random SourbyCraft-flavored, hex-colored
 * variant from {@link SourbyMessages#SERVER_FULL} via {@code kickMessage(...)}.
 *
 * <p><b>Opt-in (default OFF).</b> Registering a {@code PlayerLoginEvent} listener disables the
 * 1.20.2+ configuration-phase fast join path and slows every client join. Because most servers
 * never sit at their player cap, the bypass is not worth that cost by default. It is therefore
 * gated behind the config key {@code sourcebycraft.maxplayers.bypass-enabled} (default
 * {@code false}): when unset/false NO listener is registered.
 *
 * <p>Relocated out of the {@code perf} package on the Canvas re-platform (feat/canvas-engine,
 * PR #12) — this is a kept utility feature, not part of the deferred self-tuning perf-engine.
 */
public final class MaxPlayersBypass implements Listener {

    public static final String BYPASS_PERMISSION = "sourbycraft.maxplayers.bypass";

    /** Config key gating listener registration. Default {@code false} → no PlayerLoginEvent listener. */
    public static final String ENABLED_KEY = "sourbycraft.maxplayers.bypass-enabled";

    private MaxPlayersBypass() {}

    /**
     * Register the full-server bypass listener — but only when
     * {@code sourbycraft.maxplayers.bypass-enabled} is {@code true}. Returns whether a listener
     * was actually registered.
     */
    public static boolean register(Plugin plugin) {
        boolean enabled = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool(ENABLED_KEY, false);
        if (!enabled) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "max-players bypass: disabled (default) — no PlayerLoginEvent listener, fast joins kept."
                + " Set " + ENABLED_KEY + "=true to allow bypass holders onto a full server"
                + " (costs the config-phase fast join path).");
            return false;
        }
        Bukkit.getPluginManager().registerEvents(new MaxPlayersBypass(), plugin);
        dev.iyanz.sourbycraft.util.SourbyLogger.info(
            "max-players bypass: enabled via " + ENABLED_KEY + "=true — note this disables the config-phase fast join path.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        if (e.getPlayer().hasPermission(BYPASS_PERMISSION) || e.getPlayer().isOp()) {
            e.setResult(PlayerLoginEvent.Result.ALLOWED);
            return;
        }
        Component msg = SourbyMessages.get(SourbyMessages.SERVER_FULL);
        if (msg != Component.empty()) {
            e.kickMessage(msg);
        }
    }
}
