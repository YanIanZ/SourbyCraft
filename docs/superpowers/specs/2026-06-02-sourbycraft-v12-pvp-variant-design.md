# SourbyCraft v12.0 — Build-variant Split (Normal + PVP)

**Date:** 2026-06-02
**Branch:** `feat/pvp-server` → target merge to `main`
**Version bump:** v11.0 → v12.0-REL
**Tagline:** *Lightning Fast Performance · Feature Rich*

## Summary

v12.0 splits the single SourbyCraft paperclip JAR into two build-time variants from
one codebase: `SourbyCraft-<ver>.jar` (general SMP) and `SourbyCraft-PVP-<ver>.jar`
(PvP arena backend, tuned as proxy sub-server). Same `gradlew` task tree, variant
selected via `-Pvariant=pvp|normal`. PvP variant adds four PvP-only patches, ships
proxy-aware defaults, auto-installs PvP-essential plugins, and brands itself
distinctly in console / `/ver` / `/plugins`.

## Goals

1. Two JARs from one Gradle codebase — no fork, no runtime mode flag.
2. PvP variant: matured perf bundle (network, entity-tracker, GC advisory, combat
   completion) on top of v11.0 KB + no-cooldown + DAB throttle + view-dist cap.
3. Tagline + branding cohesive across banner / `/ver` / `/plugins` / `/pl` /
   startup log lines.
4. PvP variant pre-tuned as Velocity/Bungee proxy backend.
5. Operator-visible "neater" plugin output — categorized, colored, aligned.

## Non-goals

- Single JAR with runtime `--pvp` flag (ruled out: dead code paths, JIT cost).
- Separate fork/branch publishing (ruled out: drift risk).
- CPU pinning (JNA platform fragility — left as future work).
- Reorganizing `plugins/` directory on disk (Bukkit compat risk).

## §1 Build system

### Patch convention
- `patches/server/0001..0999-*.patch` — shared baseline (both variants apply)
- `patches/server/9001..9999-PVP-*.patch` — PvP-only patches
- Same scheme for `patches/api/`
- PvP patches MUST touch separate files or appended-only blocks vs shared
  patches; reviewer enforces this — line-number drift breaks `git am`.

### Gradle wiring
`gradle.properties`:
```properties
variant = normal
releaseVersion = 12
internalVersion = v12-REL
```

`build.gradle.kts` (excerpt):
```kotlin
val variant = providers.gradleProperty("variant").getOrElse("normal")
val isPvp = variant == "pvp"

configure<PaperweightPatcherExtension> {
    upstreams.paper {
        listOf("api", "server").forEach { part ->
            patchDir("paper${part.replaceFirstChar { it.titlecaseChar() }}") {
                upstreamPath = "paper-$part"
                patchesDir = file("patches/$part")
                featurePatchDir = patchesDir.dir(".")
                excludes = if (isPvp) emptySet() else setOf("9*.patch")
            }
        }
    }
}
```

### Output naming
- `./gradlew createReobfPaperclipJar -Pvariant=normal` → `SourbyCraft-12.0-REL.jar`
- `./gradlew createReobfPaperclipJar -Pvariant=pvp` → `SourbyCraft-PVP-12.0-REL.jar`

### CI matrix
GitHub Actions: matrix `variant: [normal, pvp]`, each builds + smoke-tests, both
artifacts uploaded to release. PR builds run both legs.

## §2 Variant config

### Layout
```
sourbycraft-server/src/main/resources/
├── sourbycraft.yml                    # baseline (shared)
├── variant-overlay/
│   ├── normal/sourbycraft.yml         # general SMP defaults
│   └── pvp/sourbycraft.yml            # PvP arena defaults
```

Gradle task `processVariantResources` (runs before `processResources`):
- Copies baseline `sourbycraft.yml` → staging
- Deep-merges `variant-overlay/<variant>/sourbycraft.yml` onto staging
- Result bundled into JAR resources at `sourbycraft.yml`

