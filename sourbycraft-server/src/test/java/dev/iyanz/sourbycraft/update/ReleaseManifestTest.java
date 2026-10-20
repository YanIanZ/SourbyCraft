package dev.iyanz.sourbycraft.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Parsing and binding of the signed release manifest (the signature itself: SourbyUpdaterManifestTest). */
class ReleaseManifestTest {

    static final String SHA = "ab".repeat(32);

    private static ReleaseManifest parse(String json) throws Exception {
        return ReleaseManifest.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String schema, String tag, String asset, String sha, String rollout, String yanked) {
        return "{\"schema\":" + schema + ",\"tag\":" + tag + ",\"asset\":" + asset + ",\"sha256\":" + sha
            + ",\"rollout_percent\":" + rollout + ",\"yanked\":" + yanked
            + ",\"policy_revision\":1,\"expires_at\":\"2099-01-01T00:00:00Z\"}";
    }

    @Test
    void aWellFormedManifestParses() throws Exception {
        ReleaseManifest m = parse(SourbyUpdaterCheckTest.manifestJson("v26.2-r48", "SourbyCraft-26.2-REL.jar",
            SHA.toUpperCase(), 25, List.of("v26.2-r47", "v26.2-r46")));
        assertEquals("v26.2-r48", m.tag());
        assertEquals(SHA, m.sha256(), "lower-cased");
        assertEquals(25, m.rolloutPercent());
        assertEquals(Set.of("v26.2-r47", "v26.2-r46"), m.yanked());
        m.requireBoundTo("v26.2-r48", "SourbyCraft-26.2-REL.jar", SHA);
    }

    @Test
    void everyMalformedShapeIsRefused() {
        String ok = "\"v26.2-r48\"";
        String asset = "\"a.jar\"";
        String sha = "\"" + SHA + "\"";
        List<String> bad = List.of(
            "[]", "not json", "{}",
            json("2", ok, asset, sha, "100", "[]"),                 // unknown schema
            json("\"1\"", ok, asset, sha, "100", "[]"),             // schema as string
            json("1", "\"\"", asset, sha, "100", "[]"),             // blank tag
            json("1", "5", asset, sha, "100", "[]"),                // tag not a string
            json("1", ok, asset, "\"abc\"", "100", "[]"),           // short sha
            json("1", ok, asset, sha, "101", "[]"),                 // > 100
            json("1", ok, asset, sha, "-1", "[]"),                  // < 0
            json("1", ok, asset, sha, "50.5", "[]"),                // fractional
            json("1", ok, asset, sha, "\"50\"", "[]"),              // string
            json("1", ok, asset, sha, "50", "\"v26.2-r47\""),       // yanked not an array
            json("1", ok, asset, sha, "50", "[1]"),                 // non-string entry
            json("1", ok, asset, sha, "50", "[\" \"]"));            // blank entry
        for (String b : bad) {
            assertThrows(ReleaseManifest.InvalidManifestException.class, () -> parse(b), b);
        }
        assertThrows(ReleaseManifest.InvalidManifestException.class,
            () -> ReleaseManifest.parse(new byte[] {(byte) 0xC3, (byte) 0x28}), "not UTF-8");
        assertThrows(ReleaseManifest.InvalidManifestException.class,
            () -> ReleaseManifest.parse(new byte[ReleaseManifest.MAX_BYTES + 1]), "oversize");
    }

    @Test
    void theRolloutBoundsAreInclusive() throws Exception {
        assertEquals(0, parse(SourbyUpdaterCheckTest.manifestJson("t", "a", SHA, 0, List.of())).rolloutPercent());
        assertEquals(100, parse(SourbyUpdaterCheckTest.manifestJson("t", "a", SHA, 100, List.of())).rolloutPercent());
    }

