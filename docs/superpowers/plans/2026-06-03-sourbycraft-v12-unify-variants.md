# SourbyCraft v12 Unify Variants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the dual-variant (`normal` + `pvp`) build into a single `SourbyCraft-v12-REL.jar`, gate all PvP behavior on the operator-runtime `pvp.enabled` config flag, remove the custom `/plugins` command so vanilla Paper handles it, and strip the variant-overlay infrastructure (gradle property, build tasks, resource overlay, config loader fallback).

**Architecture:** Move all PvP-specific YAML values out of `variant-overlay/pvp/*.yml` and into the baseline `sourbycraft.yml` resource. Wrap every `ymlGet(...)` consumer for PvP keys with `if (SourbyCraftConfig.pvpEnabled)` so non-PvP servers see vanilla behavior. Delete the variant build property, the marker-file logic, the PvP-patch stash/unstash hook, and the jar variant suffix. Drop overlay-loading code from `SourbyCraftConfig`. Revert patches 0031 (custom `/plugins`) and 0032 (server.properties seeder).

**Tech Stack:** Gradle / paperweight 2.0, Java 21 source / 25 toolchain, Paper 1.21.x source patches, SnakeYAML, Kyori Adventure, Bukkit/Brigadier command system.

**Key file locations (verified during planning):**
- 9xxx PvP patches live in `patches/minecraft/`, NOT `patches/server/`. The spec was wrong about path; this plan uses the correct path.
- `PluginsCommand.java` lives inside the patched paper-server tree at `paper-server/src/main/java/dev/iyanz/sourbycraft/command/PluginsCommand.java`. It is introduced by patch `0013-consolidate-all-commands-in-single-patch.patch` and reformatted by `0031-SourbyCraft-v12-plugins-reformat-use-PluginsCommandF.patch`.
- The two `commandMap.register("", new ... PluginsCommand("plugins"))` calls in `CraftServer.java` (lines 424 and 1063 of the current applied tree) are both introduced by patch 0013.
- `ServerPropertiesSeeder.java` lives at `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/ServerPropertiesSeeder.java` (a real source file, not a patched class). It is invoked from `Main.java` via patch 0032.
- `BuildInfo` (`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java`) is read by `SourbyCraftBanner` and `GcAdvisor`, plus the wiring in patch 0030 (loadPlugins).
- Five `9xxx-PVP-*.patch` files in `patches/minecraft/`: 9001 netty, 9002 entity-tracker, 9003 GC-advisor, 9004 combat, 9005 proxy-kick.

**Working-tree workflow note (paperweight 2.0):** Patches in `patches/server/` and `patches/minecraft/` are not edited directly when they contain Java logic changes. Instead, the patched tree under `paper-server/` and `sourbycraft-server/src/minecraft/` is edited, then `./gradlew rebuildPatches` regenerates the `.patch` files. For "delete a patch" operations, the `.patch` file can be removed and the working tree must be reset via `./gradlew clean applyAllPatches` so the next rebuild produces a clean series.

---

## Phase 1: Move PvP YAML values into baseline `sourbycraft.yml`

This phase preserves PvP capability inside the single jar by baking the values into the resource file that the patches already read via `ymlGet(...)`. After this phase the variant overlay is no longer needed; deletion happens in Phase 2.

### Task 1: Update baseline `sourbycraft.yml` with PvP profile values

**Files:**
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml`

- [ ] **Step 1: Replace the baseline file contents**

Write the file with the merged contents below. PvP-only keys take their values from the former PvP overlay. Keys that are universal (auto-install, gc-advisor, etc.) keep their existing baseline values. `pvp.enabled` stays `false` so a fresh install defaults to non-PvP; the runtime override block in `SourbyCraftConfig.init` activates the PvP code paths when the operator flips it.

```yaml
# SourbyCraft configuration (baseline, bundled in jar resources).
# Values here are read by patches via SourbyCraftConfig.ymlGet(...).
# PvP-only consumers wrap their ymlGet calls in `if (SourbyCraftConfig.pvpEnabled)`
# so these PvP-tuned values are effective only when the operator opts in via
# pvp.enabled in plugins/SourbyCraft/sourbycraft.yml.

pvp:
  enabled: false
  knockback:
    friction-divisor: 1.0
    vertical: 0.4
    extra-horizontal: 0.5
  no-attack-cooldown: true
  view-distance-cap: 6
  simulation-distance-cap: 5
  max-mobs-per-chunk: 4
  mob-ai-activation-range: 24

network:
  proxy-mode: velocity-modern
  netty:
    threads: auto-doubled
    snd-buf-kb: 64
    rcv-buf-kb: 64
    max-packets-per-tick: 100
  proxy-kick-grace-seconds: 5
  proxy-kick-fallback: lobby

entity-tracker:
  mob-range: 32
  item-range: 16
  xp-orb-range: 16
  player-update-interval: 1

combat:
  sweep-enabled: false
  hit-delay-ticks: 8
  hit-window-ms: 150
  fishing-rod-knockback: true
  reach-debug-command: true

branding:
  motd-suffix: false
  compact-plugin-list: true
  compact-plugin-log: true
  gc-advisor:
    enabled: true

auto-install:
  enabled: true
```

- [ ] **Step 2: Commit**

```bash
git add sourbycraft-server/src/main/resources/sourbycraft.yml
git commit -m "config: merge PvP overlay values into baseline sourbycraft.yml

PvP-specific values from variant-overlay/pvp/sourbycraft.yml are now baked
into the baseline. Consumers gated by SourbyCraftConfig.pvpEnabled in a
follow-up commit. pvp.enabled stays false so fresh installs default to
non-PvP behavior."
```

---

## Phase 2: Delete variant overlay resource tree

### Task 2: Remove `variant-overlay/` directory

**Files:**
- Delete: `sourbycraft-server/src/main/resources/variant-overlay/` (entire tree, recursive)

- [ ] **Step 1: Delete the directory**

```bash
rm -rf sourbycraft-server/src/main/resources/variant-overlay/
```

- [ ] **Step 2: Verify no source code still references the overlay resource path**

Run:
```bash
grep -rn "variant-overlay\|sourbycraft-variant-overlay" sourbycraft-server/src paper-server/src patches 2>/dev/null
```
Expected output: lines only from `SourbyCraftConfig.java` (overlay loader, to be removed in Phase 3) and the spec/plan docs.

- [ ] **Step 3: Commit**

```bash
git add -A sourbycraft-server/src/main/resources/variant-overlay/
git commit -m "build: delete variant-overlay resource tree

