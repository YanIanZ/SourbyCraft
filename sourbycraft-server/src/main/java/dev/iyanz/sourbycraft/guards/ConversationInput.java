package dev.iyanz.sourbycraft.guards;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chat typed by a player who is in a Bukkit {@code Conversation}. Chat is processed on the async
 * chat executor, not on the player's region, and the base routed conversation input through the
 * server's main-thread queue, which does not exist here: it threw, the chat chain logged "Chain
 * link failed" and the player's input was dropped, so no conversation could advance. The input is
 * now delivered on the thread that owns the player (the entity scheduler: immediately when already
 * there, otherwise at the player's next tick), which is what {@code acceptConversationInput}
 * needs because prompts call plugin code. A failure in a plugin's prompt is logged against the
 * player instead of being lost in the scheduler.
 */
public final class ConversationInput {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");

    private ConversationInput() {
    }

    /** Engine glue: deliver {@code input} to the player's conversation on the player's own region. */
    public static void deliver(final net.minecraft.server.level.ServerPlayer player, final String input) {
        final String name = player.getScoreboardName();
        route(name, input,
            task -> player.getBukkitEntity().taskScheduler.scheduleOrExecute((net.minecraft.server.level.ServerPlayer owned) -> task.run()),
            player.getBukkitEntity()::acceptConversationInput,
            LOGGER::error);
    }

    /**
     * Posts {@code accept(input)} through {@code onPlayerRegion}; an exception it throws is passed to
     * {@code errors} with the player's name and never propagates to the scheduler.
     */
    public static void route(final String playerName, final String input, final Consumer<Runnable> onPlayerRegion,
                             final Consumer<String> accept, final Consumer<String> errors) {
        onPlayerRegion.accept(() -> {
            try {
                accept.accept(input);
            } catch (final RuntimeException e) {
                errors.accept("Conversation input from " + playerName + " failed: " + e);
            }
        });
    }
}
