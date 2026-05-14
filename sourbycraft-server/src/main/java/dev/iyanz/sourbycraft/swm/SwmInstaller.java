package dev.iyanz.sourbycraft.swm;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.logging.*;

public final class SwmInstaller {
    private static final Logger LOGGER = Logger.getLogger("SourbyCraft-SWM");
    private static final String ASW_URL = "https://github.com/InfernalSuite/AdvancedSlimePaper/releases/download/%s/aspaper-%s.jar";

    public static void install(String pluginsDir) {
        if (!SourbyCraftConfig.swmEnabled || !SourbyCraftConfig.swmAutoInstall) return;
        Path jarPath = Path.of(pluginsDir, "aspaper.jar");
        if (Files.exists(jarPath)) return;

        String ver = SourbyCraftConfig.swmVersion;
        try {
            Files.createDirectories(Path.of(pluginsDir));
            HttpURLConnection c = (HttpURLConnection) URI.create(String.format(ASW_URL, ver, ver)).toURL().openConnection();
            c.setConnectTimeout(30000); c.setReadTimeout(60000);
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("ASPaper SWM installed. Restart to load.");
        } catch (IOException e) {
            LOGGER.warning("SWM download failed: " + e.getMessage());
        }
    }

    public static boolean isInstalled() {
        return Files.exists(Path.of("plugins/aspaper.jar"));
    }

    private SwmInstaller() {}
}
