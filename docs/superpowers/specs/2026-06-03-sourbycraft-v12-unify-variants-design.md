# SourbyCraft v12 — Unify Variants & Remove Custom `/plugins`

**Date**: 2026-06-03
**Status**: Approved (brainstorm), pending implementation plan
**Scope**: Single PR, 6 phases. Out-of-scope: UniverseSpigot config import, NMS plugin compat (Citizens/DecentHolograms/NBTAPI) — tracked in separate follow-up specs.

## Goal

Collapse the dual-jar build (`SourbyCraft-v12-REL.jar` + `SourbyCraft-PVP-v12-REL.jar`) into a single unified jar where PvP behavior is gated at runtime by `pvp.enabled` in `sourbycraft.yml`. Remove the custom `/plugins` command so vanilla Paper's brigadier `PaperPluginsCommand` is authoritative. Strip the variant-overlay infrastructure (gradle property, build tasks, resource overlay, config loader fallback) since it no longer serves a purpose.

## Non-Goals

- Importing the ~200-key UniverseSpigot config surface (separate scope, will be decomposed into sub-specs per category).
- NMS-binding fixes for Citizens / DecentHolograms / NBTAPI (separate investigation spec).
- Multi-profile system ("survival", "creative", "pvp" profiles). Single PvP toggle only.
- Backward-compat shims for the `variant` gradle property.

## Architecture

**Before** (v12 dual-variant):
```
gradle -Pvariant={normal|pvp}
  ├─ build.gradle.kts: isPvpVariant gating, processVariantResources, jar suffix
  ├─ patches/server/9001-9005 (stash-skip when !PvP)
  ├─ src/main/resources/variant-overlay/{normal,pvp}/*.yml + server.properties
  └─ output: 2 jars

SourbyCraftConfig:
  ymlGet(path) → overlay first, baseline fallback
```

**After** (v12 unified):
```
gradle (no variant flag)
  ├─ build.gradle.kts: single build path
  ├─ patches/server/* (former 9001-9005 apply unconditionally, runtime-gated by pvp.enabled)
  └─ output: 1 jar (SourbyCraft-v12-REL.jar)

SourbyCraftConfig:
  ymlGet(path) → baseline only
  pvpEnabled (yml: pvp.enabled, default false) → runtime gate for all PvP behavior
```

**Invariant**: PvP capability preserved 1:1. Every PvP behavior gated through `SourbyCraftConfig.pvpEnabled`. Default `false`. Opt-in via single config key.

## Components Changed

### Build (`build.gradle.kts`)
- DELETE: `sourbycraftVariant` property read, `isPvpVariant` flag, lines 53-110 (`processVariantResources` task, `generateBuildInfo` variant block, overlay copy into `build/variant-resources/`), lines 198-265 (marker file logic + PvP patch stash/unstash), lines 268-272 (jar variant suffix logic).
- KEEP: paperweight bundleJar wiring with `-PVP` suffix removed.
- VERIFY: no remaining reads of `providers.gradleProperty("variant")`.

### Patches (`patches/server/`)
- Strip `9xxx-PVP-*` prefix convention. After variant stash/skip logic is removed in phase 1, these patches apply unconditionally as part of the standard sequence.
- Affected files (5 total):
  - `9001-PVP-netty-tuning.patch`
  - `9002-PVP-entity-tracker-tightening.patch`
  - `9003-PVP-cpu-pin-gc-banner.patch`
  - `9004-PVP-combat-completion.patch`
  - `9005-PVP-proxy-kick.patch`
- AUDIT each: ensure runtime entrypoint reads `SourbyCraftConfig.pvpEnabled` and short-circuits when `false`. Patches whose effects are already field-driven (e.g., knockback math that reads `pvpKnockback*` values from config) need no change. CPU-pin, netty tuning, GC banner, and proxy-kick logic must add an explicit `pvpEnabled` check at activation if not already present.
- Final patch numbering is determined by paperweight `rebuildPatches` after phases 4 + 5 reverts. Do not hardcode target numbers; let paperweight assign them sequentially.

