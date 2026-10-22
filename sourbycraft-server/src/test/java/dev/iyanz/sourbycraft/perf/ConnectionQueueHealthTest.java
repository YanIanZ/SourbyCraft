package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConnectionQueueHealthTest {

    /** Netty adds a fixed per-message overhead to the pending size (96 bytes by default); measure it, do not assume it. */
    private static final long OVERHEAD = overhead();

    private static long overhead() {
        final EmbeddedChannel probe = new EmbeddedChannel();
        probe.write(Unpooled.EMPTY_BUFFER);
        final long bytes = probe.unsafe().outboundBuffer().totalPendingWriteBytes();
        probe.finishAndReleaseAll();
        return bytes;
    }

    private static EmbeddedChannel channel(final int low, final int high) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
        return channel;
    }

    /** Written but not flushed: the bytes sit in the outbound buffer, which is what the sample reads. */
    @Test
    void pendingBytesAndUnwritableChannelsAreCounted() {
        final EmbeddedChannel quiet = channel(1024, 2048);
        final EmbeddedChannel busy = channel(1024, 2048);
        final EmbeddedChannel backed = channel(1024, 2048);
        busy.write(Unpooled.wrappedBuffer(new byte[100]));
        backed.write(Unpooled.wrappedBuffer(new byte[4000]));
        assertTrue(busy.isWritable(), "100 bytes + overhead is under the 2048-byte high-water mark");
        assertFalse(backed.isWritable());

        final ConnectionQueueHealth.Snapshot sample = ConnectionQueueHealth.sample(List.of(quiet, busy, backed));
        assertEquals(new ConnectionQueueHealth.Snapshot(3, 0, 1, 4100L + 2 * OVERHEAD, 4000L + OVERHEAD), sample);

        backed.flush();
        busy.flush();
        assertEquals(new ConnectionQueueHealth.Snapshot(3, 0, 0, 0L, 0L),
            ConnectionQueueHealth.sample(List.of(quiet, busy, backed)), "flushed bytes leave the buffer");
        quiet.finishAndReleaseAll();
        busy.finishAndReleaseAll();
        backed.finishAndReleaseAll();
    }

    @Test
    void closedChannelsAndNullsAddNoBytes() {
        final EmbeddedChannel open = channel(1024, 2048);
        open.write(Unpooled.wrappedBuffer(new byte[5]));
        final EmbeddedChannel closed = channel(1024, 2048);
        closed.close();
        final ConnectionQueueHealth.Snapshot sample = ConnectionQueueHealth.sample(Arrays.asList(open, null, closed));
        assertEquals(new ConnectionQueueHealth.Snapshot(2, 1, 0, 5L + OVERHEAD, 5L + OVERHEAD), sample);
        open.finishAndReleaseAll();
        assertEquals(ConnectionQueueHealth.Snapshot.EMPTY, ConnectionQueueHealth.sample(List.<Channel>of()));
    }

    /** The guarded list is read under its own monitor, the lock the connection tick takes. */
    @Test
    void guardedListIsCopiedUnderItsMonitor() {
        final List<String> connections = Collections.synchronizedList(new ArrayList<>(List.of("a", "b")));
        final EmbeddedChannel a = channel(1024, 2048);
        a.write(Unpooled.wrappedBuffer(new byte[3]));
        final AtomicBoolean heldDuringLookup = new AtomicBoolean(true);
        final ConnectionQueueHealth.Snapshot sample = ConnectionQueueHealth.sample(connections, name -> {
            heldDuringLookup.compareAndSet(true, Thread.holdsLock(connections));
            return name.equals("a") ? a : null;
        });
        assertTrue(heldDuringLookup.get(), "channel lookup ran under the list's monitor");
        assertEquals(new ConnectionQueueHealth.Snapshot(1, 0, 0, 3L + OVERHEAD, 3L + OVERHEAD), sample);
        a.finishAndReleaseAll();
    }

    @Test
    void noServerYieldsEmpty() {
        assertSame(ConnectionQueueHealth.Snapshot.EMPTY, ConnectionQueueHealth.sampleServer(() -> null));
    }
}
