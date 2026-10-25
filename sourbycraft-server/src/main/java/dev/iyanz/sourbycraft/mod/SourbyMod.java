package dev.iyanz.sourbycraft.mod;

/**
 * SourbyMod — implement this interface in a mod's main class (the {@code main:} class named in
 * {@code sourbymod.yml}).
 *
 * <p>Lifecycle, in order:</p>
 * <ul>
 *   <li>{@link #onLoad(ModContext)} — during SourbyCraft bootstrap, before world load and before
 *       Bukkit plugins enable. Safe: SourbyCraft configuration access, class initialisation, the
 *       mod's data directory. Not safe: Bukkit API, worlds, schedulers.</li>
 *   <li>{@link #onEnable()} — on {@code ServerLoadEvent(STARTUP)}: worlds are loaded and plugins are
 *       enabled, so the full Bukkit/Paper API is available.</li>
 *   <li>{@link #onDisable()} — during shutdown, in reverse enable order, before the rest of
 *       SourbyCraft's services stop. Only after {@link #onEnable()} was called.</li>
 *   <li>{@link #onUnload()} — right after {@code onDisable} (or in its place when the server stopped
 *       before it was ready, so {@code onEnable} never ran), for every mod whose {@code onLoad}
 *       returned normally, before the mod's classloader is closed.</li>
 * </ul>
 *
 * <p>Mods run in-process with full server privileges. No sandbox is applied — the same trust level as
 * a plugin, including NMS access. See {@code docs/SOURBYMODS.md} for the format and limits.</p>
 */
public interface SourbyMod {

    /**
     * Bootstrap phase: after SourbyCraft's configuration is initialised, before worlds load and
     * before Bukkit plugins enable.
     */
    void onLoad(ModContext ctx);

    /** The server is ready (worlds loaded, plugins enabled); the Bukkit API is safe here. */
    default void onEnable() {}

    /** Shutdown; release resources. Called in reverse enable order, only for an enabled mod. */
    default void onDisable() {}

    /**
     * Last call before the classloader closes, whether or not the mod was enabled: release what
     * {@link #onLoad(ModContext)} acquired (threads, files). It runs during shutdown, after plugins
     * were disabled, and also when the server never became ready: do not start Bukkit work here.
     */
    default void onUnload() {}
}
