package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic contract patch 0017 inlines in {@code Entity#isInWall}.
 *
 * <p>Upstream builds an eye box and then translates it per block:
 *
 * <pre>
 *   AABB box      = AABB.ofSize(eyePosition, reducedWidth, 1.0E-6, reducedWidth);
 *   AABB toCollide = box.move(-blockX, -blockY, -blockZ);
 * </pre>
 *
 * <p>0017 replaces both with scalars to drop two allocations per entity per tick. That
 * trade is only free while the scalars are exactly what the API would have produced, and
 * twice it was not: the translation was dropped once and a reference to the removed
 * variable was left behind once, taking three follow-up commits to settle
 * ({@code 676be16}, {@code c08e691}, {@code 15b4218}, {@code 40cc1ff}).
 *
 * <p>These assertions are exact rather than approximate, deliberately. Halving by
 * {@code * 0.5} and by {@code / 2.0} are the same exact power-of-two scaling in IEEE 754,
 * and {@code a - b} is exactly {@code a + (-b)}, so any difference here is a real change
 * in the formula and not floating-point noise.
 *
 * <p>What this pins is the contract. If upstream ever changes how {@code ofSize} or
 * {@code move} computes, this fails and says the inlined copy must change with it. It
 * does not read the patch, so it cannot see the patch drifting away from the contract on
 * its own; {@code patch_policy.translated_bounds_complete} covers that side.
 */
public class IsInWallBoundsTest {

    private static final double EYE_BOX_HEIGHT = 1.0E-6D;

    /** The six scalars 0017 computes in place of {@code AABB.ofSize}. */
    private static AABB inlinedEyeBox(final double x, final double eyeY, final double z,
                                      final double width) {
        final double reducedWidth = width * 0.8F;
        final double halfWidth = reducedWidth * 0.5D;
        final double halfHeight = EYE_BOX_HEIGHT * 0.5D;
        return new AABB(x - halfWidth, eyeY - halfHeight, z - halfWidth,
                        x + halfWidth, eyeY + halfHeight, z + halfWidth);
    }

    /** The translation 0017 applies in place of {@code AABB#move}. */
    private static AABB inlinedTranslation(final AABB box, final int blockX, final int blockY,
                                           final int blockZ) {
        return new AABB(box.minX - blockX, box.minY - blockY, box.minZ - blockZ,
                        box.maxX - blockX, box.maxY - blockY, box.maxZ - blockZ);
    }

    private static void assertSameBox(final AABB expected, final AABB actual, final String what) {
        assertEquals(expected.minX, actual.minX, 0.0, what + " minX");
        assertEquals(expected.minY, actual.minY, 0.0, what + " minY");
        assertEquals(expected.minZ, actual.minZ, 0.0, what + " minZ");
        assertEquals(expected.maxX, actual.maxX, 0.0, what + " maxX");
        assertEquals(expected.maxY, actual.maxY, 0.0, what + " maxY");
        assertEquals(expected.maxZ, actual.maxZ, 0.0, what + " maxZ");
    }

    @Test
    void inlinedEyeBoxMatchesOfSizeForRepresentativeEntities() {
        // Player, baby zombie, spider, enderman, and an entity straddling the origin and
        // negative coordinates, where sign handling would show up.
        final double[][] cases = {
            {0.0, 1.62, 0.0, 0.6},
            {123.5, 65.74, -87.25, 0.6},
            {-0.5, -59.1, -0.5, 0.3},
            {1000.125, 200.0, -1000.875, 1.4},
            {-30000000.0, 319.9, 30000000.0, 0.98},
        };
        for (final double[] values : cases) {
            final double x = values[0], eyeY = values[1], z = values[2], width = values[3];
            final AABB expected = AABB.ofSize(new Vec3(x, eyeY, z),
                                              width * 0.8F, EYE_BOX_HEIGHT, width * 0.8F);
            assertSameBox(expected, inlinedEyeBox(x, eyeY, z, width),
                          "eye box at " + x + "," + eyeY + "," + z + " width " + width);
        }
    }

    @Test
    void translatedBoxMatchesMoveIncludingNegativeBlockCoordinates() {
        final AABB box = AABB.ofSize(new Vec3(12.3, 64.5, -7.8), 0.48, EYE_BOX_HEIGHT, 0.48);
        final int[][] blocks = {{0, 0, 0}, {12, 64, -8}, {-1, -60, -1}, {-30000000, 319, 30000000}};
        for (final int[] block : blocks) {
            final AABB expected = box.move(-(double) block[0], -(double) block[1], -(double) block[2]);
            assertSameBox(expected, inlinedTranslation(box, block[0], block[1], block[2]),
                          "translation by " + block[0] + "," + block[1] + "," + block[2]);
        }
    }

    @Test
    void translationIsNotAccidentallyIdentity() {
        // The regression that shipped was a box left untranslated, which is identity only
        // at the origin block. Guard that the test would actually notice.
        final AABB box = AABB.ofSize(new Vec3(12.3, 64.5, -7.8), 0.48, EYE_BOX_HEIGHT, 0.48);
        final AABB moved = inlinedTranslation(box, 12, 64, -8);
        assertEquals(12.3 - 12, moved.minX + 0.48 * 0.5, 1.0E-12, "translation must shift x");
        assertEquals(64.5 - 64, moved.minY + EYE_BOX_HEIGHT * 0.5, 1.0E-12, "translation must shift y");
    }

    @Test
    void halvingByMultiplicationMatchesDivisionExactly() {
        // The inlined form halves with * 0.5 where the API divides by 2.0. Both are exact
        // power-of-two scalings, so this is an equality and not a tolerance.
        for (final double value : new double[]{0.6, 0.48, 1.0E-6, 1.4, 0.0, -0.3, 1.0E300, 4.9E-324}) {
            assertEquals(value / 2.0, value * 0.5D, 0.0, "halving " + value);
        }
    }
}
