package dev.iyanz.sourbycraft.perf;

import java.nio.file.Path;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryEventTest {
    @Test void recordingContainsCanonicalSnapshotState(@TempDir Path directory) throws Exception {
        final Path file = directory.resolve("telemetry.jfr");
        try (var recording = new Recording()) {
            recording.enable(TelemetryEvent.class);
            recording.start();
            TelemetryEvent.record(ImmutablePerformanceSnapshot.warming());
            recording.stop();
            recording.dump(file);
        }
        final var events = RecordingFile.readAllEvents(file).stream()
            .filter(event -> event.getEventType().getName().equals("dev.iyanz.sourbycraft.PerformanceSnapshot"))
            .toList();
        assertEquals(1, events.size());
        assertEquals("WARMING", events.getFirst().getString("state"));
        assertTrue(Double.isNaN(events.getFirst().getDouble("worstTps")));
        assertEquals(0L, events.getFirst().getLong("sequence"));
    }
}