All PvP-specific YAML values were merged into baseline sourbycraft.yml
in the previous commit. The overlay tree is no longer referenced by
the build or by runtime code (overlay loader removed in Phase 3)."
```

---

## Phase 3: Strip variant gradle property + build tasks

### Task 3: Remove variant infra from `build.gradle.kts`

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Open the file and locate the variant blocks**

The following blocks are deleted in their entirety:

1. Lines 53-77: `processVariantResources` task block (entire `val variantOverlayTask = tasks.register<Copy>("processVariantResources") { ... }`).
2. Lines 79-91: the `if (thisProjectName == "sourbycraft-server")` block that wires `processVariantResources` into `ProcessResources` and configures duplicates strategy. The `ProcessResources` `exclude("variant-overlay/**")` and the dependency on `processVariantResources` go away. The `duplicatesStrategy` block on `Jar` tasks is kept by re-adding a slimmer version (see Step 2).
3. Lines 93-127: `writeBuildInfo` block AND the `tasks.named("processVariantResources").configure { finalizedBy(writeBuildInfoTask) }` line AND the second `if (thisProjectName == "sourbycraft-server")` block that wires `writeBuildInfo` into `ProcessResources`. The build-info file is regenerated as a slimmer task in Step 2 because `BuildInfo` callers still need version/timestamp.
4. Lines 154-159: variant property read + `isPvpVariant` flag + lifecycle log line.
5. Lines 198-220: variant marker file logic (`run { val marker = file("build/.sourbycraft-applied-variant") ... }`).
6. Lines 222-266: PvP-patch stash/unstash logic (`fun patchKindFor` + `allprojects { tasks.matching { ... } }`).
7. Lines 268-287: `gradle.projectsEvaluated { ... }` block that suffixes the paperclip jar with `-PVP`.

- [ ] **Step 2: Replace the deleted blocks with a slimmed `writeBuildInfo` task**

Insert this in place of the deleted `writeBuildInfo` block (around former lines 93-127):

```kotlin
    // SourbyCraft v12 — emit META-INF/sourbycraft-build.properties (no variant field;
    // single-jar build means variant identity is no longer meaningful).
    val writeBuildInfoTask = tasks.register("writeBuildInfo") {
        val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")
        val mcVersion = providers.gradleProperty("mcVersion").getOrElse("unknown")
        val outFile = layout.buildDirectory.file("generated-resources/META-INF/sourbycraft-build.properties")

        inputs.property("internalVersion", internalVersion)
        inputs.property("mcVersion", mcVersion)
        outputs.file(outFile)

        doLast {
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            val timestamp = Instant.now().toString()
            f.writeText("""
                version=$internalVersion
                mcVersion=$mcVersion
                tagline=Lightning Fast Performance · Feature Rich
                buildTimestamp=$timestamp
            """.trimIndent())
        }
    }

    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<ProcessResources>().configureEach {
            dependsOn(writeBuildInfoTask)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(layout.buildDirectory.dir("generated-resources"))
        }
        tasks.withType<Jar>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
```

Note: the output directory changes from `variant-resources/` to `generated-resources/` to drop the `variant` connotation.

- [ ] **Step 3: Verify**

Run:
```bash
grep -n "variant\|isPvp\|processVariantResources\|sourbycraft-applied-variant" build.gradle.kts
```
Expected: zero matches (only the unrelated comment word `variant` if any survives in context — review and rephrase if so).

- [ ] **Step 4: Test that build configuration still resolves**

Run:
```bash
./gradlew help -q
```
Expected: no errors. Task graph configures cleanly.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: strip variant property, processVariantResources, jar suffix

Removes the -Pvariant build flag, processVariantResources copy task,
generateBuildInfo's variant field, isPvpVariant gating, marker-file
logic, PvP-patch stash/unstash hooks, and the -PVP jar suffix.

writeBuildInfo is slimmed: no variant field, output dir renamed to
generated-resources to drop the variant connotation."
```

---

## Phase 4: Drop overlay loader from `SourbyCraftConfig`, flip pvpEnabled default

### Task 4: Remove overlay loader, flip field default

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Delete the overlay field**

Find this line (around line 29):

```java
    private static final Map<String, Object> sourbycraftYmlOverlay = loadYmlResource("/sourbycraft-variant-overlay.yml");
```

Delete it. The line above it (`private static final Map<String, Object> sourbycraftYmlBaseline = ...`) stays.

- [ ] **Step 2: Delete the overlay branch in `ymlGet`**

Find the method body (around lines 47-57):

```java
    @SuppressWarnings("unchecked")
    public static <T> T ymlGet(String dottedPath, T defaultValue) {
        Object overlayVal = lookupYml(sourbycraftYmlOverlay, dottedPath);
        if (overlayVal != null) {
            try { return (T) overlayVal; } catch (ClassCastException ignored) {}
        }
        Object baseVal = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (baseVal != null) {
            try { return (T) baseVal; } catch (ClassCastException ignored) {}
        }
        return defaultValue;
    }
```

Replace with:

```java
    @SuppressWarnings("unchecked")
    public static <T> T ymlGet(String dottedPath, T defaultValue) {
        Object baseVal = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (baseVal != null) {
            try { return (T) baseVal; } catch (ClassCastException ignored) {}
        }
        return defaultValue;
    }
```

- [ ] **Step 3: Flip the `pvpEnabled` field default**

Find line 137:

```java
    public static boolean pvpEnabled = true;                    // master toggle
```

Replace with:

```java
    public static boolean pvpEnabled = false;                   // master toggle (opt-in)
```

Rationale: with overlays removed, the field default is what gets written to the operator's `plugins/SourbyCraft/sourbycraft.yml` on first init (via `config.addDefault("pvp.enabled", pvpEnabled)`). Fresh installs default to non-PvP. Existing operator yaml files that already contain `pvp.enabled: true` (written by prior versions) keep PvP active on upgrade.

- [ ] **Step 4: Update the loader comment block to reflect the new behavior**

Find the block at the top of the class (around lines 24-29):

```java
    // SourbyCraft v12 — sourbycraft.yml + variant overlay loader.
    // Baseline is `/sourbycraft.yml`. Build packages variant-specific tweaks at
    // `/sourbycraft-variant-overlay.yml` (written by processVariantResources task).
    // ymlGet checks overlay first, then falls back to baseline, then to caller default.
    private static final Map<String, Object> sourbycraftYmlBaseline = loadYmlResource("/sourbycraft.yml");
```

Replace with:

```java
    // SourbyCraft v12 — sourbycraft.yml resource loader (single-jar, no variants).
    // PvP-only consumers gate their reads with `if (pvpEnabled)`; values for both
    // modes live in the same baseline file. ymlGet returns the baseline value,
    // falling back to the caller-supplied default if the key is absent.
    private static final Map<String, Object> sourbycraftYmlBaseline = loadYmlResource("/sourbycraft.yml");
```

- [ ] **Step 5: Verify the file compiles**

