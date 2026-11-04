package dev.iyanz.sourbycraft.brand;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseIdentityTest {
    @Test
    void releaseDisplayStaysUnchanged() {
        assertEquals("Build 47 — Aurora Nexus",
            BuildInfo.formatReleaseIdentity("47", "aurora-nexus", "REL", "26.2", "2c8660b"));
    }

    @Test
    void developmentAndExperimentalDisplaysIncludeSource() {
        for (String channel : new String[] {"DEV", "EXP"}) {
            assertEquals("Build 48 — Aurora Nexus (" + channel + ", 26.2@abc1234)",
                BuildInfo.formatReleaseIdentity("48", "aurora-nexus", channel, "26.2", "abc1234"));
        }
    }

    @Test
    void missingOrUnrecognizedChannelsNeverLookLikeReleases() {
        for (String channel : new String[] {null, "", "   ", "stable", "26.2-REL"}) {
            assertEquals("Build 48 — Aurora Nexus (UNKNOWN, ?@?)",
                BuildInfo.formatReleaseIdentity("48", "aurora-nexus", channel, null, ""));
        }
    }

    @Test
    void prereleaseIdentityCarriesTheCommitAndMarker() {
        assertEquals("Build 50@abc1234PR!",
            BuildInfo.formatReleaseIdentity("50", "aurora-nexus", "REL", "26.2", "abc1234", true));
        assertEquals("Build 50 — Aurora Nexus (DEV, 26.2@?)",
            BuildInfo.formatReleaseIdentity("50", "aurora-nexus", "DEV", "26.2", "", true));
        final BuildInfo info = BuildInfo.loadFrom(new ByteArrayInputStream("""
            version=26.2-REL
            build=50
            buildNumber=50
            prerelease=true
            codename=aurora-nexus
            gitBranch=26.2
            gitCommit=abc123456789abcdef
            """.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Build 50@abc1234PR!", info.releaseIdentity());
        assertEquals(Boolean.TRUE, info.prerelease());
    }

    @Test
    void propertiesSupplyChannelAndSourceWithoutChangingParsedFields() {
        BuildInfo info = BuildInfo.loadFrom(new ByteArrayInputStream("""
            version=26.2-DEV
            build=48-dirty
            buildNumber=48
            codename=aurora-nexus
            gitBranch=26.2
            gitCommit=abc123456789abcdef
            """.getBytes(StandardCharsets.UTF_8)));
        assertEquals("DEV", info.channel());
        assertEquals("Build 48-dirty — Aurora Nexus (DEV, 26.2@abc1234)", info.releaseIdentity());
        assertEquals("26.2-DEV", info.version());
        assertEquals("48", info.buildNumber());
        assertEquals("Build 48-dirty", info.displayVersion());
    }

    @Test
    void versionSuffixMustBeRecognizedAndMissingMetadataStaysUnknown() {
        for (String version : new String[] {null, "", "dev", "26.2", "26.2-stable", "26.2-"}) {
            assertEquals("UNKNOWN", new BuildInfo(version, "48", "48", "26.2", "dev", "", "").channel());
        }
        assertEquals("UNKNOWN", BuildInfo.loadFrom(null).channel());
        assertEquals("Build ? (UNKNOWN, ?@?)", BuildInfo.loadFrom(null).releaseIdentity());
        assertEquals("Build 48 (DEV, ?@?)",
            BuildInfo.formatReleaseIdentity("48", "dev", " dev ", "unknown", "unknown"));
    }
}
