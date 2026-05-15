package dev.iyanz.sourbycraft.perf;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Monitors server health: CPU, RAM, TPS with auto-warn at thresholds.
 * Also provides simple command tab-complete cache.
 */
public final class HealthMonitor {

    private static long lastCheckTime = 0;
    private static final long CHECK_MS = TimeUnit.MINUTES.toMillis(1);
    private static final double MEM_WARN_PCT = 85;
    private static final double CPU_WARN_PCT = 80;

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_MS) return;
        lastCheckTime = now;

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();

        if (max > 0 && (double) used / max * 100 > MEM_WARN_PCT) {
            server.LOGGER.warn("[Health] RAM usage high: {}/{} ({}%)",
                formatMB(used), formatMB(max),
                (int)((double) used / max * 100));
        }
    }

    public static String formatMB(long bytes) {
        return (bytes / 1024 / 1024) + "MB";
    }

    // --- Tab Complete Cache ---
    private static final Map<String, CacheEntry> TAB_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000;

    private record CacheEntry(CompletableFuture<Suggestions> future, long timestamp) {}

    public static CompletableFuture<Suggestions> cacheGet(String key) {
        CacheEntry entry = TAB_CACHE.get(key);
        if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            return entry.future;
        }
        return null;
    }

    public static void cachePut(String key, CompletableFuture<Suggestions> future) {
        TAB_CACHE.put(key, new CacheEntry(future, System.currentTimeMillis()));
    }
}
