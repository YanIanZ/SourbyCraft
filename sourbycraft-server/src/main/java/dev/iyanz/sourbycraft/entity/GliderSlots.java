package dev.iyanz.sourbycraft.entity;

/**
 * The equipment-slot pick behind feature patch 0007 ("eliminate elytra glide-slot stream
 * allocation"), kept in Sourby source so the patch is a call site.
 *
 * <p>Every tenth glide tick vanilla damages one glider: {@code Util.getRandom(slotsWithGliders,
 * random)}, which is {@code list.get(random.nextInt(list.size()))} over the slots, in
 * {@code EquipmentSlot.VALUES} order, whose item can glide. The patch builds a bit mask of those
 * slot indexes instead of the list, draws the same {@code random.nextInt(count(mask))} once, and
 * takes the {@link #nthSetBit} -- the same slot and the same single draw from the entity's random
 * as vanilla, for any number of glider slots, without allocating.</p>
 *
 * <p>Pure functions over an {@code int}; no upstream type, so the selection is tested without a
 * server ({@code GliderSlotsTest}).</p>
 */
public final class GliderSlots {

    private GliderSlots() {}

    /** {@code mask} with slot index {@code index} added. Indexes are {@code EquipmentSlot.VALUES} positions. */
    public static int with(final int mask, final int index) {
        if (index < 0 || index >= Integer.SIZE) {
            throw new IllegalArgumentException("slot index out of range: " + index);
        }
        return mask | (1 << index);
    }

    /** How many slots the mask holds: the size of vanilla's {@code slotsWithGliders} list. */
    public static int count(final int mask) {
        return Integer.bitCount(mask);
    }

    /**
     * The slot index of the {@code n}-th set bit, counting from the lowest: element {@code n} of
     * vanilla's list.
     */
    public static int nthSetBit(final int mask, final int n) {
        if (n < 0 || n >= Integer.bitCount(mask)) {
            throw new IllegalArgumentException("no set bit " + n + " in " + Integer.toBinaryString(mask));
        }
        int rest = mask;
        for (int i = 0; i < n; i++) {
            rest &= rest - 1; // clear the lowest set bit
        }
        return Integer.numberOfTrailingZeros(rest);
    }
}
