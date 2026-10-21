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
 * belongs under region threading. What happens is decided by the calling thread, not by Paper's
 * {@code async} flag ({@code Player#chat} always passes {@code false}, from whatever thread):</p>
 * <ul>
 *   <li>the caller owns the player (a plugin's {@code Player#chat} on the player's region): the
 *       event fires inline, then the message continues ({@link Delivery#INLINE});</li>
 *   <li>the caller is not a tick thread (the asynchronous chat thread): the event is handed to the
 *       player's entity scheduler and the caller waits, bounded ({@link #START_TIMEOUT_NANOS} to
 *       start, {@link #RUN_TIMEOUT_NANOS} more to finish), then continues with the event's
 *       message, format, recipients and cancelled state, as on Paper ({@link Delivery#HANDED_OFF});</li>
 *   <li>the caller is a tick thread that does not own the player (a plugin's {@code Player#chat}
 *       from the global region scheduler or another region): it must not wait, since two tick
 *       threads waiting on each other deadlock. The event and the rest of the message are handed
 *       to the player's entity scheduler as one continuation and the caller returns at once; the
 *       message is processed on the player's next tick ({@link Delivery#CONTINUED_ON_OWNER}).</li>
 * </ul>
 *
 * <p>A message is dropped, with a rate-limited warning, only when its event could not be run: the
 * player left, the region did not start the event in time, or the listeners were still running
 * when the wait ran out ({@link Delivery#DROPPED}). In the last case the listeners finish on the
 * region, but nothing reads their result and the message is never sent: the part that sends it
 * (the {@code deliver} step) runs only after a completed wait. A chat filter that could not answer
 * must not be bypassed, and a message must not arrive late and out of order.</p>
 *
 * <p>Order of events for one chat message, as on Paper: {@code AsyncPlayerChatEvent} (chat thread),
 * {@code PlayerChatEvent} (sender's region, only with listeners), {@code AsyncChatEvent} (chat
 * thread), {@code ChatEvent} (sender's region, only with listeners), delivery (from the chat
 * thread after the wait; Paper sent it from the main thread inside the {@code ChatEvent} hop).</p>
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
    private static final String WHERE = "on the thread that owns the sender (inline when the caller owns the player; "
        + "otherwise on the player's region, the asynchronous chat thread waiting for it, bounded)";

    /** How a legacy chat event was handled. */
    public enum Delivery {
        /** The caller owns the player: event and message ran inline. */
        INLINE,
        /** The event ran on the player's region while the caller waited; the message continued on the caller. */
        HANDED_OFF,
        /** The caller is a tick thread that does not own the player: event and message run on the player's next tick. */
        CONTINUED_ON_OWNER,
        /** The event could not be run (or not in time); the message is dropped and was never sent. */
        DROPPED
    }

    private LegacyChat() {
    }

    /**
     * Engine glue: runs {@code fire} (posts one legacy synchronous chat event) on the thread that
     * owns {@code player}, then {@code deliver} (everything the message does after that event).
     *
     * @param player the sender
     * @param fire posts the event
     * @param deliver continues the message with the event's result; never run for a dropped message
     * @return how the event was handled
     */
    public static Delivery onSenderRegion(final ServerPlayer player, final Runnable fire, final Runnable deliver) {
        LegacyListenerNotice.report(PlayerChatEvent.getHandlerList(), "PlayerChatEvent", WHERE, LOGGER::info);
        LegacyListenerNotice.report(io.papermc.paper.event.player.ChatEvent.getHandlerList(), "ChatEvent", WHERE, LOGGER::info);
        final RegionOwnership ownership = RegionOwnership.current();
        return onSenderRegion(player.getScoreboardName(),
            () -> ownership.isTickThreadFor(player), ownership::isTickThread,
            (body, retired) -> player.getBukkitEntity().taskScheduler.schedule(
                (ServerPlayer owned) -> body.run(), (ServerPlayer gone) -> retired.run(), 1L),
            fire, deliver, START_TIMEOUT_NANOS, RUN_TIMEOUT_NANOS, System::nanoTime, LegacyChat::warn);
    }

    /** {@link #onSenderRegion(ServerPlayer, Runnable, Runnable)} with ownership, scheduler, bounds, clock and log injected. */
    static Delivery onSenderRegion(final String playerName, final BooleanSupplier ownsPlayer, final BooleanSupplier onTickThread,
                                   final BoundedHandoff.Target playerRegion, final Runnable fire, final Runnable deliver,
                                   final long startNanos, final long runNanos, final LongSupplier clock,
                                   final Consumer<String> warn) {
        if (ownsPlayer.getAsBoolean()) {
            fire.run();
            deliver.run();
            return Delivery.INLINE;
        }
        if (onTickThread.getAsBoolean()) {
            // A tick thread that does not own the player must not wait for its region (two tick
            // threads waiting on each other deadlock): hand the rest of the message over instead.
            final Runnable continuation = () -> {
                try {
                    fire.run();
                    deliver.run();
                } catch (final RuntimeException e) {
                    LOGGER.error("Exception processing chat for {}", playerName, e);
                }
            };
            boolean posted;
            try {
                posted = playerRegion.post(continuation, () -> warnLimited(clock, warn, "Legacy chat event for " + playerName
                    + " could not run: the player left before their next tick; the message is dropped."));
            } catch (final RuntimeException e) {
                posted = false;
            }
            if (!posted) {
                warnLimited(clock, warn, "Legacy chat event for " + playerName
                    + " could not be handed to the player's region (REFUSED); the message is dropped.");
                return Delivery.DROPPED;
            }
            return Delivery.CONTINUED_ON_OWNER;
        }
        final BoundedHandoff.Result result = BoundedHandoff.run(playerRegion, fire, startNanos, runNanos);
        switch (result.outcome()) {
            case COMPLETED:
                deliver.run();
                return Delivery.HANDED_OFF;
            case FAILED:
                throw new RuntimeException("Exception processing chat", result.failure());
            case OVERRAN:
                warnLimited(clock, warn, "Legacy chat event for " + playerName + " was still running on the player's region"
                    + " when the wait ran out (OVERRAN); the message is dropped and will not be sent when the listeners finish.");
                return Delivery.DROPPED;
            default:
                warnLimited(clock, warn, "Legacy chat event for " + playerName + " was not delivered on the player's region ("
                    + result.outcome() + "); the message is dropped.");
                return Delivery.DROPPED;
        }
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
