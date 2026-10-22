package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/** The skip-versus-fail policy for zstd tests, driven with explicit env values (no real env needed). */
public class ZstdTestSupportTest {

    @Test
    void onlyOneRequiresZstd() {
        assertTrue(ZstdTestSupport.required("1"));
        assertFalse(ZstdTestSupport.required(null));
        assertFalse(ZstdTestSupport.required(""));
        assertFalse(ZstdTestSupport.required("0"));
        assertFalse(ZstdTestSupport.required("true"));
    }

    @Test
    void availableZstdNeverSkipsOrFails() {
        assertDoesNotThrow(() -> ZstdTestSupport.check(true, "1"));
        assertDoesNotThrow(() -> ZstdTestSupport.check(true, null));
    }

    @Test
    void missingOptionalZstdSkips() {
        assertThrows(TestAbortedException.class, () -> ZstdTestSupport.check(false, null));
        assertThrows(TestAbortedException.class, () -> ZstdTestSupport.check(false, "0"));
    }

    @Test
    void missingRequiredZstdFailsInsteadOfSkipping() {
        final IllegalStateException failure =
            assertThrows(IllegalStateException.class, () -> ZstdTestSupport.check(false, "1"));
        assertTrue(failure.getMessage().contains("SOURBY_REQUIRE_ZSTD"));
    }

    @Test
    void whenRequiredZstdIsReallyOnTheClasspath() {
        if (ZstdTestSupport.required(System.getenv("SOURBY_REQUIRE_ZSTD"))) {
            assertTrue(Compression.zstdAvailable(), "SOURBY_REQUIRE_ZSTD=1 but zstd-jni is not loadable");
        }
    }
}
