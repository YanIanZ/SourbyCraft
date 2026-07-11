package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Baritone / anti-raid defense: seeds Paper's built-in anti-xray engine to <b>engine-mode 2
 * (OBFUSCATE / fake-ores)</b> with a strong, believable phantom-ore palette, plus a base-indicator
 * hidden-block set, at boot — before any world's chunk-packet controller is constructed.
 *
 * <h2>Why this defeats Baritone</h2>
 * Baritone (and every xray client) reads the block data the server sends to the client and beelines
 * to real ores. Paper engine-mode 1 (HIDE) merely replaces occluded ores with plain stone — so an
 * xray/Baritone that reads the client palette sees "no ores here" and can still infer real ore
 * positions from the ones the engine leaks (cave-exposed / above max-block-height). Engine-mode 2
 * (OBFUSCATE) instead FILLS every occluded position with a scattered mix of the {@code hidden-blocks}
 * (the ores) + {@code replacement-blocks} (stone filler) — so the client sees <em>phantom ores
 * everywhere underground</em>. Baritone cannot distinguish a real diamond from one of thousands of
 * fakes, wastes its time mining noise, and its auto-miner heuristics collapse. SourbyCraft's
 * {@link OreReveal} raytrace layer sits on top and re-sends the REAL block for ores the player has
 * genuine line-of-sight to, so a legit player standing in an open cave sees reality while an xray
 * client behind a wall sees the fake field. The two layers compose on disjoint sets: Paper obfuscates
 * what it deems occluded; OreReveal reveals the genuinely-visible subset.
 *
 * <h2>Anti-raid (base scouting)</h2>
 * Raiders xray for chests / valuables to find underground bases. The base-indicator blocks are
 * {@code EntityBlock}s; when they are in Paper's {@code hidden-blocks} list under engine-mode 2 they
 * are obfuscated (masked into the same fake ore+stone scatter) but — by Paper's own rule
 * ({@code ChunkPacketBlockControllerAntiXray}: {@code if (!(block instanceof EntityBlock))}) — are
 * NEVER themselves added to the fake palette. So a buried chest reads to an xray client as random
 * stone/ore noise, not "chest here", and the base is invisible to scouting. Standing players still
 * see it (the real block-entity data is sent for loaded, visible chunks as normal).
 *
 * <h2>Cost</h2>
 * This is a boot-time CONFIG seed, not a hot path. It runs once in {@link SourbyCraftConfig#init}
 * (which fires from the post-config hook, strictly BEFORE {@code DedicatedServer#loadLevel} builds
 * each {@code Level}'s {@code chunkPacketBlockController} from {@code paperConfig().anticheat.antiXray}).
 * The obfuscation itself is Paper's already-optimized C-tier engine code — enabling mode 2 vs mode 1
 * costs the same per-chunk pass (both walk the same sections); mode 2 writes a scattered palette
 * instead of a single stone id, a negligible per-section difference that Paper ships as its
 * recommended anti-xray mode. No new per-tick / per-packet work is added here.
 *
 * <p>Gated on {@code antixray.baritone-defense} (default true). When on, the anti-xray block of
 * {@code config/paper-world-defaults.yml} is seeded to engine-mode 2 + the strong palette ONCE — only
 * while it still holds Paper's stock defaults ({@code enabled: false}). Once seeded (or if the operator
 * has already enabled Paper anti-xray themselves) it is left untouched, so operator edits are never
 * clobbered and reboots are no-ops. Turn {@code baritone-defense} off to manage Paper anti-xray by hand.
 */
public final class PaperAntiXrayDefense {

    private PaperAntiXrayDefense() {}

    /** Paper's world-defaults config, relative to CWD (Paper's default {@code --paper-settings-directory}). */
    private static final Path WORLD_DEFAULTS = Paths.get("config", "paper-world-defaults.yml");

    /**
     * Strong phantom-ore + filler palette written to {@code hidden-blocks}. Under engine-mode 2 every
     * non-EntityBlock entry here becomes part of the scattered fake palette the client sees for occluded
     * positions — a rich, believable field of every ore + deepslate variant so Baritone/xray cannot
     * tell real from fake. Raw metal blocks + glowstone/obsidian/mossy-cobblestone/clay widen the
     * scatter so the noise looks like a naturally-generated ore-bearing cave wall. All ore-ish blocks;
     * NOT the base-indicator EntityBlocks (those are added separately, see {@link #BASE_BLOCKS}).
     */
    private static final List<String> ORE_PALETTE = List.of(
        "coal_ore", "deepslate_coal_ore",
        "copper_ore", "deepslate_copper_ore",
        "iron_ore", "deepslate_iron_ore",
        "gold_ore", "deepslate_gold_ore", "nether_gold_ore",
        "redstone_ore", "deepslate_redstone_ore",
        "lapis_ore", "deepslate_lapis_ore",
        "diamond_ore", "deepslate_diamond_ore",
        "emerald_ore", "deepslate_emerald_ore",
        "nether_quartz_ore", "ancient_debris",
        "raw_iron_block", "raw_copper_block", "raw_gold_block",
        "glowstone", "obsidian", "mossy_cobblestone", "clay"
    );

    /**
     * Stone-family filler for {@code replacement-blocks} — the "background" the fake ores are scattered
     * against. A varied but natural set so the obfuscated wall reads as real cave stone, not a flat
     * block of one type (which an xray heuristic could fingerprint). Netherrack/end-stone are handled
     * by the engine per-dimension; these cover the overworld filler.
     */
    private static final List<String> FILLER_PALETTE = List.of(
        "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "dirt", "gravel"
    );

    /**
     * Base-indicator / valuable storage EntityBlocks hidden from xray (anti-raid). Obfuscated into the
     * fake scatter under engine-mode 2 but never themselves shown as fakes (Paper skips EntityBlocks in
     * the preset palette), so a base's chests/valuables are invisible to scouting xray yet normal to a
     * player standing in the base. Config-overridable via {@code antixray.base-blocks}.
     */
    private static final List<String> BASE_BLOCKS = List.of(
        "chest", "trapped_chest", "barrel", "ender_chest",
        "shulker_box",
        "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box",
        "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box", "gray_shulker_box",
        "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
        "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box",
        "hopper", "dropper", "dispenser",
        "furnace", "blast_furnace", "smoker",
        "brewing_stand", "beacon", "conduit", "lodestone",
        "spawner", "trial_spawner", "vault",
        "decorated_pot"
    );

    /** Seed the SourbyCraft-owned baritone-defense keys into the unified TOML (absent-only). */
    public static void seedDefaults(com.electronwill.nightconfig.core.file.CommentedFileConfig f, boolean[] changed) {
        seed(f, changed, "antixray.baritone-defense", true,
            "Baritone/xray defense: seed Paper's built-in anti-xray to engine-mode 2 (fake-ores) with a "
            + "strong phantom-ore palette at boot, so xray/Baritone sees scattered fake ores everywhere "
            + "underground and cannot find the real ones. SourbyCraft's raytrace layer still reveals the "
            + "genuinely visible ores to legit players (the layers compose). true = seed config/paper-"
            + "world-defaults.yml to engine-mode 2 ONCE (only while it holds Paper's stock enabled:false "
            + "default; operator edits are never clobbered). false = never touch Paper anti-xray; manage "
            + "it yourself in paper-world-defaults.yml. Heavy on very large servers only if you also raise "
            + "max-block-height a lot; the mode-2 fill costs ~the same per chunk as mode 1.");
        seed(f, changed, "antixray.engine-mode", 2,
            "Paper anti-xray engine mode written when baritone-defense seeds the config. 2 = OBFUSCATE "
            + "(fake ores, defeats Baritone — recommended). 1 = HIDE (replace occluded ores with plain "
            + "stone; cheaper but xray can infer real ore from the leaked/exposed ones). 3 = OBFUSCATE_LAYER.");
        seed(f, changed, "antixray.max-block-height", 128,
            "Paper anti-xray only obfuscates BELOW this Y (rounded to a 16-block section). Paper's stock "
            + "default is 64; SourbyCraft raises it to 128 so mountain/mesa surface ores and shallow bases "
            + "are also protected against xray. Higher = a few more chunk sections obfuscated per send "
            + "(small, bounded cost); set back to 64 for the lightest footprint on huge servers.");
        seed(f, changed, "antixray.hide-base-blocks", true,
            "Anti-raid: add chests/barrels/shulkers/spawners/valuable storage (antixray.base-blocks) to "
            + "Paper's anti-xray hidden set so an underground base is invisible to xray scouting — the "
            + "block-entities are masked into the fake ore/stone scatter (Paper never shows an EntityBlock "
            + "as a fake) and revealed only to a player with actual line-of-sight. false = ores only.");
        seed(f, changed, "antixray.base-blocks", new ArrayList<>(BASE_BLOCKS),
            "Block ids (no minecraft: prefix) treated as base indicators and hidden from xray when "
            + "hide-base-blocks is true. Storage + valuable block-entities. Edit to taste; unknown ids are "
            + "ignored by Paper. Only applied while baritone-defense seeds the config.");
    }

    /**
     * If {@code antixray.baritone-defense} is on and Paper's world-defaults still hold the stock
     * {@code anti-xray.enabled: false}, rewrite the {@code anticheat.anti-xray} block to engine-mode 2
     * with the strong ore palette (+ base blocks when {@code hide-base-blocks}). Non-destructive: only
     * the anti-xray block is replaced (a contiguous, comment-free region Paper regenerates each boot);
     * every other comment / setting in the file is preserved verbatim. Idempotent — once seeded the
     * block no longer holds {@code enabled: false}, so subsequent boots are no-ops.
     *
     * <p>Must run in {@link SourbyCraftConfig#init} (post-config hook) which fires BEFORE
     * {@code DedicatedServer#loadLevel} builds each world's chunk-packet controller from this file.
     */
    public static void apply() {
        if (!SourbyCraftConfig.cfgBool("antixray.baritone-defense", true)) {
            SourbyLogger.debug("[antixray] baritone-defense off — Paper anti-xray left untouched");
            return;
        }
        if (!Files.isRegularFile(WORLD_DEFAULTS)) {
            SourbyLogger.warn("[antixray] baritone-defense: " + WORLD_DEFAULTS
                + " not found (non-standard config dir?); skipping engine-mode-2 seed");
            return;
        }
        final String content;
        try {
            content = Files.readString(WORLD_DEFAULTS, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SourbyLogger.warn("[antixray] baritone-defense: could not read " + WORLD_DEFAULTS + ": " + e.getMessage());
            return;
        }

        final String[] lines = content.split("\n", -1);
        // Locate the top-level "anticheat:" line and the "  anti-xray:" line under it, then the extent
        // of the anti-xray block (up to the next line at <= 2-space indent that is not part of it).
        int anticheatIdx = -1, antiXrayIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("anticheat:")) { anticheatIdx = i; }
            else if (anticheatIdx >= 0 && lines[i].equals("  anti-xray:")) { antiXrayIdx = i; break; }
        }
        if (antiXrayIdx < 0) {
            SourbyLogger.warn("[antixray] baritone-defense: anticheat.anti-xray block not found in "
                + WORLD_DEFAULTS + " (unexpected Paper layout); skipping");
            return;
        }
        // End = first line after the anti-xray keys whose indent is <= 4 spaces AND is a new key at the
        // anti-xray level or shallower. The anti-xray value keys are indented 4 spaces ("    enabled:"),
        // list items "    - x". The block ends at the next 2-space key ("  something:") or a 0-space key.
        int endIdx = lines.length;
        for (int i = antiXrayIdx + 1; i < lines.length; i++) {
            final String ln = lines[i];
            if (ln.isEmpty()) continue;
            final int indent = indentOf(ln);
            if (indent <= 2 && !ln.startsWith("    ")) { endIdx = i; break; }
        }

        // Read the current enabled state within the block; only seed while it is Paper's stock false.
        boolean stockDisabled = false;
        for (int i = antiXrayIdx + 1; i < endIdx; i++) {
            if (lines[i].trim().equals("enabled: false")) { stockDisabled = true; break; }
            if (lines[i].trim().equals("enabled: true")) { stockDisabled = false; break; }
        }
        if (!stockDisabled) {
            SourbyLogger.info("[antixray] baritone-defense: Paper anti-xray already enabled/customized in "
                + WORLD_DEFAULTS + " — leaving it untouched (already defended or operator-managed)");
            return;
        }

        final int engineMode = clampEngineMode(SourbyCraftConfig.cfgInt("antixray.engine-mode", 2));
        final int maxBlockHeight = SourbyCraftConfig.cfgInt("antixray.max-block-height", 128);
        final boolean hideBase = SourbyCraftConfig.cfgBool("antixray.hide-base-blocks", true);

        final List<String> hidden = new ArrayList<>();
        // Preserve determinism + de-dupe: LinkedHashSet keeps insertion order, drops accidental dupes.
        final Set<String> hiddenSet = new LinkedHashSet<>(ORE_PALETTE);
        if (hideBase) {
            List<String> baseBlocks = SourbyCraftConfig.cfgStringList("antixray.base-blocks");
            hiddenSet.addAll(baseBlocks.isEmpty() ? BASE_BLOCKS : baseBlocks);
        }
        hidden.addAll(hiddenSet);

        final StringBuilder block = new StringBuilder();
        block.append("  anti-xray:\n");
        block.append("    enabled: true\n");
        block.append("    engine-mode: ").append(engineMode).append('\n');
        block.append("    hidden-blocks:\n");
        for (String b : hidden) block.append("    - ").append(b).append('\n');
        block.append("    lava-obscures: false\n");
        block.append("    max-block-height: ").append(maxBlockHeight).append('\n');
        block.append("    replacement-blocks:\n");
        for (String b : FILLER_PALETTE) block.append("    - ").append(b).append('\n');
        block.append("    update-radius: 2\n");
        block.append("    use-permission: false\n");

        // Rebuild the file: [0, antiXrayIdx) + our block + [endIdx, end).
        final StringBuilder out = new StringBuilder(content.length() + block.length());
        for (int i = 0; i < antiXrayIdx; i++) out.append(lines[i]).append('\n');
        out.append(block);
        for (int i = endIdx; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }

        try {
            Files.writeString(WORLD_DEFAULTS, out.toString(), StandardCharsets.UTF_8);
            SourbyLogger.info("[antixray] baritone-defense: seeded Paper anti-xray -> engine-mode "
                + engineMode + " (fake-ores), " + hidden.size() + " hidden blocks ("
                + ORE_PALETTE.size() + " ore palette" + (hideBase ? " + base indicators" : "")
                + "), max-block-height " + maxBlockHeight + ", " + FILLER_PALETTE.size()
                + " replacement/filler blocks in " + WORLD_DEFAULTS + " — worlds build their controller from it");
        } catch (IOException e) {
            SourbyLogger.warn("[antixray] baritone-defense: could not write " + WORLD_DEFAULTS + ": " + e.getMessage());
        }
    }

    /**
     * Register a per-world confirmation log so the boot log shows the LIVE anti-xray engine each world
     * actually built its chunk-packet controller with (definitive proof the fake-ore engine is active,
     * not inert). Reuses a single {@link WorldLoadEvent} listener (worlds load after this actuator hook,
     * so {@code Bukkit.getWorlds()} is empty here); cost is one log line per world at load — no hot path.
     * Only meaningful/registered when baritone-defense is on (else there is nothing to confirm).
     */
    public static void registerConfirmationLog(org.bukkit.plugin.Plugin plugin) {
        if (!SourbyCraftConfig.cfgBool("antixray.baritone-defense", true)) return;
        org.bukkit.Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onWorldLoad(WorldLoadEvent e) {
                try {
                    var ax = ((org.bukkit.craftbukkit.CraftWorld) e.getWorld()).getHandle()
                        .paperConfig().anticheat.antiXray;
                    plugin.getLogger().info("[antixray] world '" + e.getWorld().getName()
                        + "': Paper anti-xray " + (ax.enabled ? "ACTIVE engine-mode " + ax.engineMode.getId()
                        + " (" + ax.engineMode.getDescription() + "), " + ax.hiddenBlocks.size()
                        + " hidden blocks, max-y " + ax.maxBlockHeight : "disabled"));
                } catch (Throwable ignored) { /* never break world load for a log line */ }
            }
        }, plugin);
    }

    /**
     * Whether Paper's anti-xray will be enabled for freshly-loaded worlds, read from the world-defaults
     * file's {@code anticheat.anti-xray.enabled} state. Used by {@link OreReveal#register} to decide
     * whether to warn that the raytrace layer would be inert — the actuator hook runs BEFORE
     * {@code DedicatedServer#loadLevel}, so {@code Bukkit.getWorlds()} is still empty there and cannot be
     * consulted; this reads the on-disk defaults that every world's controller is about to be built from
     * (already reflecting {@link #apply()} if baritone-defense seeded it). Returns {@code true} on read
     * failure so a transient IO problem never produces a spurious "inert" warning.
     */
    public static boolean willPaperAntiXrayBeEnabled() {
        if (!Files.isRegularFile(WORLD_DEFAULTS)) return false;
        try {
            final List<String> lines = Files.readAllLines(WORLD_DEFAULTS, StandardCharsets.UTF_8);
            boolean inAntiXray = false;
            boolean sawAnticheat = false;
            for (String ln : lines) {
                if (ln.equals("anticheat:")) { sawAnticheat = true; continue; }
                if (sawAnticheat && ln.equals("  anti-xray:")) { inAntiXray = true; continue; }
                if (inAntiXray) {
                    final String t = ln.trim();
                    if (t.equals("enabled: true")) return true;
                    if (t.equals("enabled: false")) return false;
                    // Left the anti-xray block (a new key at <= 2-space indent) without finding enabled.
                    if (!ln.isEmpty() && indentOf(ln) <= 2 && !ln.startsWith("    ")) break;
                }
            }
        } catch (IOException e) {
            return true; // fail-open: don't warn on a read error
        }
        return false;
    }

    private static int indentOf(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static int clampEngineMode(int m) {
        return (m == 1 || m == 2 || m == 3) ? m : 2;
    }

    private static void seed(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                             boolean[] changed, String path, Object def, String comment) {
        if (!f.contains(path)) {
            f.add(path, def);
            if (comment != null) f.setComment(path, comment);
            changed[0] = true;
        }
    }
}
