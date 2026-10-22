package dev.iyanz.sourbycraft.awf;

import java.util.List;
import java.util.function.BiConsumer;

/** Builds {@link AwfMaintenanceDriver}s with custom steps for tests outside this package. */
public final class AwfMaintenanceDriverTesting {

    private AwfMaintenanceDriverTesting() {}

    /** A driver on the real daemon-thread scheduler, running {@code step} every {@code periodNanos}. Not started. */
    public static AwfMaintenanceDriver onDaemonThread(final long periodNanos, final Runnable step,
                                                      final BiConsumer<String, Throwable> errorLogger) {
        return new AwfMaintenanceDriver(List.of(new AwfMaintenanceDriver.Step("test step", step)),
            AwfMaintenanceDriver.daemonThread(), periodNanos, System::nanoTime, errorLogger);
    }
}
