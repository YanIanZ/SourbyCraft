package dev.iyanz.sourbycraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

import static ddev.iyanz.sourby.SourbyCraftConfig.log;

public class SourbyCraftWorldConfig {

    private final String worldName;
    private final World.Environment environment;

    public SourbyCraftWorldConfig(String worldName, World.Environment environment) {
        this.worldName = worldName;
        this.environment = environment;
        this.init();
    }

    public void init() {
        log("-------- World Settings For [" + worldName + "] --------");
        SourbyCraftConfig.readConfig(SourbyCraftWorldConfig.class, this);
    }

    private void set(String path, Object val) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, val);
        SourbyCraftConfig.config.set("world-settings.default." + path, val);
        if (SourbyCraftConfig.config.get("world-settings." + this.worldName + "." + path) != null) {
            SourbyCraftConfig.config.addDefault("world-settings." + this.worldName + "." + path, val);
            SourbyCraftConfig.config.set("world-settings." + this.worldName + "." + path, val);
        }
    }

    private void setComment(String path, String... comment) {
        SourbyCraftConfig.config.setComments("world-settings.default." + path, List.of(comment));
        if (SourbyCraftConfig.config.get("world-settings." + this.worldName + "." + path) != null) {
            SourbyCraftConfig.config.setComments("world-settings.default." + path, List.of(comment));
        }
    }

    private ConfigurationSection getConfigurationSection(String path) {
        ConfigurationSection section = SourbyCraftConfig.config.getConfigurationSection("world-settings." + this.worldName + "." + path);
        return section != null ? section : SourbyCraftConfig.config.getConfigurationSection("world-settings.default." + path);
    }

    private boolean getBoolean(String path, boolean def) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, def);
        return SourbyCraftConfig.config.getBoolean("world-settings." + this.worldName + "." + path, SourbyCraftConfig.config.getBoolean("world-settings.default." + path));
    }

    private double getDouble(String path, double def) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, def);
        return SourbyCraftConfig.config.getDouble("world-settings." + this.worldName + "." + path, SourbyCraftConfig.config.getDouble("world-settings.default." + path));
    }

    private int getInt(String path, int def) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, def);
        return SourbyCraftConfig.config.getInt("world-settings." + this.worldName + "." + path, SourbyCraftConfig.config.getInt("world-settings.default." + path));
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getList(String path, List<T> def) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, def);
        return (List<T>) SourbyCraftConfig.config.getList("world-settings." + this.worldName + "." + path, SourbyCraftConfig.config.getList("world-settings.default." + path));
    }

    private String getString(String path, String def) {
        SourbyCraftConfig.config.addDefault("world-settings.default." + path, def);
        return SourbyCraftConfig.config.getString("world-settings." + this.worldName + "." + path, SourbyCraftConfig.config.getString("world-settings.default." + path));
    }

    private Component getComponent(String path, Component def) {
        return MiniMessage.miniMessage().deserialize(getString(path,
                MiniMessage.miniMessage().serialize(def)));
    }
}
