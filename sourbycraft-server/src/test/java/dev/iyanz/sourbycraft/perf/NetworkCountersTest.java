package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wire bytes at the head, protocol packets beside the packet handler, and messages untouched. */
class NetworkCountersTest {

    @Test
    void ratesAreTheDifferenceBetweenTwoSamples() {
        final NetworkCounters counters = new NetworkCounters();
        assertFalse(counters.rates().available());
        counters.sample(0L);
        counters.bytesIn(1000);
        counters.bytesOut(500);
        counters.packetIn();
        counters.packetIn();
        counters.sample(2_000_000_000L);
        final NetworkCounters.Rates r = counters.rates();
        assertTrue(r.available());
        assertEquals(500.0, r.bytesInPerSecond());
        assertEquals(250.0, r.bytesOutPerSecond());
        assertEquals(1.0, r.packetsInPerSecond());
    }

    @Test
    void thePipelineCountsWithoutChangingWhatPasses() {
        final NetworkCounters counters = new NetworkCounters();
        // As in the server: handlers are added during channel initialisation, before it is active.
        final EmbeddedChannel channel = new EmbeddedChannel(false, false);
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        NetworkMetrics.attach(channel.pipeline(), counters);
        NetworkMetrics.attach(channel.pipeline(), counters); // idempotent
        try {
            channel.register();
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
        final List<String> names = channel.pipeline().names();
        assertEquals(NetworkMetrics.WIRE, names.get(0));
        assertTrue(names.indexOf(NetworkMetrics.PACKETS) < names.indexOf("packet_handler"));

        final ByteBuf in = Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4, 5});
        channel.writeInbound(in);
        final Object packet = new Object();
        channel.writeInbound(packet);
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {9, 9, 9}));
        channel.writeOutbound("a packet object");

        final NetworkCounters.Totals t = counters.totals(0L);
        assertEquals(5, t.bytesIn());
        assertEquals(3, t.bytesOut());
        assertEquals(1, t.packetsIn());
        assertEquals(1, t.packetsOut());
        assertEquals(1, t.connections());
        assertSame(in, channel.readInbound(), "the same buffer passes through");
        assertSame(packet, channel.readInbound());
        channel.finishAndReleaseAll();
    }
}