Run:
```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git commit -m "config: remove overlay loader, default pvpEnabled to false

ymlGet now reads only from baseline /sourbycraft.yml. The sourbycraftYmlOverlay
field and the overlay-first branch are deleted. pvpEnabled field default flips
to false so fresh installs default to non-PvP behavior. Existing operators with
pvp.enabled=true persisted in their runtime yaml are unaffected."
```

---

## Phase 5: Gate each `9xxx-PVP-*` patch with `pvpEnabled`

These five patches in `patches/minecraft/` apply NMS-level edits that read PvP config via `ymlGet`. After Phases 1-4 they still apply unconditionally to the patched tree, but their `ymlGet` reads now return PvP values from the merged baseline. To preserve "non-PvP server = vanilla behavior" we wrap each consumer with a runtime `SourbyCraftConfig.pvpEnabled` check.

Workflow for each task in this phase:
1. `./gradlew applyAllPatches` to ensure the working tree reflects the patch series.
2. Edit the patched source file under `paper-server/` or `sourbycraft-server/src/minecraft/` (paperweight 2.0 layout).
3. `./gradlew rebuildPatches` to regenerate the `.patch` file with the new content.
4. Verify diff is minimal and includes the `pvpEnabled` gate.
5. Commit the regenerated patch.

### Task 5: Gate netty tuning (patch 9001) on `pvpEnabled`

**Files:**
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/server/network/ServerConnectionListener.java`
- Patch file: `patches/minecraft/9001-PVP-netty-tuning.patch`

- [ ] **Step 1: Apply patches**

Run:
```bash
./gradlew clean applyAllPatches
```
Expected: BUILD SUCCESSFUL, all patches apply with no rejects.

- [ ] **Step 2: Edit the static initializer**

Open `sourbycraft-server/src/minecraft/net/minecraft/server/network/ServerConnectionListener.java`. Find the `static { ... }` block introduced by the patch (search for `eventLoopThreads`). Wrap the body with a `pvpEnabled` check:

```java
    static {
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
            try {
                String threads = String.valueOf(
                    dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("network.netty.threads", "auto"));
                int resolved = -1;
                if ("auto-doubled".equalsIgnoreCase(threads)) {
                    resolved = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
                } else if (!"auto".equalsIgnoreCase(threads)) {
                    try { resolved = Integer.parseInt(threads); } catch (NumberFormatException ignored) {}
                }
                if (resolved > 0 && System.getProperty("io.netty.eventLoopThreads") == null) {
                    System.setProperty("io.netty.eventLoopThreads", Integer.toString(resolved));
                    LOGGER.info("[SourbyCraft:PvP] netty eventLoopThreads set to {}", resolved);
                }
            } catch (Throwable t) { /* ignore — never block server startup on advisory config */ }
        }
    }
```

- [ ] **Step 3: Edit the `initChannel` SO_SNDBUF / SO_RCVBUF block**

In the same file, find the `protected void initChannel(Channel channel)` block (search for `SO_SNDBUF`). Wrap the SourbyCraft additions:

```java
                                    try {
                                        channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                                        if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
                                            int sndKb = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                                                "network.netty.snd-buf-kb", Integer.valueOf(0))).intValue();
                                            int rcvKb = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                                                "network.netty.rcv-buf-kb", Integer.valueOf(0))).intValue();
                                            if (sndKb > 0) channel.config().setOption(ChannelOption.SO_SNDBUF, sndKb * 1024);
                                            if (rcvKb > 0) channel.config().setOption(ChannelOption.SO_RCVBUF, rcvKb * 1024);
                                        }
                                    } catch (ChannelException var5) {
                                    }
```

- [ ] **Step 4: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. Verify `patches/minecraft/9001-PVP-netty-tuning.patch` now contains the `pvpEnabled` gates.

- [ ] **Step 5: Commit**

```bash
git add patches/minecraft/9001-PVP-netty-tuning.patch
git commit -m "patch: gate 9001 netty tuning on pvpEnabled

Wraps the static eventLoopThreads override and the per-channel
SO_SNDBUF/SO_RCVBUF overrides in if(SourbyCraftConfig.pvpEnabled),
so non-PvP servers see vanilla Netty configuration."
```

### Task 6: Gate entity tracker tightening (patch 9002) on `pvpEnabled`

**Files:**
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/server/level/ChunkMap.java`
- Patch file: `patches/minecraft/9002-PVP-entity-tracker-tightening.patch`

- [ ] **Step 1: Edit the `ChunkMap.java` tracker block**

Open the file and find the SourbyCraft additions (search for `entity-tracker.item-range`). Wrap both blocks (tracker range caps and player update interval override) with a single outer `pvpEnabled` check:

```java
            EntityType<?> type = entity.getType();
            int i = type.clientTrackingRange() * 16;
            i = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, i); // Spigot
            if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
                try {
                    if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                        int cap = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                            "entity-tracker.item-range", Integer.valueOf(0))).intValue();
                        if (cap > 0) i = Math.min(i, cap);
                    } else if (entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                        int cap = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                            "entity-tracker.xp-orb-range", Integer.valueOf(0))).intValue();
                        if (cap > 0) i = Math.min(i, cap);
                    } else if (entity instanceof net.minecraft.world.entity.Mob
                            && !(entity instanceof net.minecraft.server.level.ServerPlayer)) {
                        int cap = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                            "entity-tracker.mob-range", Integer.valueOf(0))).intValue();
                        if (cap > 0) i = Math.min(i, cap);
                    }
                } catch (Throwable t) { /* ignore — never block tracker init on config */ }
            }
            if (i != 0) {
                int updateInterval = type.updateInterval();
                if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
                    try {
                        int forced = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                            "entity-tracker.player-update-interval", Integer.valueOf(0))).intValue();
                        if (forced > 0 && entity instanceof net.minecraft.server.level.ServerPlayer) {
                            updateInterval = forced;
                        }
                    } catch (Throwable t) { /* ignore */ }
                }
```

- [ ] **Step 2: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. Verify the patch now includes `if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled)`.

- [ ] **Step 3: Commit**

```bash
git add patches/minecraft/9002-PVP-entity-tracker-tightening.patch
git commit -m "patch: gate 9002 entity-tracker tightening on pvpEnabled

Wraps the per-entity tracking-range caps and the player update-interval
override in if(SourbyCraftConfig.pvpEnabled). Non-PvP servers get
vanilla tracker ranges."
```

### Task 7: Gate GC advisor banner (patch 9003) on `pvpEnabled`

