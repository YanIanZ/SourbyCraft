package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

/**
 * SWM I/O executor — delegates to VirtualExecutor (Java 25 virtual threads).
 */
public final class SwmIoExecutor {

    public SwmIoExecutor() {
        VirtualExecutor.init();
    }

    public java.util.concurrent.ExecutorService pool() {
        return VirtualExecutor.executor();
    }

    public void shutdown() {
        VirtualExecutor.shutdown();
    }
}
