package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class LegacyPreLoginTest {

    private static final long LONG = TimeUnit.SECONDS.toNanos(30L);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(20L);

    private final List<String> warnings = new ArrayList<>();
    private final List<String> threads = new ArrayList<>();

    private static PlayerPreLoginEvent event() {
        return new PlayerPreLoginEvent("alice", InetAddress.getLoopbackAddress(), UUID.nameUUIDFromBytes(new byte[] {1}));
    }

    /** A "global tick" that runs the work on its own named thread and waits for it, like the real hop. */
    private BoundedHandoff.Target globalTick() {
        return (body, retired) -> {
            final Thread t = Thread.ofPlatform().name("Fake Global Tick").start(body);
            try {
                t.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
    }

    private Component fire(final PlayerPreLoginEvent event, final Consumer<Event> listeners) throws ExecutionException {
        return LegacyPreLogin.fire(event, e -> {
            this.threads.add(Thread.currentThread().getName());
            listeners.accept(e);
        }, this.globalTick(), LONG, LONG, this.warnings::add);
    }

    @Test
    void anAllowedLoginContinuesAndTheEventRanOnceOnTheGlobalTick() throws ExecutionException {
        assertNull(this.fire(event(), e -> { }));
        assertEquals(List.of("Fake Global Tick"), this.threads);
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void aListenerDenialKicksWithItsMessage() throws ExecutionException {
        final Component kick = this.fire(event(), e -> ((PlayerPreLoginEvent) e).disallow(PlayerPreLoginEvent.Result.KICK_BANNED, Component.text("banned here")));
        assertEquals(Component.text("banned here"), kick);
    }

    @Test
    void theAsyncDenialIsSeenAndStandsUnlessAListenerAllowsAsOnPaper() throws ExecutionException {
        final PlayerPreLoginEvent denied = event();
        denied.disallow(PlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text("not whitelisted"));
        final PlayerPreLoginEvent.Result[] seen = new PlayerPreLoginEvent.Result[1];
        assertEquals(Component.text("not whitelisted"), this.fire(denied, e -> seen[0] = ((PlayerPreLoginEvent) e).getResult()));
        assertEquals(PlayerPreLoginEvent.Result.KICK_WHITELIST, seen[0], "listeners see the async event's result");

        final PlayerPreLoginEvent overridden = event();
        overridden.disallow(PlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text("not whitelisted"));
        assertNull(this.fire(overridden, e -> ((PlayerPreLoginEvent) e).allow()), "Paper lets the legacy event's final result decide");
    }

    @Test
    void aGlobalTickThatNeverRunsItRefusesTheLoginAndTheEventNeverFiresLate() throws ExecutionException {
        final List<Runnable> queued = new ArrayList<>();
        final int[] calls = new int[1];
        final Component kick = LegacyPreLogin.fire(event(), e -> calls[0]++, (body, retired) -> queued.add(body), SHORT, LONG, this.warnings::add);
        assertSame(LegacyPreLogin.TIMEOUT_MESSAGE, kick);
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.get(0).contains("alice") && this.warnings.get(0).contains("NOT_STARTED"), this.warnings.get(0));
        queued.get(0).run();
        assertEquals(0, calls[0], "abandoned, never fired after the login gave up");
    }

    @Test
    void aFailureWhileCallingTheEventPropagatesLikePapersWaitable() {
        final IllegalStateException boom = new IllegalStateException("callEvent broke");
        final ExecutionException thrown = assertThrows(ExecutionException.class, () -> this.fire(event(), e -> {
            throw boom;
        }));
        assertSame(boom, thrown.getCause());
    }

    @Test
    void theBoundsAreTheDocumentedOnes() {
        assertEquals(TimeUnit.SECONDS.toNanos(10L), LegacyPreLogin.START_TIMEOUT_NANOS);
        assertEquals(TimeUnit.SECONDS.toNanos(10L), LegacyPreLogin.RUN_TIMEOUT_NANOS);
    }
}
