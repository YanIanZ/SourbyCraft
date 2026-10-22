package dev.iyanz.sourbycraft.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.timers.TimerQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-level vanilla function timers, owned by the global tick rather than any region. */
public final class ScheduledFunctionTicker {
    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft/ScheduledFunctions");

    private ScheduledFunctionTicker() {}

    /** Called only after this world's game time advances in the global tick ({@code RegionizedServer#tickTime}). */
    public static void tick(final ServerLevel level) {
        RegionOwnership.current().ensureGlobalTickThread("Scheduled functions must tick on the global thread");
        final long gameTime = level.getGameTime();
        tick(level.scheduledEvents, level.getServer(), gameTime,
            failure -> LOGGER.error("Scheduled event failed in {} at game time {}; continuing with remaining events",
                level.dimension().identifier(), gameTime, failure));
    }

    /**
     * TimerQueue removes a due event before calling it. Resume after a failed callback so it is
     * consumed once and later due events still run. Vanilla callbacks choose the server's function
     * source; this does not give that source ownership of a region. Fatal JVM errors propagate.
     */
    public static <T> void tick(final TimerQueue<T> queue, final T context, final long gameTime,
                               final Consumer<Exception> logFailure) {
        while (true) {
            try {
                queue.tick(context, gameTime);
                return;
            } catch (final Exception failure) {
                logFailure.accept(failure);
            }
        }
    }

    /** A {@code /schedule} mutation; may refuse with the command's own syntax error. */
    @FunctionalInterface
    public interface CommandAction {
        int run() throws CommandSyntaxException;
    }

    /**
     * Runs a {@code /schedule} mutation on the global tick, serialized with {@link #tick}: inline
     * when already there, else queued. The command returns acceptance ({@code 1}) at once; a
     * syntax error raised on the global tick is sent to the source as a failure. Same contract as
     * the engine's console/global-state command route, reached through Aurora's
     * {@link RegionOwnership} rather than the engine class.
     */
    public static int onGlobal(final CommandAction action, final CommandSourceStack source) {
        RegionOwnership.current().executeOnGlobalTick(() -> {
            try {
                action.run();
            } catch (final CommandSyntaxException failure) {
                source.sendFailure(Component.literal(failure.getMessage()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Brigadier may request completions off the global thread; never read TimerQueue there. */
    public static CompletableFuture<Suggestions> suggest(final ServerLevel level, final SuggestionsBuilder builder) {
        final CompletableFuture<Suggestions> result = new CompletableFuture<>();
        RegionOwnership.current().executeOnGlobalTick(() -> {
            try {
                result.complete(SharedSuggestionProvider.suggest(level.scheduledEvents.getEventsIds(), builder).join());
            } catch (final Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
}
