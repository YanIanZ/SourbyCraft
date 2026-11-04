package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.Objects;
import dev.iyanz.sourbycraft.execution.LanePortions;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Owns the canonical provider registration and collector lifecycle. */
public final class MetricsRuntime {

    private static final SourbyMetricsProvider PROVIDER = new SourbyMetricsProvider();
    // Volatile so a reader does not need the lifecycle lock. close() joins the collector
    // thread while holding that lock, and a command asking where CPU went should not wait
    // behind a shutdown to be told "metrics are not running".
    private static volatile PerformanceCollector collector;
    private static boolean running;
    private static ServicesManager registeredWith;
    private static boolean cleanupNeeded;
    /**
     * Aurora World Fabric's periodic commits, on their own thread (audit AWF-3). Started and
     * stopped with metrics so shutdown keeps its order (stopped before the storage lane shuts
     * down and long before {@code AwfEngine.closeAll()}'s final commit), but independent of the
     * collector: a metrics startup failure leaves it running, and a stalled collection cannot
     * delay it. Written under the class lock; volatile so the collector thread reads it without
     * that lock ({@link #watchAwfMaintenance()}).
     */
    private static volatile dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver awfMaintenance;
    /** Whether a stall of {@link #awfMaintenance} is being reported; read and written on the collector thread. */
    private static volatile boolean awfMaintenanceStalled;
    private static java.util.function.Supplier<dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver> awfMaintenanceFactory =
        dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver::standard;

    private MetricsRuntime() {}

    public static SourbyMetrics provider() {
        return PROVIDER;
    }

    /**
     * How the machine is currently divided between execution lanes.
     *
     * <p>Kept off {@link dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot} on purpose: this is
     * operator diagnostics, not something plugins should build on yet.</p>
     */
    public static LanePortions.Report lanePortions() {
        final PerformanceCollector current = collector;
        return current != null ? current.lanePortions()
            : LanePortions.notMeasured("metrics are not running");
    }

    /** The slowest active region at the last collection, or {@code null} when none or not running. */
    public static SlowRegion slowestRegion() {
        final PerformanceCollector current = collector;
        return current == null ? null : current.slowestRegion();
    }

    public static RegionMetricsRegistry registry() {
        return RegionMetricsRegistry.INSTANCE;
    }

    public static synchronized void start(final ServicesManager services, final Plugin owner) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(owner, "owner");
        startAwfMaintenance();
        if (running) {
            return;
        }
        cleanupBeforeStart();

        PROVIDER.publish(ImmutablePerformanceSnapshot.warming());
        final PerformanceCollector candidate = new PerformanceCollector(
            PROVIDER, RegionMetricsRegistry.INSTANCE, () -> {
                // Once per collection, on the collector's thread: the other thread that runs every
                // second, so it can tell when the AWF maintenance thread has stopped.
                watchAwfMaintenance();
                return PerformanceCollector.runtimeSnapshot();
            });
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
        stopAwfMaintenance();
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

    /** Starts the AWF maintenance driver unless it runs; a failure is logged, never thrown. */
    private static void startAwfMaintenance() {
        if (awfMaintenance != null) {
            return;
        }
        dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver driver = null;
        try {
            driver = awfMaintenanceFactory.get();
            driver.start();
            awfMaintenance = driver;
        } catch (final Throwable failure) {
            if (driver != null) {
                try {
                    driver.close();
                } catch (final Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            SourbyLogger.error("Aurora World Fabric maintenance could not start; periodic AWF commits will not"
                + " run (the final commit at shutdown still does)", failure);
        }
    }

    private static void stopAwfMaintenance() {
        final dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver stopping = awfMaintenance;
        awfMaintenance = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.close();
        } catch (final Throwable failure) {
            SourbyLogger.error("Aurora World Fabric maintenance did not stop cleanly", failure);
        }
    }

    /**
     * WARNs once when the running AWF maintenance driver has not finished a pass for
     * {@link dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver#STALL_NANOS}: periodic AWF commits are not
     * being started, so what a crash would lose grows past the commit interval. INFOs once when it
     * passes again. Called by the collector each second; never takes the class lock, which
     * {@link #close} holds while it joins the collector thread.
     */
    static void watchAwfMaintenance() {
        final dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver driver = awfMaintenance;
        final long idle = driver == null ? 0L : driver.nanosSinceLastPass();
        if (idle >= dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver.STALL_NANOS) {
            if (!awfMaintenanceStalled) {
                awfMaintenanceStalled = true;
                SourbyLogger.warn("Aurora World Fabric maintenance (" + dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver.THREAD_NAME
                    + ") has not finished a pass for " + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(idle)
                    + " s: periodic AWF commits are not being started, so writes since the last commit stay only in"
                    + " memory (the final commit at shutdown does not depend on this thread)");
            }
        } else if (awfMaintenanceStalled) {
            awfMaintenanceStalled = false;
            SourbyLogger.info("Aurora World Fabric maintenance is finishing its passes again; periodic AWF commits resumed");
        }
    }

    /** The running AWF maintenance driver, or {@code null}. */
    static synchronized dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver awfMaintenanceForTesting() {
        return awfMaintenance;
    }

    /** Replaces how the driver is made; returns the previous factory. Tests only. */
    static synchronized java.util.function.Supplier<dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver>
            awfMaintenanceFactoryForTesting(
                final java.util.function.Supplier<dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver> factory) {
        final var previous = awfMaintenanceFactory;
        awfMaintenanceFactory = Objects.requireNonNull(factory, "factory");
        return previous;
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
