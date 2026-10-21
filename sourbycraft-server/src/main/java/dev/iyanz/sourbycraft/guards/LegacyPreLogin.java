package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deprecated synchronous {@link PlayerPreLoginEvent}. The region-threading base never fired it
 * (it was fired through the main-thread queue, which nothing drains here), so a legacy
 * whitelist, ban or connection-limit plugin that listens only to it got no callback and nothing
 * warned.
 *
 * <p>It is fired as Paper fires it: after {@code AsyncPlayerPreLoginEvent}, only when it has
 * listeners, pre-set to the async event's result and kick message when that denied the login, and
 * its own final result decides (a listener may allow a login the async event denied, exactly as on
 * Paper). Paper runs it on the main thread while the login's authentication thread waits; here it
 * runs on the global tick (a synchronous event needs a tick thread, and the global tick is the one
 * that owns no region) while the authentication thread waits. That thread is a virtual login
 * thread, never a region tick thread, so waiting there blocks only this login.</p>
 *
 * <p>The wait is bounded ({@link #START_TIMEOUT_NANOS} to start, {@link #RUN_TIMEOUT_NANOS} more
 * to finish). When it runs out the login is refused with {@link #TIMEOUT_MESSAGE}: a pre-login
 * check that could not answer must not let the player in. A slow listener delays the global tick
 * as it delayed the main thread on Paper.</p>
 */
public final class LegacyPreLogin {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");

    /** How long the login waits for the global tick to start the event. */
    static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    /** How much longer it waits, once listeners are running, for them to finish. */
    static final long RUN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    /** The kick message when the event could not be answered in time. */
    static final Component TIMEOUT_MESSAGE = Component.text("The server could not finish its login checks in time. Please try again.");

    /** The kick message for a login a listener refused without giving a message. */
    static final Component DENIED_MESSAGE = Component.text("You are not allowed to join this server.");

    private LegacyPreLogin() {
    }

    /** Whether any plugin listens to the legacy event (nothing is done, and nothing costs, otherwise). */
    public static boolean hasListeners() {
        return PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0;
    }

    /**
     * Engine glue: fires {@code event} through {@code callEvent} on the global tick and waits for it.
     *
     * @param event the event, already pre-set from the async event's result
     * @param callEvent the plugin manager's {@code callEvent}
     * @return the kick message when the login must be refused (never {@code null} for a refusal, even
     *         when the listener gave no message), {@code null} only when the final result is {@code ALLOWED}
     * @throws ExecutionException when calling the event threw (Paper's {@code Waitable#get} did the same)
     */
    public static @Nullable Component fire(final PlayerPreLoginEvent event, final Consumer<Event> callEvent) throws ExecutionException {
        LegacyListenerNotice.report(PlayerPreLoginEvent.getHandlerList(), "PlayerPreLoginEvent",
            "on the global tick while the login waits (bounded)", LOGGER::info);
        final RegionOwnership ownership = RegionOwnership.current();
        if (ownership.isGlobalTickThread()) {
            callEvent.accept(event);
            return verdict(event);
        }
        return fire(event, callEvent, (body, retired) -> {
            ownership.executeOnGlobalTick(body);
            return true;
        }, START_TIMEOUT_NANOS, RUN_TIMEOUT_NANOS, LOGGER::warn);
    }

    /** {@link #fire(PlayerPreLoginEvent, Consumer)} with the global tick, the bounds and the log injected. */
    static @Nullable Component fire(final PlayerPreLoginEvent event, final Consumer<Event> callEvent,
                                    final BoundedHandoff.Target globalTick, final long startNanos, final long runNanos,
                                    final Consumer<String> warn) throws ExecutionException {
        final BoundedHandoff.Result result = BoundedHandoff.run(globalTick, () -> callEvent.accept(event), startNanos, runNanos);
        switch (result.outcome()) {
            case COMPLETED:
                return verdict(event);
            case FAILED:
                throw new ExecutionException(result.failure());
            default:
                // Never read the event here: after OVERRAN a listener may still be changing it.
                warn.accept("PlayerPreLoginEvent for " + event.getName() + " was not answered by the global tick ("
                    + result.outcome() + "); the login is refused.");
                return TIMEOUT_MESSAGE;
        }
    }

    /**
     * Paper's rule: the login continues only when the final result is {@code ALLOWED}. A denial
     * without a kick message is still a denial (it is refused with {@link #DENIED_MESSAGE});
     * {@code null} is returned only for an allowed login, never because the message is missing.
     */
    static @Nullable Component verdict(final PlayerPreLoginEvent event) {
        if (event.getResult() == PlayerPreLoginEvent.Result.ALLOWED) {
            return null;
        }
        final Component message = event.kickMessage();
        return message != null ? message : DENIED_MESSAGE;
    }
}