**Files:**
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/server/MinecraftServer.java`
- Patch file: `patches/minecraft/9003-PVP-cpu-pin-gc-banner.patch`

- [ ] **Step 1: Edit the GC advisor block**

Open the file and find the SourbyCraft additions (search for `GcAdvisor.run()`). Add a `pvpEnabled` check to the existing `Boolean.TRUE.equals(...)` guard:

```java
            // SourbyCraft v12 PVP — GC advisor (warns on non-ZGC/G1, Xms!=Xmx, missing AlwaysPreTouch)
            if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled
                && Boolean.TRUE.equals(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("branding.gc-advisor.enabled", Boolean.TRUE))) {
                dev.iyanz.sourbycraft.brand.GcAdvisor.Result gcr = dev.iyanz.sourbycraft.brand.GcAdvisor.run();
                if (!gcr.acceptable()) {
                    System.out.print(dev.iyanz.sourbycraft.brand.GcAdvisor.renderWarningBanner(gcr));
                }
            }
            // SourbyCraft v12 PVP end
```

- [ ] **Step 2: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. Patch updated.

- [ ] **Step 3: Commit**

```bash
git add patches/minecraft/9003-PVP-cpu-pin-gc-banner.patch
git commit -m "patch: gate 9003 GC advisor banner on pvpEnabled

GC advisor warning banner now only renders when SourbyCraftConfig.pvpEnabled
is true (in addition to the existing branding.gc-advisor.enabled yaml gate)."
```

### Task 8: Gate combat completion (patch 9004) on `pvpEnabled`

**Files:**
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/world/entity/LivingEntity.java`
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/world/entity/player/Player.java`
- Patch file: `patches/minecraft/9004-PVP-combat-completion.patch`

- [ ] **Step 1: Edit `LivingEntity.java` hit-delay override**

Find the SourbyCraft addition (search for `combat.hit-delay-ticks`):

```java
                this.lastHurt = amount;
                int scHitDelay = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                    "combat.hit-delay-ticks", Integer.valueOf(this.invulnerableDuration))).intValue();
                this.invulnerableTime = scHitDelay > 0 ? scHitDelay : this.invulnerableDuration; // CraftBukkit - restore use of maxNoDamageTicks
```

Replace with:

```java
                this.lastHurt = amount;
                if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
                    int scHitDelay = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                        "combat.hit-delay-ticks", Integer.valueOf(this.invulnerableDuration))).intValue();
                    this.invulnerableTime = scHitDelay > 0 ? scHitDelay : this.invulnerableDuration;
                } else {
                    this.invulnerableTime = this.invulnerableDuration; // CraftBukkit - restore use of maxNoDamageTicks
                }
```

- [ ] **Step 2: Edit `Player.java` sweep gate**

Find the SourbyCraft sweep override (search for `combat.sweep-enabled`):

```java
                    boolean isSweepAttack = this.isSweepAttack(flag, flag2, flag1)
                        && Boolean.TRUE.equals(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.sweep-enabled", Boolean.TRUE));
```

Replace with:

```java
                    boolean isSweepAttack = this.isSweepAttack(flag, flag2, flag1)
                        && (!dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled
                            || Boolean.TRUE.equals(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.sweep-enabled", Boolean.TRUE)));
```

Rationale: when pvpEnabled is false, fall through to vanilla sweep logic (`isSweepAttack(...)` alone). When true, additionally consult the `combat.sweep-enabled` yaml flag (which is `false` in baseline for PvP arena style).

- [ ] **Step 3: Edit `Player.java` reach-debug recording**

Find the SourbyCraft reach-debug block (search for `combat.reach-debug-command`):

```java
                        try {
                            if (Boolean.TRUE.equals(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                                "combat.reach-debug-command", Boolean.FALSE))) {
                                ...
                            }
                        } catch (Throwable t) { /* ignore — never fail attack on debug recording */ }
```

Wrap with `pvpEnabled` check:

```java
                        try {
                            if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled
                                && Boolean.TRUE.equals(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                                    "combat.reach-debug-command", Boolean.FALSE))) {
                                double scDist = this.distanceTo(target);
                                int scPing = (this instanceof net.minecraft.server.level.ServerPlayer scSp && scSp.connection != null)
                                    ? scSp.connection.latency() : 0;
                                int scWindow = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                                    "combat.hit-window-ms", Integer.valueOf(100))).intValue();
                                dev.iyanz.sourbycraft.combat.ReachTracker.record(
                                    this.getName().getString(),
                                    target.getName().getString(),
                                    scDist, scPing, scWindow);
                            }
                        } catch (Throwable t) { /* ignore — never fail attack on debug recording */ }
```

- [ ] **Step 4: Verify the existing pvpEnabled-gated knockback block (already in patch line 63) stays unchanged**

Search for `pvpEnabled` further down in `Player.java` — the existing block reading `pvpKnockbackExtraHorizontal` is already gated and needs no edit.

- [ ] **Step 5: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add patches/minecraft/9004-PVP-combat-completion.patch
git commit -m "patch: gate 9004 combat completion (hit-delay, sweep, reach) on pvpEnabled

LivingEntity hit-delay override, Player sweep-attack gate, and Player
reach-debug recording all become no-ops when SourbyCraftConfig.pvpEnabled
is false (vanilla behavior). The existing pvpKnockbackExtraHorizontal
block is already gated and untouched."
```

### Task 9: Gate proxy-kick grace (patch 9005) on `pvpEnabled`

**Files:**
- Patched tree: `sourbycraft-server/src/minecraft/net/minecraft/server/MinecraftServer.java`
- Patch file: `patches/minecraft/9005-PVP-proxy-kick.patch`

- [ ] **Step 1: Edit the proxy-kick block**

Find the SourbyCraft addition (search for `proxy-kick-grace-seconds`). Wrap the entire `try { ... } catch (Throwable t) { ... }` block with a `pvpEnabled` outer check:

```java
        // SourbyCraft v12 PVP — proxy-aware kick grace (transfer-out via BungeeCord/Velocity channel)
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.pvpEnabled) {
            try {
                int scGraceSec = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                    "network.proxy-kick-grace-seconds", Integer.valueOf(0))).intValue();
                String scProxyMode = String.valueOf(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                    "network.proxy-mode", "none"));
                if (scGraceSec > 0 && (scProxyMode.contains("velocity") || scProxyMode.contains("bungee"))) {
                    String scFallback = String.valueOf(dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
                        "network.proxy-kick-fallback", "lobby"));
                    try {
                        java.io.ByteArrayOutputStream scBout = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream scDout = new java.io.DataOutputStream(scBout);
                        scDout.writeUTF("Connect");
                        scDout.writeUTF(scFallback);
                        net.minecraft.resources.Identifier scChan =
                            net.minecraft.resources.Identifier.parse("bungeecord:main");
                        int scN = 0;
                        if (this.playerList != null) {
                            for (net.minecraft.server.level.ServerPlayer scSp : this.playerList.getPlayers()) {
                                try {
                                    scSp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                        new net.minecraft.network.protocol.common.custom.DiscardedPayload(
                                            scChan, scBout.toByteArray())));
                                    scN++;
                                } catch (Throwable t) { /* per-player failure ignored */ }
                            }
                        }
                        LOGGER.info("[SourbyCraft:PvP] proxy transfer-out: {} players -> '{}', waiting {}s", scN, scFallback, scGraceSec);
                    } catch (java.io.IOException ignored) {}
                    try { Thread.sleep(scGraceSec * 1000L); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            } catch (Throwable t) { LOGGER.warn("[SourbyCraft:PvP] proxy-kick grace failed: {}", t.toString()); }
        }
        // SourbyCraft v12 PVP end
```

