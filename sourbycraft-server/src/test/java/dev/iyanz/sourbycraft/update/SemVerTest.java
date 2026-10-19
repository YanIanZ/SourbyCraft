package dev.iyanz.sourbycraft.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemVerTest {

    // ------------------------------------------------------------------ stripChannel

    @Test
    void stripChannelRemovesALeadingVAndATrailingChannelInAnyCase() {
        assertEquals("26.2", SemVer.stripChannel("v26.2-REL"));
        assertEquals("26.2", SemVer.stripChannel("V26.2-dev"));
        assertEquals("26.11", SemVer.stripChannel("26.11-Exp"));
        assertEquals("26.2", SemVer.stripChannel("  26.2-REL  "));
        assertEquals("26.2", SemVer.stripChannel("26.2"));
    }

    @Test
    void stripChannelKeepsTheInternalDashOfADevCore() {
        assertEquals("26.2-1.3", SemVer.stripChannel("26.2-1.3-DEV"));
        assertEquals("26.2-r48", SemVer.stripChannel("v26.2-r48"), "a release counter is not a channel");
    }

    @Test
    void stripChannelLeavesUnknownSuffixesAndDegenerateInputAlone() {
        assertEquals("26.2-hotfix", SemVer.stripChannel("26.2-hotfix"));
        assertEquals("-REL", SemVer.stripChannel("-REL"), "a dash at index 0 is not a suffix separator");
        assertEquals("REL", SemVer.stripChannel("REL"));
        assertEquals("", SemVer.stripChannel(null));
        assertEquals("", SemVer.stripChannel(""));
        assertEquals("", SemVer.stripChannel("   "));
        assertEquals("", SemVer.stripChannel("v"), "only the prefix");
    }

    // ------------------------------------------------------------------ ordering

    @Test
    void componentsCompareNumericallyNotAsStrings() {
        assertTrue(SemVer.compare("26.11", "26.2") > 0, "26.11 is newer than 26.2");
        assertTrue(SemVer.compare("26.2", "26.11") < 0);
        assertTrue(SemVer.compare("26.100", "26.99") > 0);
        assertTrue(SemVer.compare("27", "26.99.99") > 0);
    }

    @Test
    void equalVersionsCompareZeroAndTrailingZeroComponentsAreIgnored() {
        assertEquals(0, SemVer.compare("26.2", "26.2"));
        assertEquals(0, SemVer.compare("26.2", "26.2.0"));
        assertEquals(0, SemVer.compare("26.2.0.0", "26.2"));
    }

    @Test
    void aLongerVersionWithANonZeroTailIsNewer() {
        assertTrue(SemVer.compare("26.2.1", "26.2") > 0);
        assertTrue(SemVer.compare("26.2", "26.2.1") < 0);
        assertTrue(SemVer.compare("26.2.0.1", "26.2") > 0);
    }

    @Test
    void channelAndPrefixAreIgnoredByCompare() {
        assertEquals(0, SemVer.compare("26.2-REL", "26.2-DEV"));
        assertEquals(0, SemVer.compare("v26.2-EXP", "26.2"));
        assertTrue(SemVer.compare("v26.3-DEV", "26.2-REL") > 0);
    }

    @Test
    void devCoresOrderAcrossAMinorBumpNumerically() {
        // 26.<mc>-<release>-DEV: splitting on '-' keeps 26.10-1.0 above 26.9-1.0 (a lexical
        // compare of "10-1" vs "9-1" would invert it).
        assertTrue(SemVer.compare("26.10-1.0-DEV", "26.9-1.0-DEV") > 0);
        assertTrue(SemVer.compare("26.2-1.3-DEV", "26.2-1.2-DEV") > 0);
        assertTrue(SemVer.compare("26.2-1.10-DEV", "26.2-1.9-DEV") > 0);
    }

    @Test
    void aNumericComponentOutranksAnAlphaOneAtTheSamePosition() {
        assertTrue(SemVer.compare("26.2.1", "26.2.rc") > 0);
        assertTrue(SemVer.compare("26.2.rc", "26.2.1") < 0);
    }

    @Test
    void alphaComponentsCompareLexicallyIgnoringCase() {
        assertTrue(SemVer.compare("26.2.beta", "26.2.alpha") > 0);
        assertEquals(0, SemVer.compare("26.2.RC", "26.2.rc"));
    }

    @Test
    void compareIsAntisymmetricAndTransitiveOverARepresentativeSet() {
        String[] ordered = {"25.9", "26.1", "26.2", "26.2.1", "26.2.1.3", "26.10", "26.11", "27"};
        for (int i = 0; i < ordered.length; i++) {
            for (int j = 0; j < ordered.length; j++) {
                int expected = Integer.signum(Integer.compare(i, j));
                assertEquals(expected, Integer.signum(SemVer.compare(ordered[i], ordered[j])),
                    ordered[i] + " vs " + ordered[j]);
            }
        }
    }

    @Test
    void isNewerIsStrict() {
        assertTrue(SemVer.isNewer("26.3", "26.2"));
        assertFalse(SemVer.isNewer("26.2", "26.2"));
        assertFalse(SemVer.isNewer("26.2", "26.3"));
        assertFalse(SemVer.isNewer("26.2-DEV", "26.2-REL"), "channel is not an ordering input");
    }

    // ------------------------------------------------------------------ malformed input never throws

    @Test
    void malformedInputNeverThrows() {
        String[] junk = {null, "", " ", "v", "-", "--", ".", "..", "26..2", "26.2.", ".26", "1.2.x", "abc",
            "99999999999999999999.1", "26.2-", "-26.2", "v-REL", "26.2-REL-REL", "\u0000", "26.2\n"};
        for (String a : junk) {
            for (String b : junk) {
                assertDoesNotThrow(() -> SemVer.compare(a, b), a + " vs " + b);
                assertDoesNotThrow(() -> SemVer.isNewer(a, b));
            }
            assertDoesNotThrow(() -> SemVer.stripChannel(a));
        }
    }

    @Test
    void emptyAndNullVersionsAreEqualAndNeverNewerThanARealVersion() {
        assertEquals(0, SemVer.compare(null, ""));
        assertEquals(0, SemVer.compare("", "  "));
        assertTrue(SemVer.compare("", "26.2") < 0);
        assertFalse(SemVer.isNewer("", "26.2"));
        assertFalse(SemVer.isNewer(null, "26.2"));
        assertTrue(SemVer.isNewer("26.2", ""), "anything real is newer than an unknown running version");
    }

    @Test
    void anAlphaVersionRanksBelowANumericOne() {
        assertTrue(SemVer.compare("abc", "26.2") < 0);
        assertTrue(SemVer.compare("26.2", "abc") > 0);
        assertFalse(SemVer.isNewer("dev", "26.2"));
    }

    @Test
    void integerOverflowFallsBackToALexicalComponentWithoutThrowing() {
        // 20 digits does not fit an int; it becomes an alpha-style component (ranks below numbers).
        assertTrue(SemVer.compare("26.2", "26.99999999999999999999") > 0);
    }
}
