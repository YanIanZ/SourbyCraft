package io.canvasmc.canvas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldUnloadResultTest {
    @Test
    void successIsTheOnlySuccessAndEveryOtherConstantIsAFailure() {
        for (final WorldUnloadResult result : WorldUnloadResult.values()) {
            if (result == WorldUnloadResult.SUCCESS) {
                assertTrue(result.isSuccess());
                assertFalse(result.isFailure());
            } else {
                assertFalse(result.isSuccess(), result.name());
                assertTrue(result.isFailure(), result.name());
            }
        }
    }

    @Test
    void successAndFailureAreExactComplements() {
        for (final WorldUnloadResult result : WorldUnloadResult.values()) {
            assertNotEquals(result.isSuccess(), result.isFailure(), result.name());
        }
        assertEquals(1, Arrays.stream(WorldUnloadResult.values()).filter(WorldUnloadResult::isSuccess).count());
    }

    @Test
    void constantNamesAndOrderArePinnedBecausePluginsSwitchOnThem() {
        assertEquals(List.of("SUCCESS", "FAIL_PLAYERS_JOINING", "FAIL_PLAYERS_PRESENT", "FAIL_ALREADY_UNLOADING",
                "FAIL_IS_OVERWORLD", "FAIL_UNLOAD_EVENT", "FAIL_IS_SHUTDOWN", "FAIL_UNKNOWN"),
            Arrays.stream(WorldUnloadResult.values()).map(Enum::name).toList());
    }

    @Test
    void everyFailureConstantIsNamedFailAndLookupRoundTrips() {
        for (final WorldUnloadResult result : WorldUnloadResult.values()) {
            assertSame(result, WorldUnloadResult.valueOf(result.name()));
            assertEquals(result.isFailure(), result.name().startsWith("FAIL_"), result.name());
        }
        assertThrows(IllegalArgumentException.class, () -> WorldUnloadResult.valueOf("success"));
    }
}