Runtime `SourbyCraftConfig.load()` reads only `sourbycraft.yml`. No variant
branching in server code — defaults vary by build, not by runtime check.

### Variant defaults

| Key | normal | pvp |
|---|---|---|
| `pvp.enabled` | `false` | `true` |
| `pvp.knockback.friction-divisor` | `2.0` (vanilla 1.9) | `1.0` (1.8-style) |
| `pvp.knockback.vertical` | `0.4` | `0.4` |
| `pvp.knockback.extra-horizontal` | `0.5` | `0.5` |
| `pvp.no-attack-cooldown` | `false` | `true` |
| `pvp.view-distance-cap` | `10` | `6` |
| `pvp.simulation-distance-cap` | `8` | `5` |
| `pvp.max-mobs-per-chunk` | `8` | `4` |
| `pvp.mob-ai-activation-range` | `32` | `24` |
| `network.proxy-mode` | `none` | `velocity-modern` |
| `network.netty.threads` | `auto` (= cores) | `auto*2` |
| `entity-tracker.mob-range` | `48` | `32` |
| `entity-tracker.item-range` | `64` | `16` |
| `entity-tracker.xp-orb-range` | `64` | `16` |
| `entity-tracker.player-update-interval` | `2` | `1` |
| `combat.sweep-enabled` | `true` | `false` |
| `combat.hit-delay-ticks` | `10` | `8` |
| `combat.hit-window-ms` | `100` | `150` |
| `combat.fishing-rod-knockback` | `false` | `true` |
| `combat.reach-debug-command` | `false` | `true` |
| `branding.compact-plugin-log` | `false` | `true` |
| `auto-install.enabled` | `true` | `true` |

Boot log line on load:
```
[SourbyCraft] Loaded variant: PVP (pvp.enabled=true, view-dist=6, sim-dist=5)
```

## §3 Plugin bundling (Method 1: first-boot auto-installer)

### Mechanism
JAR ships `META-INF/sourbycraft-plugins/<variant>.yml` manifest. `PluginAutoInstaller`
runs at server pre-boot, before Bukkit plugin scan:
1. Read variant manifest from classpath
2. For each entry: check `plugins/<name>-*.jar` glob → skip if present
3. Else download via GitHub Releases API (reuses `swm-plugin` HTTP code path) or
   direct URL → write to `plugins/`
4. SHA256 verify when manifest provides hash; refuse on mismatch
5. Failures logged at ERROR, do NOT block boot (operator may drop JAR manually)

Disable: `sourbycraft.yml -> auto-install.enabled: false`.

### Manifest format

`META-INF/sourbycraft-plugins/normal.yml`:
```yaml
plugins:
  - name: SlimeWorldManager
    source: github
    repo: InfernalSuite/SlimeWorldManager
    asset-glob: "swm-*.jar"            # picks GitHub release asset by name pattern
  - name: spark
    source: ci
    url: "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-*-bukkit.jar"
```

Manifest schema:
- `name` (required): plugin display name + filename prefix used for existence check
- `source` (required): `github` (uses GitHub Releases API) or `ci` (direct URL)
- `repo` (required for `source: github`): `owner/repo` format
- `url` (required for `source: ci`): direct download URL, wildcards expanded server-side
- `asset-glob` (optional, `source: github`): asset name pattern when repo has multiple release assets
- `sha256` (optional): hex digest, enforced when present

`META-INF/sourbycraft-plugins/pvp.yml`:
```yaml
plugins:
  - name: SlimeWorldManager
    source: github
    repo: InfernalSuite/SlimeWorldManager
  - name: ViaVersion
    source: github
    repo: ViaVersion/ViaVersion
  - name: ViaBackwards
    source: github
    repo: ViaVersion/ViaBackwards
  - name: PacketEvents
    source: github
    repo: retrooper/packetevents
  - name: spark
    source: ci
    url: "https://ci.lucko.me/..."
```

No third-party JARs bundled in SourbyCraft JAR — pure manifest, downloads at runtime.
Avoids license entanglement.

## §4 Branding

