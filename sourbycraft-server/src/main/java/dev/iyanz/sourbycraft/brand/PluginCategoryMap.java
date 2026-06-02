package dev.iyanz.sourbycraft.brand;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginCategoryMap {

    private final Map<String, String> pluginToCategory;

    private PluginCategoryMap(Map<String, String> p2c) {
        this.pluginToCategory = p2c;
    }

    @SuppressWarnings("unchecked")
    public static PluginCategoryMap parse(InputStream in) {
        Map<String, String> p2c = new HashMap<>();
        if (in == null) return new PluginCategoryMap(p2c);
        Map<String, Object> root = new Yaml().load(in);
        if (root == null) return new PluginCategoryMap(p2c);
        Object catsObj = root.get("categories");
        if (!(catsObj instanceof Map<?, ?> cats)) return new PluginCategoryMap(p2c);
        for (Map.Entry<?, ?> e : cats.entrySet()) {
            String cat = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof List<?> list)) continue;
            for (Object plug : list) {
                p2c.put(String.valueOf(plug).toLowerCase(Locale.ROOT), cat);
            }
        }
        return new PluginCategoryMap(p2c);
    }

    public static PluginCategoryMap loadDefault() {
        try (InputStream in = PluginCategoryMap.class
            .getResourceAsStream("/META-INF/sourbycraft-plugin-categories.yml")) {
            return parse(in);
        } catch (Exception e) {
            return new PluginCategoryMap(new HashMap<>());
        }
    }

    public String categoryOf(String pluginName) {
        return pluginToCategory.getOrDefault(pluginName.toLowerCase(Locale.ROOT), "Other");
    }
}
