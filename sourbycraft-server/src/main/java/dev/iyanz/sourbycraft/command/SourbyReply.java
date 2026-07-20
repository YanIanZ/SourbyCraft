package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Folia-safe reply hop for commands whose result is produced off-thread (e.g. a virtual-thread
 * network task) and must be delivered back to the invoking {@link CommandSender}.
 *
 * <p><b>Why this exists.</b> On the pre-Folia Paper line these commands bounced the reply back to
 * the main thread with {@code MinecraftServer.getServer().execute(...)}. Folia has no global main
 * thread, so {@code MinecraftServer.execute(...)} throws
 * {@link UnsupportedOperationException} — which surfaced as a {@code /speedtest}/{@code /ping}
 * crash in the boot log. This helper replaces that call with a region-correct hop:
 *
 * <ul>
 *   <li>a {@link Player} sender → its own {@link org.bukkit.entity.Entity.Scheduler entity scheduler}
 *       ({@code player.getScheduler().run(...)}), the Folia-correct thread for touching that player;</li>
 *   <li>a non-player sender (console / command block / RCON) → run directly. Console sends are
 *       thread-safe, so no hop is needed and we avoid pinning a region scheduler for a
 *       thread-independent sender.</li>
 * </ul>
 *
 * <p>Owned by {@link MinecraftInternalPlugin#INSTANCE} — the always-enabled
 * internal plugin handle the other SourbyCraft actuators use, since server-internal code has no SDK
 * plugin handle. Any failure to schedule is logged and swallowed so a reply hop can never crash the
 * off-thread worker.
 */
public final class SourbyReply {

    private SourbyReply() {}

    /** Deliver {@code reply} to {@code sender} on the Folia-correct thread for that sender. */
    public static void run(CommandSender sender, Runnable reply) {
        try {
            if (sender instanceof Player p) {
                p.getScheduler().run(
                    MinecraftInternalPlugin.INSTANCE,
                    task -> reply.run(),
                    null);
            } else {
                // Console/RCON/command-block senders are thread-safe to message directly.
                reply.run();
            }
        } catch (Throwable t) {
            SourbyLogger.error("SourbyReply: failed to deliver command reply", t);
        }
    }
}
