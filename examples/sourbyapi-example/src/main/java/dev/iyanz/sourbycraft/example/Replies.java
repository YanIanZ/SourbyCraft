package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Sends a message from any thread by hopping to the thread that owns the recipient. */
final class Replies {

    private Replies() {
    }

    /**
     * A {@link CommandSender} that is an entity (a player) is touched only on its entity scheduler; the console and other
     * senders only on the global scheduler. A future from SourbyAPI completes on a storage thread, where neither is safe.
     */
    static void send(final Plugin plugin, final AuroraScheduler scheduler, final CommandSender sender, final String message) {
        if (sender instanceof Entity entity) {
            scheduler.runFor(plugin, entity, () -> sender.sendMessage(message), () -> { /* the player left: nothing to tell */ });
        } else {
            scheduler.runGlobal(plugin, () -> sender.sendMessage(message));
        }
    }
}
