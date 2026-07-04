package dev.iyanz.sourbycraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

import static dev.iyanz.sourbycraft.SourbyCraftConfig.log;

public class SourbyCraftWorldConfig {

    private final String worldName;
    private final World.Environment environment;

    public SourbyCraftWorldConfig(String worldName, World.Environment environment) {
        this.worldName = worldName;
        this.environment = environment;
        this.init();
    }

    // SourbyCraft S4 - lazy per-world holder. Main-thread call sites only
    // (chunk send, entity tracker, scheduler); ConcurrentHashMap is defensive.
    private static final java.util.concurrent.ConcurrentHashMap<String, SourbyCraftWorldConfig> BY_WORLD =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static SourbyCraftWorldConfig get(net.minecraft.server.level.ServerLevel level) {
        org.bukkit.World w = level.getWorld();
        return BY_WORLD.computeIfAbsent(w.getName(), n -> new SourbyCraftWorldConfig(n, w.getEnvironment()));
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
        // See SourbyCraftConfig#getComponent — TextRender catches MiniMessage
        // parser exceptions and falls back to legacy &-codes (then plain text).
        String raw = getString(path, MiniMessage.miniMessage().serialize(def));
        return dev.iyanz.sourbycraft.util.TextRender.parseOr(raw, def);
    }

    // SourbyCraft start - antixray config
    public boolean fluidObscures = true;
    public boolean allBlocks = false;
    public boolean entityObfuscation = true;
    public int entityObfuscationRange = 64;

    private void antixray() {
        fluidObscures = getBoolean("anticheat.anti-xray.fluid-obscures", fluidObscures);
        allBlocks = getBoolean("anticheat.anti-xray.all-blocks", allBlocks);
        entityObfuscation = getBoolean("anticheat.anti-xray.entity-obfuscation", entityObfuscation);
        entityObfuscationRange = getInt("anticheat.anti-xray.entity-obfuscation-range", entityObfuscationRange);
    }
    // SourbyCraft end
}
