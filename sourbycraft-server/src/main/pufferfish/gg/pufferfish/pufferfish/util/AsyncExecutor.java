package gg.pufferfish.pufferfish.util;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

@Deprecated
public class AsyncExecutor {

    @Deprecated
    public AsyncExecutor(String threadName) {}

    @Deprecated public void start() {}
    @Deprecated public void kill() {}

    public void submit(Runnable task) {
        VirtualExecutor.run(task);
    }

    @Deprecated
    public static void initPool(int threadCount) {
        VirtualExecutor.init();
    }

    @Deprecated
    public static void shutdownPool() {
        VirtualExecutor.shutdown();
    }

    public static void submitToPool(Runnable task) {
        VirtualExecutor.run(task);
    }
}
