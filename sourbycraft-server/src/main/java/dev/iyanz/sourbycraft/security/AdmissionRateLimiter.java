package dev.iyanz.sourbycraft.security;

import java.util.LinkedHashMap;

/**
 * Process-wide admission counters shared by the Netty loops. A short monitor protects only
 * bounded in-memory operations; no callbacks, logging, DNS or I/O happen while it is held.
 *
 * <p>Windows are fixed one-second buckets of one process-wide epoch derived from the caller's
 * monotonic clock. The epoch is clamped monotonically under the monitor, so a sample taken
 * before the monitor and delivered late counts in the latest observed window instead of
 * reopening (and replenishing) an older one. Fixed windows allow up to twice a limit across one
 * boundary. Memory is capped independently of connection lifetime and address cardinality: the
 * table is access-ordered (LRU) and evicts only an entry whose window has expired, and peers that
 * find the table full of active entries are untracked: they count only against the global budget,
 * never against a shared per-address bucket.
 */
final class AdmissionRateLimiter {
    private static final long SECOND = 1_000_000_000L;
    private final int capacity;
    private final LinkedHashMap<AddressKey, Window> peers = new LinkedHashMap<>(16, 0.75f, true);
    private final Window global = new Window();
    private long latestEpoch = Long.MIN_VALUE;

    /** An address count of -1 means bypass/untracked; global -1 means address KICK withheld it. */
    record Counts(long global, long address) {}

    AdmissionRateLimiter(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    Counts record(byte[] address, boolean login, long nowNanos) {
        return record(address, login, nowNanos, 64, false, true, Long.MAX_VALUE, false);
    }

    Counts record(byte[] address, boolean login, long nowNanos, int ipv6PrefixLength,
                  boolean skipAddress, long addressMaximum, boolean rejectAddress) {
        return record(address, login, nowNanos, ipv6PrefixLength, skipAddress, true, addressMaximum, rejectAddress);
    }

    /**
     * @param skipAddress  count globally only (loopback, proxy transport address, late-forwarded login)
     * @param countGlobal  false for the second, per-address-only observation of an event already
     *                     counted globally (Velocity forwarding); the global count is then -1
     */
    synchronized Counts record(byte[] address, boolean login, long nowNanos, int ipv6PrefixLength,
                               boolean skipAddress, boolean countGlobal, long addressMaximum, boolean rejectAddress) {
        AddressKey key = AddressKey.of(address, ipv6PrefixLength);
        // Samples taken before this monitor can arrive out of order. Never reopen an old epoch.
        long epoch = Math.max(latestEpoch, Math.floorDiv(nowNanos, SECOND));
        latestEpoch = epoch;
        long addressCount = -1;
        if (!skipAddress) {
            Window peer = peers.get(key); // access order keeps the least recently used entry first
            if (peer == null) {
                // At most one stale LRU eviction. An active LRU means all entries are active;
                // untracked peers skip address checks rather than sharing a false offender budget.
                if (peers.size() == capacity && peers.firstEntry().getValue().epoch < epoch) peers.pollFirstEntry();
                if (peers.size() < capacity) {
                    peer = new Window();
                    peers.put(key, peer);
                }
            }
            if (peer != null) addressCount = peer.increment(login, epoch);
        }
        // Explicit per-address KICK rejects do not consume everyone else's global allowance.
        if (!countGlobal || (rejectAddress && addressCount > addressMaximum)) return new Counts(-1, addressCount);
        return new Counts(global.increment(login, epoch), addressCount);
    }

    synchronized int trackedAddresses() { return peers.size(); }

    private static final class Window {
        long epoch = Long.MIN_VALUE;
        long connections;
        long logins;
        long increment(boolean login, long currentEpoch) {
            if (epoch != currentEpoch) {
                epoch = currentEpoch;
                connections = logins = 0;
            }
            if (login) return logins = logins == Long.MAX_VALUE ? logins : logins + 1;
            return connections = connections == Long.MAX_VALUE ? connections : connections + 1;
        }
    }

    /** Immutable numeric address only: ports and caller-owned byte arrays are not retained. */
    static String addressLabel(byte[] bytes, int ipv6PrefixLength) {
        AddressKey key = AddressKey.of(bytes, ipv6PrefixLength);
        if (!key.ipv6()) return ((key.low() >>> 24) & 255) + "." + ((key.low() >>> 16) & 255)
            + "." + ((key.low() >>> 8) & 255) + "." + (key.low() & 255);
        var text = new StringBuilder();
        for (int group = 0; group < 8; group++) {
            if (group != 0) text.append(':');
            long half = group < 4 ? key.high() : key.low();
            text.append(Long.toHexString((half >>> (48 - (group % 4) * 16)) & 65535));
        }
        return text.append('/').append(ipv6PrefixLength).toString();
    }

    private record AddressKey(long high, long low, boolean ipv6, int prefixLength) {
        static AddressKey of(byte[] bytes, int ipv6PrefixLength) {
            if (ipv6PrefixLength < 1 || ipv6PrefixLength > 128) throw new IllegalArgumentException("IPv6 prefix must be 1..128");
            if (bytes.length != 4 && bytes.length != 16) throw new IllegalArgumentException("Expected IPv4 or IPv6 bytes");
            boolean mapped = bytes.length == 16;
            if (mapped) {
                for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
                mapped &= bytes[10] == (byte)255 && bytes[11] == (byte)255;
            }
            if (bytes.length == 4 || mapped) {
                long value = 0;
                for (int i = bytes.length - 4; i < bytes.length; i++) value = (value << 8) | (bytes[i] & 255L);
                return new AddressKey(0, value, false, 32);
            }
            long high = 0, low = 0;
            for (int i = 0; i < 8; i++) high = (high << 8) | (bytes[i] & 255L);
            for (int i = 8; i < 16; i++) low = (low << 8) | (bytes[i] & 255L);
            high &= ipv6PrefixLength >= 64 ? -1L : -1L << (64 - ipv6PrefixLength);
            low &= ipv6PrefixLength <= 64 ? 0L : -1L << (128 - ipv6PrefixLength);
            return new AddressKey(high, low, true, ipv6PrefixLength);
        }
    }
}
