package dev.iyanz.sourbycraft.perf;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Outbound queue health of the server's connections, read from Netty: how many bytes each channel
 * has accepted but not yet written to the socket, and whether it is over its write-buffer high-water
 * mark (Netty's {@code isWritable() == false}).
 *
 * <p><b>What is read.</b> Per channel, {@link ChannelOutboundBuffer#totalPendingWriteBytes()} and
 * {@link Channel#isWritable()}; both are volatile reads in Netty and may be taken off the event loop.
 * Netty's pending size includes a fixed per-message overhead (96 bytes by default,
 * {@code io.netty.transport.outboundBufferEntrySizeOverhead}), so pending bytes are an upper bound
 * on payload bytes, and the same figure Netty compares with the water marks.
 * The values are one instant's view, approximate under concurrent writes. A channel without an
 * outbound buffer (closed, or not registered yet) counts as {@code closed} and adds no bytes.</p>
 *
 * <p><b>What is not read.</b> Packets the game has queued before they reach Netty
 * ({@code Connection.pendingActions}, private, flushed on the connection's owner) are not visible here;
 * reading them needs a feature patch or an access transformer. Neither is per-packet-type data nor
 * any history: this is a point sample for {@code /perf network}, not a recorder.</p>
 *
 * <p>Sampling copies the channel list under the list's own lock (the same lock the connection tick
 * takes) and reads the channels outside it, so a slow read never holds the tick.</p>
 */
public final class ConnectionQueueHealth {

    /**
     * One sample.
     *
     * @param connections   channels sampled, open or not
     * @param closed        channels with no outbound buffer (closed or not yet registered)
     * @param unwritable    open channels over their high-water mark
     * @param pendingBytes  outbound bytes not yet written, summed over open channels
     * @param maxPendingBytes the largest single channel's pending bytes
     */
    public record Snapshot(int connections, int closed, int unwritable, long pendingBytes, long maxPendingBytes) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0L, 0L);
    }

    private ConnectionQueueHealth() {}

    /** Samples the given channels; {@code null} entries are skipped. */
    public static Snapshot sample(final Iterable<? extends Channel> channels) {
        int connections = 0;
        int closed = 0;
        int unwritable = 0;
        long pending = 0L;
        long max = 0L;
        for (final Channel channel : channels) {
            if (channel == null) continue;
            connections++;
            final ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
            if (buffer == null || !channel.isOpen()) {
                closed++;
                continue;
            }
            final long bytes = Math.max(0L, buffer.totalPendingWriteBytes());
            pending += bytes;
            max = Math.max(max, bytes);
            if (!channel.isWritable()) unwritable++;
        }
        return new Snapshot(connections, closed, unwritable, pending, max);
    }

    /**
     * Samples a list of game connections guarded by its own monitor, the way
     * {@code ServerConnectionListener.connections} is: the channels are copied under the lock and read after.
     */
    public static <C> Snapshot sample(final List<C> guardedConnections,
                                      final java.util.function.Function<? super C, ? extends Channel> channelOf) {
        final List<Channel> channels;
        synchronized (guardedConnections) {
            channels = new ArrayList<>(guardedConnections.size());
            for (final C connection : guardedConnections) {
                channels.add(connection == null ? null : channelOf.apply(connection));
            }
        }
        return sample(channels);
    }

    /**
     * The running server's connections, or {@link Snapshot#EMPTY} when there is no server yet.
     * {@code MinecraftServer.getConnection()} creates the listener if it does not exist (Spigot); the
     * server creates it during startup, before any command can call this.
     */
    public static Snapshot sampleServer() {
        return sampleServer(net.minecraft.server.MinecraftServer::getServer);
    }

    static Snapshot sampleServer(final Supplier<net.minecraft.server.MinecraftServer> server) {
        final net.minecraft.server.MinecraftServer current = server.get();
        if (current == null) return Snapshot.EMPTY;
        return sample(current.getConnection().getConnections(), connection -> connection.channel);
    }
}
