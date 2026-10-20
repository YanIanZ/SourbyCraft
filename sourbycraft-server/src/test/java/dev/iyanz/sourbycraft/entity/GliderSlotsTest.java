package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature patch 0007 picks the equipment slot whose glider takes durability damage without
 * vanilla's per-call list and stream. The regression this pins: the pick must be vanilla's pick,
 * {@code Util.getRandom(slotsWithGliders, random)} = {@code list.get(random.nextInt(list.size()))},
 * for every set of glider slots and every seed -- the same slot AND the same consumption of the
 * entity's random, so nothing drawn from that random later changes. Modelled with
 * {@link java.util.Random}, whose {@code nextInt(bound)} has the same contract.
 */
class GliderSlotsTest {

    /** EquipmentSlot.VALUES has 8 entries in 26.2; the mask supports up to 32. */
    private static final int SLOTS = 8;

    private static int vanilla(final int mask, final Random random) {
        final List<Integer> slotsWithGliders = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) if ((mask & (1 << i)) != 0) slotsWithGliders.add(i);
        return slotsWithGliders.get(random.nextInt(slotsWithGliders.size()));
    }

    private static int patched(final int mask, final Random random) {
        return GliderSlots.nthSetBit(mask, random.nextInt(GliderSlots.count(mask)));
    }

    /** The single-pass reservoir pick 0007 used before: same distribution, different draws. */
    private static int reservoir(final int mask, final Random random) {
        int picked = -1;
        int seen = 0;
        for (int i = 0; i < SLOTS; i++) {
            if ((mask & (1 << i)) != 0 && random.nextInt(++seen) == 0) picked = i;
        }
        return picked;
    }

    @Test
    void thePickAndTheRandomAfterItMatchVanillaForEveryMaskAndSeed() {
        for (int mask = 1; mask < (1 << SLOTS); mask++) {
            for (long seed = 0; seed < 64; seed++) {
                final Random a = new Random(seed);
                final Random b = new Random(seed);
                assertEquals(vanilla(mask, a), patched(mask, b), "mask " + Integer.toBinaryString(mask) + " seed " + seed);
                assertEquals(a.nextLong(), b.nextLong(), "same random consumption, mask " + Integer.toBinaryString(mask));
            }
        }
    }

    @Test
    void theOldReservoirPickDrewMoreThanVanillaOnceTwoSlotsHadGliders() {
        // Why 0007 changed: with one glider both draw once, with two the reservoir drew twice and
        // shifted every later roll of the entity's random.
        final Random a = new Random(1);
        final Random b = new Random(1);
        vanilla(0b1, a);
        reservoir(0b1, b);
        assertEquals(a.nextLong(), b.nextLong(), "one glider: same consumption");

        boolean diverged = false;
        for (long seed = 0; seed < 16 && !diverged; seed++) {
            final Random v = new Random(seed);
            final Random r = new Random(seed);
            vanilla(0b11, v);
            reservoir(0b11, r);
            diverged = v.nextLong() != r.nextLong();
        }
        assertTrue(diverged, "two gliders: the reservoir pick consumed the random differently");
    }

    @Test
    void noGliderFailsLikeVanilla() {
        // Vanilla's Util.getRandom on an empty list calls nextInt(0), which throws; the patched
        // call site passes count(0) = 0 to the same nextInt. The caller only runs with a glider.
        assertEquals(0, GliderSlots.count(0));
        assertThrows(IllegalArgumentException.class, () -> new Random(0).nextInt(GliderSlots.count(0)));
    }

    @Test
    void nthSetBitCountsFromTheLowestSlot() {
        assertEquals(0, GliderSlots.nthSetBit(0b1, 0));
        assertEquals(4, GliderSlots.nthSetBit(0b10110, 2));
        assertEquals(1, GliderSlots.nthSetBit(0b10110, 0));
        assertEquals(31, GliderSlots.nthSetBit(1 << 31, 0));
        assertThrows(IllegalArgumentException.class, () -> GliderSlots.nthSetBit(0b101, 2));
        assertThrows(IllegalArgumentException.class, () -> GliderSlots.nthSetBit(0b101, -1));
    }

    @Test
    void slotIndexesPastTheMaskAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> GliderSlots.with(0, 32));
        assertEquals(0b100, GliderSlots.with(0, 2));
        assertEquals(0b101, GliderSlots.with(0b1, 2));
    }
}
