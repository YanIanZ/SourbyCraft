package dev.iyanz.sourbycraft.perf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.key.Key;

/**
 * Installs {@link NetworkCounters} into every new connection's pipeline, through Paper's
 * channel-initialize hook.
 *
 * <p>Two handlers, both pass-through: one at the head of the pipeline counts wire bytes; one in
 * front of {@code packet_handler} counts protocol packets. Neither copies, retains or modifies a
 * message. The hook is Paper's {@code ChannelInitializeListenerHolder}, which Paper documents as
 * unofficial; if it is missing at runtime, installation fails, is logged by the boot stage, and the
 * server runs without network counters.</p>
 */
public final class NetworkMetrics {

    static final String WIRE = "sourbycraft_wire_counter";
    static final String PACKETS = "sourbycraft_packet_counter";
    static final String PACKET_HANDLER = "packet_handler";
    private static final Key KEY = Key.key("sourbycraft", "network-counters");

    private NetworkMetrics() {}

    public static void install() {
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(KEY,
            channel -> attach(channel.pipeline(), NetworkCounters.GLOBAL));
    }

    /** Adds both handlers to one pipeline. Package-visible for tests. */
    static void attach(final ChannelPipeline pipeline, final NetworkCounters counters) {
        if (pipeline.get(WIRE) == null) {
            pipeline.addFirst(WIRE, new WireCounter(counters));
        }
        if (pipeline.get(PACKETS) == null) {
            if (pipeline.get(PACKET_HANDLER) != null) {
                pipeline.addBefore(PACKET_HANDLER, PACKETS, new PacketCounter(counters));
            } else {
                pipeline.addLast(PACKETS, new PacketCounter(counters));
            }
        }
    }

    private static long size(final Object msg) {
        if (msg instanceof ByteBuf buf) return buf.readableBytes();
        if (msg instanceof ByteBufHolder holder) return holder.content().readableBytes();
        return -1L;
    }

    @ChannelHandler.Sharable
    static final class WireCounter extends ChannelDuplexHandler {
        private final NetworkCounters counters;

        WireCounter(final NetworkCounters counters) {
            this.counters = counters;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            this.counters.connectionOpened();
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            final long n = size(msg);
            if (n > 0) this.counters.bytesIn(n);
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            final long n = size(msg);
            if (n > 0) this.counters.bytesOut(n);
            ctx.write(msg, promise);
        }
    }

    @ChannelHandler.Sharable
    static final class PacketCounter extends ChannelDuplexHandler {
        private final NetworkCounters counters;

        PacketCounter(final NetworkCounters counters) {
            this.counters = counters;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (size(msg) < 0) this.counters.packetIn();
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (size(msg) < 0) this.counters.packetOut();
            ctx.write(msg, promise);
        }
    }
}
