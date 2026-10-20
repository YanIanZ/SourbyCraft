package io.canvasmc.canvas.simd;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.Logger;

/** Pure-Java parts of the SIMD switch: version parsing and the "never throw, just say no" capability probe. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SimdDetectionTest {
    private String savedVersion;
    private boolean savedEnabled;
    private boolean savedTestRun;

    @BeforeEach
    void save() {
        this.savedVersion = System.getProperty("java.version");
        this.savedEnabled = SIMDDetection.isEnabled;
        this.savedTestRun = SIMDDetection.testRun;
    }

    @AfterEach
    void restore() {
        System.setProperty("java.version", this.savedVersion);
        SIMDDetection.isEnabled = this.savedEnabled;
        SIMDDetection.testRun = this.savedTestRun;
    }

    private static int parse(final String javaVersion) {
        System.setProperty("java.version", javaVersion);
        return SIMDDetection.getJavaVersion();
    }

    @Test
    void parsesLegacyOneDotXVersions() {
        assertEquals(8, parse("1.8.0_292"));
        assertEquals(8, parse("1.8.0"));
        assertEquals(7, parse("1.7.0_80"));
    }

    @Test
    void parsesModernMajorMinorAndPreReleaseVersions() {
        assertEquals(17, parse("17.0.2"));
        assertEquals(21, parse("21"));
        assertEquals(25, parse("25.0.2"));
        assertEquals(26, parse("26-ea"));
        assertEquals(21, parse("21.0.1-beta"));
        assertEquals(17, parse("17-internal"), "vendor suffixes after a dash are ignored");
    }

    @Test
    void agreesWithTheRunningJvm() {
        System.setProperty("java.version", this.savedVersion);
        assertEquals(Runtime.version().feature(), SIMDDetection.getJavaVersion());
    }

    @Test
    void unparsableVersionsSurfaceAsNumberFormatException() {
        assertThrows(NumberFormatException.class, () -> parse("garbage"));
    }

    @Test
    void oldJavaIsRefusedWithoutTouchingStateOrLogging() {
        final Logger logger = mock(Logger.class);
        SIMDDetection.testRun = false;
        System.setProperty("java.version", "1.8.0_292");
        assertFalse(SIMDDetection.canEnable(logger));
        assertFalse(SIMDChecker.canEnable(logger));
        assertFalse(SIMDDetection.testRun, "the vector classes are not probed on Java < 17");
        verifyNoInteractions(logger);
    }

    @Test
    void anUnreadableVersionMeansDisabledNotACrash() {
        final Logger logger = mock(Logger.class);
        System.setProperty("java.version", "garbage");
        assertDoesNotThrow(() -> assertFalse(SIMDDetection.canEnable(logger)));
        assertDoesNotThrow(() -> assertFalse(SIMDChecker.canEnable(logger)),
            "SIMDChecker swallows the NumberFormatException of a bad version too");
        verifyNoInteractions(logger);
    }

    @Test
    void probingNeverFlipsTheEnabledFlagOnItsOwn() {
        SIMDDetection.isEnabled = false;
        final Logger logger = mock(Logger.class);
        final boolean result = assertDoesNotThrow(() -> SIMDDetection.canEnable(logger));
        assertFalse(SIMDDetection.isEnabled, "canEnable only reports; the caller decides to set isEnabled");
        if (result) {
            assertTrue(SIMDDetection.testRun, "a positive answer implies the probe ran");
        }
    }
}
