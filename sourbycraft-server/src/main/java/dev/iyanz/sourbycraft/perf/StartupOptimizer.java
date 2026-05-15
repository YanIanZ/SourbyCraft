package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Prints a performance summary at server startup with optimization hints.
 */
public final class StartupOptimizer {

    public static void print(MinecraftServer server) {
        server.LOGGER.info("--- SourbyCraft Performance ---");

        int cores = Runtime.getRuntime().availableProcessors();
        long maxMem = Runtime.getRuntime().maxMemory();
        int heapGB = (int)(maxMem / 1024 / 1024 / 1024);

        server.LOGGER.info("  CPU: {} cores | Heap: {}GB | JDK: {}",
            cores, heapGB, System.getProperty("java.version"));

        // Config review
        if (SourbyCraftConfig.entityTickRateLimit) {
            server.LOGGER.info("  Entity Tick Rate: 1/{} (limiter ON)", SourbyCraftConfig.entityTickRate);
        }
        if (SourbyCraftConfig.mobTickDistance > 0) {
            server.LOGGER.info("  Mob AI Distance: {} blocks", SourbyCraftConfig.mobTickDistance);
        }
        if (SourbyCraftConfig.maxEntityPerChunk > 0) {
            server.LOGGER.info("  Entity/Chunk Cap: {}", SourbyCraftConfig.maxEntityPerChunk);
        }
        if (SourbyCraftConfig.poolEntityData) {
            server.LOGGER.info("  Entity Data Pool: ON");
        }
        if (SourbyCraftConfig.fluidObscures) {
            server.LOGGER.info("  AntiXray Fluid Obscure: ON");
        }

        // Hints
        if (heapGB < 4) {
            server.LOGGER.warn("  ! Heap < 4GB — consider more RAM for better performance");
        }
        if (cores < 4) {
            server.LOGGER.warn("  ! < 4 CPU cores — performance may degrade with many players");
        }
        if (SourbyCraftConfig.entityTickRate <= 1 && !DynamicPerformanceScaler.isEnabled()) {
            server.LOGGER.info("  Hint: enable /perf scale on for auto TPS management");
        }

        server.LOGGER.info("---------------------------------");
    }
}
