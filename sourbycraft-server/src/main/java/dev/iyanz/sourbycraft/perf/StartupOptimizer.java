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
            server.LOGGER.info("  Entity Tick Rate: 1/{} (limiter ON)", SourbyCraftConfig.entityTickRate());
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
        if (SourbyCraftConfig.entityTickRate() <= 1 && !DynamicPerformanceScaler.isEnabled()) {
            server.LOGGER.info("  Hint: enable /perf scale on for auto TPS management");
        }

        // SourbyCraft v10.3 — JVM recommendations (no shell script needed)
        printJvmRecommendations(server, heapGB, cores);

        server.LOGGER.info("---------------------------------");
    }

    private static void printJvmRecommendations(MinecraftServer server, int heapGB, int cores) {
        java.util.List<String> args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean hasZGC = args.stream().anyMatch(a -> a.contains("UseZGC"));
        boolean hasG1 = args.stream().anyMatch(a -> a.contains("UseG1GC"));
        boolean hasShenandoah = args.stream().anyMatch(a -> a.contains("UseShenandoahGC"));
        boolean hasGenerational = args.stream().anyMatch(a -> a.contains("ZGenerational"));
        boolean hasPreTouch = args.stream().anyMatch(a -> a.contains("AlwaysPreTouch"));
        boolean hasHugePages = args.stream().anyMatch(a -> a.contains("UseTransparentHugePages"));

        int jvmMajor = parseJavaMajor(System.getProperty("java.version"));
        String currentGC = hasZGC ? (hasGenerational ? "ZGC generational" : "ZGC") :
            hasG1 ? "G1" : hasShenandoah ? "Shenandoah" : "default";

        server.LOGGER.info("  JVM args: GC={} preTouch={} hugePages={}",
            currentGC, hasPreTouch ? "on" : "off", hasHugePages ? "on" : "off");

        // Recommendations
        if (heapGB >= 8 && jvmMajor >= 21 && !hasZGC) {
            server.LOGGER.warn("  ! Recommend ZGC generational on JDK 21+ with {}GB heap:", heapGB);
            server.LOGGER.warn("    -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages");
        } else if (heapGB < 8 && !hasG1 && !hasZGC) {
            server.LOGGER.warn("  ! Recommend G1 on small heap ({}GB):", heapGB);
            server.LOGGER.warn("    -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=8M -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages");
        } else if (hasZGC && jvmMajor >= 21 && !hasGenerational) {
            server.LOGGER.warn("  ! Recommend adding -XX:+ZGenerational on JDK 21+");
        }
        if (!hasPreTouch) {
            server.LOGGER.info("  Hint: add -XX:+AlwaysPreTouch for consistent latency");
        }
    }

    private static int parseJavaMajor(String v) {
        if (v == null) return 0;
        try {
            String first = v.split("\\.")[0];
            int n = Integer.parseInt(first);
            return n == 1 ? Integer.parseInt(v.split("\\.")[1]) : n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