- [ ] **Step 2: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add patches/minecraft/9005-PVP-proxy-kick.patch
git commit -m "patch: gate 9005 proxy-kick grace on pvpEnabled

The transfer-out + sleep block now only runs when SourbyCraftConfig.pvpEnabled
is true. Non-PvP servers exit cleanly without the proxy transfer dance."
```

---

## Phase 6: Revert patch 0032 (server.properties seeder) + delete seeder class

### Task 10: Remove patch 0032

**Files:**
- Delete patch: `patches/server/0032-SourbyCraft-v12-seed-server-properties-from-variant-.patch`
- Delete source: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/ServerPropertiesSeeder.java`
- Delete dir if empty: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/`

- [ ] **Step 1: Delete the patch file**

```bash
rm patches/server/0032-SourbyCraft-v12-seed-server-properties-from-variant-.patch
```

- [ ] **Step 2: Reset patched tree and rebuild patches**

```bash
./gradlew clean applyAllPatches
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. The `ServerPropertiesSeeder.seed()` line in `paper-server/.../Main.java` is no longer in any patch and disappears from the working tree.

- [ ] **Step 3: Delete the seeder source file**

```bash
rm sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/ServerPropertiesSeeder.java
rmdir sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/ 2>/dev/null || true
```

- [ ] **Step 4: Verify no remaining references**

```bash
grep -rn "ServerPropertiesSeeder" sourbycraft-server/src paper-server/src patches 2>/dev/null
```
Expected: zero matches.

- [ ] **Step 5: Verify build still works**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A patches/server/ sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/boot/
git commit -m "revert: drop patch 0032 server.properties seeder

The seeder existed to merge the variant-overlay server.properties into
CWD before MC reads it. With overlays gone the seeder is dead code:
operators edit server.properties directly, no overlay seeding needed."
```

---

## Phase 7: Remove custom `/plugins` command

### Task 11: Revert patch 0031 (PluginsCommand reformat)

**Files:**
- Delete patch: `patches/server/0031-SourbyCraft-v12-plugins-reformat-use-PluginsCommandF.patch`

- [ ] **Step 1: Delete patch 0031**

```bash
rm patches/server/0031-SourbyCraft-v12-plugins-reformat-use-PluginsCommandF.patch
```

- [ ] **Step 2: Reset + reapply**

```bash
./gradlew clean applyAllPatches
```
Expected: BUILD SUCCESSFUL. The reformatted `PluginsCommand.java` from 0031 is gone; the minimal version from patch 0013 still exists.

- [ ] **Step 3: Commit**

```bash
git add patches/server/
git commit -m "revert: drop patch 0031 PluginsCommand reformat

Custom /plugins formatting is going away (next commit removes the
command entirely). The reformat patch is no longer needed."
```

### Task 12: Strip `PluginsCommand` introduction from patch 0013

**Files:**
- Modify patch: `patches/server/0013-consolidate-all-commands-in-single-patch.patch`
  (will rebuild via paperweight)
- Modify patched tree: `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java`
- Delete patched tree: `paper-server/src/main/java/dev/iyanz/sourbycraft/command/PluginsCommand.java`

- [ ] **Step 1: Apply patches into working tree**

```bash
./gradlew clean applyAllPatches
```

- [ ] **Step 2: Delete the patched `PluginsCommand.java`**

```bash
rm paper-server/src/main/java/dev/iyanz/sourbycraft/command/PluginsCommand.java
```

If the `command/` directory becomes empty:
```bash
rmdir paper-server/src/main/java/dev/iyanz/sourbycraft/command/ 2>/dev/null || true
rmdir paper-server/src/main/java/dev/iyanz/sourbycraft/ 2>/dev/null || true
```

- [ ] **Step 3: Remove both `PluginsCommand` registrations from `CraftServer.java`**

Open `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java`. Find the two lines (around 424 and 1063):

```java
        this.commandMap.register("", new dev.iyanz.sourbycraft.command.PluginsCommand("plugins"));
```

Delete both lines and any surrounding SourbyCraft-only comment/import that becomes orphaned.

- [ ] **Step 4: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. Verify `patches/server/0013-consolidate-all-commands-in-single-patch.patch` no longer contains the `PluginsCommand` introduction or the two `commandMap.register` lines.

```bash
grep -n "PluginsCommand" patches/server/0013-consolidate-all-commands-in-single-patch.patch
```
Expected: zero matches.

- [ ] **Step 5: Verify vanilla Paper `/plugins` is still registered**

```bash
grep -n '"plugins"' paper-server/src/main/java/io/papermc/paper/command/PaperPluginsCommand.java
```
Expected: line `return Commands.literal("plugins")` (the vanilla brigadier registration) is present.

- [ ] **Step 6: Verify full build still works**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add patches/server/0013-consolidate-all-commands-in-single-patch.patch
git commit -m "patch: drop custom /plugins from patch 0013

PluginsCommand.java and its two commandMap.register('plugins', ...) calls
in CraftServer.java are removed. Vanilla Paper PaperPluginsCommand
(brigadier) handles /plugins from now on."
```

---

## Phase 8: Simplify plugin auto-installer

### Task 13: Create unified plugin manifest

**Files:**
- Delete: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/normal.yml`
- Delete: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/pvp.yml`
- Delete dir: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/`
- Create: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins.yml`

- [ ] **Step 1: Create the unified manifest**

Write `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins.yml`:

```yaml
# SourbyCraft v12 — first-boot plugin auto-installer manifest (unified).
# NOTE: SlimeWorldManager is replaced by SourbyCraftSWM (bundled, auto-installed
# via dev.iyanz.sourbycraft.swm.installer.PluginInstaller). Do not re-add it here.
plugins:
  - name: spark
    source: jenkins
    url: "https://ci.lucko.me/job/spark/lastSuccessfulBuild"
    asset-glob: "spark-*-bukkit.jar"
```

- [ ] **Step 2: Delete the old variant manifests**

```bash
rm sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/normal.yml
rm sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/pvp.yml
rmdir sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/
```

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/resources/META-INF/
git commit -m "config: unified plugin manifest, drop variant manifests

A single META-INF/sourbycraft-plugins.yml replaces the per-variant
normal.yml + pvp.yml. ViaVersion, ViaBackwards, and PacketEvents are
no longer auto-installed (operators install them manually if needed).
spark remains the only auto-installed plugin."
```

### Task 14: Update `PluginAutoInstaller` to use the unified manifest

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginAutoInstaller.java`

