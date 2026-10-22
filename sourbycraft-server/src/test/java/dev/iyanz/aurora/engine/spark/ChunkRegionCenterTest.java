package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.spark.plugin.AbstractFoliaChunkInfo;
import dev.iyanz.aurora.engine.spark.plugin.ChunkRegionCenter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** OBS-14 engine spark: the small value types the chunk reports are keyed by. */
class ChunkRegionCenterTest {

    private static final class Info extends AbstractFoliaChunkInfo<String> {
        Info(final int x, final int z, final ChunkRegionCenter center) {
            super(x, z, center);
        }

        @Override public me.lucko.spark.paper.common.platform.world.CountMap<String> getEntityCounts() { return null; }
        @Override public String entityTypeName(final String type) { return type; }
    }

    @Test
    void unknownIsTheMinusOneRegionAtTheOrigin() {
        assertEquals(new ChunkRegionCenter(-1L, 0, 0), ChunkRegionCenter.UNKNOWN);
        assertEquals(-1L, ChunkRegionCenter.UNKNOWN.id());
    }

    @Test
    void chunkInfoCarriesItsCoordinatesAndRegionId() {
        final Info info = new Info(-3, 12, new ChunkRegionCenter(77L, 8, 8));
        assertEquals(-3, info.getX());
        assertEquals(12, info.getZ());
        assertEquals(77L, info.getFoliaRegionId());
        assertEquals(-1L, new Info(0, 0, ChunkRegionCenter.UNKNOWN).getFoliaRegionId());
    }

    @Test
    void chunkInfoEqualityIsByCoordinatesOnly() {
        // spark's AbstractChunkInfo.equals is x/z based; the region id does not take part
        assertEquals(new Info(1, 2, new ChunkRegionCenter(1L, 0, 0)), new Info(1, 2, new ChunkRegionCenter(2L, 0, 0)));
        assertNotEquals(new Info(1, 2, ChunkRegionCenter.UNKNOWN), new Info(2, 1, ChunkRegionCenter.UNKNOWN));
    }
}
