package dev.iyanz.sourbycraft.api.event;

import static dev.iyanz.sourbycraft.api.event.EventTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import dev.iyanz.sourbycraft.api.event.EntityTeleportAsyncEvent.TeleportType;
import dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.PortalType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

/**
 * Handler lists, {@link Event#callEvent()} and the sync/async flag, as implemented by the API classes. The
 * server's own "may only be triggered from the owning thread" guard lives in the server's plugin manager, not in
 * this module; what this module owns, and what is checked here, is the flag the guard reads and how
 * {@code callEvent()} turns the plugin manager's behaviour into its return value.
 */
class EventDispatchTest {
    private record Sample<E extends Event>(Class<E> type, Supplier<E> factory) {
    }

    private static final List<Sample<?>> SAMPLES = List.of(
        new Sample<>(EntityPortalAsyncEvent.class,
            () -> new EntityPortalAsyncEvent(entity(), world(), world(), PortalType.NETHER)),
        new Sample<>(EntityPostPortalAsyncEvent.class,
            () -> new EntityPostPortalAsyncEvent(entity(), world(), world(), PortalType.NETHER)),
        new Sample<>(EntityTeleportAsyncEvent.class,
            () -> new EntityTeleportAsyncEvent(entity(), location(world(), 0), null, TeleportCause.PLUGIN, TeleportType.SAME_REGION)),
        new Sample<>(EntityPostTeleportAsyncEvent.class,
            () -> new EntityPostTeleportAsyncEvent(entity(), location(world(), 0), null, TeleportCause.PLUGIN, TeleportType.SAME_REGION)),
        new Sample<>(PlayerRespawnAsyncEvent.class,
            () -> new PlayerRespawnAsyncEvent(player(), location(world(), 0), false, false, false, RespawnReason.DEATH)),
        new Sample<>(PlayerPostRespawnAsyncEvent.class,
            () -> new PlayerPostRespawnAsyncEvent(player(), location(world(), 0), false, false, false, RespawnReason.DEATH)),
        new Sample<>(PlayerSaveEvent.class, () -> new PlayerSaveEvent(player(), false)),
        new Sample<>(PlayerViewEndCreditsEvent.class, () -> new PlayerViewEndCreditsEvent(player(), true)),
        new Sample<>(WorldUnloadAsyncEvent.class, () -> new WorldUnloadAsyncEvent(world())));

    @Test
    void everyEventHasItsOwnHandlerListReachableStaticallyAndPerInstance() throws ReflectiveOperationException {
        final Map<HandlerList, Class<?>> seen = new IdentityHashMap<>();
        for (final Sample<?> sample : SAMPLES) {
            final Method accessor = sample.type().getMethod("getHandlerList");
            assertTrue(Modifier.isStatic(accessor.getModifiers()), sample.type() + " needs a static getHandlerList()");
            final HandlerList viaStatic = (HandlerList) accessor.invoke(null);
            assertNotNull(viaStatic);
            assertSame(viaStatic, sample.factory().get().getHandlers(),
                sample.type().getSimpleName() + ": instance and static handler list must be the same object");
            final Class<?> previous = seen.put(viaStatic, sample.type());
            assertNull(previous, sample.type().getSimpleName() + " shares its HandlerList with " + previous);
        }
    }

    @Test
    void listenersOnlyReceiveTheEventTypeTheyRegisteredFor() {
        final AtomicInteger portalCalls = new AtomicInteger();
        final AtomicInteger postPortalCalls = new AtomicInteger();
        try (var swap = new Swap();
             var a = new Registration(EntityPortalAsyncEvent.getHandlerList(), EntityPortalAsyncEvent.class, e -> portalCalls.incrementAndGet());
             var b = new Registration(EntityPostPortalAsyncEvent.getHandlerList(), EntityPostPortalAsyncEvent.class, e -> postPortalCalls.incrementAndGet())) {
            dispatchToRegisteredListeners(swap);
            new EntityPortalAsyncEvent(entity(), world(), world(), PortalType.NETHER).callEvent();
            assertEquals(1, portalCalls.get());
            assertEquals(0, postPortalCalls.get());
            new EntityPostPortalAsyncEvent(entity(), world(), world(), PortalType.NETHER).callEvent();
            assertEquals(1, portalCalls.get());
            assertEquals(1, postPortalCalls.get());
        }
    }

    @Test
    void unregisteringRemovesTheListener() {
        final AtomicInteger calls = new AtomicInteger();
        try (var swap = new Swap()) {
            dispatchToRegisteredListeners(swap);
            try (var registration = new Registration(PlayerSaveEvent.getHandlerList(), PlayerSaveEvent.class, e -> calls.incrementAndGet())) {
                new PlayerSaveEvent(player(), false).callEvent();
            }
            new PlayerSaveEvent(player(), false).callEvent();
        }
        assertEquals(1, calls.get());
    }

