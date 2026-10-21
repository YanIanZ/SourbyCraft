package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.event.player.PlayerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deprecated synchronous chat events: Bukkit's {@link PlayerChatEvent} and Paper's
 * {@code io.papermc.paper.event.player.ChatEvent}. The region-threading base disabled both (their
 * hop targeted the main-thread queue, which nothing drains here), so a legacy chat or format plugin
 * listening only to them never saw a message, and its cancel or format change was ignored.
 *
 * <p>Each is fired on the thread that owns the sender, which is where Paper's "main thread" event
 * belongs under region threading: inline when the calling thread already owns the player (a
 * plugin's {@code Player#chat} on the player's region), otherwise handed to the player's entity
 * scheduler while the chat thread waits, bounded ({@link #START_TIMEOUT_NANOS} to start,
 * {@link #RUN_TIMEOUT_NANOS} more to finish). Chat processing then continues with the event's
 * message, format, recipients and cancelled state, as on Paper.</p>
 *
 * <p>When the event could not be delivered (the player left, the region did not run it in time, or
 * the caller is a tick thread that does not own the player and so must not wait) the message is
 * dropped and a warning is logged: a chat filter that could not answer must not be bypassed.</p>
 *
 * <p>Order of events for one chat message, as on Paper: {@code AsyncPlayerChatEvent} (chat thread),
 * {@code PlayerChatEvent} (sender's region, only with listeners), {@code AsyncChatEvent} (chat
 * thread), {@code ChatEvent} (sender's region, only with listeners; the message is sent from
 * there), delivery.</p>
 */
public final class LegacyChat {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");

    /** How long the chat thread waits for the sender's region to start the event. */
    static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    /** How much longer it waits, once listeners are running, for them to finish. */
    static final long RUN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    /** At most one "not delivered" warning per this interval, so a stalled region cannot flood the log. */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final AtomicLong NEXT_WARN = new AtomicLong(Long.MIN_VALUE);

    private LegacyChat() {
    }

    /**
     * Engine glue: runs {@code fire} (which posts one legacy synchronous chat event) on the thread
     * that owns {@code player}.
     *
     * @param player the sender
     * @param async whether the caller is the asynchronous chat thread (Paper's {@code ChatProcessor#async})
     * @param fire posts the event and applies its result
     * @return {@code true} when {@code fire} ran to completion; {@code false} when it was not
     *         delivered in time and the message must be dropped
     */
    public static boolean onSenderRegion(final ServerPlayer player, final boolean async, final Runnable fire) {
        LegacyListenerNotice.report(PlayerChatEvent.getHandlerList(), "PlayerChatEvent",
            "on the sender's region thread while the chat thread waits (bounded)", LOGGER::info);
        LegacyListenerNotice.report(io.papermc.paper.event.player.ChatEvent.getHandlerList(), "ChatEvent",
            "on the sender's region thread while the chat thread waits (bounded)", LOGGER::info);
        return onSenderRegion(player.getScoreboardName(), async,
            () -> RegionOwnership.current().isTickThreadFor(player),
            (body, retired) -> player.getBukkitEntity().taskScheduler.schedule(
                (ServerPlayer owned) -> body.run(), (ServerPlayer gone) -> retired.run(), 1L),
            fire, START_TIMEOUT_NANOS, RUN_TIMEOUT_NANOS, System::nanoTime, LegacyChat::warn);
    }

    /** {@link #onSenderRegion(ServerPlayer, boolean, Runnable)} with ownership, scheduler, bounds, clock and log injected. */
    static boolean onSenderRegion(final String playerName, final boolean async, final BooleanSupplier ownsPlayer,
                                  final BoundedHandoff.Target playerRegion, final Runnable fire,
                                  final long startNanos, final long runNanos, final LongSupplier clock,
                                  final Consumer<String> warn) {
        if (ownsPlayer.getAsBoolean()) {
            fire.run();
            return true;
        }
        if (!async) {
            // A synchronous caller is a tick thread. Waiting on another region from a tick thread
            // can deadlock two regions, so the event is not fired and the message is dropped.
            warnLimited(clock, warn, "Legacy chat event for " + playerName
                + " was requested from a tick thread that does not own the player; the message is dropped.");
            return false;
        }
        final BoundedHandoff.Result result = BoundedHandoff.run(playerRegion, fire, startNanos, runNanos);
        if (result.outcome() == BoundedHandoff.Outcome.COMPLETED) {
            return true;
        }
        if (result.outcome() == BoundedHandoff.Outcome.FAILED) {
            throw new RuntimeException("Exception processing chat", result.failure());
        }
        warnLimited(clock, warn, "Legacy chat event for " + playerName + " was not delivered on the player's region ("
            + result.outcome() + "); the message is dropped.");
        return false;
    }

    private static void warnLimited(final LongSupplier clock, final Consumer<String> warn, final String message) {
        final long now = clock.getAsLong();
        final long next = NEXT_WARN.get();
        if ((next == Long.MIN_VALUE || now - next >= 0) && NEXT_WARN.compareAndSet(next, now + WARN_INTERVAL_NANOS)) {
            warn.accept(message);
        }
    }

    private static void warn(final String message) {
        LOGGER.warn(message);
    }

    /** Test seam: let the next warning through. */
    static void resetWarningsForTests() {
        NEXT_WARN.set(Long.MIN_VALUE);
    }
}
