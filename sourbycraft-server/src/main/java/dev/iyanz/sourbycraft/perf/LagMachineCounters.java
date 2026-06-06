package dev.iyanz.sourbycraft.perf;

/**
 * Per-tick projectile-chunk-load counter for the P2 lag-machine throttle.
 * Reset at tick start by a MinecraftServer.tickChildren hook. Main-thread access only —
 * no synchronization needed.
 */
public final class LagMachineCounters {

    private static int projectileChunkLoadsThisTick = 0;

    private LagMachineCounters() {}

    public static void resetTickCounters() {
        projectileChunkLoadsThisTick = 0;
    }

    public static int projectileChunkLoadsThisTick() {
        return projectileChunkLoadsThisTick;
    }

    public static void incrementProjectileChunkLoad() {
        projectileChunkLoadsThisTick++;
    }
}
