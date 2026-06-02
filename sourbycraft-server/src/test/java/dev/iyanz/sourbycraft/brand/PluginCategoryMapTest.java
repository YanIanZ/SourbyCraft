package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PluginCategoryMapTest {

    @Test
    void mapsKnownPlugin() {
        var yaml = """
            categories:
              Core: [LuckPerms]
              PvP:  [ViaVersion]
            """;
        var map = PluginCategoryMap.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals("Core", map.categoryOf("LuckPerms"));
        assertEquals("PvP", map.categoryOf("ViaVersion"));
    }

    @Test
    void unknownPluginReturnsOther() {
        var map = PluginCategoryMap.parse(new ByteArrayInputStream("categories: {}".getBytes()));
        assertEquals("Other", map.categoryOf("MyCustomPlugin"));
    }

    @Test
    void caseInsensitiveMatch() {
        var yaml = "categories:\n  Core: [LuckPerms]\n";
        var map = PluginCategoryMap.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals("Core", map.categoryOf("luckperms"));
    }
}
