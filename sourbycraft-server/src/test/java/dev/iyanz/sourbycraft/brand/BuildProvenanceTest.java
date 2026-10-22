package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Build provenance read from {@code sourbycraft-build.properties} (TODO Inovasi "provenance manifest"). */
class BuildProvenanceTest {

    private static BuildProvenance parse(String text) {
        return BuildProvenance.loadFrom(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void keysAreReadInDisplayOrderWithUnknownKeysLast() {
        BuildProvenance p = parse("""
            gitCommit=%s
            treeHash=%s
            dirty=false
            provenance.Zz-Future=%s
            provenance.Jdk=Eclipse Adoptium 25.0.1+8-LTS
            provenance.Paper-Features=%s
            provenance.Minecraft-Features=%s
            unrelated=1
            """.formatted("a".repeat(40), HASH, HASH, HASH, HASH));
        assertEquals(List.of("Minecraft-Features", "Paper-Features", "Jdk", "Zz-Future"), List.copyOf(p.entries().keySet()));
        assertFalse(p.dirty());
        assertEquals("a".repeat(40), p.commit());
        assertFalse(p.isEmpty());
    }

    @Test
    void displayRowsShortenHashesButKeepTheJdkWhole() {
        BuildProvenance p = parse("gitCommit=" + "a".repeat(40) + "\ntreeHash=" + HASH + "\ndirty=true\n"
            + "provenance.Engine-Baseline=" + HASH + "\nprovenance.Jdk=Eclipse Adoptium 25.0.1+8-LTS\n");
        List<String[]> rows = p.displayRows();
        assertArrayEquals(new String[] {"Source", "aaaaaaaaaaaa tree 0123456789ab (dirty)"}, rows.get(0));
        assertArrayEquals(new String[] {"Engine baseline manifest", "0123456789ab"}, rows.get(1));
        assertArrayEquals(new String[] {"Built with JDK", "Eclipse Adoptium 25.0.1+8-LTS"}, rows.get(2));
        assertEquals(3, rows.size());
    }

    @Test
    void aBuildWithoutProvenanceSaysSoAndNeverThrows() {
        BuildProvenance none = BuildProvenance.loadFrom(null);
        assertTrue(none.isEmpty());
        assertTrue(none.dirty(), "an unknown source is never reported clean");
        List<String[]> rows = none.displayRows();
        assertArrayEquals(new String[] {"Source", "unknown tree unknown (dirty)"}, rows.get(0));
        assertArrayEquals(new String[] {"Provenance", "not recorded in this build"}, rows.get(1));
        assertEquals("unknown", parse("provenance.Toolchain-Lock=unknown\n").displayRows().get(1)[1]);
    }

    @Test
    void theTestClasspathResourceIsReadWhenPresent() {
        // processResources ran writeBuildInfo; when it did, the provenance block is in it.
        BuildProvenance loaded = BuildProvenance.load();
        assertSame(loaded, BuildProvenance.load(), "cached");
        if (BuildProvenance.class.getResource("/META-INF/sourbycraft-build.properties") != null) {
            assertTrue(loaded.entries().containsKey("Jdk"), loaded.entries().toString());
            assertTrue(loaded.entries().containsKey("Minecraft-Features"), loaded.entries().toString());
        }
    }
}
