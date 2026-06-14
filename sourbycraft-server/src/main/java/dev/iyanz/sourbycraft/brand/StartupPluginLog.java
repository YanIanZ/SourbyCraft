package dev.iyanz.sourbycraft.brand;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StartupPluginLog {

    private static final String[] ORDER = {"Core", "World", "Util", "Economy", "Other"};
    private static final String[] ICONS = {"⚡", "🌍", "🔧", "💰", "·"};

    private StartupPluginLog() {}

    /**
     * Print compact grouped plugin list. Called from MinecraftServer after
     * Bukkit's POSTWORLD plugin-enable phase completes.
     */
    public static void printSummary(long bootStartNanos) {
        boolean enabled = Boolean.TRUE.equals(
            dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("branding.compact-plugin-log", Boolean.TRUE));
        if (!enabled) return;

        PluginCategoryMap cats = PluginCategoryMap.loadDefault();
        Plugin[] all = Bukkit.getPluginManager().getPlugins();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Plugin p : all) {
            grouped.computeIfAbsent(cats.categoryOf(p.getName()), k -> new ArrayList<>())
                .add(p.getName());
        }
        long elapsedMs = (System.nanoTime() - bootStartNanos) / 1_000_000;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[SourbyCraft] Plugins ready (%.1fs):%n", elapsedMs / 1000.0));
        for (int i = 0; i < ORDER.length; i++) {
            List<String> list = grouped.get(ORDER[i]);
            if (list == null || list.isEmpty()) continue;
            sb.append(String.format("  %s %s (%d)  %s%n",
                ICONS[i], ORDER[i], list.size(), String.join(" · ", list)));
        }
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info("\n" + sb);
    }
}
