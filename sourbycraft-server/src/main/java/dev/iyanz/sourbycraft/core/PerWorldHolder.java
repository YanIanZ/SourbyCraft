package dev.iyanz.sourbycraft.core;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Name-keyed per-world cache with centralized WorldUnloadEvent eviction —
 * world resets can reuse world names, so every per-world map MUST evict on
 * unload. One shared listener serves all holders.
 *
 * <p><b>Folia note (F2c port).</b> This is a pure Bukkit-API helper and is
 * Folia-safe as-is: {@code WorldUnloadEvent} still fires on Folia, and the
 * backing map is a {@link ConcurrentHashMap} so region-thread reads/writes are
 * safe. The one shared listener is registered under the internal Minecraft
 * plugin handle by the perf-engine bootstrap.
 */
public final class PerWorldHolder<T> {

    private static final List<PerWorldHolder<?>> ALL = new CopyOnWriteArrayList<>();
    private static volatile boolean listenerRegistered;

    private final ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();

    public PerWorldHolder() {
        ALL.add(this);
    }

    /** Idempotent; call once from plugin enable. Holders created before registration still evict. */
    public static void registerCleanup(Plugin plugin) {
        if (listenerRegistered) return;
        listenerRegistered = true;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorldUnload(WorldUnloadEvent e) {
                String name = e.getWorld().getName();
                for (PerWorldHolder<?> holder : ALL) holder.map.remove(name);
            }
        }, plugin);
    }

    public T computeIfAbsent(String worldName, Function<String, T> factory) {
        // Lock-free fast path: CHM.computeIfAbsent takes the bin lock even for reads on any bin
        // collision, and this sits on per-pair-per-tick antixray paths across region threads.
        T v = map.get(worldName);
        return v != null ? v : map.computeIfAbsent(worldName, factory);
    }
    public T get(String worldName) { return map.get(worldName); }
    public void put(String worldName, T value) { map.put(worldName, value); }
    public void remove(String worldName) { map.remove(worldName); }
    public boolean isEmpty() { return map.isEmpty(); }
}
