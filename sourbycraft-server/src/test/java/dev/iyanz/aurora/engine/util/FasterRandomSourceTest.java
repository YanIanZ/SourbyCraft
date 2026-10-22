package dev.iyanz.aurora.engine.util;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util: {@link FasterRandomSource} must be reproducible from its seed, because world
 * generation derives positional sources from it.
 */
class FasterRandomSourceTest {

    @Test
    void sameSeedSameSequence() {
        final FasterRandomSource a = new FasterRandomSource(1234L);
        final FasterRandomSource b = new FasterRandomSource(1234L);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.nextLong(), b.nextLong());
        }
        assertNotEquals(new FasterRandomSource(1L).nextLong(), new FasterRandomSource(2L).nextLong());
    }

    @Test
    void setSeedRestartsTheSequence() {
        final FasterRandomSource source = new FasterRandomSource(99L);
        final long first = source.nextLong();
        source.nextLong();
        source.setSeed(99L);
        assertEquals(first, source.nextLong());
    }

    @Test
    void consumeCountSkipsExactlyThatManyLongs() {
        final FasterRandomSource skipped = new FasterRandomSource(7L);
        final FasterRandomSource stepped = new FasterRandomSource(7L);
        skipped.consumeCount(5);
        for (int i = 0; i < 5; i++) stepped.nextLong();
        assertEquals(stepped.nextLong(), skipped.nextLong());
    }

    @Test
    void boundedValuesStayInRange() {
        final FasterRandomSource source = new FasterRandomSource(42L);
        for (int i = 0; i < 1_000; i++) {
            final int bits = source.next(8);
            assertTrue(bits >= 0 && bits < 256, "next(8) = " + bits);
            final int bounded = source.nextInt(10);
            assertTrue(bounded >= 0 && bounded < 10);
            final int ranged = source.nextInt(-5, 5);
            assertTrue(ranged >= -5 && ranged < 5);
            final double unit = source.nextDouble();
            assertTrue(unit >= 0.0 && unit < 1.0);
            final float unitFloat = source.nextFloat();
            assertTrue(unitFloat >= 0.0F && unitFloat < 1.0F);
        }
    }

    @Test
    void forksAreDeterministicGivenTheParentSeed() {
        final RandomSource forkA = new FasterRandomSource(5L).fork();
        final RandomSource forkB = new FasterRandomSource(5L).fork();
        assertEquals(forkA.nextLong(), forkB.nextLong());

        final PositionalRandomFactory positionalA = new FasterRandomSource(5L).forkPositional();
        final PositionalRandomFactory positionalB = new FasterRandomSource(5L).forkPositional();
        assertEquals(positionalA.at(1, 64, -3).nextLong(), positionalB.at(1, 64, -3).nextLong());
        assertNotEquals(positionalA.at(1, 64, -3).nextLong(), positionalA.at(2, 64, -3).nextLong());
        assertEquals(positionalA.fromHashOf("minecraft:ore").nextLong(), positionalB.fromHashOf("minecraft:ore").nextLong());
        assertEquals(new FasterRandomSource(77L).nextLong(), positionalA.fromSeed(77L).nextLong());
    }
}
