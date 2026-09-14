package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The banner's engine name comes from the release codename, so the two cannot drift. */
public class EngineNameTest {

    private static BuildInfo withCodename(final String codename) {
        return new BuildInfo("26.2-REL", "45c", "45", "26.2", codename, "tagline", "");
    }

    @Test
    void capitalizesTheReleaseCodename() {
        assertEquals("Aurora", withCodename("aurora").engineName());
        assertEquals("Cookies", withCodename("cookies").engineName());
    }

    @Test
    void alreadyCapitalizedCodenamesSurvive() {
        assertEquals("Aurora", withCodename("Aurora").engineName());
    }

    @Test
    void fallsBackToTheProductNameWhenNoCodenameIsStamped() {
        // An unstamped or development build must not print an empty engine name.
        assertEquals("SourbyCraft", withCodename("").engineName());
        assertEquals("SourbyCraft", withCodename("   ").engineName());
        assertEquals("SourbyCraft", withCodename("dev").engineName());
        assertEquals("SourbyCraft", withCodename(null).engineName());
    }

    @Test
    void singleCharacterCodenameDoesNotOverrun() {
        assertEquals("A", withCodename("a").engineName());
    }
}
