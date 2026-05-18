package dev.iyanz.sourbycraft.async;

import net.minecraft.server.level.ServerLevel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DimensionThreadManager {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft-DimThread");
    private static final Map<ServerLevel, ExecutorService> dimensionExecutors = new ConcurrentHashMap<>();

    public static void startDimension(ServerLevel level) {
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.multithreadingEnabled) return;
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.virtualThreads) return;

        String name = "Dimension-Worker-" + level.dimension().identifier().getPath();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        dimensionExecutors.put(level, executor);
        LOGGER.log(Level.INFO, "Started dimension thread: " + name);
    }

    public static void stopDimension(ServerLevel level) {
        ExecutorService executor = dimensionExecutors.remove(level);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void stopAll() {
        for (ServerLevel level : dimensionExecutors.keySet()) {
            stopDimension(level);
        }
    }

    public static boolean isDimensionThreaded(ServerLevel level) {
        return dimensionExecutors.containsKey(level);
    }

    public static void tickDimension(ServerLevel level, Runnable tickTask) {
        ExecutorService executor = dimensionExecutors.get(level);
        if (executor != null && !executor.isShutdown()) {
            executor.execute(tickTask);
        } else {
            tickTask.run();
        }
    }
}
