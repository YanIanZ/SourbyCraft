package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chunk system touches the metrics before configuration loads, so the switch is read early. */
class ChunkGenerationMetricsEarlyConfigTest {

    @TempDir Path dir;

    @Test
    void missingFilesMeanOff() {
        assertFalse(ChunkGenerationMetrics.readEarly(this.dir.resolve("a.toml"), this.dir.resolve("u.toml")));
    }

    @Test
    void theAuroraSettingTurnsItOn() throws Exception {
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.diagnostics]\nchunk-generation-metrics = true\n");
        assertTrue(ChunkGenerationMetrics.readEarly(aurora, this.dir.resolve("u.toml")));
    }

    @Test
    void auroraTomlWinsOverTheUnifiedFile() throws Exception {
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.diagnostics]\nchunk-generation-metrics = false\n");
        final Path unified = Files.writeString(this.dir.resolve("u.toml"),
            "[aurora.diagnostics]\nchunk-generation-metrics = true\n");
        assertFalse(ChunkGenerationMetrics.readEarly(aurora, unified));
        assertTrue(ChunkGenerationMetrics.readEarly(this.dir.resolve("none.toml"), unified));
    }

    @Test
    void unparseableOrInvalidValuesStayOff() throws Exception {
        final Path broken = Files.writeString(this.dir.resolve("broken.toml"), "[aurora.diagnostics\nx = \n");
        assertFalse(ChunkGenerationMetrics.readEarly(broken, this.dir.resolve("u.toml")));
        final Path typo = Files.writeString(this.dir.resolve("typo.toml"),
            "[aurora.diagnostics]\nchunk-generation-metrics = \"yes\"\n");
        assertFalse(ChunkGenerationMetrics.readEarly(typo, this.dir.resolve("u.toml")));
    }

    @Test
    void earlyReadNeverWrites() throws Exception {
        final String text = "[aurora.diagnostics]\nlane-sampling = false\n";
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"), text);
        assertFalse(ChunkGenerationMetrics.readEarly(aurora, this.dir.resolve("u.toml")));
        assertEquals(text, Files.readString(aurora));
        assertFalse(Files.exists(this.dir.resolve("u.toml")));
    }

    @Test
    void settingParsesDefaultOffTrueAndInvalid() {
        final var none = AuroraConfig.parse(new dev.iyanz.sourbycraft.config.ConfigSnapshot(Map.of()));
        assertFalse(none.config().diagnostics().chunkGenerationMetrics());
        assertFalse(AuroraConfig.DEFAULT.diagnostics().chunkGenerationMetrics());
        final var on = AuroraConfig.parse(new dev.iyanz.sourbycraft.config.ConfigSnapshot(
            Map.of(AuroraConfig.CHUNK_GENERATION_METRICS_KEY, true)));
        assertTrue(on.config().diagnostics().chunkGenerationMetrics());
        assertTrue(on.config().diagnostics().laneSampling(), "lane sampling keeps its own default");
        assertEquals(List.of(), on.invalidKeys());
        final var typo = AuroraConfig.parse(new dev.iyanz.sourbycraft.config.ConfigSnapshot(
            Map.of(AuroraConfig.CHUNK_GENERATION_METRICS_KEY, "yes")));
        assertFalse(typo.config().diagnostics().chunkGenerationMetrics());
        assertEquals(List.of(AuroraConfig.CHUNK_GENERATION_METRICS_KEY), typo.invalidKeys());
    }

    @Test
    void changingItIsRestartRequiredNotALiveChange() {
        assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, AuroraConfig.CHUNK_GENERATION_METRICS.lifecycle());
        final AuroraConfig on = AuroraConfig.parse(new dev.iyanz.sourbycraft.config.ConfigSnapshot(
            Map.of(AuroraConfig.CHUNK_GENERATION_METRICS_KEY, true))).config();
        assertEquals(0, on.liveChangesComparedTo(AuroraConfig.DEFAULT));
        assertTrue(on.reloadSummary(AuroraConfig.DEFAULT).contains(AuroraConfig.CHUNK_GENERATION_METRICS_KEY));
    }
}
