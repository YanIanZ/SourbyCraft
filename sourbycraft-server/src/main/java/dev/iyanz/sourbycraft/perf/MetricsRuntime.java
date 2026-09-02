package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Owns the canonical provider registration and collector lifecycle. */
public final class MetricsRuntime {

    private static final SourbyMetricsProvider PROVIDER = new SourbyMetricsProvider();
    private static PerformanceCollector collector;
    private static boolean running;

    private MetricsRuntime() {}

    public static SourbyMetrics provider() {
        return PROVIDER;
    }

    public static RegionMetricsRegistry registry() {
        return RegionMetricsRegistry.INSTANCE;
    }

    public static synchronized void start(final ServicesManager services, final Plugin owner) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(owner, "owner");
        if (running) {
            return;
        }

        PROVIDER.publish(ImmutablePerformanceSnapshot.warming());
        final PerformanceCollector candidate = new PerformanceCollector(
            PROVIDER, RegionMetricsRegistry.INSTANCE, PerformanceCollector::runtimeSnapshot);
        boolean registrationAttempted = false;
        try {
            registrationAttempted = true;
            services.register(SourbyMetrics.class, PROVIDER, owner, ServicePriority.Normal);
            candidate.start();
            collector = candidate;
            running = true;
        } catch (final Throwable failure) {
            candidate.close();
            if (registrationAttempted) {
                try {
                    services.unregister(SourbyMetrics.class, PROVIDER);
                } catch (final Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            PROVIDER.publish(ImmutablePerformanceSnapshot.unavailable("Metrics startup failed: "
                + failure.getClass().getSimpleName()));
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Metrics startup failed", failure);
        }
    }

    public static synchronized void close(final ServicesManager services) {
        Objects.requireNonNull(services, "services");
        if (!running) {
            return;
        }
        running = false;
        final PerformanceCollector closing = collector;
        collector = null;
        if (closing != null) {
            closing.close();
        }
        services.unregister(SourbyMetrics.class, PROVIDER);
    }
}
