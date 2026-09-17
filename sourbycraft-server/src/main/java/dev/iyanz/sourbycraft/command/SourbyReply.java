package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
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
 *   <li>a {@link Player} sender → its own {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler entity scheduler}
 *       ({@code player.getScheduler().run(...)}), the Folia-correct thread for touching that player;</li>
 *   <li>a non-player {@link org.bukkit.entity.Entity} sender → its own entity scheduler, for the
 *       same reason;</li>
 *   <li>a {@link BlockCommandSender} → the region that owns the block. Messaging a command block
 *       is <em>not</em> thread-safe: {@code BaseCommandBlock.sendSystemMessage} writes
 *       {@code lastOutput} on the block entity and calls {@code onUpdated}, guarded by both
 *       {@code AsyncCatcher} and Folia's {@code threadCheck}. Calling it from a worker throws,
 *       and this class used to swallow that into a log line — so a command block running a
 *       command whose reply came back off-thread silently got no output;</li>
 *   <li>console and RCON → run directly. Those senders write to a logger or a connection buffer
 *       and own no world state, so there is nothing to hop to.</li>
 * </ul>
 *
 * <p>The distinction is the point: "non-player" is not a category with one safety story in it. A
 * thread-safe message sink and a block the world owns behave nothing alike.
 *
 * <p>Owned by {@link MinecraftInternalPlugin#INSTANCE} — the always-enabled
 * internal plugin handle the other SourbyCraft actuators use, since server-internal code has no SDK
 * plugin handle. Any failure to schedule is logged and swallowed so a reply hop can never crash the
 * off-thread worker.
 */
public final class SourbyReply {

    private SourbyReply() {}

    /** Where a reply has to run to be safe for a given sender. */
    public enum Hop {
        /** The entity's own scheduler: the thread that owns that entity. */
        ENTITY,
        /** The region owning the sender's block, because messaging it writes block-entity state. */
        REGION,
        /** No hop: the sender owns no world state. */
        DIRECT
    }

    /**
     * The hop a sender requires.
     *
     * <p>Separated from {@link #run} because this is the safety decision, and a decision worth
     * testing should not be reachable only through a static scheduler call.</p>
     */
    public static Hop hopFor(final CommandSender sender) {
        if (sender instanceof Entity) {                  // Player is an Entity.
            return Hop.ENTITY;
        }
        return sender instanceof BlockCommandSender ? Hop.REGION : Hop.DIRECT;
    }

    /** Deliver {@code reply} to {@code sender} on the Folia-correct thread for that sender. */
    public static void run(CommandSender sender, Runnable reply) {
        try {
            if (sender instanceof Entity entity) {           // Player is an Entity.
                entity.getScheduler().run(
                    MinecraftInternalPlugin.INSTANCE,
                    task -> reply.run(),
                    null);
            } else if (sender instanceof BlockCommandSender block) {
                // getBlock() reads the block entity's level and position, both fixed once it is
                // placed. Everything the reply then does happens on the owning region thread.
                Bukkit.getRegionScheduler().execute(
                    MinecraftInternalPlugin.INSTANCE, block.getBlock().getLocation(), reply);
            } else {
                // Console and RCON own no world state.
                reply.run();
            }
        } catch (Throwable t) {
            SourbyLogger.error("SourbyReply: failed to deliver command reply", t);
        }
    }
}
