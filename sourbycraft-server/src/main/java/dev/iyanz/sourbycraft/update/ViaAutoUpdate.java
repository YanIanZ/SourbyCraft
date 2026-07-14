package dev.iyanz.sourbycraft.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * Keeps the auto-provisioned ViaVersion + ViaBackwards jars current by pulling the latest Modrinth
 * release for this MC version. Runs on the same async cadence as {@link SourbyUpdater} (never a
 * region thread). A new jar is downloaded, SHA-512-verified against Modrinth's published hash, and
 * placed in {@code plugins/} replacing the old file; like every plugin swap it takes effect on the
 * next restart (the auto-updater's self-restart applies it along with a core update).
 *
 * <p>Gated on {@code viaversion.auto-provision}: only manage Via when SourbyCraft provisions it.
 * A no-op (network best-effort) when Via has not published for the running MC version yet.
 */
public final class ViaAutoUpdate {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Modrinth project id + plugins/ filename prefix for each managed plugin. */
    private record Project(String id, String prefix) {}
    private static final Project[] PROJECTS = {
        new Project("P1OZGk5p", "ViaVersion-"),
        new Project("NpvuJQoq", "ViaBackwards-"),
    };

    private ViaAutoUpdate() {}

    /** Best-effort: check + update both Via plugins. Never throws. */
    public static void check() {
        if (!me.earthme.luminol.config.modules.misc.AutoUpdateConfig.viaAutoUpdate) return;
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.viaVersionAutoProvision) return;
        final String mc = BuildInfo.load().mcVersion();
        final Path pluginsDir = Path.of("plugins");
        for (final Project p : PROJECTS) {
            try {
                updateOne(p, mc, pluginsDir);
            } catch (Throwable t) {
                SourbyLogger.warn("via-updater: " + p.prefix + " check failed: " + t.getMessage());
            }
        }
    }

    private static void updateOne(Project p, String mc, Path pluginsDir) throws Exception {
        // Latest version of this project, preferring one that lists our MC version + a paper/folia loader.
        final JsonObject latest = latestVersion(p.id, mc);
        if (latest == null) {
            SourbyLogger.info("via-updater: no Modrinth release found for " + p.prefix + " (mc " + mc + ")");
            return;
        }
        final JsonObject file = primaryJar(latest);
        if (file == null) return;
        final String filename = file.get("filename").getAsString();
        // Already installed? (exact filename present -> up to date).
        if (Files.isRegularFile(pluginsDir.resolve(filename))) return;

        final String url = file.get("url").getAsString();
        final String sha512 = file.getAsJsonObject("hashes").get("sha512").getAsString();
        final Path tmp = pluginsDir.resolve(filename + ".part");
        Files.createDirectories(pluginsDir);
        Files.deleteIfExists(tmp);
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        final HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new Exception("HTTP " + resp.statusCode() + " downloading " + url);
        }
        final String actual = sha512(tmp);
        if (actual == null || !actual.equalsIgnoreCase(sha512)) {
            Files.deleteIfExists(tmp);
            throw new Exception("SHA-512 mismatch for " + filename);
        }
        // Verified — remove old same-prefix jars, move the new one in.
        removePrefixJars(pluginsDir, p.prefix, filename);
        Files.move(tmp, pluginsDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        SourbyLogger.info("via-updater: updated " + p.prefix + "-> " + filename
            + " (SHA-512 verified). Takes effect on the next restart.");
    }

    private static JsonObject latestVersion(String projectId, String mc) throws Exception {
        final String url = "https://api.modrinth.com/v2/project/" + projectId + "/version";
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "SourbyCraft-ViaUpdater")
            .GET().build();
        final HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        final JsonArray versions = GSON.fromJson(resp.body(), JsonArray.class);
        JsonObject bestMc = null, bestAny = null; // newest first is Modrinth's order, but be explicit
        for (final JsonElement el : versions) {
            if (!el.isJsonObject()) continue;
            final JsonObject v = el.getAsJsonObject();
            if (!hasPaperLoader(v)) continue;
            // STABLE releases only. A SNAPSHOT/beta (e.g. ViaVersion 5.11.0-SNAPSHOT) can carry a
            // half-finished mapping and hard-crash on load (Protocol1_13 blockStates null) — never
            // auto-install one.
            if (!isRelease(v)) continue;
            if (bestAny == null) bestAny = v;
            if (bestMc == null && listContains(v, "game_versions", mc)) bestMc = v;
        }
        return bestMc != null ? bestMc : bestAny;
    }

    /** Modrinth version_type == "release" — excludes beta/alpha/snapshot channels. */
    private static boolean isRelease(JsonObject v) {
        return v.has("version_type") && v.get("version_type").isJsonPrimitive()
            && "release".equalsIgnoreCase(v.get("version_type").getAsString());
    }

    private static boolean hasPaperLoader(JsonObject v) {
        return listContains(v, "loaders", "paper") || listContains(v, "loaders", "folia")
            || listContains(v, "loaders", "bukkit") || listContains(v, "loaders", "spigot");
    }

    private static boolean listContains(JsonObject o, String key, String value) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return false;
        for (final JsonElement e : o.getAsJsonArray(key)) {
            if (e.isJsonPrimitive() && e.getAsString().equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static JsonObject primaryJar(JsonObject version) {
        if (!version.has("files") || !version.get("files").isJsonArray()) return null;
        JsonObject firstJar = null;
        for (final JsonElement fe : version.getAsJsonArray("files")) {
            if (!fe.isJsonObject()) continue;
            final JsonObject f = fe.getAsJsonObject();
            final String name = f.has("filename") ? f.get("filename").getAsString() : "";
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (firstJar == null) firstJar = f;
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
        }
        return firstJar;
    }

    private static void removePrefixJars(Path pluginsDir, String prefix, String keep) throws Exception {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, prefix + "*.jar")) {
            for (final Path jar : ds) {
                if (!jar.getFileName().toString().equals(keep)) Files.deleteIfExists(jar);
            }
        }
    }

    private static String sha512(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-512");
            final byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
