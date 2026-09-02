package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRuntimeTest {

    private final FakeServices services = new FakeServices();

    @AfterEach
    void closeRuntime() {
        MetricsRuntime.close(this.services);
    }

    @Test
    void startRegistersCanonicalProviderExactlyOnceAtNormalPriority() {
        MetricsRuntime.start(this.services, plugin());
        MetricsRuntime.start(this.services, plugin());

        assertEquals(1, this.services.registrations);
        assertSame(MetricsRuntime.provider(), this.services.provider);
        assertEquals(ServicePriority.Normal, this.services.priority);
        assertSame(RegionMetricsRegistry.INSTANCE, MetricsRuntime.registry());
    }

    @Test
    void partialRegistrationFailureRollsBackExactProviderAndCanRetry() {
        this.services.failAfterRegister = true;

        assertThrows(IllegalStateException.class, () -> MetricsRuntime.start(this.services, plugin()));
        assertEquals(1, this.services.unregistrations);
        assertSame(MetricsRuntime.provider(), this.services.unregistered);

        this.services.failAfterRegister = false;
        MetricsRuntime.start(this.services, plugin());
        assertEquals(2, this.services.registrations);
        assertEquals(MetricState.WARMING, MetricsRuntime.provider().snapshot().freshness().state());
    }

    @Test
    void closeIsIdempotentAndUnregistersOnlyTheCanonicalInstance() {
        MetricsRuntime.start(this.services, plugin());

        MetricsRuntime.close(this.services);
        MetricsRuntime.close(this.services);

        assertEquals(1, this.services.unregistrations);
        assertSame(MetricsRuntime.provider(), this.services.unregistered);
    }

    @Test
    void failedRollbackMustBeCleanedBeforeRetryCanRegisterAgain() {
        this.services.failAfterRegister = true;
        this.services.failUnregister = true;
        assertThrows(IllegalStateException.class, () -> MetricsRuntime.start(this.services, plugin()));

        this.services.failAfterRegister = false;
        assertThrows(IllegalStateException.class, () -> MetricsRuntime.start(this.services, plugin()));
        assertEquals(1, this.services.registrations);

        this.services.failUnregister = false;
        MetricsRuntime.start(this.services, plugin());

        assertEquals(List.of("register", "unregister", "unregister", "unregister", "register"),
            this.services.events);
        assertEquals(2, this.services.registrations);
    }

    @Test
    void closeStopsCollectorAndAllowsUnregisterCleanupRetryWithoutThrowing() {
        MetricsRuntime.start(this.services, plugin());
        this.services.failUnregister = true;

        assertDoesNotThrow(() -> MetricsRuntime.close(this.services));
        assertTrue(this.services.collectorStoppedAtUnregister);

        this.services.failUnregister = false;
        assertDoesNotThrow(() -> MetricsRuntime.close(this.services));
        assertEquals(2, this.services.unregistrations);
    }

    private static Plugin plugin() {
        return (Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "metrics-test";
                case "isEnabled" -> true;
                case "toString" -> "metrics-test";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte)0;
        if (type == short.class) return (short)0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        return 0.0D;
    }

    private static final class FakeServices implements ServicesManager {
        private int registrations;
        private int unregistrations;
        private boolean failAfterRegister;
        private boolean failUnregister;
        private boolean collectorStoppedAtUnregister;
        private Object provider;
        private Object unregistered;
        private ServicePriority priority;
        private final List<String> events = new ArrayList<>();

        @Override
        public <T> void register(final Class<T> service, final T provider, final Plugin plugin,
                                 final ServicePriority priority) {
            ++this.registrations;
            this.events.add("register");
            this.provider = provider;
            this.priority = priority;
            if (this.failAfterRegister) throw new IllegalStateException("registration failed");
        }

        @Override
        public void unregisterAll(final Plugin plugin) {}

        @Override
        public void unregister(final Class<?> service, final Object provider) {
            ++this.unregistrations;
            this.events.add("unregister");
            this.unregistered = provider;
            this.collectorStoppedAtUnregister = Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("SourbyCraft-PerformanceCollector"));
            if (this.failUnregister) throw new IllegalStateException("unregister failed");
        }

        @Override
        public void unregister(final Object provider) {
            throw new AssertionError("runtime must unregister by service and exact provider");
        }

        @Override
        public <T> T load(final Class<T> service) {
            return service.cast(this.provider);
        }

        @Override
        public <T> RegisteredServiceProvider<T> getRegistration(final Class<T> service) {
            return null;
        }

        @Override
        public List<RegisteredServiceProvider<?>> getRegistrations(final Plugin plugin) {
            return new ArrayList<>();
        }

        @Override
        public <T> Collection<RegisteredServiceProvider<T>> getRegistrations(final Class<T> service) {
            return List.of();
        }

        @Override
        public Collection<Class<?>> getKnownServices() {
            return List.of(SourbyMetrics.class);
        }

        @Override
        public <T> boolean isProvidedFor(final Class<T> service) {
            return this.provider != null;
        }
    }
}
