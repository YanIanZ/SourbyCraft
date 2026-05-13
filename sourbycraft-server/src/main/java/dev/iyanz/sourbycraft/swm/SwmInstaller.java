package dev.iyanz.sourbycraft.swm;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SwmInstaller {
    private static final Logger LOGGER = Logger.getLogger("SourbyCraft-SWM");
    private static final String SWM_URL = "https://github.com/InfernalSuite/AdvancedSlimeWorldManager/releases/download/%s/AdvancedSlimeWorldManager-%s.jar";

    public static void install(String pluginsDir) {
        if (!SourbyCraftConfig.swmEnabled || !SourbyCraftConfig.swmAutoInstall) return;

        Path jarPath = Path.of(pluginsDir, "SlimeWorldManager.jar");
        if (Files.exists(jarPath)) {
            LOGGER.log(Level.INFO, "SWM jar already installed");
            return;
        }

        String version = SourbyCraftConfig.swmVersion;
        String url = String.format(SWM_URL, version, version);
        LOGGER.log(Level.INFO, "Downloading SlimeWorldManager v" + version + "...");

        try {
            Files.createDirectories(Path.of(pluginsDir));
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "SourbyCraft-SWM-Installer");

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.log(Level.INFO, "SlimeWorldManager installed. Restart server to load.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to download SWM: " + e.getMessage());
        }
    }

    public static boolean isInstalled() {
        return Files.exists(Path.of("plugins/SlimeWorldManager.jar"));
    }

    private SwmInstaller() {}
}
