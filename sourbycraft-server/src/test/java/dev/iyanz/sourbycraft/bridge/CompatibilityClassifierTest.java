package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.bridge.CompatibilityClassifier.Evidence;
import org.junit.jupiter.api.Test;

/** A state may never be stronger than the evidence behind it. */
class CompatibilityClassifierTest {

    private static CompatibilityState classify(final boolean declares, final boolean enabled, final boolean failed,
                                               final boolean bridged, final boolean fatal) {
        return CompatibilityClassifier.classify(new Evidence(declares, enabled, failed, bridged, fatal));
    }

    @Test
    void aDeclaredEnabledPluginIsNative() {
        assertEquals(CompatibilityState.NATIVE, classify(true, true, false, false, false));
    }

    @Test
    void bridgedRequiresEnableAndBridgeInitialisationAndNoFatalViolation() {
        assertEquals(CompatibilityState.BRIDGED, classify(false, true, false, true, false));
        assertEquals(CompatibilityState.FAILED, classify(false, true, false, true, true));
        assertEquals(CompatibilityState.DISABLED, classify(false, false, false, true, false));
    }

    @Test
    void anUndeclaredPluginWithoutABridgeIsNeverShownGreen() {
        assertEquals(CompatibilityState.FAILED, classify(false, true, false, false, false));
    }

    @Test
    void aRecordedFailureWinsOverEverythingElse() {
        assertEquals(CompatibilityState.FAILED, classify(true, true, true, false, false));
        assertEquals(CompatibilityState.FAILED, classify(true, false, true, false, false));
    }

    @Test
    void notEnabledWithoutAFailureIsDisabled() {
        assertEquals(CompatibilityState.DISABLED, classify(true, false, false, false, false));
    }

    @Test
    void thePaletteMatchesTheBridgeContract() {
        assertEquals("#4DA3FF", CompatibilityState.NATIVE.hex());
        assertEquals("#57D68D", CompatibilityState.BRIDGED.hex());
        assertEquals("#FF5C70", CompatibilityState.FAILED.hex());
        assertEquals("#8B949E", CompatibilityState.DISABLED.hex());
    }
}