### Tagline
**"Lightning Fast Performance · Feature Rich"** — single source of truth in
`META-INF/sourbycraft-build.properties`:
```
tagline=Lightning Fast Performance · Feature Rich
variant=pvp
version=v12.0-REL
mcVersion=1.21.11
buildTimestamp=2026-06-02T10:30:00Z
```

Written by Gradle task `writeBuildInfo` (runs in `processVariantResources` chain).

### Startup banner
Printed by `MinecraftServer` early init, after JVM args, before plugin load:

```
   ╔══════════════════════════════════════════════════════════╗
   ║                                                          ║
   ║   ⚡  SOURBYCRAFT  ⚡   ·  v12.0  ·  PVP                  ║
   ║                                                          ║
   ║   Lightning Fast Performance  ·  Feature Rich            ║
   ║                                                          ║
   ║   Paper 1.21.11  ·  Java 25  ·  Variant: PVP             ║
   ║   pvp.enabled=true  view=6/5  mobs/chunk=4               ║
   ║                                                          ║
   ╚══════════════════════════════════════════════════════════╝
```

Normal variant prints `Variant: NORMAL` and omits the `pvp.enabled` summary line.

### /ver output
```
SourbyCraft v12.0-REL · Variant: PVP
Minecraft 1.21.11 · Paper API 1.21.11
Java 25 (Adoptium) · Uptime 2h 14m
"Lightning Fast Performance · Feature Rich"
```
Aliases unchanged: `/version`, `/about`.

### Server-list MOTD suffix
Opt-in: `branding.motd-suffix: true` (default OFF — operators usually want own MOTD).
Suffix appended to MOTD:
- PVP: ` §8[§b⚡§8 PVP §8]`
- Normal: ` §8[§b⚡§8 SourbyCraft §8]`

### README + repo description
- README header replaces top shield-row with tagline + badges below
- GitHub repo description set to `"⚡ Lightning Fast Performance · Feature Rich — Paper fork w/ PvP-arena variant"` (manual `gh repo edit` post-merge)

## §5 /plugins, /pl, startup log reformat

### Category source
`META-INF/sourbycraft-plugin-categories.yml` (bundled in JAR):
```yaml
categories:
  Core:    [LuckPerms, EssentialsX, Vault, PlaceholderAPI]
  PvP:     [CombatLogX, ViaVersion, ViaBackwards, PacketEvents, ProtocolLib]
  World:   [WorldEdit, WorldGuard, SlimeWorldManager, FastAsyncWorldEdit]
  Util:    [spark, Multiverse-Core]
  Economy: [EconomyAPI, CMI]
```
Unknown plugin → `Other` category.

Operator override: `plugins/SourbyCraft/categories.yml` (deep-merge after embedded).

### /plugins and /pl output
Same handler, new formatter:
```
┌─ ⚡ Lightning Fast · ✨ Feature Rich ─────────────────┐
│ §6Core§r        LuckPerms         §av5.4.130
│                 EssentialsX       §av2.21.0
│                 Vault             §av1.7.3
│ §cPvP§r         CombatLogX        §av11.2.1
│                 ViaVersion        §av5.2.1
│                 PacketEvents      §7v2.5.0§8 (disabled)
│ §aWorld§r       WorldEdit         §av7.3.7
│                 SlimeWorldMgr     §av2.10.0
│ §eUtil§r        spark             §av1.10.142
│ §8Other§r       MyCustomPlugin    §av1.0
└─ 9 plugins · 8 enabled · 1 disabled · 142 MB heap ──┘
```

Color/status legend:
- `§a` green = enabled, version OK
- `§7` gray + `(disabled)` = registered but disabled
- `§c` red + `(failed)` = enable error
- `§e` yellow + `(missing dep: X)` = depend on absent plugin

Column alignment: name padded to 18 chars, version right-aligned. Width-aware —
falls back to flat list when terminal width < 60 cols, or when
`branding.compact-plugin-list=false`.

