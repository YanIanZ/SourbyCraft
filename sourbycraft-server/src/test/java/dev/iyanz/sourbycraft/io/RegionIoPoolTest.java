package dev.iyanz.sourbycraft.io;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionIoPoolTest {

    @Test
    void identityOpRoundTrips() throws Exception {
        RegionIoPool pool = new RegionIoPool(/* size */ 2, /* queueCap */ 8);
        try {
            ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
            CompletableFuture<ByteBuffer> f = pool.submit(buf -> buf.duplicate(), src);
            ByteBuffer out = f.get(2, TimeUnit.SECONDS);
            assertEquals(4, out.remaining());
            assertEquals((byte) 1, out.get());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void queueBackpressureRejectsWhenFull() {
        RegionIoPool pool = new RegionIoPool(1, 1);
        try {
            pool.submit(buf -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                return buf;
            }, ByteBuffer.allocate(0));
            pool.submit(buf -> buf, ByteBuffer.allocate(0));
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> pool.submit(buf -> buf, ByteBuffer.allocate(0)));
        } finally {
            pool.shutdown();
        }
    }
}
