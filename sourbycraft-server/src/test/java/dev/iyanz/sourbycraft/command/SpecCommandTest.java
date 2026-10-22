package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** /spec reports what the machine is; a field it cannot read says so rather than reading zero. */
public class SpecCommandTest {

    @Test
    void anUnreadableClockSaysUnavailableRatherThanZero() {
        // OSHI returns -1 where a platform will not report frequency. "0.00 GHz" would look like
        // a measurement, and an operator would believe it.
        assertEquals("unavailable", SpecCommand.frequency(-1L));
        assertEquals("unavailable", SpecCommand.frequency(0L));
    }

    @Test
    void aReadableClockIsReportedInGigahertz() {
        assertEquals("3.40 GHz", SpecCommand.frequency(3_400_000_000L));
        assertEquals("4.90 GHz", SpecCommand.frequency(4_900_000_000L));
    }

    @Test
    void renderingWithoutHardwareStillProducesAPanel() {
        // OSHI resolves on a virtual thread, so an early /spec has no hardware yet. It must say
        // so and still report everything the JVM itself knows.
        final var lines = SpecCommand.render();
        assertFalse(lines.isEmpty());
        final String all = lines.toString();
        assertTrue(all.contains("Heap allocation"), "JVM-side facts are always available");
        assertTrue(all.contains("Processors visible to the JVM"));
    }
}
