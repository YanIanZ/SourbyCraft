package dev.iyanz.sourbycraft.optimizer;

import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Application-level memory manager for SourbyCraft.
 * Reduces GC pressure via object pooling, soft-reference caching,
 * and pre-allocation of hot-path arrays.
 */
public final class MemoryOptimizer {

    private static final int MAX_POOL_SIZE = 256;
    private static final Map<Class<?>, Deque<Object>> OBJECT_POOLS = new ConcurrentHashMap<>();
    private static final Map<String, SoftReference<Object>> SOFT_CACHE = new ConcurrentHashMap<>();

    // --- Object Pool ---

    @SuppressWarnings("unchecked")
    public static <T> T borrow(Class<T> type, Supplier<T> factory) {
        Deque<Object> pool = OBJECT_POOLS.computeIfAbsent(type, k -> new ArrayDeque<>());
        T obj = (T) pool.pollFirst();
        return obj != null ? obj : factory.get();
    }

    @SuppressWarnings("unchecked")
    public static <T> void recycle(Class<T> type, T obj) {
        if (obj == null) return;
        Deque<Object> pool = OBJECT_POOLS.computeIfAbsent(type, k -> new ArrayDeque<>());
        if (pool.size() < MAX_POOL_SIZE) {
            pool.offerLast(obj);
        }
    }

    // --- Soft Reference Cache ---

    @SuppressWarnings("unchecked")
    public static <T> T cacheGet(String key, Supplier<T> factory) {
        SoftReference<Object> ref = SOFT_CACHE.get(key);
        Object val = ref != null ? ref.get() : null;
        if (val != null) return (T) val;

        val = factory.get();
        if (val != null) {
            SOFT_CACHE.put(key, new SoftReference<>(val));
        }
        return (T) val;
    }

    public static void cacheInvalidate(String keyPrefix) {
        Iterator<Map.Entry<String, SoftReference<Object>>> it = SOFT_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(keyPrefix)) {
                it.remove();
            }
        }
    }

    // --- Array Pre-allocation ---

    public static int[] preallocatedInts(int size) {
        return new int[size];
    }

    public static long[] preallocatedLongs(int size) {
        return new long[size];
    }

    // --- Stats ---

    public static int poolSize(Class<?> type) {
        Deque<Object> pool = OBJECT_POOLS.get(type);
        return pool != null ? pool.size() : 0;
    }

    public static int cacheSize() {
        return SOFT_CACHE.size();
    }

    public static String stats() {
        return "MemoryOptimizer: pools=" + OBJECT_POOLS.size() + " cache=" + SOFT_CACHE.size() + " items, " +
            "pool items=" + OBJECT_POOLS.values().stream().mapToInt(Deque::size).sum();
    }
}
