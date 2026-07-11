package dev.iyanz.sourbycraft;

import net.minecraft.server.level.ServerLevel;

/**
 * Per-world SourbyCraft settings (Folia base) — read from the single unified TOML.
 *
 * <p>Folia port of the pre-folia {@code SourbyCraftWorldConfig}. The Paper tag drove this
 * off a YAML {@code world-settings.<world>.*} tree; on the Folia base every SourbyCraft
 * knob lives in the one unified TOML ({@code sourbycraft_config/sourbycraft_global_config.toml}),
 * so this reads through {@link SourbyCraftConfig#cfgBool}/{@link SourbyCraftConfig#cfgInt}
 * with a per-world override that falls back to the shared default:
 *
 * <pre>
 *   antixray.world-overrides.&lt;world&gt;.fluid-obscures   (per-world override)
 *   antixray.fluid-obscures                             (shared default)
 * </pre>
 *
 * <p>Instances are cached by world name in a {@link dev.iyanz.sourbycraft.core.PerWorldHolder}
 * (Folia-safe {@link java.util.concurrent.ConcurrentHashMap}, evicted on {@code WorldUnloadEvent}).
 * {@link #get(ServerLevel)} is called from the region-thread chunk-send hot path
 * ({@link dev.iyanz.sourbycraft.antixray.OreReveal#onChunkSent}), so it must never touch disk —
 * the unified TOML is fully loaded before any world exists and reads are pure map lookups.
 */
public final class SourbyCraftWorldConfig {

    private final String worldName;

    // SourbyCraft - antixray config (per-world, read once at construction).
    /** When true, a fluid (water/lava) between eye and ore obscures it (ore stays hidden). */
    public final boolean fluidObscures;
    /** When true, ALL Paper anti-xray hidden-blocks (not just ores) are raytrace-gated. */
    public final boolean allBlocks;
    /** F2/Agent-B reuse: obfuscate tile-entity / block-entity NBT beyond the range below. */
    public final boolean entityObfuscation;
    /** F2/Agent-B reuse: distance (blocks) past which entity/tile NBT is obfuscated. */
    public final int entityObfuscationRange;

    private SourbyCraftWorldConfig(String worldName) {
        this.worldName = worldName;
        this.fluidObscures = worldBool("fluid-obscures", true);
        this.allBlocks = worldBool("all-blocks", false);
        this.entityObfuscation = worldBool("entity-obfuscation", true);
        this.entityObfuscationRange = worldInt("entity-obfuscation-range", 64);
    }

    // SourbyCraft MT1 - PerWorldHolder centralizes WorldUnloadEvent eviction (SWM island resets
    // reuse world names, so a cached config MUST drop on unload). Folia-safe ConcurrentHashMap.
    private static final dev.iyanz.sourbycraft.core.PerWorldHolder<SourbyCraftWorldConfig> BY_WORLD =
        new dev.iyanz.sourbycraft.core.PerWorldHolder<>();

    /** Cached per-world config for {@code level}'s Bukkit world (region-thread safe, no disk). */
    public static SourbyCraftWorldConfig get(ServerLevel level) {
        String name = level.getWorld().getName();
        return BY_WORLD.computeIfAbsent(name, SourbyCraftWorldConfig::new);
    }

    /** Drop the cached config on world unload — SWM island resets reuse world names. */
    public static void invalidate(String worldName) {
        BY_WORLD.remove(worldName);
    }

    /** Per-world override under {@code antixray.world-overrides.<world>.<key>}, else shared default. */
    private boolean worldBool(String key, boolean def) {
        boolean shared = SourbyCraftConfig.cfgBool("antixray." + key, def);
        return SourbyCraftConfig.cfgBool("antixray.world-overrides." + worldName + "." + key, shared);
    }

    private int worldInt(String key, int def) {
        int shared = SourbyCraftConfig.cfgInt("antixray." + key, def);
        return SourbyCraftConfig.cfgInt("antixray.world-overrides." + worldName + "." + key, shared);
    }
}
