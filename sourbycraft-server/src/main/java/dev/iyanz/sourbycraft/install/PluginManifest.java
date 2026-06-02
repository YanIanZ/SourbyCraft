package dev.iyanz.sourbycraft.install;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PluginManifest {

    private PluginManifest() {}

    public static List<PluginEntry> parse(InputStream in) {
        if (in == null) return List.of();
        Map<String, Object> root = new Yaml().load(in);
        if (root == null) return List.of();
        Object pluginsObj = root.get("plugins");
        if (!(pluginsObj instanceof List<?> list)) return List.of();

        List<PluginEntry> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new PluginEntry(
                asString(m.get("name")),
                asString(m.get("source")),
                asString(m.get("repo")),
                asString(m.get("url")),
                asString(m.get("asset-glob")),
                asString(m.get("sha256"))
            ));
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
