package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiles round-trip, and only same-class boots are compared. */
class StartupProfileTest {

    @TempDir Path dir;

    private static StartupProfile profile(final String startClass, final long total) {
        final Map<String, Long> phases = new LinkedHashMap<>();
        phases.put("boot:configuration", 12L);
        phases.put("startup-index:analyse", 40L);
        return new StartupProfile(startClass, total, phases);
    }

    @Test
    void roundTripsInOrder() throws Exception {
        final Path file = this.dir.resolve("p.properties");
        profile("warm", 5000).save(file);
        final StartupProfile read = StartupProfile.load(file);
        assertEquals(profile("warm", 5000), read);
        assertEquals("boot:configuration", read.phaseMillis().keySet().iterator().next());
    }

    @Test
    void aDamagedProfileReadsAsNone() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("p.properties"), "format=1\ntotal-ms=abc\n");
        assertNull(StartupProfile.load(file));
        assertNull(StartupProfile.load(this.dir.resolve("absent")));
    }

    @Test
    void differentStartClassesAreNotCompared() {
        final String text = profile("warm", 4000).compareTo(profile("cold", 9000));
        assertTrue(text.contains("not comparable"), text);
    }

    @Test
    void sameClassBootsReportTheDifferenceWithoutCallingItABenchmark() {
        final String text = profile("warm", 4100).compareTo(profile("warm", 4000));
        assertTrue(text.contains("+100 ms") && text.contains("not a benchmark"), text);
    }

    @Test
    void theTimelineSavesAndComparesAgainstTheLastBoot() {
        final Path file = this.dir.resolve("timeline.properties");
        StartupTimeline.phase("boot:test", 3_000_000L);
        StartupTimeline.startClass("cold");
        final StartupProfile first = StartupTimeline.complete(1_000L, 6_000L, file);
        assertEquals(5_000L, first.totalMillis());
        assertEquals(3L, first.phaseMillis().get("boot:test"));
        final StartupProfile second = StartupTimeline.complete(1_000L, 5_500L, file);
        assertEquals(first, StartupTimeline.previous());
        assertEquals(second, StartupTimeline.last());
    }
}
