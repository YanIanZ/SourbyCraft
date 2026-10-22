package dev.iyanz.sourbycraft.awf;

import org.junit.jupiter.api.Assumptions;

/**
 * The zstd counterpart of {@code RedisTestServer}'s {@code SOURBY_REQUIRE_REDIS}: a test that
 * needs zstd-jni is skipped when it is missing, unless {@code SOURBY_REQUIRE_ZSTD=1} is set (CI
 * sets it), in which case it fails. A codec test that skipped silently would leave the zstd path
 * unverified while the build reads green.
 */
public final class ZstdTestSupport {

    private ZstdTestSupport() {}

    /** Whether the environment demands zstd; split out so the policy is testable. */
    static boolean required(final String envValue) {
        return "1".equals(envValue);
    }

    /** Throws when zstd is required but missing, aborts (skips) when optional and missing. */
    static void check(final boolean available, final String envValue) {
        if (available) return;
        if (required(envValue)) {
            throw new IllegalStateException("zstd-jni is required (SOURBY_REQUIRE_ZSTD=1) but not on the test classpath");
        }
        Assumptions.abort("zstd-jni is not on the test classpath");
    }

    /** Call at the top of a test that cannot run without zstd. */
    public static void requireZstd() {
        check(Compression.zstdAvailable(), System.getenv("SOURBY_REQUIRE_ZSTD"));
    }

    /** For tests that run without zstd but must not skip the zstd part when CI requires it. */
    public static boolean zstdOrSkipPart() {
        final boolean available = Compression.zstdAvailable();
        if (!available) check(false, System.getenv("SOURBY_REQUIRE_ZSTD"));
        return available;
    }
}