### Startup plugin-load log lines
Replaces vanilla `[Plugin] Enabling Foo v1.0` flat spam with grouped batch
printed after `ServerLoadEvent`:
```
[SourbyCraft] Plugins ready (4.2s):
  ⚡ Core (4)    LuckPerms · EssentialsX · Vault · PAPI
  ⚔ PvP (3)     CombatLogX · ViaVersion · PacketEvents
  🌍 World (2)  WorldEdit · SlimeWorldMgr
  🔧 Util (1)   spark
```

Suppression of vanilla lines via `log4j` filter; gated by
`branding.compact-plugin-log` (default `true` PVP, `false` normal).

## §6 Sub-server method (Velocity/Bungee backend tuning, PVP variant)

Normal variant unchanged (general SMP, `online-mode=true` default).

### PVP overlay files

`variant-overlay/pvp/paper-global.yml`:
```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "CHANGE-ME-SEE-DOCS"
  bungee-cord:
    online-mode: true
```

`variant-overlay/pvp/server.properties`:
```properties
online-mode=false
prevent-proxy-connections=false
network-compression-threshold=-1
enforce-secure-profile=false
allow-nether=false
```

`variant-overlay/normal/server.properties`:
```properties
allow-nether=true
```

`variant-overlay/pvp/spigot.yml`:
```yaml
settings:
  bungeecord: false
```

`variant-overlay/pvp/paper-world-defaults.yml`:
```yaml
chunks:
  prevent-moving-into-unloaded-chunks: true
entities:
  spawning:
    despawn-ranges:
      monster:
        hard: 64
        soft: 24
```

### Boot guards (PVP only)
1. `velocity.secret` == `CHANGE-ME-SEE-DOCS` → loud WARN banner, refuse plugin
   auto-install of network plugins
2. `online-mode=true` in `server.properties` → WARN (non-fatal — standalone use OK)

### Proxy-aware kick handling
New patch (PVP-only, part of §7 bundle no — separate patch `9005-PVP-proxy-kick.patch`):
- On `/stop`, broadcast `PluginMessage("BungeeCord", "KickPlayer", ...)` /
  Velocity-modern transfer-out to fallback before SIGTERM
- Grace: `network.proxy-kick-grace-seconds: 5` (0 disables)

### IP-forward header strict
Part of `9005-PVP-proxy-kick.patch`:
- Reject connections lacking valid Velocity/Bungee forward header when
  `proxies.velocity.enabled=true`
- Log to ERROR level (Paper default is WARN)

### /sys addition (both variants, surfaces only when `proxy-mode != none`)
```
Proxy:     Velocity (modern forwarding) · secret OK
Players:   12 connected · 8 transferred-in last 1h
```

## §7 PvP perf bundle (4 PVP-only patches)

All `9XXX-PVP-*` per §1, applied only when `variant=pvp`.

### §7.1 `9001-PVP-netty-tuning.patch`

`MinecraftServer.java` + `ServerConnection.java`:
- Netty `EventLoopGroup` thread count: `Runtime.availableProcessors() * 2`
- `ChannelOption.TCP_NODELAY=true` forced
- `ChannelOption.SO_SNDBUF` / `SO_RCVBUF` raised to 256 KB
- `Connection.maxPacketsPerTick` 100 → 200

Config knobs: `sourbycraft.yml -> network.netty.{threads, snd-buf-kb, rcv-buf-kb, max-packets-per-tick}`.

### §7.2 `9002-PVP-entity-tracker-tightening.patch`

`ServerEntity.java` + `ChunkMap.java`:
- Non-player entity tracking range cap: `min(Paper config, entity-tracker.mob-range)`
- Item entity tracking range: `entity-tracker.item-range` (PVP 16)
- Player→Player update interval: `entity-tracker.player-update-interval` (PVP 1)
- XP orb tracking: capped at `entity-tracker.xp-orb-range` (PVP 16)

### §7.3 `9003-PVP-cpu-pin-gc-banner.patch`

