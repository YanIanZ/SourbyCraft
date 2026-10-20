package dev.iyanz.sourbycraft.update;

import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.JAR_URL;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.LISTING;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.SIG_URL;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.listing;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.manifestJson;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.manifestUrl;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.ok;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.release;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.sha256;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.sign;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.tinyJar;
import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.SourbyUpdater.ApplyMode;
import dev.iyanz.sourbycraft.update.SourbyUpdater.Outcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signed release manifest on the check path: accept, reject, tampered, rollout boundary,
 * yank, missing manifest, and "not enforced without a key". Running build: 26.2-REL build 47.
 */
class SourbyUpdaterManifestTest {

    @TempDir Path root;
    SourbyUpdaterCheckTest.ScriptedTransport transport;
    KeyPair key;
    ReleaseSignatureVerifier verifier;
    byte[] jar;
    String jarSha;
    String serverId;

    @BeforeEach
    void setUp() throws Exception {
        transport = new SourbyUpdaterCheckTest.ScriptedTransport();
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = ReleaseSignatureVerifier.fromEncodedKey(key.getPublic().getEncoded());
        jar = tinyJar();
        jarSha = sha256(jar);
        serverId = "0123456789abcdef0123456789abcdef";
    }

    private SourbyUpdater updater(ReleaseSignatureVerifier v) {
        BuildInfo build = new BuildInfo("26.2-REL", "47", "47", "26.2", "aurora-nexus", "", "");
        return new SourbyUpdater(root.resolve("auto_update"), transport, () -> build,
            new SourbyUpdaterCheckTest.TestClock(Instant.parse("2026-10-20T12:00:00Z")), v,
            new UpdateRollbackGuard(root.resolve("auto_update")), () -> serverId);
    }

    private String rel(String tag) {
        return release(tag, jar.length, jarSha, false, true);
    }

