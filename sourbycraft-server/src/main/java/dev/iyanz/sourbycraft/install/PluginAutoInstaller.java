package dev.iyanz.sourbycraft.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PluginAutoInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");

    public record Result(int installedCount, int skippedCount, int failedCount) {}

    private PluginAutoInstaller() {}

    public static Result installFromVariant(String variant, Path pluginsDir) {
        String resource = "/META-INF/sourbycraft-plugins/" + variant + ".yml";
        try (InputStream in = PluginAutoInstaller.class.getResourceAsStream(resource)) {
            List<PluginEntry> entries = PluginManifest.parse(in);
            return installAll(entries, pluginsDir);
        } catch (IOException e) {
            LOGGER.warn("[install] Failed to read manifest {}: {}", resource, e.getMessage());
            return new Result(0, 0, 0);
        }
    }

    public static Result installAll(List<PluginEntry> entries, Path pluginsDir) {
        int installed = 0, skipped = 0, failed = 0;
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            LOGGER.warn("[install] Cannot create plugins dir: {}", e.getMessage());
            return new Result(0, 0, entries.size());
        }
        for (PluginEntry e : entries) {
            if (PluginExistenceCheck.isInstalled(pluginsDir, e.name())) {
                LOGGER.info("[install] {} already present, skipping", e.name());
                skipped++;
                continue;
            }
            try {
                Path out = PluginDownloader.download(e, pluginsDir);
                if (out != null) {
                    LOGGER.info("[install] downloaded {} -> {}", e.name(), out.getFileName());
                    installed++;
                } else {
                    LOGGER.warn("[install] {} download returned no file", e.name());
                    failed++;
                }
            } catch (Exception ex) {
                LOGGER.warn("[install] {} failed: {}", e.name(), ex.getMessage());
                failed++;
            }
        }
        return new Result(installed, skipped, failed);
    }
}