Advisory only — no JNI/JNA dep. `MinecraftServer` early init:
- Detect GC via `ManagementFactory.getGarbageCollectorMXBeans()`
- Accepted GCs: ZGC (with `+ZGenerational`) OR G1 (G1 is generational by default).
  Anything else (ParallelGC, Serial, CMS, SerialOld) → WARN.
- If not accepted → loud WARN banner recommending:
  ```
  -XX:+UseZGC -XX:+ZGenerational
  -XX:+AlwaysPreTouch
  -XX:+UseLargePages
  -Xms=Xmx (same value)
  ```
- Detect `-Xms` != `-Xmx` → add to WARN
- Detect missing `AlwaysPreTouch` → add to WARN

`/sys` adds GC line:
```
GC:        ZGC Generational · 0 long pauses last 5m ✓
```

Silence: `branding.gc-advisor.enabled: false`.

### §7.4 `9004-PVP-combat-completion.patch`

`LivingEntity.java` + `Player.java` + `ServerPlayer.java` + `FishingHook.java`:
- `sweepAttack()` gated on `combat.sweep-enabled` (PVP overlay: `false`)
- `invulnerableTime` (i-frames) → `combat.hit-delay-ticks` (PVP `8`, vanilla `10`)
- `FishingHook.retrieve()` applies KB to hooked entity using
  `pvp.knockback.friction-divisor`
- New command `/reach` (registered when `combat.reach-debug-command: true`) —
  shows server-side reach of last hit:
  ```
  [reach] last hit: PlayerA → PlayerB · 3.42 blocks · 85ms latency · window=150ms ✓
  ```
- Hit-window widening: tolerate up to `combat.hit-window-ms` ms of attack-packet
  lateness (PVP `150`, vanilla `100`)

### §7.5 `9005-PVP-proxy-kick.patch`

Implements proxy-aware shutdown + strict IP-forward header (full description in §6).
Listed here for completeness of the `9XXX-PVP-*` patch series.

## §8 Testing + rollout

### CI verification
- Matrix `variant: [normal, pvp]` builds both legs on every PR
- Each leg: compile → paperclip JAR → smoke (start, /stop within 30s)
- New job `verify-variant-divergence`:
  - Extracts `META-INF/sourbycraft-build.properties` from each JAR
  - Asserts `variant=normal` and `variant=pvp` respectively
  - Asserts JAR file names match expected pattern

### Patch parity check
CI script (`scripts/verify-patch-parity.sh`):
- Counts shared patches (`0001-0999-*.patch`) — must equal across `applyPatches`
  invocations on both variants
- PVP patches (`9001-9999-*.patch`) — must apply cleanly on PVP only;
  `git apply --check` against normal-variant tree must FAIL for each PVP patch
  (proves filtering works)

### Smoke checklist (operator, manual)

Normal jar:
1. Boot fresh server with `SourbyCraft-12.0-REL.jar`
2. Console: `Loaded variant: NORMAL`, banner shows `Variant: NORMAL`
3. `/ver` shows `Variant: NORMAL`
4. `/plugins` shows new boxed format
5. `pvp.enabled=false` in seeded `sourbycraft.yml`
6. `allow-nether=true` in seeded `server.properties`
7. `/stop` exits clean