- [ ] **Step 1: Replace `installFromVariant` with `install`**

Open the file. Replace the existing `installFromVariant(String variant, Path pluginsDir)` method with:

```java
    public static Result install(Path pluginsDir) {
        String resource = "/META-INF/sourbycraft-plugins.yml";
        try (InputStream in = PluginAutoInstaller.class.getResourceAsStream(resource)) {
            List<PluginEntry> entries = PluginManifest.parse(in);
            return installAll(entries, pluginsDir);
        } catch (IOException e) {
            LOGGER.warn("[install] Failed to read manifest {}: {}", resource, e.getMessage());
            return new Result(0, 0, 0);
        }
    }
```

The signature drops the `String variant` parameter. The resource path is fixed.

- [ ] **Step 2: Verify the file compiles**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD FAILED. The patch 0030 wiring still references `installFromVariant(sbi.variant(), pluginsDir)`. That gets fixed in the next task.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginAutoInstaller.java
git commit -m "install: replace installFromVariant with install(pluginsDir)

Single manifest path. Caller in patch 0030 updated in next commit."
```

### Task 15: Update patch 0030 (loadPlugins wiring) to call `install(pluginsDir)`

**Files:**
- Modify patched tree: `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java`
- Patch file: `patches/server/0030-SourbyCraft-v12-wire-PluginAutoInstaller-into-loadPl.patch`

- [ ] **Step 1: Apply patches**

```bash
./gradlew clean applyAllPatches
```
Expected: may fail because patch 0030 references `installFromVariant`. If `applyAllPatches` fails, the failure is acceptable here — proceed to edit the working tree manually based on the patch content shown below.

Alternative if applyAllPatches succeeded: open `CraftServer.java` and find the SourbyCraft block in `loadPlugins()` (around line 567+).

- [ ] **Step 2: Edit the `loadPlugins` SourbyCraft block**

Replace the existing block:

```java
        // SourbyCraft v12 — auto-install variant plugins before plugin scan
        try {
            dev.iyanz.sourbycraft.brand.BuildInfo sbi = dev.iyanz.sourbycraft.brand.BuildInfo.load();
            boolean autoInstall = Boolean.TRUE.equals(
                dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("auto-install.enabled", Boolean.TRUE));
            if (autoInstall) {
                java.nio.file.Path pluginsDir = java.nio.file.Path.of("plugins");
                dev.iyanz.sourbycraft.install.PluginAutoInstaller.Result r =
                    dev.iyanz.sourbycraft.install.PluginAutoInstaller.installFromVariant(sbi.variant(), pluginsDir);
                org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
                    "[install] variant={}: installed={} skipped={} failed={}",
                    sbi.variant(), r.installedCount(), r.skippedCount(), r.failedCount()
                );
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn(
                "[install] auto-installer error (continuing boot): " + t.getMessage());
        }
```

With:

```java
        // SourbyCraft v12 — auto-install bundled plugins before plugin scan
        try {
            boolean autoInstall = Boolean.TRUE.equals(
                dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("auto-install.enabled", Boolean.TRUE));
            if (autoInstall) {
                java.nio.file.Path pluginsDir = java.nio.file.Path.of("plugins");
                dev.iyanz.sourbycraft.install.PluginAutoInstaller.Result r =
                    dev.iyanz.sourbycraft.install.PluginAutoInstaller.install(pluginsDir);
                org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
                    "[install] installed={} skipped={} failed={}",
                    r.installedCount(), r.skippedCount(), r.failedCount()
                );
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn(
                "[install] auto-installer error (continuing boot): " + t.getMessage());
        }
```

The `BuildInfo` lookup and the `sbi.variant()` argument are removed.

- [ ] **Step 3: Rebuild patches**

```bash
./gradlew rebuildPatches
```
Expected: BUILD SUCCESSFUL. Verify:

```bash
grep -n "installFromVariant\|sbi.variant" patches/server/0030-SourbyCraft-v12-wire-PluginAutoInstaller-into-loadPl.patch
```
Expected: zero matches.

- [ ] **Step 4: Commit**

```bash
git add patches/server/0030-SourbyCraft-v12-wire-PluginAutoInstaller-into-loadPl.patch
git commit -m "patch: 0030 calls PluginAutoInstaller.install(pluginsDir)

Drops the BuildInfo.load() variant lookup; the single-jar build has no
variant identity. Log line simplified."
```

### Task 16: Update `PluginAutoInstallerTest`

**Files:**
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginAutoInstallerTest.java`

- [ ] **Step 1: Read the existing test**

```bash
cat sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginAutoInstallerTest.java
```

Existing test calls `PluginAutoInstaller.installAll(...)` (still exists) — no signature change needed there. Just verify no test method references `installFromVariant`.

- [ ] **Step 2: Run the test**

```bash
./gradlew :sourbycraft-server:test --tests "dev.iyanz.sourbycraft.install.PluginAutoInstallerTest" -q
```
Expected: PASS.

If the test references `installFromVariant`, replace those calls with `install(pluginsDir)`.

- [ ] **Step 3: Commit (only if test was modified)**

If no changes were needed in Step 2, skip commit. Otherwise:

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginAutoInstallerTest.java
git commit -m "test: update PluginAutoInstallerTest for install(pluginsDir) signature"
```

---

## Phase 9: Drop `variant` field from `BuildInfo` consumers

### Task 17: Update `BuildInfo` to drop `variant` and `isPvp`

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java`

- [ ] **Step 1: Replace the record**

```java
package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(
    String version,
    String mcVersion,
    String tagline,
    String buildTimestamp
) {
    private static final String RESOURCE = "/META-INF/sourbycraft-build.properties";

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return loadFrom(in);
        } catch (IOException e) {
            return loadFrom(null);
        }
    }

    public static BuildInfo loadFrom(InputStream in) {
        Properties p = new Properties();
        if (in != null) {
            try {
                p.load(in);
            } catch (IOException e) {
                // fall through to defaults
            }
        }
        return new BuildInfo(
            p.getProperty("version", "dev"),
            p.getProperty("mcVersion", "unknown"),
            p.getProperty("tagline", "Lightning Fast Performance · Feature Rich"),
            p.getProperty("buildTimestamp", "")
        );
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD FAILED — `SourbyCraftBanner` and `GcAdvisor` still call `info.isPvp()` or read `info.variant()`. Fixed in next two tasks.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java
git commit -m "brand: drop variant field and isPvp() from BuildInfo

BuildInfo no longer carries variant identity. version, mcVersion, tagline,
buildTimestamp remain. Consumers updated in next commits."
```

### Task 18: Update `SourbyCraftBanner` to remove variant text

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/SourbyCraftBanner.java`

- [ ] **Step 1: Replace the file**

Overwrite `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/SourbyCraftBanner.java` with:

```java
package dev.iyanz.sourbycraft.brand;

