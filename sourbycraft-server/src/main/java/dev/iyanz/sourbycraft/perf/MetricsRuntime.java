package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Owns the canonical provider registration and collector lifecycle. */
public final class MetricsRuntime {

    private static final SourbyMetricsProvider PROVIDER = new SourbyMetricsProvider();
    private static PerformanceCollector collector;
    private static boolean running;
    private static ServicesManager registeredWith;
    private static boolean cleanupNeeded;

    private MetricsRuntime() {}

    public static SourbyMetrics provider() {
        return PROVIDER;
    }

    /** Exposes the separately collected global-region window to built-in server consumers only. */
    public static WindowMetrics globalWindow(final PerformanceSnapshot snapshot, final MetricWindow window) {
        return snapshot instanceof ImmutablePerformanceSnapshot internal
            ? internal.global().window(window) : ImmutableWindowMetrics.EMPTY;
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
        cleanupBeforeStart();

        PROVIDER.publish(ImmutablePerformanceSnapshot.warming());
        final PerformanceCollector candidate = new PerformanceCollector(
            PROVIDER, RegionMetricsRegistry.INSTANCE, PerformanceCollector::runtimeSnapshot);
        try {
            registeredWith = services;
            cleanupNeeded = true;
            services.register(SourbyMetrics.class, PROVIDER, owner, ServicePriority.Normal);
            candidate.start();
            collector = candidate;
            running = true;
        } catch (final Throwable failure) {
            candidate.close();
            try {
                cleanupRegistration();
            } catch (final Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
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
        running = false;
        final PerformanceCollector closing = collector;
        collector = null;
        if (closing != null) {
            closing.close();
        }
        if (cleanupNeeded && registeredWith == null) {
            registeredWith = services;
        }
        try {
            cleanupRegistration();
        } catch (final Throwable failure) {
            SourbyLogger.error("Metrics provider unregister failed; cleanup will be retried", failure);
        }
    }

    private static void cleanupBeforeStart() {
        if (!cleanupNeeded) {
            return;
        }
        try {
            cleanupRegistration();
        } catch (final Throwable failure) {
            throw new IllegalStateException("Previous metrics provider registration cleanup failed", failure);
        }
    }

    private static void cleanupRegistration() {
        if (!cleanupNeeded) {
            return;
        }
        final ServicesManager services = Objects.requireNonNull(registeredWith, "registeredWith");
        services.unregister(SourbyMetrics.class, PROVIDER);
        cleanupNeeded = false;
        registeredWith = null;
    }
}
