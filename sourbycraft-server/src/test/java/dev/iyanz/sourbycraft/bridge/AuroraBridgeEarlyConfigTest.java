package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The loader asks before configuration loads, so the bridge reads its keys itself. */
class AuroraBridgeEarlyConfigTest {

    @TempDir Path dir;

    @Test
    void missingFilesMeanOff() {
        assertEquals(AuroraConfig.Bridge.DEFAULT,
            AuroraBridge.readEarly(this.dir.resolve("a.toml"), this.dir.resolve("u.toml")));
    }

    @Test
    void auroraTomlWinsOverTheUnifiedFile() throws Exception {
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"), "[aurora.bridge]\nmode = \"safe\"\n");
        final Path unified = Files.writeString(this.dir.resolve("u.toml"),
            "[aurora.bridge]\nmode = \"off\"\nquarantine-after = 7\n");
        final AuroraConfig.Bridge bridge = AuroraBridge.readEarly(aurora, unified);
        assertEquals(AuroraConfig.BridgeMode.SAFE, bridge.mode());
        assertEquals(7, bridge.quarantineAfter());
    }

    @Test
    void anUnparseableFileKeepsTheBridgeOff() throws Exception {
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"), "[aurora.bridge\nmode = safe\n");
        assertEquals(AuroraConfig.Bridge.DEFAULT, AuroraBridge.readEarly(aurora, this.dir.resolve("u.toml")));
    }
}
