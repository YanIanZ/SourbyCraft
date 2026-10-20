package io.canvasmc.canvas.simd;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.bukkit.map.MapPalette;
import org.junit.jupiter.api.Test;

/**
 * {@link VectorMapPalette} needs the {@code jdk.incubator.vector} module, which is not resolved for a plain
 * classpath JVM and which this module's test task does not add (build files are out of scope here). The checks
 * therefore run in a child JVM started with {@code --add-modules jdk.incubator.vector} (see {@link Child}); the
 * test is skipped, not passed, if that module or the child cannot be used.
 */
class VectorMapPaletteTest {
    private static final int EXPECTED_AGREEMENT_PERCENT = 99;
    private static final long CHILD_TIMEOUT_SECONDS = 60;

    @Test
    void vectorizedMatchingAgreesWithTheScalarPaletteMatcher() throws Exception {
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        assumeTrue(new File(javaBin).canExecute(), "no java launcher at " + javaBin);
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()
                || ModuleFinderHolder.systemHasVectorModule(),
            "this JDK does not ship jdk.incubator.vector");
        final String classpath = System.getProperty("java.class.path");
        assumeTrue(classpath != null && !classpath.isBlank(), "no java.class.path");

        final List<String> command = new ArrayList<>(List.of(javaBin, "--add-modules", "jdk.incubator.vector",
            "-Djava.awt.headless=true", "-cp", classpath, Child.class.getName()));
        // Output goes to a file, not a pipe: nothing can block on a full pipe, and the deadline below always applies.
        final java.nio.file.Path log = java.nio.file.Files.createTempFile("vector-palette-child", ".log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        final String text;
        try {
            final boolean finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            text = new String(java.nio.file.Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertTrue(finished, "child JVM did not finish within " + CHILD_TIMEOUT_SECONDS + " s and was killed; output so far:\n" + text);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS); // reap it
            }
            java.nio.file.Files.deleteIfExists(log);
        }
        // A classpath that the test worker does not pass on shows up as a launch error, not as an assertion.
        assumeTrue(!text.contains("Could not find or load main class") && !text.contains("ClassNotFoundException: "
            + Child.class.getName()), "child JVM could not see the test classpath:\n" + text);
        assertEquals(0, process.exitValue(), text);
        assertTrue(text.contains("CHILD-OK"), text);
    }

    private static final class ModuleFinderHolder {
        static boolean systemHasVectorModule() {
            return java.lang.module.ModuleFinder.ofSystem().find("jdk.incubator.vector").isPresent();
        }
    }

    /** Runs with the vector module on the module path; exits non-zero with a message on the first failed check. */
    public static final class Child {
        @SuppressWarnings("deprecation")
        public static void main(final String[] args) {
            try {
                run();
                System.out.println("CHILD-OK");
            } catch (final Throwable t) {
                t.printStackTrace(System.out);
                System.exit(1);
            }
        }

        private static byte scalar(final int argb) {
            return MapPalette.matchColor(new Color(argb, true));
        }

        private static void check(final boolean condition, final String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }

        private static void run() {
            final int species = jdk.incubator.vector.IntVector.SPECIES_PREFERRED.length();
            System.out.println("species length " + species);

            // every opaque palette entry (index >= 4) must map to the first entry with that exact colour
            final List<Integer> exact = new ArrayList<>();
            for (int c = 4; c < MapPalette.colors.length; c++) {
                exact.add(MapPalette.colors[c].getRGB() | 0xFF000000);
            }
            for (final int length : new int[]{0, 1, Math.max(1, species - 1), species, species + 1, 3 * species + 2, exact.size()}) {
                final int[] in = new int[length];
                for (int i = 0; i < length; i++) {
                    in[i] = exact.get(i % exact.size());
                }
                final byte[] out = new byte[length + 3];
                Arrays.fill(out, (byte) 0x55);
                VectorMapPalette.matchColorVectorized(in, out);
                for (int i = 0; i < length; i++) {
                    check(out[i] == scalar(in[i]), "length " + length + " pixel " + i + " vector=" + out[i] + " scalar=" + scalar(in[i]));
                }
                for (int i = length; i < out.length; i++) {
                    check(out[i] == 0x55, "wrote past the input length at " + i);
                }
            }

            // transparency threshold: alpha < 128 -> 0, alpha >= 128 -> a palette entry (never 0 for an opaque colour)
            final int size = 4 * species + 3;
            final int[] alphaIn = new int[size];
            for (int i = 0; i < size; i++) {
                final int alpha = (i % 2 == 0) ? 127 : 128;
                alphaIn[i] = (alpha << 24) | 0x00FF8000;
            }
            final byte[] alphaOut = new byte[size];
            VectorMapPalette.matchColorVectorized(alphaIn, alphaOut);
            for (int i = 0; i < size; i++) {
                check(alphaOut[i] == scalar(alphaIn[i]), "alpha pixel " + i + " vector=" + alphaOut[i] + " scalar=" + scalar(alphaIn[i]));
                check((alphaOut[i] == 0) == (i % 2 == 0), "alpha threshold wrong at " + i);
            }

            // arbitrary colours: float32 vs float64 may differ on near ties, but not often
            final Random random = new Random(42);
            final int count = 20_000;
            final int[] randomIn = new int[count];
            for (int i = 0; i < count; i++) {
                randomIn[i] = 0xFF000000 | random.nextInt(1 << 24);
            }
            final byte[] randomOut = new byte[count];
            VectorMapPalette.matchColorVectorized(randomIn, randomOut);
            int same = 0;
            for (int i = 0; i < count; i++) {
                if (randomOut[i] == scalar(randomIn[i])) {
                    same++;
                }
            }
            final int percent = (int) (100L * same / count);
            System.out.println("random agreement " + same + "/" + count);
            check(percent >= EXPECTED_AGREEMENT_PERCENT, "agreement only " + same + "/" + count);
        }
    }
}
