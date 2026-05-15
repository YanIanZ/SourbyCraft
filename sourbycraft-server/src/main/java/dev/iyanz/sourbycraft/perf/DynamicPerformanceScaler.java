package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.minecraft.server.MinecraftServer;

public final class DynamicPerformanceScaler {

    private static boolean enabled = true;
    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL = 100;
    private static final double TPS_CRITICAL = 15.0;
    private static final double TPS_WARNING = 18.0;
    private static final int RATE_MAX = 8;
    private static final int RATE_MIN = 1;

    public static void tick(MinecraftServer server) {
        if (!enabled || !SourbyCraftConfig.entityTickRateLimit) return;
        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        double tps = getAverageTPS(server);
        int rate = SourbyCraftConfig.entityTickRate;

        if (tps < TPS_CRITICAL) {
            rate = Math.min(RATE_MAX, rate * 2);
        } else if (tps < TPS_WARNING) {
            rate = Math.min(RATE_MAX, rate + 1);
        } else if (tps >= 19.8) {
            rate = Math.max(RATE_MIN, rate - 1);
        }

        SourbyCraftConfig.entityTickRate = rate;
    }

    private static double getAverageTPS(MinecraftServer server) {
        long nanos = server.getAverageTickTimeNanos();
        return nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
    }

    public static void setEnabled(boolean e) { enabled = e; }
    public static boolean isEnabled() { return enabled; }
}
