package dev.iyanz.sourbycraft.core;

import org.bukkit.plugin.Plugin;

/** A SourbyCraft feature with an isolated lifecycle. Enable failures never propagate. */
public interface SourbyModule {
    String name();
    void enable(Plugin plugin) throws Exception;
    default void disable() {}
}
