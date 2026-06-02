package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginsCommandFormatterTest {

    record TestPlugin(String name, String version, boolean enabled) implements PluginsCommandFormatter.PluginInfo {}

    @Test
    void rendersBoxedHeader() {
        var out = PluginsCommandFormatter.render(
            List.of(new TestPlugin("LuckPerms", "5.4.130", true)),
            PluginCategoryMap.loadDefault());
        assertTrue(out.contains("Lightning Fast"));
        assertTrue(out.contains("┌"));
        assertTrue(out.contains("└"));
    }

    @Test
    void groupsByCategory() {
        var plugins = List.of(
            new TestPlugin("LuckPerms", "5.4", true),
            new TestPlugin("WorldEdit", "7.3", true),
            new TestPlugin("ViaVersion", "5.2", true)
        );
        String out = PluginsCommandFormatter.render(plugins, PluginCategoryMap.loadDefault());
        int coreIdx = out.indexOf("Core");
        int pvpIdx = out.indexOf("PvP");
        int worldIdx = out.indexOf("World");
        assertTrue(coreIdx > 0 && pvpIdx > 0 && worldIdx > 0);
    }

    @Test
    void showsDisabledStatus() {
        var p = new TestPlugin("Foo", "1.0", false);
        String out = PluginsCommandFormatter.render(List.of(p), PluginCategoryMap.loadDefault());
        assertTrue(out.contains("disabled"));
    }

    @Test
    void footerShowsCount() {
        var plugins = List.of(
            new TestPlugin("A", "1", true),
            new TestPlugin("B", "1", false));
        String out = PluginsCommandFormatter.render(plugins, PluginCategoryMap.loadDefault());
        assertTrue(out.contains("2 plugins"));
        assertTrue(out.contains("1 enabled"));
        assertTrue(out.contains("1 disabled"));
    }
}
