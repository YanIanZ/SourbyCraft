package dev.iyanz.sourbycraft.abysslink;

import java.util.concurrent.TimeUnit;

/** Passive peer liveness, measured only with this process's monotonic clock. V1 proxy owns probes. */
final class AbyssLinkHeartbeat {
    private long lastSeenNanos;
    void received(long now) { lastSeenNanos=now; }
    boolean expired(long now,AbyssLinkSettings settings) {
        return now-lastSeenNanos>=TimeUnit.MILLISECONDS.toNanos(3L*settings.heartbeatMillis()+settings.timeoutMillis());
    }
}
