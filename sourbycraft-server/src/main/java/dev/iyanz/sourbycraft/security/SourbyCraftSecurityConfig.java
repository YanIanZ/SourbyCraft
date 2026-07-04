package dev.iyanz.sourbycraft.security;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

public class SourbyCraftSecurityConfig {

    private static File CONFIG_FILE;
    private static boolean loaded = false;

    // NBT limits
    public static long nbtMaxBytes = 2_097_152L;     // 2MB
    public static int nbtMaxDepth = 64;
    public static int nbtMaxStringLength = 4_096;
    public static int nbtMaxListSize = 65_536;

    // Sign limits
    public static int signMaxLineLength = 256;
    public static int signMaxTotalChars = 1_024;

    // Anvil limits
    public static int anvilMaxItemNameLength = 128;

    // Recipe book limits
    public static int recipeBookMaxPacketSize = 20_480;

    // Creative mode limits
    public static int creativeMaxItemNbtSize = 2_048;

    // Packet guard limits (packet-guard: section)
    public static int bookEditMaxTotalChars = 65_536;
    public static int customPayloadMaxBytes = 8_192;
    public static int containerClickMaxPerTick = 20;
    public static int recipeBookMaxPerTick = 16;

    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        if (configFile != null && configFile.exists()) {
            load();
        } else {
            saveDefault(); // fresh install: materialize full file including packet-guard
        }
        clampAll();
        loaded = true;
        SourbyLogger.info("[SourbyCraft] security limits active:"
            + " nbt=" + nbtMaxBytes + "B/d" + nbtMaxDepth + "/s" + nbtMaxStringLength + "/l" + nbtMaxListSize
            + " sign=" + signMaxLineLength + "/" + signMaxTotalChars
            + " anvil=" + anvilMaxItemNameLength
            + " creative=" + creativeMaxItemNbtSize + "B"
            + " book=" + bookEditMaxTotalChars
            + " payload=" + customPayloadMaxBytes + "B"
            + " clicks/t=" + containerClickMaxPerTick
            + " recipe/t=" + recipeBookMaxPerTick);
    }

    @SuppressWarnings("unchecked")
    public static void load() {
        if (CONFIG_FILE == null || !CONFIG_FILE.exists()) return;

        try (InputStream in = new FileInputStream(CONFIG_FILE)) {
            // SourbyCraft - SnakeYAML SafeConstructor blocks gadget RCE via !!tags
            Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;

            Map<String, Object> crash = (Map<String, Object>) root.get("crash-prevention");
            if (crash != null) {
                Map<String, Object> nbt = (Map<String, Object>) crash.get("nbt");
                if (nbt != null) {
                    nbtMaxBytes = getLong(nbt, "max-bytes", nbtMaxBytes);
                    nbtMaxDepth = getInt(nbt, "max-depth", nbtMaxDepth);
                    nbtMaxStringLength = getInt(nbt, "max-string-length", nbtMaxStringLength);
                    nbtMaxListSize = getInt(nbt, "max-list-size", nbtMaxListSize);
                }

                Map<String, Object> sign = (Map<String, Object>) crash.get("sign");
                if (sign != null) {
                    signMaxLineLength = getInt(sign, "max-line-length", signMaxLineLength);
                    signMaxTotalChars = getInt(sign, "max-total-chars", signMaxTotalChars);
                }

                Map<String, Object> anvil = (Map<String, Object>) crash.get("anvil");
                if (anvil != null) {
                    anvilMaxItemNameLength = getInt(anvil, "max-item-name-length", anvilMaxItemNameLength);
                }

                Map<String, Object> recipeBook = (Map<String, Object>) crash.get("recipe-book");
                if (recipeBook != null) {
                    recipeBookMaxPacketSize = getInt(recipeBook, "max-packet-size", recipeBookMaxPacketSize);
                }

                Map<String, Object> creative = (Map<String, Object>) crash.get("creative-item");
                if (creative != null) {
                    creativeMaxItemNbtSize = getInt(creative, "max-nbt-size", creativeMaxItemNbtSize);
                }
            }

            Map<String, Object> guard = (Map<String, Object>) root.get("packet-guard");
            if (guard != null) {
                Map<String, Object> book = (Map<String, Object>) guard.get("book-edit");
                if (book != null) bookEditMaxTotalChars = getInt(book, "max-total-chars", bookEditMaxTotalChars);
                Map<String, Object> payload = (Map<String, Object>) guard.get("custom-payload");
                if (payload != null) customPayloadMaxBytes = getInt(payload, "max-bytes", customPayloadMaxBytes);
                Map<String, Object> click = (Map<String, Object>) guard.get("container-click");
                if (click != null) containerClickMaxPerTick = getInt(click, "max-per-tick", containerClickMaxPerTick);
                Map<String, Object> recipe = (Map<String, Object>) guard.get("recipe-book");
                if (recipe != null) recipeBookMaxPerTick = getInt(recipe, "max-per-tick", recipeBookMaxPerTick);
            }

        } catch (Exception e) {
            SourbyLogger.error("Failed to load security config: " + e.getMessage());
        }
    }

    /** Sanity floors/ceilings so operator typos cannot break normal gameplay or lift hard protocol caps. */
    private static void clampAll() {
        nbtMaxBytes = Math.max(65_536L, nbtMaxBytes);
        nbtMaxDepth = Math.max(16, Math.min(512, nbtMaxDepth));
        nbtMaxStringLength = Math.max(256, nbtMaxStringLength);
        nbtMaxListSize = Math.max(256, nbtMaxListSize);
        signMaxLineLength = Math.max(16, signMaxLineLength);
        signMaxTotalChars = Math.max(64, signMaxTotalChars);
        anvilMaxItemNameLength = Math.max(1, Math.min(256, anvilMaxItemNameLength));
        recipeBookMaxPacketSize = Math.max(256, recipeBookMaxPacketSize);
        creativeMaxItemNbtSize = Math.max(256, creativeMaxItemNbtSize);
        bookEditMaxTotalChars = Math.max(1_024, bookEditMaxTotalChars);
        customPayloadMaxBytes = Math.max(1_024, Math.min(32_767, customPayloadMaxBytes));
        containerClickMaxPerTick = Math.max(4, containerClickMaxPerTick);
        recipeBookMaxPerTick = Math.max(4, recipeBookMaxPerTick);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    private static void saveDefault() {
        try {
            if (CONFIG_FILE == null) return;
            CONFIG_FILE.getParentFile().mkdirs();
            java.io.PrintWriter w = new java.io.PrintWriter(CONFIG_FILE);
            w.println("crash-prevention:");
            w.println("  nbt:");
            w.println("    max-bytes: " + nbtMaxBytes);
            w.println("    max-depth: " + nbtMaxDepth);
            w.println("    max-string-length: " + nbtMaxStringLength);
            w.println("    max-list-size: " + nbtMaxListSize);
            w.println("  sign:");
            w.println("    max-line-length: " + signMaxLineLength);
            w.println("    max-total-chars: " + signMaxTotalChars);
            w.println("  anvil:");
            w.println("    max-item-name-length: " + anvilMaxItemNameLength);
            w.println("  recipe-book:");
            w.println("    max-packet-size: " + recipeBookMaxPacketSize);
            w.println("  creative-item:");
            w.println("    max-nbt-size: " + creativeMaxItemNbtSize);
            w.println("packet-guard:");
            w.println("  book-edit:");
            w.println("    max-total-chars: " + bookEditMaxTotalChars);
            w.println("  custom-payload:");
            w.println("    max-bytes: " + customPayloadMaxBytes);
            w.println("  container-click:");
            w.println("    max-per-tick: " + containerClickMaxPerTick);
            w.println("  recipe-book:");
            w.println("    max-per-tick: " + recipeBookMaxPerTick);
            w.close();
        } catch (Exception e) {
            SourbyLogger.warn("Failed to save default security config: " + e.getMessage());
        }
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : def;
    }

    private static long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).longValue() : def;
    }
}
