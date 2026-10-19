package dev.iyanz.sourbycraft.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BuildVersion} plus the tag/release-counter ordering in {@link SourbyUpdater} that depends on it. */
class BuildVersionTest {

    private static int cmp(String a, String b) {
        return Integer.signum(BuildVersion.parse(a).compareTo(BuildVersion.parse(b)));
    }

    // ------------------------------------------------------------------ parse

    @Test
    void nullAndBlankParseToTheUnknownSingleton() {
        assertSame(BuildVersion.UNKNOWN, BuildVersion.parse(null));
        assertSame(BuildVersion.UNKNOWN, BuildVersion.parse(""));
        assertSame(BuildVersion.UNKNOWN, BuildVersion.parse("   "));
        assertTrue(BuildVersion.UNKNOWN.isUnknown());
        assertEquals("", BuildVersion.UNKNOWN.toString());
    }

    @Test
    void tokensSplitOnDotAndDashAndNumbersBecomeIntegers() {
        BuildVersion v = BuildVersion.parse("44.2-hotfix1");
        assertEquals(List.of(44, 2, "hotfix1"), v.tokens());
        assertEquals("44.2-hotfix1", v.raw());
        assertEquals("44.2-hotfix1", v.toString());
        assertFalse(v.isUnknown());
    }

    @Test
    void rawIsTrimmedAndBlankSegmentsAreSkipped() {
        BuildVersion v = BuildVersion.parse("  43..1-  ");
        assertEquals("43..1-", v.raw());
        assertEquals(List.of(43, 1), v.tokens());
    }

    @Test
    void aSeparatorOnlyStringIsUnknownButKeepsItsRawForDisplay() {
        BuildVersion v = BuildVersion.parse("..-");
        assertTrue(v.isUnknown());
        assertEquals("..-", v.raw());
    }

    @Test
    void anOversizedNumberBecomesATextTokenInsteadOfThrowing() {
        BuildVersion v = BuildVersion.parse("99999999999");
        assertEquals(List.of("99999999999"), v.tokens());
    }

    @Test
    void tokensAreImmutable() {
        BuildVersion v = BuildVersion.parse("43.1");
        assertThrows(UnsupportedOperationException.class, () -> v.tokens().add(9));
    }

    // ------------------------------------------------------------------ ordering

    @Test
    void numericComponentsCompareAsNumbers() {
        assertEquals(1, cmp("10", "9"));
        assertEquals(1, cmp("43.10", "43.9"));
        assertEquals(-1, cmp("42", "43"));
        assertEquals(0, cmp("43", "43"));
    }

    @Test
    void moreComponentsIsNewerWhenTheSharedPrefixIsEqual() {
        assertEquals(1, cmp("43.1", "43"));
        assertEquals(1, cmp("44-hotfix", "44"));
        assertEquals(1, cmp("44.2-hotfix1", "44.2"));
        assertEquals(-1, cmp("43", "43.1"));
    }

    @Test
    void unlikeSemVerTrailingZeroIsNotIgnored() {
        // BuildVersion is a release COUNTER ordering: 43.0 has more components than 43, so it is newer.
        assertEquals(1, cmp("43.0", "43"));
        assertEquals(0, SemVer.compare("43.0", "43"), "SemVer, by contrast, pads with zeros");
    }

    @Test
    void aNumericComponentOutranksATextOneAtTheSamePosition() {
        assertEquals(1, cmp("44.1", "44-hotfix"));
        assertEquals(-1, cmp("44-hotfix", "44.1"));
    }

    @Test
    void textComponentsCompareCaseInsensitively() {
        assertEquals(0, cmp("44-HotFix", "44-hotfix"));
        assertEquals(1, cmp("44-hotfix2", "44-hotfix1"));
        assertEquals(-1, cmp("44-alpha", "44-beta"));
    }

    @Test
    void unknownIsOlderThanAnyRealVersionAndEqualToItself() {
        assertEquals(-1, cmp("", "1"));
        assertEquals(1, cmp("1", null));
        assertEquals(0, cmp(null, ""));
    }

    // ------------------------------------------------------------------ tag helpers built on it

