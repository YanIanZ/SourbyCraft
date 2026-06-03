package dev.iyanz.sourbycraft.nms;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SanityHarnessPlugin extends JavaPlugin {

    private static final List<String> TARGETS = List.of(
        "Citizens", "NBTAPI", "DecentHolograms", "FastAsyncWorldEdit"
    );

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runAndWriteResults, 20L);
    }

    private void runAndWriteResults() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : TARGETS) {
            rows.add(checkPlugin(name));
        }
        writeJson(rows);
        getLogger().info("[sanity-harness] wrote nms-compat-result.json with " + rows.size() + " rows");
    }

    private Map<String, Object> checkPlugin(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("plugin", name);
        if (p == null) {
            row.put("enabled", false);
            row.put("sanity_passed", false);
            row.put("fail_reason", "NOT_LOADED");
            row.put("stack_hash", "");
            return row;
        }
        if (!p.isEnabled()) {
            row.put("enabled", false);
            row.put("sanity_passed", false);
            row.put("fail_reason", "LOADED_NOT_ENABLED");
            row.put("stack_hash", "");
            return row;
        }
        try {
            String result = SanityFixtures.invoke(name, this);
            row.put("enabled", true);
            row.put("sanity_passed", true);
            row.put("fail_reason", "");
            row.put("stack_hash", "");
            row.put("fixture_result", result);
            return row;
        } catch (Throwable t) {
            row.put("enabled", true);
            row.put("sanity_passed", false);
            row.put("fail_reason", t.getClass().getSimpleName() + ": " + safeMessage(t));
            row.put("stack_hash", stackHash(t));
            return row;
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m.replace('"', '\'');
    }

    private static String stackHash(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String normalized = sw.toString()
            .replaceAll("\\d+", "N")
            .replaceAll("\\$\\d+", "\\$N")
            .replaceAll("@[0-9a-f]+", "@HEX")
            .toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "nohash";
        }
    }

    private void writeJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            sb.append("  {");
            int j = 0;
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (j++ > 0) sb.append(", ");
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
            }
            sb.append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Path out = getServer().getWorldContainer().toPath().resolve("nms-compat-result.json");
        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("[sanity-harness] failed to write " + out + ": " + e.getMessage());
        }
    }
}
