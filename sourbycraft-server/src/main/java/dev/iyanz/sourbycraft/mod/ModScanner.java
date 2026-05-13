package dev.iyanz.sourbycraft.mod;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

public final class ModScanner {
    private static final Path MODS_DIR = Path.of("mods");

    public record ModInfo(String name, String version, String id, String fileName) {}

    public static List<ModInfo> scan() {
        if (!Files.exists(MODS_DIR)) return List.of();
        try (Stream<Path> files = Files.list(MODS_DIR)) {
            return files
                .filter(f -> f.toString().endsWith(".jar"))
                .map(ModScanner::readModInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static ModInfo readModInfo(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Try NeoForge mods.toml
            JarEntry neoforgeEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neoforgeEntry != null) {
                return parseNeoForgeMod(jar, neoforgeEntry, jarPath);
            }
            // Try Forge mods.toml
            JarEntry forgeEntry = jar.getJarEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                return parseNeoForgeMod(jar, forgeEntry, jarPath);
            }
            // Try Fabric fabric.mod.json
            JarEntry fabricEntry = jar.getJarEntry("fabric.mod.json");
            if (fabricEntry != null) {
                return parseFabricMod(jar, fabricEntry, jarPath);
            }
            // Try plugin.yml (Bukkit plugin in mods folder)
            JarEntry pluginEntry = jar.getJarEntry("plugin.yml");
            if (pluginEntry != null) {
                return parsePluginMod(jar, pluginEntry, jarPath);
            }
            // Unknown jar — just show filename
            return new ModInfo(
                jarPath.getFileName().toString().replace(".jar", ""),
                "?",
                "unknown",
                jarPath.getFileName().toString()
            );
        } catch (IOException e) {
            return null;
        }
    }

    private static ModInfo parseNeoForgeMod(JarFile jar, JarEntry entry, Path jarPath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = jar.getInputStream(entry)) {
            // Simple TOML-like parsing for [[mods]] section
            String content = new String(in.readAllBytes());
            String modId = extractTomlValue(content, "modId");
            String displayName = extractTomlValue(content, "displayName");
            String version = extractTomlValue(content, "version");
            return new ModInfo(
                displayName != null ? displayName : jarPath.getFileName().toString().replace(".jar", ""),
                version != null ? version : "?",
                modId != null ? modId : "neoforge",
                jarPath.getFileName().toString()
            );
        }
    }

    private static ModInfo parseFabricMod(JarFile jar, JarEntry entry, Path jarPath) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            String content = new String(in.readAllBytes());
            String name = extractJsonValue(content, "name");
            String version = extractJsonValue(content, "version");
            String id = extractJsonValue(content, "id");
            return new ModInfo(
                name != null ? name : jarPath.getFileName().toString().replace(".jar", ""),
                version != null ? version : "?",
                id != null ? id : "fabric",
                jarPath.getFileName().toString()
            );
        }
    }

    private static ModInfo parsePluginMod(JarFile jar, JarEntry entry, Path jarPath) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            String content = new String(in.readAllBytes());
            String name = extractYamlValue(content, "name");
            String version = extractYamlValue(content, "version");
            return new ModInfo(
                name != null ? name : jarPath.getFileName().toString().replace(".jar", ""),
                version != null ? version : "?",
                "bukkit",
                jarPath.getFileName().toString()
            );
        }
    }

    private static String extractTomlValue(String content, String key) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + " = ") || line.startsWith(key + "=")) {
                String val = line.substring(line.indexOf('=') + 1).trim();
                return val.replace("\"", "").replace("'", "");
            }
            if (line.startsWith(key + " = \"") || line.startsWith(key + "=\"")) {
                return line.replaceAll(".*\"(.*)\".*", "$1");
            }
        }
        return null;
    }

    private static String extractJsonValue(String content, String key) {
        String[] lines = content.replace("{", "\n").replace("}", "\n").split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("\"" + key + "\"")) {
                String val = line.replaceAll(".*\"(.*)\".*", "$1");
                if (!val.equals(line) && val.length() < 100) return val;
            }
        }
        return null;
    }

    private static String extractYamlValue(String content, String key) {
        for (String line : content.split("\n")) {
            if (line.trim().startsWith(key + ":")) {
                return line.substring(line.indexOf(':') + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    private ModScanner() {}
}
