package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.SourbyUpdater.ApplyMode;
import dev.iyanz.sourbycraft.update.SourbyUpdater.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.LISTING;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.listing;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.ok;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.release;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateChannelTest {

    // ------------------------------------------------------------------ parseFromVersion

    @Test
    void channelIsTheTokenAfterTheLastDash() {
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("26.2-REL"));
        assertSame(UpdateChannel.DEV, UpdateChannel.parseFromVersion("26.2-DEV"));
        assertSame(UpdateChannel.EXP, UpdateChannel.parseFromVersion("26.2-EXP"));
        assertSame(UpdateChannel.DEV, UpdateChannel.parseFromVersion("26.2-1.3-DEV"));
        assertSame(UpdateChannel.EXP, UpdateChannel.parseFromVersion("26.11-r48-EXP"));
    }

    @Test
    void parsingIsCaseAndWhitespaceTolerant() {
        assertSame(UpdateChannel.DEV, UpdateChannel.parseFromVersion("26.2-dev"));
        assertSame(UpdateChannel.EXP, UpdateChannel.parseFromVersion("26.2-Exp"));
        assertSame(UpdateChannel.DEV, UpdateChannel.parseFromVersion("26.2- DEV "));
    }

    @Test
    void unknownOrAbsentSuffixesDefaultToTheConservativeRelChannel() {
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion(null));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion(""));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("   "));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("26.2"));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("26.2-"));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("26.2-nightly"));
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("v26.2-r48"), "a bare release tag is REL");
        assertSame(UpdateChannel.REL, UpdateChannel.parseFromVersion("garbage"));
    }

    @Test
    void aVersionThatIsJustTheChannelWordIsThatChannel() {
        assertSame(UpdateChannel.DEV, UpdateChannel.parseFromVersion("DEV"));
        assertSame(UpdateChannel.EXP, UpdateChannel.parseFromVersion("exp"));
    }

    // ------------------------------------------------------------------ parseConfig

    @Test
    void operatorConfigParsesTheThreeNamesLeniently() {
        assertSame(UpdateChannel.REL, UpdateChannel.parseConfig("REL"));
        assertSame(UpdateChannel.DEV, UpdateChannel.parseConfig(" dev "));
        assertSame(UpdateChannel.EXP, UpdateChannel.parseConfig("Exp"));
    }

    @Test
    void operatorConfigNullBlankOrUnknownMeansAutoDetect() {
        assertNull(UpdateChannel.parseConfig(null));
        assertNull(UpdateChannel.parseConfig(""));
        assertNull(UpdateChannel.parseConfig("   "));
        assertNull(UpdateChannel.parseConfig("stable"));
        assertNull(UpdateChannel.parseConfig("26.2-DEV"));
    }

    // ------------------------------------------------------------------ tagMatchesChannel / suffix

    @Test
    void aTagMatchesOnlyItsOwnChannelWithOrWithoutAVPrefix() {
        assertTrue(UpdateChannel.DEV.tagMatchesChannel("v26.2-r49-DEV"));
        assertTrue(UpdateChannel.DEV.tagMatchesChannel("V26.2-r49-dev"));
        assertTrue(UpdateChannel.EXP.tagMatchesChannel(" 26.2-EXP "));
        assertTrue(UpdateChannel.REL.tagMatchesChannel("v26.2-r48"));
        assertFalse(UpdateChannel.REL.tagMatchesChannel("v26.2-r49-DEV"));
        assertFalse(UpdateChannel.DEV.tagMatchesChannel("v26.2-r48"));
        assertFalse(UpdateChannel.EXP.tagMatchesChannel("v26.2-r49-DEV"));
    }

    @Test
    void nullOrBlankTagsMatchNoChannel() {
        for (UpdateChannel c : UpdateChannel.values()) {
            assertFalse(c.tagMatchesChannel(null));
            assertFalse(c.tagMatchesChannel(""));
            assertFalse(c.tagMatchesChannel("   "));
        }
    }

    @Test
    void everyTagBelongsToExactlyOneChannel() {
        for (String tag : List.of("v26.2-r48", "v26.2-r49-DEV", "26.2-EXP", "weird", "26.2-")) {
            long matches = java.util.Arrays.stream(UpdateChannel.values()).filter(c -> c.tagMatchesChannel(tag)).count();
            assertEquals(1, matches, tag);
        }
    }

    @Test
    void suffixIsTheEnumNameAndRoundTripsThroughParse() {
        for (UpdateChannel c : UpdateChannel.values()) {
            assertEquals(c.name(), c.suffix());
            assertSame(c, UpdateChannel.parseFromVersion("26.2-" + c.suffix()));
            assertSame(c, UpdateChannel.parseConfig(c.suffix()));
        }
    }

    // ------------------------------------------------------------------ resolution inside the updater

    @TempDir Path root;
    private String savedChannel;
    private boolean savedPrerelease;

    @BeforeEach
    void saveSettings() {
        savedChannel = AutoUpdateSettings.channel;
        savedPrerelease = AutoUpdateSettings.allowPrerelease;
        AutoUpdateSettings.channel = "";
    }

    @AfterEach
    void restoreSettings() {
        AutoUpdateSettings.channel = savedChannel;
        AutoUpdateSettings.allowPrerelease = savedPrerelease;
    }

    private Outcome check(String buildVersion, String... tags) {
        SourbyUpdaterCheckTest.ScriptedTransport transport = new SourbyUpdaterCheckTest.ScriptedTransport();
        String[] releases = new String[tags.length];
        for (int i = 0; i < tags.length; i++) releases[i] = release(tags[i], 10, "00".repeat(32), false);
        transport.enqueue(LISTING, ok(listing(releases), Map.of()));
        BuildInfo build = new BuildInfo(buildVersion, "47", "47", "26.2", "aurora-nexus", "", "");
        SourbyUpdater updater = new SourbyUpdater(root.resolve("auto_update"), transport, () -> build,
            Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), java.time.ZoneOffset.UTC),
            ReleaseSignatureVerifier.inactive(), new UpdateRollbackGuard(root.resolve("auto_update")));
        return updater.check(ApplyMode.NOTIFY);
    }

    @Test
    void aRelBuildNeverOffersADevOrExpTag() {
        assertEquals(Outcome.NO_RELEASE, check("26.2-REL", "v26.2-r50-DEV", "v26.2-r51-EXP"));
        assertEquals(Outcome.NOTIFIED, check("26.2-REL", "v26.2-r50-DEV", "v26.2-r48"));
    }

    @Test
    void aDevBuildOnlyOffersDevTags() {
        assertEquals(Outcome.NO_RELEASE, check("26.2-DEV", "v26.2-r48", "v26.2-r51-EXP"));
        assertEquals(Outcome.NOTIFIED, check("26.2-DEV", "v26.2-r48", "v26.2-r49-DEV"));
    }

    @Test
    void anOperatorChannelOverridesTheBuildSuffix() {
        AutoUpdateSettings.channel = "DEV";
        assertEquals(Outcome.NOTIFIED, check("26.2-REL", "v26.2-r49-DEV"), "forced DEV on a REL build");
        AutoUpdateSettings.channel = "dev";
        assertEquals(Outcome.NO_RELEASE, check("26.2-REL", "v26.2-r48"), "forced DEV ignores REL tags");
    }

    @Test
    void anUnknownOperatorChannelFallsBackToTheBuildSuffix() {
        AutoUpdateSettings.channel = "stable";
        assertEquals(Outcome.NOTIFIED, check("26.2-REL", "v26.2-r48"));
        assertEquals(Outcome.NO_RELEASE, check("26.2-REL", "v26.2-r49-DEV"));
    }
}
