package dev.iyanz.sourbycraft.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every file that opens network connections is listed, with the thread it runs on, in
 * docs/architecture/region-io-audit.md. A new one fails here until it is reviewed and added.
 */
class BlockingIoBoundaryTest {

    private static final Pattern NETWORK = Pattern.compile(
        "java\\.net\\.|HttpClient|URLConnection|\\.openStream\\(|new Socket\\(|InetAddress");

    private static final Set<String> AUDITED = Set.of(
        "dev/iyanz/sourbycraft/update/SourbyUpdater.java",
        "dev/iyanz/sourbycraft/update/ViaAutoUpdate.java",
        "dev/iyanz/sourbycraft/command/SpeedtestCommand.java",
        "dev/iyanz/sourbycraft/util/GeoUtil.java",
        "dev/iyanz/sourbycraft/bootstrap/LibDownloader.java",
        "dev/iyanz/sourbycraft/bootstrap/PluginProvisioner.java",
        "dev/iyanz/sourbycraft/awf/redis/RedisClient.java");

    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            final Path candidate = dir.resolve("sourbycraft-server/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            if (Files.isDirectory(dir.resolve("src/main/java/dev/iyanz/sourbycraft"))) return dir.resolve("src/main/java");
        }
        throw new IllegalStateException("could not locate sourbycraft-server/src/main/java");
    }

    @Test
    void networkIoIsConfinedToAuditedFiles() throws IOException {
        final Path root = sourceRoot();
        final Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("dev/iyanz/sourbycraft"))) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (NETWORK.matcher(Files.readString(file)).find()) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(new TreeSet<>(AUDITED), found,
            "network I/O appeared in or left a file; update docs/architecture/region-io-audit.md and AUDITED");
    }
}
