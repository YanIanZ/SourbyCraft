package dev.iyanz.sourbycraft.perf;

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
 * <p>A player holding {@code sourbycraft.maxplayers.bypass} — or a server op —
 * may connect even when the server is at/over its max-player cap. Implemented as
 * a {@link PlayerLoginEvent} listener: when the login result is
 * {@link PlayerLoginEvent.Result#KICK_FULL KICK_FULL} and the joining player has
 * the bypass permission (or is op), the result is flipped to
 * {@link PlayerLoginEvent.Result#ALLOWED ALLOWED}. Any other kick reason
 * (banned, whitelist, …) is left untouched.
 *
 * <p><b>Folia safety.</b> {@code PlayerLoginEvent} fires at login time, before the
 * player is assigned to a region, so this is a safe, region-free hook. The handler
 * only reads a permission flag and flips an enum field — it touches no entity, no
 * world, and schedules nothing. Registered against
 * {@link org.leavesmc.leaves.plugin.MinecraftInternalPlugin#INSTANCE} like the
 * other SourbyCraft actuators.
 *
 * <p>Runs at {@link EventPriority#HIGH} so it observes the fullness decision made
 * by the server / lower-priority plugins, but still leaves {@code MONITOR}
 * observers an accurate final result.
 *
 * <p><b>F1-7 (varied lang).</b> When the player is NOT bypassing and the kick stands,
 * the vanilla "Server is full" kick message is replaced with a random SourbyCraft-flavored,
 * hex-colored variant from {@link SourbyMessages#SERVER_FULL} via {@code kickMessage(...)}.
 * MiniMessage parse only — Folia-safe (still just enum/String/Component work, no entity/world).
 *
 * <p><b>Opt-in (default OFF).</b> Registering a {@code PlayerLoginEvent} listener trips the
 * server's {@code HorriblePlayerLoginEventHack}, which disables the 1.20.2+ configuration-phase
 * fast path and slows every client join. Because most servers never sit at their player cap, the
 * bypass is <b>not worth that cost by default</b>. It is therefore gated behind the config key
 * {@code sourbycraft.maxplayers.bypass-enabled} (default {@code false}): when unset/false NO
 * listener is registered, so there is no {@code PlayerLoginEvent} listener and joins stay fast.
 * An operator who genuinely needs full-server bypass sets the key to {@code true}, accepting the
 * config-phase fast-path cost. (A future NMS-level bypass that avoids the event entirely is noted
 * as follow-up.)
 */
public final class MaxPlayersBypass implements Listener {

    public static final String BYPASS_PERMISSION = "sourbycraft.maxplayers.bypass";

    /** Config key gating listener registration. Default {@code false} → no PlayerLoginEvent listener. */
    public static final String ENABLED_KEY = "sourbycraft.maxplayers.bypass-enabled";

    private MaxPlayersBypass() {}

    /**
     * Register the full-server bypass listener — but only when
     * {@code sourbycraft.maxplayers.bypass-enabled} is {@code true}. Default OFF so no
     * {@code PlayerLoginEvent} listener exists and the config-phase fast join path stays enabled
     * (no {@code HorriblePlayerLoginEventHack}). Returns whether a listener was actually registered.
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
            "max-players bypass: enabled via " + ENABLED_KEY
            + "=true — note this disables the config-phase fast join path (HorriblePlayerLoginEventHack).");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        // Folia-safe: permission read + isOp() only; no entity/world access.
        if (e.getPlayer().hasPermission(BYPASS_PERMISSION) || e.getPlayer().isOp()) {
            e.setResult(PlayerLoginEvent.Result.ALLOWED);
            return;
        }
        // Non-bypass player stays kicked: swap vanilla "Server is full" for a random SourbyCraft
        // server-full variant. MiniMessage parse only — no off-thread entity/world access.
        Component msg = SourbyMessages.get(SourbyMessages.SERVER_FULL);
        if (msg != Component.empty()) {
            e.kickMessage(msg);
        }
    }
}