public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("   ╔══════════════════════════════════════════════════════════╗\n");
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   ⚡  SOURBYCRAFT  ⚡   ·  %-30s ║%n",
            info.version()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   %-54s ║%n", info.tagline()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   Paper %s  ·  Java %-30s ║%n",
            info.mcVersion(),
            System.getProperty("java.specification.version")));
        sb.append("   ║                                                          ║\n");
        sb.append("   ╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
```

Diff vs. prior:
- `String variant = info.isPvp() ? "PVP" : "NORMAL";` removed.
- The two format strings that interpolated `variant` widened to absorb the freed columns (`%-7s · %-7s` → `%-30s`; `Variant: %-8s` line removed; `Paper %s · Java %s · Variant: %-8s` → `Paper %s · Java %-30s`).
- The `if (info.isPvp()) { sb.append("PvP-tuned defaults active") }` block removed.
- Box width preserved (58-char inner row).

- [ ] **Step 3: Verify**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: closer to success; `GcAdvisor` may still fail.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/SourbyCraftBanner.java
git commit -m "brand: remove variant label from startup banner"
```

### Task 19: Update `GcAdvisor` to remove variant message

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/GcAdvisor.java`

- [ ] **Step 1: Read the file and locate variant message**

```bash
grep -n "PVP variant" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/GcAdvisor.java
```

Expected: line `sb.append("║  ⚠  PVP variant tuned for ZGC generational       ║\n");` at line 63 (from earlier grep).

- [ ] **Step 2: Replace the line**

Open the file. Find line 63:

```java
        sb.append("║  ⚠  PVP variant tuned for ZGC generational       ║\n");
```

Replace with:

```java
        sb.append("║  ⚠  PvP mode tuned for ZGC generational          ║\n");
```

Box-drawing alignment preserved (35 chars between `⚠  ` and `║`). Wording shifts from build-time `variant` to runtime `mode`.

- [ ] **Step 3: Verify the whole module compiles**

```bash
./gradlew :sourbycraft-server:compileJava -q
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/GcAdvisor.java
git commit -m "brand: GcAdvisor wording — 'PvP mode' not 'PVP variant'"
```

### Task 20: Update patch 0029 (`/ver` tagline) — keep tagline, drop variant text

**Files:**
- Patch file: `patches/server/0029-SourbyCraft-v12-ver-shows-variant-tagline.patch`
- Patched tree: `paper-server/src/main/java/dev/iyanz/sourbycraft/command/VerCommand.java`

The current patch 0029 adds three lines to `VerCommand.java`:
1. `import dev.iyanz.sourbycraft.brand.BuildInfo;`
2. `BuildInfo sbi=BuildInfo.load();`
3. `s.sendMessage(text().append(text("Variant: ",...)).append(text(sbi.isPvp()?"PVP":"NORMAL",...)).append(text(" · ",...)).append(text("\""+sbi.tagline()+"\"",...)));`

We keep the tagline display but drop the variant label.

- [ ] **Step 1: Apply patches**

```bash
./gradlew clean applyAllPatches
```

- [ ] **Step 2: Edit `VerCommand.java`**

Open `paper-server/src/main/java/dev/iyanz/sourbycraft/command/VerCommand.java`. Find the SourbyCraft v12 block (search for `BuildInfo.load()`). Replace the line:

```java
        s.sendMessage(text().append(text("Variant: ",SourbyCraftColors.LABEL)).append(text(sbi.isPvp()?"PVP":"NORMAL",sbi.isPvp()?SourbyCraftColors.DANGER:SourbyCraftColors.SUCCESS)).append(text(" · ",SourbyCraftColors.DIM)).append(text("\""+sbi.tagline()+"\"",SourbyCraftColors.VALUE)));
```

With:

```java
        s.sendMessage(text().append(text("Tagline: ",SourbyCraftColors.LABEL)).append(text("\""+sbi.tagline()+"\"",SourbyCraftColors.VALUE)));
```

The `BuildInfo sbi=BuildInfo.load();` line above stays (still needed for `sbi.tagline()`).

- [ ] **Step 3: Rebuild patches**

```bash
./gradlew rebuildPatches
```

- [ ] **Step 4: Verify the patch no longer references variant/isPvp**

```bash
grep -n "variant\|isPvp" patches/server/0029-SourbyCraft-v12-ver-shows-variant-tagline.patch
```
Expected: zero matches.

- [ ] **Step 5: Optionally rename the patch file to reflect new content**

```bash
mv patches/server/0029-SourbyCraft-v12-ver-shows-variant-tagline.patch \
   patches/server/0029-SourbyCraft-v12-ver-shows-tagline.patch
```

- [ ] **Step 6: Commit**

```bash
git add patches/server/
git commit -m "patch: 0029 /ver shows tagline only (variant label dropped)

The 'Variant: PVP/NORMAL' chip in /ver output is gone with the single-jar
build. /ver still displays the tagline."
```

---

## Phase 10: Release artifact cleanup

### Task 21: Build the unified jar

- [ ] **Step 1: Clean build**

```bash
./gradlew clean
```

- [ ] **Step 2: Apply patches**

```bash
./gradlew applyAllPatches
```
Expected: BUILD SUCCESSFUL. All patches apply cleanly with no rejects.

- [ ] **Step 3: Build the paperclip jar**

```bash
./gradlew createPaperclipJar
```
Expected: BUILD SUCCESSFUL. Produces a single jar with no `-PVP` suffix.

- [ ] **Step 4: Locate the produced jar**

```bash
find sourbycraft-server/build -name "*.jar" -newer build.gradle.kts
```
Expected: one jar file, name pattern `SourbyCraft-paperclip-v12-REL.jar` or similar (paperweight default).

- [ ] **Step 5: Copy to `release/` and rename**

```bash
cp sourbycraft-server/build/libs/SourbyCraft-paperclip-*.jar release/SourbyCraft-v12-REL.jar
```

Adjust the source path if `find` reported a different location.

- [ ] **Step 6: Delete the PvP release jar**

```bash
rm release/SourbyCraft-PVP-v12-REL.jar
```

- [ ] **Step 7: Regenerate checksums**

```bash
( cd release && sha256sum SourbyCraft-v12-REL.jar > checksums.txt )
```

Or on macOS (no `sha256sum`):

```bash
( cd release && shasum -a 256 SourbyCraft-v12-REL.jar > checksums.txt )
```

- [ ] **Step 8: Verify checksums file has one line**

```bash
wc -l release/checksums.txt
```
Expected: `1 release/checksums.txt`.

- [ ] **Step 9: Verify the jar boots a server**

```bash
mkdir -p /tmp/sourbycraft-smoke && cp release/SourbyCraft-v12-REL.jar /tmp/sourbycraft-smoke/server.jar
cd /tmp/sourbycraft-smoke
echo "eula=true" > eula.txt
java -Xmx2G -jar server.jar nogui 2>&1 | head -120
```
Expected: server boots, no fatal errors, no `[SourbyCraft:PvP]` log lines (since `pvp.enabled` defaults to false on fresh init). Type `stop` to shut down.

- [ ] **Step 10: Commit release artifacts**

```bash
git add release/
git commit -m "release: unified v12 jar, drop PVP artifact

release/SourbyCraft-v12-REL.jar replaces both prior jars. checksums.txt
contains a single sha256 line for the new artifact."
```

---

## Phase 11: Full smoke + final sweep

### Task 22: Grep for any remaining variant references

- [ ] **Step 1: Search source + patches**

```bash
grep -rn "variant\|isPvp\|sourbycraft-variant-overlay" \
    build.gradle.kts \
    sourbycraft-server/src \
    paper-server/src \
    patches \
    release \
    2>/dev/null \
    | grep -v "^Binary" \
    | grep -v "docs/superpowers"
```

Expected: only contextual matches (e.g., unrelated word "variant" inside comments about Minecraft block variants, NMS texture variants, etc.). Zero matches for `isPvp`, `sourbycraft-variant-overlay`, `processVariantResources`, `installFromVariant`. If anything turns up, edit it inline and commit.

- [ ] **Step 2: Search for legacy `PluginsCommand` references**

```bash
grep -rn "PluginsCommand" \
    build.gradle.kts \
    sourbycraft-server/src \
    paper-server/src \
    patches \
    2>/dev/null
```

Expected: only references in vanilla Paper's own files (`paper-api/src/main/java/org/bukkit/command/defaults/PluginsCommand.java`) and possibly inside `.gradle/caches/`. Zero references inside `patches/` or `sourbycraft-server/src/`.

### Task 23: Run server smoke against the unified jar

- [ ] **Step 1: Fresh data dir**

```bash
rm -rf /tmp/sourbycraft-smoke && mkdir /tmp/sourbycraft-smoke
cp release/SourbyCraft-v12-REL.jar /tmp/sourbycraft-smoke/server.jar
cd /tmp/sourbycraft-smoke
echo "eula=true" > eula.txt
```

- [ ] **Step 2: Boot with `pvp.enabled: false` (default)**

```bash
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
SERVER_PID=$!
sleep 30
grep -E "Done|SourbyCraft" boot.log | head -20
kill -INT $SERVER_PID
wait $SERVER_PID 2>/dev/null
```

Expected: log shows `Done (XXs)!`, no `[SourbyCraft:PvP] PvP server mode active` line, no `[SourbyCraft:PvP] netty eventLoopThreads set to` line, no `proxy transfer-out` line.

- [ ] **Step 3: Flip `pvp.enabled: true` and re-boot**

```bash
sed -i.bak 's/^  enabled: false/  enabled: true/' plugins/SourbyCraft/sourbycraft.yml
grep "enabled:" plugins/SourbyCraft/sourbycraft.yml | head -3
java -Xmx2G -jar server.jar nogui > boot-pvp.log 2>&1 &
SERVER_PID=$!
sleep 30
grep -E "SourbyCraft:PvP|Done" boot-pvp.log
kill -INT $SERVER_PID
wait $SERVER_PID 2>/dev/null
```

Expected: log shows `Done (XXs)!`, plus `[SourbyCraft:PvP] PvP server mode active — KB friction=...`, plus the netty eventLoopThreads line.

- [ ] **Step 4: Verify `/plugins` is brigadier-vanilla**

Either inspect log for `PaperPluginsCommand` registration debug output, or attach via console and run `/plugins` — output should be Paper's grouped-by-author format, not the custom `Plugins (N): ...` legacy format.

- [ ] **Step 5: Verify spark was auto-installed**

```bash
ls plugins/spark-*.jar
```
Expected: one jar file matching `spark-*-bukkit.jar`. ViaVersion / ViaBackwards / PacketEvents jars should NOT be present.

- [ ] **Step 6: Commit smoke evidence (optional — usually skipped)**

If anything failed, fix root cause, return to the relevant phase, repeat.

### Task 24: Final commit / cleanup

- [ ] **Step 1: Verify git status is clean**

```bash
git status
```
Expected: working tree clean, branch ahead of `origin/feat/pvp-server` by all commits from this PR.

- [ ] **Step 2: Inspect commit log**

```bash
git log --oneline -30
```
Expected: roughly 18-22 commits from this plan, all on the current branch.

- [ ] **Step 3: Optional — squash trivially small commits**

If any phase produced a "no-op" commit (e.g., Task 16 if no test changes), `git reset --soft HEAD~1` to combine with the preceding commit. Skip if unsure.

---

## Out-of-Scope Reminders

These are explicitly NOT covered by this plan and will be tracked in separate specs:

1. **UniverseSpigot config import (~200 keys)** — async/behavior/combat/fixes/limiters/particles/sounds/performance categories. Each category gets its own sub-spec.
2. **NMS plugin compatibility (Citizens, DecentHolograms, NBTAPI, etc.)** — separate investigation spec.
3. **Multi-profile system** (survival/creative/pvp profiles). Single PvP toggle only.

Do not pull these into this PR.

---

## Verification Summary

After all phases complete, the following should be true:

| Check | Command | Expected |
|---|---|---|
| Single jar in release/ | `ls release/*.jar` | `SourbyCraft-v12-REL.jar` (one file) |
| Single checksum line | `wc -l release/checksums.txt` | `1` |
| No variant gradle property | `grep -c 'gradleProperty("variant")' build.gradle.kts` | `0` |
| No variant overlay tree | `ls sourbycraft-server/src/main/resources/variant-overlay/` | `No such file or directory` |
| No overlay loader field | `grep -c sourbycraftYmlOverlay sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | `0` |
| pvpEnabled default false | `grep 'pvpEnabled = ' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | shows `= false` |
| Patches 9001-9005 gated | `grep -c 'pvpEnabled' patches/minecraft/9001-PVP-netty-tuning.patch` | `>= 2` (static + initChannel) |
| Patch 0031 gone | `ls patches/server/0031-*.patch 2>/dev/null` | empty |
| Patch 0032 gone | `ls patches/server/0032-*.patch 2>/dev/null` | empty |
| No custom /plugins in 0013 | `grep -c PluginsCommand patches/server/0013-*.patch` | `0` |
| Unified plugin manifest | `ls sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins.yml` | file exists |
| Variant manifest dir gone | `ls sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/` | `No such file or directory` |
| `installFromVariant` removed | `grep -rn installFromVariant sourbycraft-server/src patches` | empty |
| `BuildInfo.isPvp` removed | `grep -rn 'isPvp\(' sourbycraft-server/src patches` | empty |
