package dev.iyanz.sourbycraft.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Violation logging + counters for sourbycraft-security.yml enforcement.
 * Happy path (no violation) never calls into this class — zero cost.
 * Violation path: one map lookup + nanoTime compare; strings built only
 * when a line is actually emitted (max 1 per category per second).
 */
public final class SecurityGuard {

    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;

    private static final class Cat {
        final AtomicLong total = new AtomicLong();
        final AtomicLong suppressed = new AtomicLong();
        volatile long lastLogNanos = 0L;
    }

    private static final ConcurrentHashMap<String, Cat> CATEGORIES = new ConcurrentHashMap<>();

    private SecurityGuard() {}

    /** Records a violation; emits a rate-limited WARN (max 1/category/second). playerName may be null. */
    public static void violation(String category, String playerName, String detail) {
        Cat cat = CATEGORIES.computeIfAbsent(category, k -> new Cat());
        cat.total.incrementAndGet();
        long now = System.nanoTime();
        if (now - cat.lastLogNanos < LOG_INTERVAL_NANOS) {
            cat.suppressed.incrementAndGet();
            return;
        }
        cat.lastLogNanos = now;
        long sup = cat.suppressed.getAndSet(0L);
        dev.iyanz.sourbycraft.util.SourbyLogger.warn(
            "[security:" + category + "] " + (playerName == null ? "<unknown>" : playerName) + " — " + detail
            + (sup > 0 ? " (+" + sup + " suppressed)" : "") + " total=" + cat.total.get());
    }

    /** Total violations per category since boot (snapshot). */
    public static Map<String, Long> counters() {
        java.util.HashMap<String, Long> out = new java.util.HashMap<>();
        CATEGORIES.forEach((k, v) -> out.put(k, v.total.get()));
        return out;
    }

    /**
     * Encoded NBT size of an item stack, measured through a counting-only sink
     * (no buffer allocation). Used only on the creative-slot packet path —
     * human-rate packets, so the one serialization here is negligible.
     * Fail-closed: an unmeasurable item reports Long.MAX_VALUE (treated oversized).
     */
    public static long encodedSize(net.minecraft.world.item.ItemStack stack,
                                   net.minecraft.core.HolderLookup.Provider registries) {
        try {
            net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops =
                net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries);
            net.minecraft.nbt.Tag tag = net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            CountingDataOutput sink = new CountingDataOutput();
            net.minecraft.nbt.NbtIo.write((net.minecraft.nbt.CompoundTag) tag, sink);
            return sink.count();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    /** DataOutput that counts bytes and discards them. */
    public static final class CountingDataOutput implements java.io.DataOutput {
        private long count = 0;
        public long count() { return count; }
        @Override public void write(int b) { count += 1; }
        @Override public void write(byte[] b) { count += b.length; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
        @Override public void writeBoolean(boolean v) { count += 1; }
        @Override public void writeByte(int v) { count += 1; }
        @Override public void writeShort(int v) { count += 2; }
        @Override public void writeChar(int v) { count += 2; }
        @Override public void writeInt(int v) { count += 4; }
        @Override public void writeLong(long v) { count += 8; }
        @Override public void writeFloat(float v) { count += 4; }
        @Override public void writeDouble(double v) { count += 8; }
        @Override public void writeBytes(String s) { count += s.length(); }
        @Override public void writeChars(String s) { count += 2L * s.length(); }
        @Override public void writeUTF(String s) { count += 2L + s.length(); } // approximation is fine for a guard
    }
}
