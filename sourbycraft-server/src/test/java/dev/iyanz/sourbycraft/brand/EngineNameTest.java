package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Since Build 47 the release codename names the release ("Aurora Nexus"), not the engine. The
 * engine is Aurora whatever the codename, and the codename is shown separately.
 */
public class EngineNameTest {

    private static BuildInfo withCodename(final String codename) {
        return new BuildInfo("26.2-REL", "47", "47", "26.2", codename, "tagline", "");
    }

    @Test
    void theEngineIsAuroraWhateverTheCodename() {
        assertEquals("Aurora", withCodename("aurora-nexus").engineName());
        assertEquals("Aurora", withCodename("cookies").engineName());
        assertEquals("Aurora", withCodename("").engineName());
        assertEquals("Aurora", withCodename(null).engineName());
        assertEquals("Aurora", withCodename("dev").engineName());
    }

    @Test
    void theCodenameIsDisplayedAsWords() {
        assertEquals("Aurora Nexus", withCodename("aurora-nexus").codenameDisplay());
        assertEquals("Cookies", withCodename("cookies").codenameDisplay());
        assertEquals("A", withCodename("a").codenameDisplay());
    }

    @Test
    void anUnstampedBuildIsADevelopmentBuild() {
        assertEquals("Development", withCodename("").codenameDisplay());
        assertEquals("Development", withCodename("   ").codenameDisplay());
        assertEquals("Development", withCodename("dev").codenameDisplay());
        assertEquals("Development", withCodename(null).codenameDisplay());
    }
}
