package dev.iyanz.sourbycraft.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

/**
 * The Build 54 {@link SourbyAPI} entry point: a missing feature is a {@link FeatureUnavailableException} naming the
 * feature and, where one switches it on, the configuration key; service lookups ask the services manager every time
 * until the service events are bound, then are cached until a service is registered or unregistered or a plugin is
 * disabled (the owner's disable unbinds); a missing service is never cached; MultiProfile counts as available only
 * while its service reports itself enabled.
 *
 * <p>No server runs: the case installs a Mockito {@link Server} (services manager and plugin manager mocks, the
 * registered event executors captured and fired directly) into {@link Bukkit} for its own duration and puts back what
 * was there, together with the static state of {@link SourbyAPI} it touches. Deterministic and single-threaded.</p>
 */
class SourbyApiLookupTest {

    private static Field field(final Class<?> owner, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void unbind() throws Exception {
        final Method unbind = SourbyAPI.class.getDeclaredMethod("unbind");
        unbind.setAccessible(true);
        unbind.invoke(null);
    }

    @Test
    void featureUnavailableNamesTheFeatureAndTheKey() {
        final FeatureUnavailableException keyed = new FeatureUnavailableException("AWF MultiProfile (AuroraProfiles)",
            SourbyAPI.MULTIPROFILE_ENABLED_KEY, "it is off");
        assertEquals("AWF MultiProfile (AuroraProfiles) is not available: it is off (enable it with aurora.awf.multiprofile.enabled)",
            keyed.getMessage());
        assertEquals("AWF MultiProfile (AuroraProfiles)", keyed.feature());
        assertEquals(Optional.of("aurora.awf.multiprofile.enabled"), keyed.configKey());
        final FeatureUnavailableException plain = new FeatureUnavailableException("metrics", null, "not registered");
        assertEquals("metrics is not available: not registered", plain.getMessage());
        assertTrue(plain.configKey().isEmpty());
        assertTrue(RuntimeException.class.isAssignableFrom(FeatureUnavailableException.class), "callers must not be forced to catch it");
    }

    @Test
    void lookupsAreCachedUntilAServiceEventAndMissingFeaturesNameTheirKey() throws Exception {
        final Field serverField = field(Bukkit.class, "server");
        final Field sourbyField = field(SourbyAPI.class, "sourbyCraft");
        final Field boundField = field(SourbyAPI.class, "eventsBound");
        final Object previousServer = serverField.get(null);
        final Object previousSourby = sourbyField.get(null);

        final Server server = mock(Server.class);
        final ServicesManager services = mock(ServicesManager.class);
        final PluginManager plugins = mock(PluginManager.class);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(plugins);
        final Map<Class<?>, EventExecutor> executors = new ConcurrentHashMap<>();
        doAnswer(call -> {
            executors.put(call.getArgument(0), call.getArgument(3));
            return null;
        }).when(plugins).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
        final AtomicReference<AuroraWorlds> worlds = new AtomicReference<>(mock(AuroraWorlds.class));
        final AtomicInteger loads = new AtomicInteger();
        when(services.load(AuroraWorlds.class)).thenAnswer(call -> {
            loads.incrementAndGet();
            return worlds.get();
        });
        final AuroraProfiles profiles = mock(AuroraProfiles.class);
        when(services.load(AuroraProfiles.class)).thenReturn(profiles);
        when(services.load(ProfileTypeRegistry.class)).thenReturn(null);
        final Plugin owner = mock(Plugin.class);
        final Listener listener = new Listener() {};
        try {
            unbind();
            serverField.set(null, server);
            sourbyField.set(null, Boolean.TRUE);
            assertTrue(SourbyAPI.isSourbyCraft());

            // Unbound: every lookup asks.
            final AuroraWorlds first = worlds.get();
            assertSame(first, SourbyAPI.worlds());
            assertSame(first, SourbyAPI.findWorlds().orElseThrow());
            assertEquals(2, loads.get());

            // Bound: cached until a service event.
            SourbyAPI.bindServiceEvents(owner);
            assertTrue((boolean) boundField.get(null));
            assertEquals(Set.of(ServiceRegisterEvent.class, ServiceUnregisterEvent.class, PluginDisableEvent.class), executors.keySet());
            loads.set(0);
            assertSame(first, SourbyAPI.worlds());
            assertSame(first, SourbyAPI.worlds());
            assertEquals(1, loads.get(), "a bound lookup was not cached");

            final AuroraWorlds replaced = mock(AuroraWorlds.class);
            worlds.set(replaced);
            fire(executors, listener, new ServiceUnregisterEvent(new RegisteredServiceProvider<>(AuroraWorlds.class, first,
                ServicePriority.Normal, owner)));
            assertSame(replaced, SourbyAPI.worlds(), "an unregistration left a stale service cached");
            assertEquals(2, loads.get());

            // A missing service is not cached; its exception names no key (AuroraWorlds has none to switch).
            worlds.set(null);
            fire(executors, listener, new ServiceRegisterEvent(new RegisteredServiceProvider<>(AuroraWorlds.class, replaced,
                ServicePriority.Normal, owner)));
            assertTrue(SourbyAPI.findWorlds().isEmpty());
            final FeatureUnavailableException noWorlds = assertThrows(FeatureUnavailableException.class, SourbyAPI::worlds);
            assertTrue(noWorlds.configKey().isEmpty());
            assertTrue(noWorlds.getMessage().startsWith("Aurora World Fabric worlds (AuroraWorlds) is not available"),
                noWorlds.getMessage());
            assertEquals(4, loads.get(), "a missing service was cached");
            worlds.set(first);
            assertSame(first, SourbyAPI.worlds());

            // MultiProfile: present only while its service is enabled; the exception names the key that enables it.
            assertTrue(SourbyAPI.findProfiles().isEmpty(), "a disabled MultiProfile service was offered");
            final FeatureUnavailableException off = assertThrows(FeatureUnavailableException.class, SourbyAPI::profiles);
            assertEquals(Optional.of(SourbyAPI.MULTIPROFILE_ENABLED_KEY), off.configKey());
            assertTrue(off.getMessage().endsWith("(enable it with " + SourbyAPI.MULTIPROFILE_ENABLED_KEY + ")"), off.getMessage());
            when(profiles.isEnabled()).thenReturn(true);
            assertSame(profiles, SourbyAPI.profiles());
            final FeatureUnavailableException noTypes = assertThrows(FeatureUnavailableException.class, SourbyAPI::profileTypes);
            assertEquals(Optional.of(SourbyAPI.MULTIPROFILE_ENABLED_KEY), noTypes.configKey());
            assertTrue(SourbyAPI.findProfileTypes().isEmpty());

            // Another plugin's disable invalidates; the owner's disable unbinds (uncached from then on).
            loads.set(0);
            fire(executors, listener, new PluginDisableEvent(mock(Plugin.class)));
            assertSame(first, SourbyAPI.worlds());
            assertEquals(1, loads.get());
            assertTrue((boolean) boundField.get(null));
            fire(executors, listener, new PluginDisableEvent(owner));
            assertFalse((boolean) boundField.get(null), "the owner's disable did not unbind");
            loads.set(0);
            SourbyAPI.worlds();
            SourbyAPI.worlds();
            assertEquals(2, loads.get(), "an unbound lookup was cached");
        } finally {
            unbind();
            sourbyField.set(null, previousSourby);
            serverField.set(null, previousServer);
        }
    }

    private static void fire(final Map<Class<?>, EventExecutor> executors, final Listener listener, final Event event) throws Exception {
        executors.get(event.getClass()).execute(listener, event);
    }
}