    private void manifest(String tag, int rollout, String... yanked) throws Exception {
        byte[] m = manifestJson(tag, "SourbyCraft-slim.jar", jarSha, rollout, List.of(yanked)).getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl(tag), ok(m, Map.of()));
        transport.enqueue(manifestUrl(tag) + ".sig", ok(sign(key, m), Map.of()));
    }

    private void manifest(String tag, int rollout, long revision, String expiresAt, String... yanked) throws Exception {
        byte[] m = manifestJson(tag, "SourbyCraft-slim.jar", jarSha, rollout, List.of(yanked), revision, expiresAt)
            .getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl(tag), ok(m, Map.of()));
        transport.enqueue(manifestUrl(tag) + ".sig", ok(sign(key, m), Map.of()));
    }

    /** A release whose jar asset was deleted: only its manifest and signature remain. */
    private static String jarless(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"assets\":["
            + "{\"name\":\"" + ReleaseManifest.ASSET_NAME + "\",\"browser_download_url\":\"" + manifestUrl(tag) + "\",\"size\":300},"
            + "{\"name\":\"" + ReleaseManifest.SIGNATURE_ASSET_NAME + "\",\"browser_download_url\":\"" + manifestUrl(tag) + ".sig\",\"size\":64}]}";
    }

    private void listingOf(String... releases) {
        transport.enqueue(LISTING, ok(listing(releases), Map.of()));
    }

    private Path staged() {
        return root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar");
    }

    // ------------------------------------------------------------------ accept

    @Test
    void anAcceptedManifestLeavesTheJarSignatureGateInForce() throws Exception {
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100);
        transport.enqueue(JAR_URL, ok(jar, Map.of()));
        // The release has no jar .sig asset in this fixture: the jar-signature gate still applies.
        assertEquals(Outcome.STAGE_FAILED, updater(verifier).check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING, manifestUrl("v26.2-r48"), manifestUrl("v26.2-r48") + ".sig", JAR_URL), transport.urls());
    }

    @Test
    void aValidManifestAndJarSignatureStage() throws Exception {
        listingOf(release("v26.2-r48", jar.length, jarSha, true, true));
        manifest("v26.2-r48", 100);
        transport.enqueue(JAR_URL, ok(jar, Map.of()));
        transport.enqueue(SIG_URL, ok(sign(key, jar), Map.of()));
        assertEquals(Outcome.STAGED, updater(verifier).check(ApplyMode.STAGE));
        assertTrue(Files.isRegularFile(staged()));
    }

    @Test
    void notifyModeAnnouncesAnIncludedReleaseWithoutDownloadingTheJar() throws Exception {
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY));
        assertFalse(transport.urls().contains(JAR_URL));
    }

    // ------------------------------------------------------------------ reject / tampered / missing

    @Test
    void aManifestSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        listingOf(rel("v26.2-r48"));
        byte[] m = manifestJson("v26.2-r48", "SourbyCraft-slim.jar", jarSha, 100, List.of()).getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl("v26.2-r48"), ok(m, Map.of()));
        transport.enqueue(manifestUrl("v26.2-r48") + ".sig", ok(sign(other, m), Map.of()));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(JAR_URL), "no jar download after a rejected manifest");
    }

    @Test
    void aTamperedManifestIsRejected() throws Exception {
        listingOf(rel("v26.2-r48"));
        byte[] signed = manifestJson("v26.2-r48", "SourbyCraft-slim.jar", jarSha, 10, List.of()).getBytes(StandardCharsets.UTF_8);
        byte[] served = manifestJson("v26.2-r48", "SourbyCraft-slim.jar", jarSha, 100, List.of()).getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl("v26.2-r48"), ok(served, Map.of()));
        transport.enqueue(manifestUrl("v26.2-r48") + ".sig", ok(sign(key, signed), Map.of()));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(Files.exists(staged()));
    }

    @Test
    void aSignedManifestOfAnotherReleaseOrJarIsRejected() throws Exception {
        listingOf(rel("v26.2-r48"));
        byte[] replay = manifestJson("v26.2-r49", "SourbyCraft-slim.jar", jarSha, 100, List.of()).getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl("v26.2-r48"), ok(replay, Map.of()));
        transport.enqueue(manifestUrl("v26.2-r48") + ".sig", ok(sign(key, replay), Map.of()));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));

        listingOf(rel("v26.2-r48"));
        byte[] otherJar = manifestJson("v26.2-r48", "SourbyCraft-slim.jar", "00".repeat(32), 100, List.of()).getBytes(StandardCharsets.UTF_8);
        transport.enqueue(manifestUrl("v26.2-r48"), ok(otherJar, Map.of()));
        transport.enqueue(manifestUrl("v26.2-r48") + ".sig", ok(sign(key, otherJar), Map.of()));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
    }

    @Test
    void withAKeyAMissingManifestFailsClosed() throws Exception {
        listingOf(release("v26.2-r48", jar.length, jarSha, true, false));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING), transport.urls(), "nothing downloaded");
        assertFalse(Files.exists(root.resolve("auto_update/core.path")));
    }

    @Test
    void aBrokenNewestManifestDoesNotFallThroughToAnOlderRelease() throws Exception {
        listingOf(release("v26.2-r49", jar.length, jarSha, false, false), rel("v26.2-r48"));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(manifestUrl("v26.2-r48")));
    }

    @Test
    void withoutAKeyTheManifestIsNeverFetched() throws Exception {
        listingOf(rel("v26.2-r48"));
        transport.enqueue(JAR_URL, ok(jar, Map.of()));
        assertEquals(Outcome.STAGED, updater(ReleaseSignatureVerifier.inactive()).check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING, JAR_URL), transport.urls());
    }

    // ------------------------------------------------------------------ yank

    @Test
    void aSelfYankedReleaseIsNeverStagedOrAnnounced() throws Exception {
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100, "v26.2-r48");
        assertEquals(Outcome.YANKED, updater(verifier).check(ApplyMode.AUTO));
        assertFalse(transport.urls().contains(JAR_URL));
        assertFalse(Files.exists(root.resolve("auto_update/core.path")));
    }

    @Test
    void theNewestManifestYanksAnOlderReleaseAndAYankedNewestFallsBackToTheNextOne() throws Exception {
        // r50 yanks itself and r49; r48 is still good, so r48 is chosen.
        listingOf(rel("v26.2-r50"), rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r50", 100, "v26.2-r50", "v26.2-r49");
        manifest("v26.2-r49", 100);
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY));
        assertTrue(transport.urls().contains(manifestUrl("v26.2-r48")));
    }

    @Test
    void aReleaseYankedByANewerManifestIsNotStagedEvenIfItsOwnManifestIsClean() throws Exception {
        listingOf(rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 0, "v26.2-r48");   // r49 rolling out to nobody yet, and yanks r48
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(JAR_URL));
    }

    // ------------------------------------------------------------------ rollout boundary

    @Test
    void theRolloutBoundaryOnTheCheckPath() throws Exception {
        int bucket = RolloutBucket.bucket(serverId, "v26.2-r48");
        assertTrue(bucket < 99, "fixture id must leave room for bucket + 1: " + bucket);

        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", bucket);
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.STAGE), "bucket == percent: not yet");

        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", bucket + 1);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY), "bucket < percent: included");
    }

    @Test
    void aDeferredNewestReleaseFallsBackToAnOlderFullyRolledOutOne() throws Exception {
        listingOf(rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 0);
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY));
    }

    @Test
    void noServerIdMeansOnlyFullRolloutsArrive() throws Exception {
        serverId = null;
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 99);
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.NOTIFY));
    }

    @Test
    void atMostTheCandidateLimitOfManifestsIsFetched() throws Exception {
        String[] tags = {"v26.2-r55", "v26.2-r54", "v26.2-r53", "v26.2-r52", "v26.2-r51", "v26.2-r50"};
        String[] releases = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            releases[i] = rel(tags[i]);
            if (i < SourbyUpdater.MAX_MANIFEST_CANDIDATES) manifest(tags[i], 0);
        }
        listingOf(releases);
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.NOTIFY));
        assertFalse(transport.urls().contains(manifestUrl("v26.2-r50")));
        assertEquals(1 + 2 * SourbyUpdater.MAX_MANIFEST_CANDIDATES, transport.urls().size());
    }

    @Test
    void releasesNotNewerThanTheRunningBuildAreNotInspected() throws Exception {
        listingOf(rel("v26.2-r47"));
        assertEquals(Outcome.UP_TO_DATE, updater(verifier).check(ApplyMode.NOTIFY));
        assertEquals(List.of(LISTING), transport.urls());
    }

    // ------------------------------------------------------------------ review fixes: jarless yank, replay, expiry, state

    @Test
    void deletingTheJarOfTheYankingReleaseDoesNotHideItsYank() throws Exception {
        // r49's signed manifest yanks r48; r49's jar was deleted. r48 must not be taken.
        listingOf(jarless("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 100, "v26.2-r48");
        manifest("v26.2-r48", 100);
        Outcome outcome = updater(verifier).check(ApplyMode.STAGE);
        assertNotEquals(Outcome.STAGED, outcome);
        assertEquals(Outcome.NO_RELEASE, outcome, "r49 has no jar and r48 is yanked");
        assertFalse(transport.urls().contains(JAR_URL));
        assertTrue(ReleasePolicyState.load(root.resolve("auto_update")).yanked().contains("v26.2-r48"));
    }

    @Test
    void aJarlessNewerReleaseWithoutAManifestFailsClosed() throws Exception {
        listingOf("{\"tag_name\":\"v26.2-r49\",\"draft\":false,\"prerelease\":false,\"assets\":[]}", rel("v26.2-r48"));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(manifestUrl("v26.2-r48")));
    }

    @Test
    void aYankSurvivesTheYankingReleaseBeingDeletedAndAReplayedCleanManifest() throws Exception {
        listingOf(rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 0, "v26.2-r48");      // r49 not rolled out yet, yanks r48
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.STAGE));
        // r49 deleted entirely; r48 served with a clean manifest: the persisted yank still holds.
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.YANKED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(JAR_URL));
    }

    @Test
    void aReplayedOlderRevisionCannotWidenTheRollout() throws Exception {
        int bucket = RolloutBucket.bucket(serverId, "v26.2-r48");
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", bucket, 20, "2099-01-01T00:00:00Z");     // revision 20: not reached yet
        assertEquals(Outcome.ROLLOUT_DEFERRED, updater(verifier).check(ApplyMode.NOTIFY));
        assertEquals(20, ReleasePolicyState.load(root.resolve("auto_update")).revision("v26.2-r48"));

        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100, 10, "2099-01-01T00:00:00Z");        // older revision 10 said 100 %
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.NOTIFY));
        assertEquals(20, ReleasePolicyState.load(root.resolve("auto_update")).revision("v26.2-r48"), "never lowered");

        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100, 21, "2099-01-01T00:00:00Z");        // a genuinely newer policy
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY));
    }

    @Test
    void anExpiredManifestCannotMakeAReleaseInstallableButItsYanksCount() throws Exception {
        listingOf(rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 100, 5, "2026-10-19T00:00:00Z", "v26.2-r48");   // clock is 2026-10-20
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(transport.urls().contains(JAR_URL));
        ReleasePolicyState state = ReleasePolicyState.load(root.resolve("auto_update"));
        assertTrue(state.yanked().contains("v26.2-r48"));
        assertEquals(0, state.revision("v26.2-r49"), "an expired revision is not accepted");
    }

    @Test
    void aStaleNewestFallsBackToAnOlderFreshRelease() throws Exception {
        listingOf(rel("v26.2-r49"), rel("v26.2-r48"));
        manifest("v26.2-r49", 100, 5, "2026-10-19T00:00:00Z");
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY));
    }

    @Test
    void aMissingStateFileIsTrustOnFirstUseAndACorruptOneFailsClosed() throws Exception {
        Path dir = root.resolve("auto_update");
        listingOf(rel("v26.2-r48"));
        manifest("v26.2-r48", 100);
        assertEquals(Outcome.NOTIFIED, updater(verifier).check(ApplyMode.NOTIFY), "no state yet: first use");
        assertTrue(Files.isRegularFile(dir.resolve(ReleasePolicyState.FILE)));

        Files.writeString(dir.resolve(ReleasePolicyState.FILE), "{not json");
        listingOf(rel("v26.2-r48"));
        assertEquals(Outcome.MANIFEST_REJECTED, updater(verifier).check(ApplyMode.NOTIFY));
        assertEquals(1, transport.urls().stream().filter(u -> u.equals(manifestUrl("v26.2-r48"))).count(),
            "a corrupt state stops before any manifest download");
    }

    @Test
    void withoutAKeyNoPolicyStateIsWritten() throws Exception {
        listingOf(rel("v26.2-r48"));
        assertEquals(Outcome.NOTIFIED, updater(ReleaseSignatureVerifier.inactive()).check(ApplyMode.NOTIFY));
        assertFalse(Files.exists(root.resolve("auto_update").resolve(ReleasePolicyState.FILE)));
    }
}
