package dev.iyanz.sourbycraft.swm.installer;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;

public final class PluginInstaller {
    private static final String RELEASES_URL = "https://github.com/YanIanZ/SourbyCraft/releases/download/%s/SourbyCraftSWM-%s.jar";
    private static final String SWM_PLUGIN_VERSION = "3.0.0";

    public static void install(String pluginsDir) {
        if (!SourbyCraftConfig.swmEnabled || !SourbyCraftConfig.swmAutoInstall) return;
        Path jarPath = Path.of(pluginsDir, "SourbyCraftSWM.jar");
        if (Files.exists(jarPath)) return;

        String tag = SourbyCraftConfig.swmVersion;
        try {
            Files.createDirectories(Path.of(pluginsDir));
            String url = String.format(RELEASES_URL, tag, SWM_PLUGIN_VERSION);
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            SourbyLogger.info("SourbyCraftSWM plugin v" + SWM_PLUGIN_VERSION + " installed (" + tag + "). Restart server to load.");
        } catch (IOException e) {
            SourbyLogger.warn("SWM plugin download failed: " + e.getMessage());
        }
    }

    private PluginInstaller() {}
}
