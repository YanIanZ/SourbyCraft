package dev.iyanz.sourbycraft.mod;

/**
 * SourbyMod — implement this interface in your mod's main class (declared as {@code main:} in sourbymod.yml).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #onLoad} — called at bootstrap, BEFORE world load and Bukkit plugin loading.
 *       Safe to access: SourbyCraft config, Knobs. To register runtime features use
 *       {@code ctx.registerModule(...)} — do NOT call {@code ModuleRegistry.add} directly here,
 *       it is wiped by SWPlugin's reload-guard clear before mods enroll. NOT safe: Bukkit API, worlds.</li>
 *   <li>{@link #onEnable} — called when SourbyCraft's SWPlugin enables (Bukkit API is safe).</li>
 *   <li>{@link #onDisable} — called during server shutdown in reverse enable order.</li>
 * </ul>
 *
 * <p>Mods run in-process with full server privileges. No sandbox is applied — same trust level as plugins.
 * See {@code docs/SOURBYMODS.md} for format spec and limits.
 */
public interface SourbyMod {

    /**
     * Bootstrap phase: after SourbyCraft config init, BEFORE world load and plugin load.
     * Safe: config/Knobs access, class init, {@code ctx.registerModule(...)}. NOT safe:
     * Bukkit API, worlds, and direct {@code ModuleRegistry.add} (use ctx.registerModule instead).
     */
    void onLoad(ModContext ctx);

    /** Server started, plugins enabling (called via ModuleRegistry). Bukkit API safe. */
    default void onEnable() {}

    /** Server shutdown. */
    default void onDisable() {}
}
