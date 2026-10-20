package dev.iyanz.aurora.engine.spark;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.lucko.spark.paper.common.SparkPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine spark: {@link FoliaSparkPlugin}. Building one starts a whole {@code SparkPlatform}
 * (plugin folder, config, scheduler), so only its reported spark version is covered here; the rest
 * of the class is delegation to the pieces tested next to this one.
 */
class FoliaSparkPluginTest {

    @Test
    void theReportedSparkVersionIsTheSparkJarOnTheClasspath() throws URISyntaxException {
        final Path jar = Path.of(SparkPlatform.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Matcher matcher = Pattern.compile("spark-paper-(\\d+(?:\\.\\d+)+)\\.jar").matcher(jar.getFileName().toString());
        assertTrue(matcher.matches(), "expected a versioned spark-paper jar, found " + jar.getFileName());

        assertEquals(matcher.group(1), FoliaSparkPlugin.SPARK_VERSION,
            "a literal here goes stale when the dependency is bumped, and spark reports it as its own version");
    }
}