### Config loader (`SourbyCraftConfig.java`)
- DELETE field `sourbycraftYmlOverlay` (line 29).
- DELETE overlay branch in `ymlGet` (lines 48-51) and its associated try/catch.
- KEEP: `pvpEnabled` + all `pvp*` keys (lines 137-151) + the PvP runtime override block (lines 354-381).
- **FLIP** field default `public static boolean pvpEnabled = true` → `false` (line 137). Required so that fresh installs default to non-PvP. Existing operators whose yml already contains `pvp.enabled: true` (written by prior versions' `config.addDefault`) keep PvP behavior on upgrade.

### Custom `/plugins` command
- DELETE FILE: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/PluginsCommand.java`.
- EDIT `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` lines 424 and 1063: remove both `this.commandMap.register("", new dev.iyanz.sourbycraft.command.PluginsCommand("plugins"));` lines.
- REVERT patch `0031-SourbyCraft-v12-plugins-reformat-use-PluginsCommandF.patch`: delete the patch file, rebuild series from current paper-server state via `./gradlew rebuildPatches`.
- Vanilla `PaperPluginsCommand.java` (line 71) registers `/plugins` automatically — no further work needed to restore it.

### Plugin auto-installer
- DELETE `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/normal.yml`.
- DELETE `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/pvp.yml`.
- CREATE `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins.yml` containing only the `spark` Jenkins entry (verbatim from current `normal.yml`).
- EDIT `PluginAutoInstaller.java` (and `PluginManifest.java` if it scans per-variant): hardcode resource path to `/META-INF/sourbycraft-plugins.yml`, drop variant-aware manifest selection logic.

### Variant overlay assets
- DELETE recursively: `sourbycraft-server/src/main/resources/variant-overlay/`.
- REVERT patch `0032-SourbyCraft-v12-seed-server-properties-from-variant-.patch`: delete the patch file, rebuild series.
- VERIFY no code references the resource path `/sourbycraft-variant-overlay.yml`.

### Release artifacts
- DELETE `release/SourbyCraft-PVP-v12-REL.jar`.
- KEEP `release/SourbyCraft-v12-REL.jar` (rebuilt fresh from unified codebase).
- UPDATE `release/checksums.txt` to a single sha256 line for the unified jar.

## Data Flow

### Config read

**Before**:
```
ymlGet("pvp.knockback.friction") →
  1. lookupYml(sourbycraftYmlOverlay, path)   ← variant-overlay/pvp/sourbycraft.yml
  2. lookupYml(sourbycraftYmlBaseline, path)  ← sourbycraft.yml (resources root)
  3. defaultValue
```

**After**:
```
ymlGet("pvp.knockback.friction") →
  1. lookupYml(sourbycraftYmlBaseline, path)  ← sourbycraft.yml (only)
  2. defaultValue
```

Operator-edited `plugins/SourbyCraft/sourbycraft.yml` (runtime config file) unchanged — still authoritative via `config.getBoolean/Int/etc.` in `SourbyCraftConfig.init`.

### PvP activation path

```
Server boot
  └─ SourbyCraftConfig.init(file)
       ├─ readConfig(): hydrate all keys from operator yml
       ├─ pvpEnabled = config.getBoolean("pvp.enabled", false)   // default OFF
       └─ if (pvpEnabled) { existing override block lines 354-381 }
            ├─ override PufferfishConfig.startDistance (DAB)
            ├─ tighten maxEntityPerChunk, maxSpecialsPerChunk
            └─ log activation banner

Runtime tick / hot path (patches 0033-0037)
  └─ each PvP feature checks SourbyCraftConfig.pvpEnabled at entry
       ├─ knockback: read pvpKnockback{Horizontal,Vertical,Extra*,Friction}
       ├─ attack cooldown: gate by pvpNoAttackCooldown
       ├─ view-distance cap: apply pvpViewDistanceCap if > 0
       └─ ... per patch
```

No overlay → PvP default off on fresh install. Operator must explicitly set `pvp.enabled: true`.

### Plugin auto-installer

**Before**:
```
PluginAutoInstaller.run()
  └─ select manifest: /META-INF/sourbycraft-plugins/{variant}.yml
  └─ parse PluginManifest → PluginEntry[]
  └─ for each entry: PluginDownloader.fetch + place in plugins/
```

**After**:
```
PluginAutoInstaller.run()
  └─ load /META-INF/sourbycraft-plugins.yml (fixed path)
  └─ parse → [{name: spark, source: jenkins, url: ..., asset-glob: spark-*-bukkit.jar}]
  └─ PluginDownloader.fetch + place in plugins/
```

No variant lookup. No build-info read.

### `/plugins` command dispatch

**Before**: `commandMap.register("plugins", PluginsCommand)` overrides vanilla — custom dispatch fires.
**After**: registration removed → `PaperPluginsCommand.literal("plugins")` (brigadier) handles it via standard Paper command tree.

## Error Handling & Edge Cases

### Migration: existing operator running PvP jar
- Prior versions wrote `pvp.enabled: true` to operator yml on first init via `config.addDefault("pvp.enabled", pvpEnabled)` where the field default was `true`. So every existing server (normal and PvP variant alike) has the key persisted as `true` on disk.
- After merge, field default flips to `false`, but the operator yml's persisted `true` wins → PvP runtime activates identically for existing PvP operators. No data migration code needed.
- Existing **normal** operators carry the same persisted `pvp.enabled: true`. Their normal-variant behavior was historically governed by the `if (pvpEnabled)` override block at lines 354-381 (which would have triggered all along) — investigate whether normal variant operators were already getting PvP-style DAB tightening / view-distance caps. If yes: no behavior change post-merge. If no (e.g., field was false in deployed normal builds): document the new default in release notes and instruct normal operators to set `pvp.enabled: false`.
- Fresh-install operators: `pvp.enabled` absent → field default `false` written → no PvP behavior. Opt-in by editing yml.

### Migration: paper-global.yml / spigot.yml / server.properties
- These files exist in the operator's data dir and were only seeded on first boot by the overlay. Existing operators unaffected (their files are already on disk and won't be overwritten).
- Fresh PvP operator after merge: gets normal Paper defaults + `pvp.enabled` toggle. View-distance / sim-distance enforced at runtime via PvP override block (already in place via `pvpViewDistanceCap` / `pvpSimulationDistanceCap`). During patch port (phase 1), verify patch 0033 (former 9001) clamps Paper's `viewDistance` from these caps. If not, add clamp logic.

### Patch rebuild after revert
- Reverting patches 0031 + 0032 requires `./gradlew rebuildPatches` after editing `paper-server` source tree manually.
- Risk: dirty `paper-server` tree from current variant work. Mitigate: run `./gradlew applyAllPatches` to fresh state first, verify clean, then make edits.

### Patch number collision
- Paperweight `rebuildPatches` reassigns numbers after the 0031 + 0032 reverts and the patch renames. No manual collision check needed.

### Stale references to `PluginsCommand.java`
- Grep for `PluginsCommand` across all modules before deletion. If `sourbycraft-api`, swm modules, test-plugin, or other patches (e.g., 0013 consolidate-all-commands) reference the class, fix or fail build.
- Inspect patch 0013 (`consolidate-all-commands-in-single-patch.patch`) — it may register `PluginsCommand` and need edit.

### Stale references to overlay resource path
- After loader cleanup, grep across all modules for `getResourceAsStream("/sourbycraft-variant-overlay.yml")` and `sourbycraft-variant-overlay`. Remove any hits.

### Build-info `variant` property consumers
- `generateBuildInfo` writes `variant=$variant` to `META-INF/sourbycraft-build.properties`. Grep for consumers — likely brand banner and `/ver` command (patch 0029-SourbyCraft-v12-ver-shows-variant-tagline).
- Decide: drop the `variant=` field entirely, or hardcode `variant=unified`. Update consumers accordingly. Recommend dropping the field; consumers fall back to neutral string.

### Release checksums.txt format
- Current file has 2 entries (normal + PVP). After cleanup → 1 entry. Same `<sha256>  <filename>` format as existing lines.

### Backward compat for operators with `pvp.enabled: false` running a former PvP server
- They will lose PvP behavior on upgrade unless they flip the flag. Document in release notes / CHANGELOG. Not a code issue.

## Testing

### Build verification
- `./gradlew clean applyAllPatches` — full patch sequence applies clean, no rejects.
- `./gradlew createPaperclipJar` — produces single `SourbyCraft-paperclip-v12-REL.jar`, no `-PVP` suffix anywhere.
- `./gradlew rebuildPatches` after revert — diff stays minimal (no spurious churn).
- Grep gate: `grep -rn "variant" build.gradle.kts sourbycraft-server/src` → only comments / unrelated refs. Zero references to `processVariantResources`, `isPvpVariant`, `sourbycraft-variant-overlay.yml`, `META-INF/sourbycraft-plugins/{normal,pvp}.yml`.

### Smoke: server boot
- Launch fresh data dir, `pvp.enabled` absent → server boots, no PvP banner in log, `/plugins` returns Paper's brigadier output (with grouping by author/source, not custom format).
- Set `pvp.enabled: true` in `plugins/SourbyCraft/sourbycraft.yml`, restart → log line `[SourbyCraft:PvP] PvP server mode active — KB friction=1.0 no-cooldown=true …` present. DAB `startDistance` tightened.

### PvP behavior runtime
- With `pvp.enabled: true`:
  - Hit player, knockback feels 1.8 (friction=1.0). Stop sprint = no critical (`preventCriticalsIfSprinting`).
  - Attack cooldown = 1.0 always (`pvpNoAttackCooldown`).
  - View-distance forced to 6 (`pvpViewDistanceCap`).
  - Mob AI throttled past 16 blocks.
- With `pvp.enabled: false`:
  - Vanilla 1.9 knockback + cooldown bar. View-distance from `server.properties`. Mob AI vanilla / Pufferfish defaults.

### Plugin auto-installer
- Fresh boot, no `plugins/spark-*.jar` → installer fetches spark from Jenkins, places in `plugins/`. No attempt to fetch ViaVersion, ViaBackwards, or PacketEvents.
- Existing spark jar present → installer skips (existence check).

### `/plugins` removal
- `/plugins` from console + in-game → Paper's brigadier `PaperPluginsCommand` output. No custom format.
- Tab-completion works (vanilla behavior).

### Release artifact
- `release/SourbyCraft-PVP-v12-REL.jar` absent.
- `release/SourbyCraft-v12-REL.jar` rebuilt; sha256 in `checksums.txt` matches the new jar.
- `checksums.txt` has exactly 1 line.

### Regression
- Existing non-PvP, non-variant patches unchanged (no accidental edits during rebuild). Diff against pre-PR `patches/server/` and confirm only target patches (0031, 0032, former 9001-9005) moved.
- Existing tests pass (`./gradlew test` if defined).

## Implementation Phases

Seven phases in a single PR. Each phase leaves the tree build-able.

1. **Remove variant build infra** from `build.gradle.kts` (gradle property, `processVariantResources`, `generateBuildInfo` variant block, `isPvpVariant` gating, marker file logic, jar suffix, PvP-patch stash/unstash logic). After this phase, `gradle applyAllPatches` applies all `9xxx-PVP-*` patches as part of the normal sequence (since stash/skip logic is gone).
2. **Audit PvP patches for `pvpEnabled` gating**: edit `9001-9005` patch contents (or paper-server source then rebuildPatches) so each runtime entrypoint checks `SourbyCraftConfig.pvpEnabled` and short-circuits when `false`. Patches whose effects are already field-driven (knockback math reads `pvpKnockback*`) need no change.
3. **Flip `pvpEnabled` field default** from `true` to `false` in `SourbyCraftConfig.java` line 137. Remove overlay loader: delete `sourbycraftYmlOverlay` field + branch in `ymlGet`.
4. **Delete `variant-overlay/` resource tree** + revert patch `0032-SourbyCraft-v12-seed-server-properties-from-variant-.patch` (delete file, rebuild series).
5. **Remove custom `/plugins`**: delete `PluginsCommand.java`, remove the two `CraftServer.java` registrations, revert patch `0031-SourbyCraft-v12-plugins-reformat-use-PluginsCommandF.patch`, rebuild patch series. After rebuild, paperweight will renumber the patches; final patch numbers are paperweight-determined and not specified here.
6. **Simplify plugin auto-installer**: delete `normal.yml` + `pvp.yml`, create unified `sourbycraft-plugins.yml` (spark only), hardcode path in `PluginAutoInstaller`.
7. **Release cleanup**: delete `SourbyCraft-PVP-v12-REL.jar`, rebuild unified jar, regenerate `release/checksums.txt` (single sha256 line).

Rejected alternatives:
- **Bottom-up start from `/plugins`**: unnatural sequencing — variant infra is more fundamental and should go first.
- **Big-bang single commit**: hard to review and revert per concern.