PVP jar:
1. Boot fresh server with `SourbyCraft-PVP-12.0-REL.jar`
2. Console: `Loaded variant: PVP`, banner shows `Variant: PVP`
3. `/ver` shows `Variant: PVP`
4. `/reach` registered (Bukkit `/plugins` does NOT list it — it's NMS command)
5. Hit dummy → `/reach` prints reach + latency + window
6. `/sys` shows `Proxy: Velocity (modern forwarding)` line
7. `pvp.enabled=true`, `view-distance-cap=6` in seeded config
8. `allow-nether=false` in seeded `server.properties`
9. WARN banner if `velocity.secret` unchanged
10. WARN banner if not ZGC
11. `/stop` triggers 5s proxy-kick grace before exit

### Operator migration v11 → v12

**v11 single-jar users:** download `SourbyCraft-12.0-REL.jar` (normal variant).
No config breakage — existing `pvp.*` keys read at current values. Variant overlay
seeds only on FRESH install (no existing `sourbycraft.yml`).

**v11 PvP-tuned users:** download `SourbyCraft-PVP-12.0-REL.jar`. Existing
`sourbycraft.yml` NOT overwritten. Operator manually merges new defaults if
desired. Boot prints hint:
```
[SourbyCraft] PVP variant has updated defaults. To apply:
              rename sourbycraft.yml → sourbycraft.yml.bak, restart.
```

**Rollback path v12 → v11:** drop v11 JAR back in. v12-specific `pvp.*` /
`network.netty.*` / `entity-tracker.*` keys remain in YAML, ignored by v11
(Snakeyaml lax mode).

### Release artifacts (`release/`)
- `SourbyCraft-12.0-REL.jar` (normal variant)
- `SourbyCraft-PVP-12.0-REL.jar` (pvp variant)
- `RELEASE-NOTES-v12.md`
- `checksums.txt` (sha256 of both jars)

## Module / file map

| File | Purpose | New / changed |
|---|---|---|
| `gradle.properties` | `variant=normal`, `releaseVersion=12`, `internalVersion=v12-REL` | changed |
| `build.gradle.kts` | Variant property, patch-filter logic | changed |
| `patches/server/9001-PVP-netty-tuning.patch` | §7.1 | new |
| `patches/server/9002-PVP-entity-tracker-tightening.patch` | §7.2 | new |
| `patches/server/9003-PVP-cpu-pin-gc-banner.patch` | §7.3 | new |
| `patches/server/9004-PVP-combat-completion.patch` | §7.4 | new |
| `patches/server/9005-PVP-proxy-kick.patch` | §6 | new |
| `sourbycraft-server/src/main/resources/sourbycraft.yml` | Baseline config | changed |
| `sourbycraft-server/src/main/resources/variant-overlay/normal/sourbycraft.yml` | Normal overlay | new |
| `sourbycraft-server/src/main/resources/variant-overlay/pvp/sourbycraft.yml` | PVP overlay | new |
| `sourbycraft-server/src/main/resources/variant-overlay/normal/server.properties` | Normal server.props seed | new |
| `sourbycraft-server/src/main/resources/variant-overlay/pvp/server.properties` | PVP server.props seed | new |
| `sourbycraft-server/src/main/resources/variant-overlay/pvp/paper-global.yml` | PVP paper-global seed | new |
| `sourbycraft-server/src/main/resources/variant-overlay/pvp/spigot.yml` | PVP spigot seed | new |
| `sourbycraft-server/src/main/resources/variant-overlay/pvp/paper-world-defaults.yml` | PVP world defaults | new |
| `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/normal.yml` | Normal plugin manifest | new |
| `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/pvp.yml` | PVP plugin manifest | new |
| `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugin-categories.yml` | Category map | new |
| `sourbycraft-server/.../PluginAutoInstaller.java` | First-boot installer | new |
| `sourbycraft-server/.../SourbyCraftBanner.java` | Startup banner | new |
| `sourbycraft-server/.../PluginsCommand.java` | /plugins+/pl new formatter | changed |
| `sourbycraft-server/.../GcAdvisor.java` | §7.3 GC detection + warn | new |
| `sourbycraft-server/.../ReachCommand.java` | §7.4 /reach | new |
| `scripts/verify-patch-parity.sh` | CI patch-filter sanity | new |
| `.github/workflows/build.yml` | Matrix variant build | changed |
| `README.md` | Tagline header, variant docs | changed |

## Open questions / future work

- CPU pinning via JNA — deferred. Would land as `9006-PVP-cpu-pin.patch` once
  cross-platform story (macOS vs Linux vs Windows) is settled.
- Plugin auto-install offline mode — variant manifests assume network at first
  boot. Future: optional `--bundle-plugins` Gradle flag to embed JARs (license
  audit required).
- `/plugins` category override via per-plugin annotation — currently static
  YAML. Future: read `categories: [PvP]` from plugin's own `plugin.yml`.
