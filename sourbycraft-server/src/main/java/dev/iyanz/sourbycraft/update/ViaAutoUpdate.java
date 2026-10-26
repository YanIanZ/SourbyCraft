package dev.iyanz.sourbycraft.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.iyanz.sourbycraft.bootstrap.PluginProvisioner;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Best-effort installation of reviewed Via pins. Metadata must explicitly list the running MC
 * version and a Bukkit-family loader. Both URL and bytes remain anchored to the shipped pin;
 * installing a newer upstream version requires a reviewed pin update. Existing plugin jars,
 * including renamed operator builds, are preserved. Installed jars take effect on restart.
 */
public final class ViaAutoUpdate {
    private static final Gson GSON = new Gson();
    private static final HttpTransport HTTP = HttpTransport.jdk();
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final long MAX_JAR_BYTES = 32L * 1024 * 1024;
    record Pin(String id, String prefix, String filename, String url, String sha256, long size) {}

    private ViaAutoUpdate() {}

    /**
     * Reinstall missing pins, unless {@code updater.via-auto-update} is off, Clip runs offline, or
     * {@code plugins.viaversion.auto-provision} is anything but absent (default on) or a boolean
     * {@code true}: a quoted or misspelt value disables the download, as it does at boot.
     */
    public static void check() {
        if (!AutoUpdateSettings.viaAutoUpdate || Boolean.getBoolean("sourbyclip.offline")) return;
        if (!provisioningAllowed(dev.iyanz.sourbycraft.SourbyCraftConfig.cfgGet(
            dev.iyanz.sourbycraft.config.UtilityKeys.VIAVERSION_AUTO_PROVISION.path(), null))) return;
        String mc = BuildInfo.load().mcVersion();
        for (var trusted : PluginProvisioner.viaPins()) {
            Pin pin = new Pin(trusted.projectId(), trusted.prefix(), trusted.filename(), trusted.url(), trusted.sha256(), trusted.size());
            try {
                if (check(pin, mc, PluginProvisioner.pluginsDirectory(), HTTP)) {
                    SourbyLogger.info("via-updater: installed pinned " + pin.filename() + "; takes effect on restart.");
                }
            } catch (Exception failure) {
                SourbyLogger.warn("via-updater: " + pin.prefix() + " check failed: " + failure.getMessage());
            }
        }
    }

    /** Strict read of the auto-provision value: absent means the default (on); only {@code Boolean.TRUE} enables. */
    static boolean provisioningAllowed(Object configured) {
        return configured == null || Boolean.TRUE.equals(configured);
    }

    static boolean check(Pin pin, String mc, Path plugins, HttpTransport transport) throws Exception {
        if (PluginProvisioner.hasViaJar(plugins, pin.prefix())) return false;
        JsonArray versions;
        try (var response = transport.get("https://api.modrinth.com/v2/project/" + pin.id() + "/version", Map.of())) {
            if (response.status() != 200) return false;
            byte[] metadata = response.bodyOrEmpty().readNBytes(MAX_METADATA_BYTES + 1);
            if (metadata.length > MAX_METADATA_BYTES) throw new IOException("Via metadata exceeds limit");
            versions = GSON.fromJson(new String(metadata, StandardCharsets.UTF_8), JsonArray.class);
        }
        return select(versions, mc, pin) != null && install(pin, plugins, transport);
    }

    static JsonObject select(JsonArray versions, String mc, Pin pin) {
        if (versions == null) return null;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject version = element.getAsJsonObject();
            if (!version.has("version_type") || !"release".equalsIgnoreCase(version.get("version_type").getAsString())) continue;
            if (!contains(version, "game_versions", mc)) continue;
            if (!(contains(version, "loaders", "paper") || contains(version, "loaders", "folia")
                || contains(version, "loaders", "bukkit") || contains(version, "loaders", "spigot"))) continue;
            if (!version.has("files") || !version.get("files").isJsonArray()) continue;
            for (JsonElement entry : version.getAsJsonArray("files")) {
                if (!entry.isJsonObject()) continue;
                JsonObject file = entry.getAsJsonObject();
                if (file.has("filename") && file.has("url") && pin.filename().equals(file.get("filename").getAsString())
                    && pin.url().equals(file.get("url").getAsString())) return file;
            }
        }
        return null;
    }

    private static boolean contains(JsonObject object, String key, String value) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return false;
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive() && value.equalsIgnoreCase(element.getAsString())) return true;
        }
        return false;
    }

    static synchronized boolean install(Pin pin, Path plugins, HttpTransport transport) throws Exception {
        if (pin.size() <= 0 || pin.size() > MAX_JAR_BYTES || !pin.url().startsWith("https://")
            || !pin.sha256().matches("[0-9a-f]{64}") || !Path.of(pin.filename()).getFileName().toString().equals(pin.filename())) {
            throw new IOException("Invalid Via trust pin");
        }
        Files.createDirectories(plugins);
        if (PluginProvisioner.hasViaJar(plugins, pin.prefix())) return false;
        Path temporary = Files.createTempFile(plugins, ".sourby-via-", ".part");
        try {
            try (var response = transport.get(pin.url(), Map.of())) {
                if (response.status() != 200) throw new IOException("Via download HTTP " + response.status());
                if (response.contentLength() > pin.size()) throw new IOException("Via Content-Length exceeds pinned size");
                try (var input = response.bodyOrEmpty(); var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    long count = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        count += read;
                        if (count > pin.size()) throw new IOException("Via download exceeds pinned size");
                        output.write(buffer, 0, read);
                    }
                    if (count != pin.size()) throw new IOException("Via download differs from pinned size");
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            if (!pin.sha256().equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Via SHA-256 differs from shipped pin");
            // Preserve operator jars appearing during a download. Move without REPLACE_EXISTING.
            if (PluginProvisioner.hasViaJar(plugins, pin.prefix())) return false;
            Files.move(temporary, plugins.resolve(pin.filename()));
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
