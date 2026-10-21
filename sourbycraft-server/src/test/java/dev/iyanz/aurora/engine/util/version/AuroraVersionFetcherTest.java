package dev.iyanz.aurora.engine.util.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The {@code /version} commit link points at SourbyCraft, not the Canvas project (owner decision 2026-10-21). */
class AuroraVersionFetcherTest {

    @Test
    void commitLinksIntoTheSourbyCraftRepository() {
        assertEquals("https://github.com/YanIanZ/SourbyCraft/commit/f402d5c2", AuroraVersionFetcher.commitUrl("f402d5c2"));
        assertEquals("https://github.com/YanIanZ/SourbyCraft/", AuroraVersionFetcher.commitUrl(null));
        assertEquals("https://github.com/YanIanZ/SourbyCraft/", AuroraVersionFetcher.commitUrl(" "));
        assertFalse(AuroraVersionFetcher.REPOSITORY.toLowerCase(java.util.Locale.ROOT).contains("canvas"));
    }
}