    @Test
    void callEventReturnsFalseExactlyWhenAHandlerCancelled() {
        try (var swap = new Swap();
             var cancel = new Registration(EntityTeleportAsyncEvent.getHandlerList(), EntityTeleportAsyncEvent.class, e -> e.setCancelled(true))) {
            dispatchToRegisteredListeners(swap);
            final var event = new EntityTeleportAsyncEvent(entity(), location(world(), 0), null, TeleportCause.PLUGIN, TeleportType.CROSS_WORLD);
            assertFalse(event.callEvent(), "cancelled -> false");
            assertTrue(event.isCancelled());
        }
        try (var swap = new Swap();
             var observe = new Registration(EntityTeleportAsyncEvent.getHandlerList(), EntityTeleportAsyncEvent.class, e -> { })) {
            dispatchToRegisteredListeners(swap);
            final var event = new EntityTeleportAsyncEvent(entity(), location(world(), 0), null, TeleportCause.PLUGIN, TeleportType.CROSS_WORLD);
            assertTrue(event.callEvent(), "a handler that leaves it alone -> true");
        }
    }

    @Test
    void handlersSeeAndCanChangeTheDestinationBeforeCallEventReturns() {
        final var redirected = world();
        try (var swap = new Swap();
             var redirect = new Registration(EntityPortalAsyncEvent.getHandlerList(), EntityPortalAsyncEvent.class,
                 e -> e.setTo(redirected))) {
            dispatchToRegisteredListeners(swap);
            final var event = new EntityPortalAsyncEvent(entity(), world(), world(), PortalType.NETHER);
            assertTrue(event.callEvent());
            assertSame(redirected, event.getTo(), "the handler's setTo is visible to the caller after callEvent");
        }
    }

    @Test
    void nonCancellableEventsAlwaysReportTrue() {
        try (var swap = new Swap()) {
            dispatchToRegisteredListeners(swap);
            assertTrue(new PlayerSaveEvent(player(), true).callEvent());
            assertTrue(new EntityPostTeleportAsyncEvent(entity(), location(world(), 0), null, TeleportCause.PLUGIN, TeleportType.SAME_REGION).callEvent());
            assertTrue(new PlayerViewEndCreditsEvent(player(), false).callEvent());
        }
    }

    @Test
    void callEventHandsTheSameInstanceToThePluginManagerExactlyOnce() {
        try (var swap = new Swap()) {
            final var event = new WorldUnloadAsyncEvent(world());
            event.callEvent();
            verify(swap.pluginManager).callEvent(event);
            verify(swap.pluginManager, org.mockito.Mockito.times(1)).callEvent(any(Event.class));
        }
    }

    @Test
    void aRefusalFromThePluginManagerPropagatesAndIsNotSwallowed() {
        try (var swap = new Swap()) {
            final var event = new PlayerSaveEvent(player(), false);
            doThrow(new IllegalStateException("PlayerSaveEvent cannot be triggered asynchronously from another thread."))
                .when(swap.pluginManager).callEvent(event);
            final var thrown = assertThrows(IllegalStateException.class, event::callEvent);
            assertTrue(thrown.getMessage().contains("another thread"));
        }
    }

    @Test
    void aCancelledStateSetBeforeCallEventIsStillReportedWhenNoHandlerRuns() {
        try (var swap = new Swap()) {
            final var event = new WorldUnloadAsyncEvent(world());
            event.setCancelled(true);
            assertFalse(event.callEvent(), "callEvent reports the final cancelled state, not only handler changes");
            verify(swap.pluginManager).callEvent(event);
        }
    }

    /**
     * Observed behaviour, pinned: none of the "...AsyncEvent" classes pass {@code isAsync=true} to Bukkit's
     * {@link Event}; the name means "fires on a region thread", not Bukkit's async flag. The server's plugin
     * manager therefore applies its synchronous-event rule to them. Changing this flag changes which threads may
     * fire these events, so it must be a deliberate, reviewed change.
     */
    @Test
    void theAsyncNamedEventsAreSynchronousInBukkitsSense() {
        for (final Sample<?> sample : SAMPLES) {
            assertFalse(sample.factory().get().isAsynchronous(), sample.type().getSimpleName());
        }
    }

    @Test
    void cancellableSetMatchesTheDocumentedMutableEvents() {
        final List<String> cancellable = new ArrayList<>();
        for (final Sample<?> sample : SAMPLES) {
            if (Cancellable.class.isAssignableFrom(sample.type())) {
                cancellable.add(sample.type().getSimpleName());
            }
        }
        assertEquals(List.of("EntityPortalAsyncEvent", "EntityTeleportAsyncEvent", "WorldUnloadAsyncEvent"), cancellable);
    }
}