    @Test
    void releaseCounterIsParsedFromTheTagIncludingHotfixForms() {
        assertEquals(List.of(48), SourbyUpdater.releaseVersionOf("v26.2-r48").tokens());
        assertEquals(List.of(43, 1), SourbyUpdater.releaseVersionOf("v26.2-r43.1").tokens());
        assertEquals(List.of(44, "hotfix"), SourbyUpdater.releaseVersionOf("v26.2-r44-hotfix").tokens());
        assertEquals(List.of(48), SourbyUpdater.releaseVersionOf("V26.2-R48").tokens(), "case-insensitive");
        assertEquals(List.of(48), SourbyUpdater.releaseVersionOf("v26.2-r48-DEV").tokens(), "channel stripped first");
    }

    @Test
    void tagsWithoutAReleaseCounterAreUnknown() {
        assertSame(BuildVersion.UNKNOWN, SourbyUpdater.releaseVersionOf(null));
        assertTrue(SourbyUpdater.releaseVersionOf("v26.2").isUnknown());
        assertTrue(SourbyUpdater.releaseVersionOf("v26.2-REL").isUnknown());
        assertTrue(SourbyUpdater.releaseVersionOf("nightly").isUnknown());
    }

    @Test
    void coreOfStripsPrefixChannelAndReleaseCounter() {
        assertEquals("26.2", SourbyUpdater.coreOf("v26.2-r48"));
        assertEquals("26.2", SourbyUpdater.coreOf("v26.2-r48-DEV"));
        assertEquals("26.2", SourbyUpdater.coreOf("26.2-REL"));
        assertEquals("26.2", SourbyUpdater.coreOf("26.2"));
        assertEquals("26.2-1.3", SourbyUpdater.coreOf("26.2-1.3-DEV"));
    }

    @Test
    void compareTagsOrdersReleaseCountersNumericallyNotLexically() {
        assertTrue(SourbyUpdater.compareTags("v26.2-r10", "v26.2-r9") > 0, "r10 is newer than r9");
        assertTrue(SourbyUpdater.compareTags("v26.2-r9", "v26.2-r10") < 0);
        assertTrue(SourbyUpdater.compareTags("v26.2-r43.1", "v26.2-r43") > 0, "hotfix is newer");
        assertTrue(SourbyUpdater.compareTags("v26.2-r44-hotfix", "v26.2-r44") > 0);
        assertEquals(0, SourbyUpdater.compareTags("v26.2-r48", "26.2-r48"));
    }

    @Test
    void compareTagsLetsTheMinecraftCoreWinOverTheReleaseCounter() {
        assertTrue(SourbyUpdater.compareTags("v26.3-r1", "v26.2-r99") > 0);
        assertTrue(SourbyUpdater.compareTags("v26.11-r1", "v26.2-r99") > 0);
    }

    @Test
    void isNewerThanRunningNeedsADifferentCoreOrAHigherKnownBuild() {
        BuildVersion own47 = BuildVersion.parse("47");
        assertTrue(SourbyUpdater.isNewerThanRunning("v26.2-r48", "26.2-REL", own47));
        assertFalse(SourbyUpdater.isNewerThanRunning("v26.2-r47", "26.2-REL", own47), "same build");
        assertFalse(SourbyUpdater.isNewerThanRunning("v26.2-r46", "26.2-REL", own47), "older build");
        assertTrue(SourbyUpdater.isNewerThanRunning("v26.2-r47.1", "26.2-REL", own47), "hotfix of the running build");
        assertTrue(SourbyUpdater.isNewerThanRunning("v26.3-r1", "26.2-REL", own47), "newer core wins regardless of counter");
        assertFalse(SourbyUpdater.isNewerThanRunning("v26.1-r99", "26.2-REL", own47), "older core never offered");
    }

    @Test
    void whenEitherSideHasNoReleaseCounterTheSameCoreIsNeverNewer() {
        assertFalse(SourbyUpdater.isNewerThanRunning("v26.2-r48", "26.2-REL", BuildVersion.UNKNOWN),
            "a jar that cannot say its build is never told to update within the same core");
        assertFalse(SourbyUpdater.isNewerThanRunning("v26.2", "26.2-REL", BuildVersion.parse("47")),
            "a tag without a counter is not newer than a known build");
    }
}
