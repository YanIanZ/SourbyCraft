package dev.iyanz.sourbycraft.brand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginsCommandFormatter {

    public interface PluginInfo {
        String name();
        String version();
        boolean enabled();
    }

    private static final String[] CATEGORY_ORDER = {"Core", "PvP", "World", "Util", "Economy", "Other"};
    private static final String[] CATEGORY_COLORS = {"§6", "§c", "§a", "§e", "§b", "§8"};

    private PluginsCommandFormatter() {}

    public static String render(List<? extends PluginInfo> plugins, PluginCategoryMap cats) {
        Map<String, List<PluginInfo>> grouped = new LinkedHashMap<>();
        for (String c : CATEGORY_ORDER) grouped.put(c, new ArrayList<>());
        for (PluginInfo p : plugins) {
            grouped.get(cats.categoryOf(p.name())).add(p);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("┌─ ⚡ Lightning Fast · ✨ Feature Rich ─────────────────┐\n");
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            String cat = CATEGORY_ORDER[i];
            String color = CATEGORY_COLORS[i];
            List<PluginInfo> list = grouped.get(cat);
            if (list.isEmpty()) continue;
            boolean first = true;
            for (PluginInfo p : list) {
                String catLabel = first ? String.format("%s%-10s§r", color, cat) : "          ";
                String status = p.enabled()
                    ? String.format("§av%s", p.version())
                    : String.format("§7v%s§8 (disabled)", p.version());
                sb.append(String.format("│ %s  %-18s %s%n", catLabel, p.name(), status));
                first = false;
            }
        }
        long enabled = plugins.stream().filter(PluginInfo::enabled).count();
        long disabled = plugins.size() - enabled;
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        sb.append(String.format("└─ %d plugins · %d enabled · %d disabled · %d MB heap ──┘%n",
            plugins.size(), enabled, disabled, heapMb));
        return sb.toString();
    }
}
