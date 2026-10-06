package dev.yanianz.intave.library.pledge;

import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native tick callbacks must have an explicit player owner, never a reflected global tickable. */
public final class TickEnd {
    private static final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Runnable>> subscribers = new ConcurrentHashMap<>();
    private TickEnd() {}
    public static void start() { }
    public static void subscribe(Runnable task) {
        throw new UnsupportedOperationException("Use TickEnd.subscribe(player, task) with an explicit region owner");
    }
    public static void unsubscribe(Runnable task) { subscribers.values().forEach(tasks -> tasks.remove(task)); }
    public static void subscribe(Player player, Runnable task) {
        subscribers.computeIfAbsent(player.getUniqueId(), ignored -> new CopyOnWriteArrayList<>()).addIfAbsent(task);
    }
    public static void ownerTick(Player player) {
        var tasks = subscribers.get(player.getUniqueId());
        if (tasks != null) tasks.forEach(Runnable::run);
    }
    public static void forget(Player player) { subscribers.remove(player.getUniqueId()); }
    public static void clear() { subscribers.clear(); }
}
