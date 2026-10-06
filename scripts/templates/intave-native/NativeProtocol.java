package dev.yanianz.intave.integration;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.ProtocolConfig;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.error.BasicErrorReporter;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.scheduler.ProtocolScheduler;
import com.comphenix.protocol.scheduler.Task;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.collect.ImmutableSet;
import dev.yanianz.intave.NativeIntave;
import io.netty.channel.*;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** ProtocolLib packet access API is embedded as a library; there is no ProtocolLib plugin/manager. */
public final class NativeProtocol extends ProtocolLibrary implements InvocationHandler, AutoCloseable {
    private static final Key KEY = Key.key("sourbycraft", "intave-native");
    private static final String HANDLER = "sourbycraft_intave_native";
    private static volatile NativeProtocol instance;
    private final CopyOnWriteArrayList<PacketListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<PacketType, java.util.Collection<? extends PacketListener>> internal = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, Boolean> channels = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);
    private final NativeService owner;
    private volatile boolean closed;
    private volatile boolean accepting = true;

    public NativeProtocol(NativeService owner) {
        if (ProtocolLibrary.getProtocolManager() != null) {
            throw new IllegalStateException("An external ProtocolLib manager is already active; native isolation unavailable");
        }
        this.owner = owner;
        ProtocolManager manager = (ProtocolManager) Proxy.newProxyInstance(ProtocolManager.class.getClassLoader(),
            new Class<?>[]{ProtocolManager.class}, this);
        // Keep library settings in memory, separate from the engine's operator configuration.
        var settings = new org.bukkit.configuration.file.YamlConfiguration();
        settings.set("global.auto updater.notify", false);
        settings.set("global.auto updater.download", false);
        settings.set("global.metrics", false);
        NativeService configOwner = new NativeService() {
            @Override public org.bukkit.configuration.file.FileConfiguration getConfig() { return settings; }
            @Override public java.io.File getDataFolder() { return owner.getDataFolder(); }
            @Override public void reloadConfig() {}
            @Override public void saveConfig() {}
        };
        init(owner, new ProtocolConfig(configOwner), manager, new Scheduler(owner), new BasicErrorReporter());
        disableUpdates();
        instance = this;
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(KEY, this::attach);
        var connections = MinecraftServer.getServer().getConnection().getConnections();
        synchronized (connections) {
            for (Connection connection : connections) {
                Channel channel = connection.channel;
                if (channel != null && channel.isActive()) channel.eventLoop().execute(() -> attach(channel));
            }
        }
    }

    public static NativeProtocol current() {
        NativeProtocol current = instance;
        if (current == null || current.closed) throw new IllegalStateException("Native packet adapter is not available");
        return current;
    }
    public boolean hasListeners() { return !listeners.isEmpty(); }
    public void internalSubscriptions(PacketType type, java.util.Collection<? extends PacketListener> subscriptions) {
        internal.put(type, subscriptions);
    }
    public void clearInternalSubscriptions() { internal.clear(); }
    private void attach(Channel channel) {
        if (closed || !accepting || channel.pipeline().get(HANDLER) != null) return;
        if (channel.pipeline().get("packet_handler") == null) {
            NativeIntave.packetFailure(new IllegalStateException("Native packet handler cannot locate Connection in channel pipeline"));
            return;
        }
        channels.put(channel, Boolean.TRUE);
        channel.closeFuture().addListener(ignored -> channels.remove(channel));
        channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
            private Player player(ChannelHandlerContext ctx) {
                Object handler = ctx.pipeline().get("packet_handler");
                if (handler instanceof Connection connection && connection.getPacketListener() instanceof ServerGamePacketListenerImpl game) {
                    return game.player.getBukkitEntity();
                }
                return null;
            }
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                Object result = process(player(ctx), message, false);
                if (result != null) ctx.fireChannelRead(result);
            }
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                Object result = process(player(ctx), message, true);
                if (result != null) ctx.write(result, promise);
                else promise.trySuccess();
            }
        });
    }

    private Object process(Player player, Object message, boolean outbound) {
        if (closed || !accepting || player == null || !(message instanceof Packet<?>) || !NativeIntave.status().active()) return message;
        try {
            PacketContainer container = PacketContainer.fromPacket(message);
            PacketEvent event = outbound ? PacketEvent.fromServer(this, container, player) : PacketEvent.fromClient(this, container, player);
            // TinyProtocol's INTERNAL path was outbound-only and preceded ProtocolLib callbacks.
            if (outbound) {
                var subscriptions = internal.get(container.getType());
                if (subscriptions != null) subscriptions.forEach(listener -> listener.onPacketSending(event));
                if (event.isCancelled()) return null;
            }
            if (bypass.get() || !NativeIntave.packetFiltersEnabled()) return event.getPacket().getHandle();
            List<PacketListener> ordered = listeners.stream().filter(listener -> {
                ListeningWhitelist whitelist = outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
                return whitelist.isEnabled() && whitelist.getTypes().contains(container.getType());
            }).sorted(Comparator.comparingInt(listener -> (outbound ? listener.getSendingWhitelist()
                : listener.getReceivingWhitelist()).getPriority().getSlot())).toList();
            for (PacketListener listener : ordered) {
                if (outbound) listener.onPacketSending(event); else listener.onPacketReceiving(event);
            }
            return event.isCancelled() ? null : event.getPacket().getHandle();
        } catch (RuntimeException | LinkageError failure) {
            NativeIntave.packetFailure(failure);
            return message;
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
        Object[] args = supplied == null ? new Object[0] : supplied;
        switch (method.getName()) {
            case "toString": return "SourbyCraft native packet adapter";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            case "isClosed": return closed;
            case "getMinecraftVersion": return MinecraftVersion.getCurrentVersion();
            case "getProtocolVersion": return ((CraftPlayer) args[0]).getHandle().connection.connection.protocolVersion;
            case "getPacketListeners": return ImmutableSet.copyOf(listeners);
            case "addPacketListener": listeners.addIfAbsent((PacketListener) args[0]); return null;
            case "removePacketListener": listeners.remove(args[0]); return null;
            case "removePacketListeners": listeners.removeIf(listener -> listener.getPlugin() == args[0]); return null;
            case "createPacket": {
                PacketContainer packet = new PacketContainer((PacketType) args[0]);
                if (args.length == 1 || Boolean.TRUE.equals(args[1])) packet.getModifier().writeDefaults();
                return packet;
            }
            case "verifyWhitelist": return null;
            case "getSendingFilterTypes": return listeners.stream().flatMap(l -> l.getSendingWhitelist().getTypes().stream()).collect(java.util.stream.Collectors.toSet());
            case "getReceivingFilterTypes": return listeners.stream().flatMap(l -> l.getReceivingWhitelist().getTypes().stream()).collect(java.util.stream.Collectors.toSet());
            case "sendServerPacket":
            case "receiveClientPacket":
            case "recieveClientPacket": {
                if (closed || !accepting || !NativeIntave.status().active()) return null;
                CraftPlayer player = (CraftPlayer) args[0];
                Object packet = ((PacketContainer) args[1]).getHandle();
                boolean filtered = args.length < 3 || !(args[args.length - 1] instanceof Boolean) || (Boolean) args[args.length - 1];
                Channel channel = player.getHandle().connection.connection.channel;
                if (method.getName().equals("sendServerPacket")) {
                    player.getHandle().connection.connection.sendNative((Packet<?>) packet, filtered);
                    return null;
                }
                Runnable deliver = () -> {
                    boolean previous = bypass.get();
                    bypass.set(!filtered);
                    try {
                        if (closed || !channel.isActive()) return;
                        ChannelHandlerContext context = channel.pipeline().context(HANDLER);
                        if (context == null) throw new IllegalStateException("Native incoming packet handler is absent");
                        Object result = process(player, packet, false);
                        if (result != null) context.fireChannelRead(result);
                    } finally { bypass.set(previous); }
                };
                if (channel.eventLoop().inEventLoop()) deliver.run(); else channel.eventLoop().execute(deliver);
                return null;
            }
            default: throw new UnsupportedOperationException("Native packet API does not implement " + method.getName());
        }
    }

    /** Shutdown runs after Aurora region threads stop; drain Netty callbacks before module disposal. */
    public void quiesce() throws Exception {
        accepting = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var barriers = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (Channel channel : channels.keySet()) {
            if (channel.eventLoop().inEventLoop()) throw new IllegalStateException("Native shutdown cannot run on a Netty event loop");
            try { barriers.add(channel.eventLoop().submit(() -> {})); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                if (channel.isActive()) throw rejected;
            }
        }
        for (var barrier : barriers) {
            barrier.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        io.papermc.paper.network.ChannelInitializeListenerHolder.removeListener(KEY);
        listeners.clear();
        internal.clear();
        channels.keySet().forEach(channel -> {
            try {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                if (channel.isActive()) throw rejected;
            }
        });
        channels.clear();
        instance = null;
    }

    private static final class Scheduler implements ProtocolScheduler {
        private final NativeService owner;
        Scheduler(NativeService owner) { this.owner = owner; }
        @Override public Task runTask(Runnable runnable) { return scheduleSyncDelayedTask(runnable, 1); }
        @Override public Task scheduleSyncDelayedTask(Runnable runnable, long delay) {
            var task = Bukkit.getGlobalRegionScheduler().runDelayed(owner, ignored -> runnable.run(), Math.max(1, delay));
            return task::cancel;
        }
        @Override public Task scheduleSyncRepeatingTask(Runnable runnable, long delay, long period) {
            var task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(owner, ignored -> runnable.run(), Math.max(1, delay), Math.max(1, period));
            return task::cancel;
        }
        @Override public Task runTaskAsync(Runnable runnable) {
            var task = Bukkit.getAsyncScheduler().runNow(owner, ignored -> runnable.run());
            return task::cancel;
        }
    }
}
