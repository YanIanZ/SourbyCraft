package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Arithmetic compatibility checks for collision patch 0019. The oracle is AABB.move,
 * followed by the original object overload. These tests cover numeric semantics, not
 * world traversal, predicates, or region ownership; those require integration coverage.
 */
class BlockCollisionBoundsTest {
    @Test
    void scalarTranslationPreservesBothIntersectionRulesAndRetainedBounds() {
        final double epsilon = CollisionUtil.COLLISION_EPSILON;
        final AABB[] shapes = {
            new AABB(0, 0, 0, 1, 1, 1),
            new AABB(0.125, 0, 0.25, 0.875, 0.5, 0.75),
            new AABB(-0.5, 0, -0.5, 1.5, 1.5, 1.5)
        };
        final int[][] offsets = {{0, 0, 0}, {-17, -64, -33}, {15, 319, 16},
            {29_999_999, 64, -29_999_999}};
        for (final AABB shape : shapes) {
            for (final int[] offset : offsets) {
                final double minX = shape.minX + offset[0];
                final double minY = shape.minY + offset[1];
                final double minZ = shape.minZ + offset[2];
                final double maxX = shape.maxX + offset[0];
                final double maxY = shape.maxY + offset[1];
                final double maxZ = shape.maxZ + offset[2];
                final AABB reference = shape.move(offset[0], offset[1], offset[2]);
                assertEquals(reference, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
                for (final double overlap : new double[] {-1, 0, epsilon / 2, epsilon, epsilon * 2, 0.25}) {
                    for (int axis = 0; axis < 3; axis++) {
                        final AABB query = new AABB(
                            axis == 0 ? maxX - overlap : minX,
                            axis == 1 ? maxY - overlap : minY,
                            axis == 2 ? maxZ - overlap : minZ,
                            maxX + 1, maxY + 1, maxZ + 1);
                        assertEquals(query.intersects(reference),
                            query.intersects(minX, minY, minZ, maxX, maxY, maxZ));
                        assertEquals(CollisionUtil.voxelShapeIntersect(query, reference),
                            CollisionUtil.voxelShapeIntersect(query, minX, minY, minZ, maxX, maxY, maxZ));
                    }
                }
            }
        }
    }

    @Test
    void fullBlockAndPartialShapeMustRetainDifferentEpsilonRules() {
        final AABB block = new AABB(0, 0, 0, 1, 1, 1);
        final AABB shallowOverlap = new AABB(1 - CollisionUtil.COLLISION_EPSILON / 2,
            0.25, 0.25, 2, 0.75, 0.75);
        assertTrue(shallowOverlap.intersects(block), "Full block uses the vanilla strict test");
        assertFalse(CollisionUtil.voxelShapeIntersect(shallowOverlap, block),
            "Other single-box shapes require more than epsilon overlap");
    }
}
