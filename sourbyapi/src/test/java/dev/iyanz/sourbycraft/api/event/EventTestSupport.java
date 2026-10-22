package dev.iyanz.sourbycraft.api.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

/**
 * Shared fixtures for the {@code dev.iyanz.sourbycraft.api.event} tests.
 *
 * <p>{@link Bukkit#setServer} refuses to redefine the singleton, and other test classes of this module may already
 * have installed one (paper-api's {@code TestServer} is on the same test source set), so the {@code Bukkit.server}
 * field is swapped by reflection and always put back by {@link Swap#close()}.
 */
final class EventTestSupport {
    private EventTestSupport() {
    }

    /** An installed fake server whose plugin manager is {@link #pluginManager}; restores the previous one on close. */
    static final class Swap implements AutoCloseable {
        final Server server = mock(Server.class);
        final PluginManager pluginManager = mock(PluginManager.class);
        private final Field field;
        private final Server previous;

        Swap() {
            try {
                this.field = Bukkit.class.getDeclaredField("server");
                this.field.setAccessible(true);
                this.previous = (Server) this.field.get(null);
                when(this.server.getPluginManager()).thenReturn(this.pluginManager);
                this.field.set(null, this.server);
            } catch (final ReflectiveOperationException e) {
                throw new AssertionError("cannot swap Bukkit.server", e);
            }
        }

        @Override
        public void close() {
            try {
                this.field.set(null, this.previous);
            } catch (final IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Registers {@code handler} on the event type's real {@link HandlerList}; closing unregisters it so no
     * handler leaks into other tests.
     */
    static final class Registration implements AutoCloseable {
        private final HandlerList list;
        private final RegisteredListener registered;

        <E extends Event> Registration(final HandlerList list, final Class<E> type, final Consumer<E> handler) {
            this.list = list;
            final Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            this.registered = new RegisteredListener(new Listener() {
            }, (listener, event) -> handler.accept(type.cast(event)), EventPriority.NORMAL, plugin, false);
            list.register(this.registered);
        }

        @Override
        public void close() {
            this.list.unregister(this.registered);
        }
    }

    /**
     * Makes {@code swap}'s plugin manager dispatch like the server does for a plain (non-guarded) call: every
     * registered listener of the event's own {@link HandlerList} is invoked.
     */
    static void dispatchToRegisteredListeners(final Swap swap) {
        org.mockito.Mockito.doAnswer(invocation -> {
            final Event event = invocation.getArgument(0);
            for (final RegisteredListener registration : event.getHandlers().getRegisteredListeners()) {
                registration.callEvent(event);
            }
            return null;
        }).when(swap.pluginManager).callEvent(org.mockito.ArgumentMatchers.any(Event.class));
    }

    static Entity entity() {
        return mock(Entity.class);
    }

    static Player player() {
        return mock(Player.class);
    }

    static World world() {
        return mock(World.class);
    }

    static Location location(final World world, final double x) {
        return new Location(world, x, 64, 0, 0f, 0f);
    }
}