    @Test
    void aManifestIsBoundToOneReleaseOneAssetAndOneDigest() throws Exception {
        ReleaseManifest m = parse(SourbyUpdaterCheckTest.manifestJson("v26.2-r48", "a.jar", SHA, 100, List.of()));
        assertThrows(ReleaseManifest.InvalidManifestException.class, () -> m.requireBoundTo("v26.2-r49", "a.jar", SHA));
        assertThrows(ReleaseManifest.InvalidManifestException.class, () -> m.requireBoundTo("v26.2-r48", "b.jar", SHA));
        assertThrows(ReleaseManifest.InvalidManifestException.class, () -> m.requireBoundTo("v26.2-r48", "a.jar", "cd".repeat(32)));
        assertThrows(ReleaseManifest.InvalidManifestException.class, () -> m.requireBoundTo("v26.2-r48", "a.jar", null));
    }

    @Test
    void theShapeScriptsReleaseManifestPyWritesParses() throws Exception {
        // Byte-for-byte the layout of json.dumps(manifest, indent=2) + "\n" in scripts/release_manifest.py.
        String written = """
            {
              "schema": 1,
              "tag": "v26.2-r48",
              "asset": "SourbyCraft-26.2-REL.jar",
              "sha256": "%s",
              "rollout_percent": 25,
              "yanked": [
                "v26.2-r46"
              ],
              "policy_revision": 1792454400,
              "issued_at": "2026-10-20T00:00:00Z",
              "expires_at": "2027-01-18T00:00:00Z"
            }
            """.formatted(SHA);
        ReleaseManifest m = parse(written);
        assertEquals(25, m.rolloutPercent());
        assertEquals(Set.of("v26.2-r46"), m.yanked());
        assertEquals(1792454400L, m.policyRevision());
        assertEquals(java.time.Instant.parse("2027-01-18T00:00:00Z"), m.expiresAt());
    }

    @Test
    void revisionAndExpiryAreRequiredAndChecked() throws Exception {
        String base = SourbyUpdaterCheckTest.manifestJson("t", "a", SHA, 100, List.of(), 5, "2027-01-01T00:00:00Z");
        ReleaseManifest m = parse(base);
        assertFalse(m.expiredAt(java.time.Instant.parse("2027-01-01T00:00:00Z")), "the expiry instant itself is still valid");
        assertTrue(m.expiredAt(java.time.Instant.parse("2027-01-01T00:00:01Z")));
        for (String bad : List.of(
            base.replace(",\"policy_revision\":5", ""),
            base.replace("\"policy_revision\":5", "\"policy_revision\":0"),
            base.replace("\"policy_revision\":5", "\"policy_revision\":\"5\""),
            base.replace("\"policy_revision\":5", "\"policy_revision\":5.5"),
            base.replace(",\"expires_at\":\"2027-01-01T00:00:00Z\"", ""),
            base.replace("2027-01-01T00:00:00Z", "next year"))) {
            assertThrows(ReleaseManifest.InvalidManifestException.class, () -> parse(bad), bad);
        }
    }

    @Test
    void duplicateMemberNamesAreRefusedIncludingEscapedSpellings() throws Exception {
        String base = SourbyUpdaterCheckTest.manifestJson("t", "a", SHA, 0, List.of("v26.2-r1"));
        parse(base);
        String open = base.substring(0, base.length() - 1);
        List<String> ambiguous = List.of(
            open + ",\"rollout_percent\":100}",
            open + ",\"rollout\\u005fpercent\":100}",
            open + ",\"yanked\":[]}",
            open + ",\"\\u0079anked\":[]}",
            open + ",\"extra\":{\"x\":1,\"x\":2}}",
            base + " {}",
            "{'schema':1}");
        for (String text : ambiguous) {
            ReleaseManifest.InvalidManifestException e =
                assertThrows(ReleaseManifest.InvalidManifestException.class, () -> parse(text), text);
            assertTrue(e.getMessage().contains("duplicate") || e.getMessage().contains("strict") || e.getMessage().contains("after"),
                e.getMessage());
        }
    }
}
