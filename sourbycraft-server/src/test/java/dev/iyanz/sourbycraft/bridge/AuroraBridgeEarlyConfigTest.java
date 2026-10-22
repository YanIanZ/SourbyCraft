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

    /** Follow-up case 1 (LightingLuminol port): defaults, bounds, malformed values and file precedence of the limits. */
    @Test
    void limitKeysDefaultToZeroAndFollowTheSameFilePrecedence() throws Exception {
        assertEquals(0, AuroraConfig.Bridge.DEFAULT.maxPendingTasksPerPlugin());
        assertEquals(0, AuroraConfig.Bridge.DEFAULT.maxRunningAsyncTasksPerPlugin());
        final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.bridge]\nmode = \"safe\"\nmax-pending-tasks-per-plugin = 10\n");
        final Path unified = Files.writeString(this.dir.resolve("u.toml"),
            "[aurora.bridge]\nmax-pending-tasks-per-plugin = 99\nmax-running-async-tasks-per-plugin = 2\n");
        final AuroraConfig.Bridge bridge = AuroraBridge.readEarly(aurora, unified);
        assertEquals(10, bridge.maxPendingTasksPerPlugin(), "aurora.toml wins");
        assertEquals(2, bridge.maxRunningAsyncTasksPerPlugin(), "unset in aurora.toml: the unified file's value");
    }

    @Test
    void outOfRangeOrMalformedLimitsFallBackToUnlimitedWithoutTouchingTheOtherKeys() throws Exception {
        for (final String value : new String[] {"-1", "100001", "2.5", "\"ten\""}) {
            final Path aurora = Files.writeString(this.dir.resolve("aurora.toml"),
                "[aurora.bridge]\nmode = \"safe\"\nmax-pending-tasks-per-plugin = " + value
                    + "\nmax-running-async-tasks-per-plugin = " + value + "\n");
            final AuroraConfig.Bridge bridge = AuroraBridge.readEarly(aurora, this.dir.resolve("u.toml"));
            assertEquals(0, bridge.maxPendingTasksPerPlugin(), value);
            assertEquals(0, bridge.maxRunningAsyncTasksPerPlugin(), value);
            assertEquals(AuroraConfig.BridgeMode.SAFE, bridge.mode(), value);
        }
        final Path edge = Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.bridge]\nmax-pending-tasks-per-plugin = 100000\nmax-running-async-tasks-per-plugin = 0\n");
        assertEquals(100_000, AuroraBridge.readEarly(edge, this.dir.resolve("u.toml")).maxPendingTasksPerPlugin());
    }
}
