package dev.iyanz.sourbycraft.swm.installer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Auto-install + auto-update for SourbyCraftSWM plugin.
 *
 * v9.22: queries GitHub Releases API for latest version, compares against
 * locally installed jar's manifest Implementation-Version, downloads if newer.
 */
public final class PluginInstaller {

    private static final String GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/YanIanZ/SourbyCraft/releases/latest";
    private static final String JAR_NAME = "SourbyCraftSWM.jar";

    public static void install(String pluginsDir) {
        // SourbyCraft v9.24 — always log entry for diagnosis
        SourbyLogger.info("PluginInstaller.install() called: swmEnabled=" + SourbyCraftConfig.swmEnabled
            + " swmAutoInstall=" + SourbyCraftConfig.swmAutoInstall
            + " swmAutoUpdate=" + SourbyCraftConfig.swmAutoUpdate);
        if (!SourbyCraftConfig.swmEnabled || !SourbyCraftConfig.swmAutoInstall) {
            SourbyLogger.info("PluginInstaller skipped (enabled=" + SourbyCraftConfig.swmEnabled
                + " autoInstall=" + SourbyCraftConfig.swmAutoInstall + ")");
            return;
        }
        Path jarPath = Path.of(pluginsDir, JAR_NAME);

        try {
            Files.createDirectories(Path.of(pluginsDir));

            String localVersion = jarVersion(jarPath);
            ReleaseInfo latest = null;
            if (SourbyCraftConfig.swmAutoUpdate) {
                latest = fetchLatestRelease();
            }

            if (Files.exists(jarPath)) {
                if (latest == null) {
                    SourbyLogger.info("SourbyCraftSWM installed (v" + (localVersion != null ? localVersion : "?") + "). Update check skipped.");
                    return;
                }
                if (localVersion != null && localVersion.equals(latest.version)) {
                    SourbyLogger.info("SourbyCraftSWM up-to-date (v" + localVersion + ").");
                    return;
                }
                SourbyLogger.info("SourbyCraftSWM update available: v" + localVersion + " -> v" + latest.version);
            } else {
                if (latest == null) {
                    SourbyLogger.warn("SourbyCraftSWM not installed and update check failed; enable swm.auto-update or install manually.");
                    return;
                }
                SourbyLogger.info("Installing SourbyCraftSWM v" + latest.version + "...");
            }

            download(latest.downloadUrl, jarPath);
            SourbyLogger.info("SourbyCraftSWM v" + latest.version + " installed at " + jarPath + ". Restart to load.");
        } catch (IOException e) {
            SourbyLogger.warn("SourbyCraftSWM install/update failed: " + e.getMessage());
        }
    }

    /** GitHub Releases /latest endpoint -> newest tag + matching jar asset URL. */
    private static ReleaseInfo fetchLatestRelease() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(GITHUB_LATEST_RELEASE_API).toURL().openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "SourbyCraft");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            int code = c.getResponseCode();
            if (code != 200) {
                SourbyLogger.warn("GitHub API returned " + code + " for SWM release check");
                return null;
            }
            try (InputStreamReader r = new InputStreamReader(c.getInputStream())) {
                JsonObject json = new Gson().fromJson(r, JsonObject.class);
                String tag = json.get("tag_name").getAsString();
                JsonArray assets = json.getAsJsonArray("assets");
                String downloadUrl = null;
                for (int i = 0; i < assets.size(); i++) {
                    JsonObject a = assets.get(i).getAsJsonObject();
                    String name = a.get("name").getAsString();
                    if (name.toLowerCase().contains("swm") && name.endsWith(".jar")) {
                        downloadUrl = a.get("browser_download_url").getAsString();
                        break;
                    }
                }
                if (downloadUrl == null) {
                    SourbyLogger.warn("No SWM jar asset in latest release " + tag);
                    return null;
                }
                return new ReleaseInfo(tag, downloadUrl);
            }
        } catch (IOException e) {
            SourbyLogger.warn("GitHub API fetch failed: " + e.getMessage());
            return null;
        }
    }

    private static void download(String url, Path target) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SourbyCraft");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Read jar's manifest Implementation-Version, or null if unreadable. */
    private static String jarVersion(Path jarPath) {
        if (!Files.exists(jarPath)) return null;
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            Manifest m = jf.getManifest();
            if (m == null) return null;
            return m.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }

    private record ReleaseInfo(String version, String downloadUrl) {}

    private PluginInstaller() {}
}
