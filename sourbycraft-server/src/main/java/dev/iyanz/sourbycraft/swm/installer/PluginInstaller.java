package dev.iyanz.sourbycraft.swm.installer;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.logging.Logger;

public final class PluginInstaller {
    private static final Logger LOGGER = Logger.getLogger("SourbyCraft-SWM");
    private static final String RELEASES_URL = "https://github.com/YanIanZ/SourbyCraft/releases/download/%s/SourbyCraftSWM-%s.jar";

    public static void install(String pluginsDir) {
        if (!SourbyCraftConfig.swmEnabled || !SourbyCraftConfig.swmAutoInstall) return;
        Path jarPath = Path.of(pluginsDir, "SourbyCraftSWM.jar");
        if (Files.exists(jarPath)) return;

        String ver = SourbyCraftConfig.swmVersion;
        try {
            Files.createDirectories(Path.of(pluginsDir));
            String url = String.format(RELEASES_URL, ver, ver);
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("SourbyCraftSWM plugin v" + ver + " installed. Restart server to load.");
        } catch (IOException e) {
            LOGGER.warning("SWM plugin download failed: " + e.getMessage());
        }
    }

    private PluginInstaller() {}
}
