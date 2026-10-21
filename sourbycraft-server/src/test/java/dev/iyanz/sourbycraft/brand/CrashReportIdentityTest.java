package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.SharedConstants;
import org.bukkit.craftbukkit.CraftCrashReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Crash reports name the SourbyCraft build from {@link BuildInfo} (Paper feature patch 0015 adds
 * the line to {@code CraftCrashReport}); upstream's {@code BrandInfo} line, built from the jar
 * manifest, is kept as it is.
 */
class CrashReportIdentityTest {

    @BeforeAll
    static void detectGameVersion() {
        // ServerBuildInfo, read first by CraftCrashReport, needs the game version, as on a server.
        // Idempotent process-wide setup that Bootstrap also performs; nothing to restore.
        SharedConstants.tryDetectVersion();
    }

    private static BuildInfo info(final String properties) {
        return BuildInfo.loadFrom(new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aReleaseBuildIsNamedTheWayVerNamesIt() {
        final BuildInfo build = info("""
            version=26.2-REL
            build=47
            mcVersion=26.2
            codename=aurora-nexus
            buildTimestamp=2026-10-20T10:00:00Z
            """);
        assertEquals("Build 47 — Aurora Nexus (channel 26.2-REL, Minecraft 26.2, built 2026-10-20T10:00:00Z)",
            CrashReportIdentity.line(build));
    }

    @Test
    void missingFieldsStillGiveALine() {
        assertEquals("Build ? (UNKNOWN, ?@?) (channel dev, Minecraft unknown, built unknown)",
            CrashReportIdentity.line(info("")));
    }

    @Test
    void theCrashReportDetailCarriesTheLineBeforeAnythingThatNeedsARunningServer() {
        // No server runs in this test: CraftCrashReport fails on Bukkit.getName() and appends its
        // own failure, so the BuildInfo line must already be in the detail by then.
        final String detail = new CraftCrashReport().call();
        assertTrue(detail.startsWith("\n   SourbyCraft: " + CrashReportIdentity.line()), detail);
    }
}
