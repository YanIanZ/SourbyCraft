<h1 align="center">⚡ SourbyCraft</h1>

<p align="center"><strong>Lightning Fast Performance · Feature Rich · Self-Tuning</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.1.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-26.1.2--REL-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/release-r9-blue?style=flat-square">
  <img src="https://img.shields.io/badge/jar%20size-31M-green?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<p align="center"><em>High-performance Paper fork on year-based Minecraft 26.1.2 with self-tuning perf engine, slim jar bootstrap, NMS-level security, anti-xray, and SWM. Fork of <a href="https://github.com/PaperMC/Paper">Paper</a>.</em></p>

---

## What's New in 26.1.2-REL

**Latest tag:** [`v26.1.2-r9`](https://github.com/YanIanZ/SourbyCraft/releases/tag/v26.1.2-r9) — Friday, 19 June 2026, 12:01 (GMT+7).

### r9 highlights
- **SWM v13 NBT mismatch fix** — `SlimeSerializer` previously wrote extra data via `NbtIo.writeCompressed` (gzip) then wrapped the result in the outer zlib pass, while `v13SlimeWorldReader` decompressed only the zlib layer and called `NbtIo.read` on raw gzip bytes — first byte `0x1F` got parsed as a tag id, blowing up every SS2 island join with `Invalid tag id: 31`. Writer now emits uncompressed NBT (canonical), reader detects the legacy gzip magic and falls back to `NbtIo.readCompressed` so existing on-disk islands keep loading.

### r8 highlights
- **Unified text-rendering helper** — new `dev.iyanz.sourbycraft.util.TextRender` accepts any mix of MiniMessage tags (`<red>`, `<gradient:#A:#B>`, `<rainbow>`), legacy `&` colour codes, Adventure-style `&#RRGGBB` hex, classic `§x§R§R§G§G§B§B`, and raw unicode emoji glyphs. Malformed input never throws — it falls through to legacy parsing and finally to a plain text component, so a typo in `sourbycraft.yml` cannot crash the boot path.
- **`SourbyCraftConfig.getComponent` + world-config getComponent** swapped to TextRender. Previously a single bad MiniMessage tag in an operator's config would surface as a `ParsingException` on boot.

### r7 highlights
- **SWM heap-leak fix** — `AdvancedSlimePaperImpl#onWorldUnload` was defined but never called, so every unloaded SS2 island world stayed pinned in heap (full `chunkStorage` retained). `SWPlugin#onEnable` now registers a `WorldUnloadEvent` listener that bridges to `onWorldUnload(name)`. Operators reporting 90 %+ RAM with steady island generation will see flat memory after r7.
- **`/ver` banner format** — Replaces Paper's `VERSION_FULL` (`26.1.2-DEV-<hash>`) with the SourbyCraft build identity from `META-INF/sourbycraft-build.properties` plus the GMT+7 day-name build timestamp (e.g. `26.1.2-REL  Friday, 19 June 2026 11:10`).
- **`/perf` command now registered** — Was defined but missing from the boot registration array, so the Brigadier root shadowed it. Added `perf`, `perfengine`, `tuner` to `sourbycraftCommandNames` + the register-loop in `DedicatedServer#initServer` (new patch `0045`).
- **`/sys plugins` rewrite** — Reports `<active> active / <loaded> loaded / <errored> errored`. Disabled-but-registered plugins and load-time failures (captured by the new `PluginLoadDiagnostics` JUL handler) are each listed with their name + first deep-cause line so operators see "X failed, last message: Y" without grepping the log.

### r6 highlights
- **Banner version sync** — the in-jar `META-INF/sourbycraft-build.properties` is now derived from the current git branch (REL on `release/*`, EXP on `experimental/feat*`, DEV elsewhere) via `sourbycraftSuffixProvider`. The hardcoded `internalVersion` in `gradle.properties` is gone — the banner always matches the jar filename and manifest.
- **`ChunkMap#getChunkDataFixContextTag` null-guard** — SWM-backed levels (and any plugin that calls `runActionOnUnloadedChunks` on a level without a registered `LevelStem` typeKey) used to NPE on every chunk upgrade. New feature patch `0044` wraps the `stemKey.identifier()` deref in a null check, killing the stack-trace spam during SS2 island generation.
- **Release-task config-cache safety** — `writeBuildInfo` + `assembleReleaseArtifacts` now opt out of the configuration cache (they shell out to git at task time). Other tasks keep caching.

### r4 highlights
- **REL artifact naming** — `release/*` branches stamp the manifest as `Implementation-Version: 26.1.2-REL`. Canonical jar is `SourbyCraft-26.1.2-REL.jar`.
- **Persistent `/tpsbar` + `/rambar` toggle** — `BarToggleManager` owns per-player BossBars with 1s live refresh. First invocation shows, second hides. Replaces the 10-second auto-hide.
- **`/perf` command** (aliases `/perfengine`, `/tuner`) — branded panel with live `PerfSensor` tier + warmup, six signal lines (TPS 1s/30s/5m, MSPT, Mem%, GC/min), full `KnobRegistry` snapshot.
- **`/ping` always emits the Location row** — async GeoIP via `VirtualExecutor`, "looking up..." placeholder, "unavailable" fallback when the lookup misses.

Major upgrade from `12-EXP` (1.21.11) to year-based Minecraft 26.1.2:

- **Paper 26.1.2 hard-fork migration** — paperweight-patcher 2.0.0-beta.21, mache 26.1.2+build.3, paperclip 3.0.4. Year-based MC versioning (no more 1.x). Spigot mappings + reobf pipeline dropped.
- **Java 25 required** — uses unnamed variables, virtual threads, ZGC generational. JDK 21 no longer supported.
- **Single-variant build** — PVP variant + Pufferfish vendor dropped. Single `SourbyCraft-26.1.2-REL.jar` artifact.
- **Adventure book force-op exploit blocked** — `BookSanitizer` strips `click_event` + `hover_event` from written-book page components on the creative-slot inject path. Closes the classic rigged-book-clicks-as-op vector.
- **YAML loaders hardened** — every `new Yaml()` site replaced with `SafeConstructor` to block tag-based gadget RCE (`!!javax.script.ScriptEngineManager` etc.).
- **Plugin / library downloaders hardened** — `PluginDownloader`, `LibDownloader`, SWM `PluginInstaller`: https-only (manual redirect re-check), 100 MB cap with running byte counter, path-traversal containment.
- **SWM `FileLoader` path traversal fixed** — `/swm load <name>` rejects slash / `..` / leading-dot, normalizes target, verifies containment inside baseDir.
- **Perf-engine P2 lag-machine wiring** — projectile chunk-load throttle (per-tick + per-projectile), excess minecart/boat sweeper, excess falling-block sweeper. All gated by `Knobs.LAG_MACHINE_*` (default ON for snowball/firework save fixes, OFF for sweepers).
- **Sourby Bootstrap (slim jar)** — release jar 31 M (down from 57 M). 6 optional libs + Ookla speedtest CLI lazy-download on first boot into `libraries/` with SHA-256 verification.
- **Anti-xray extension (stonar96 RayTraceAntiXray port)** — async ore visibility cache + entity LOS gate + particle LOS gate. NMS hooks on `ChunkMap.TrackedEntity#updatePlayer` (patch 0040) and `ServerLevel#sendParticles` (patch 0041). Hides mobs / item drops / `TextDisplay` holograms / particles behind walls. Liquid surface delegated to Paper `anticheat.anti-xray.fluid-obscures`. All toggles default OFF.
- **Spark profiler integration** — `SparkBridge` lazy-binds to `me.lucko.spark.api.SparkProvider`, `/sparkview` command renders TPS 5s/10s/1m/5m/15m + MSPT 10s/1m/5m mean/max/95th + CPU process/system + per-GC totals through the SourbyCraft hex palette and the same `▰▱` bar style as the rest of /tps /sys /ver. Toggle via `spark.enabled` in sourbycraft.yml.

See `release/RELEASE-NOTES-26.1.2.md` for the full patch list.

---

## Build

```bash
./gradlew assembleReleaseArtifacts
```

Output: `release/SourbyCraft-26.1.2-REL.jar` (~31 M slim) + `release/checksums.txt`.

---

## First Boot

The release jar is slim (~31 M). First boot downloads ~28 M of optional libraries into `libraries/` with SHA-256 verification:

| Lib | Size | Source |
|---|---|---|
| `sqlite-jdbc` | ~14M | `repo1.maven.org` |
| `mysql-connector-j` | ~2.6M | `repo1.maven.org` |
| `spark-paper` | ~3M | `repo.papermc.io` |
| `Flare` (profiler engine) | ~2M | `jitpack.io` |
| `protobuf-java` | ~1.9M | `repo1.maven.org` |
| `sentry` (opt-in error tracking) | ~918K | `repo1.maven.org` |
| Ookla `speedtest` CLI (lazy on `/speedtest`) | ~2.5M | `install.speedtest.net` |

**Required outbound HTTPS:** `repo1.maven.org`, `repo.papermc.io`, `jitpack.io`, `install.speedtest.net`.

**Offline first boot:** `[SourbyBootstrap] FATAL` log lists every URL + destination path under `libraries/`. Side-load the files manually and restart. Subsequent boots are silent cache-hit fast path (zero downloads, baseline boot time).

---

## Self-Tuning Perf Engine

Status across the 9-sub-project roadmap:

| Sub-project | Status | What it ships |
|---|---|---|
| **P0** Knob Registry API | ✓ shipped | `BoolKnob`/`IntKnob` abstraction, `Knobs` static holder, `KnobRegistry`, boot-time snapshot line |
| **P1** Load Sensor + Tier Classifier | ✓ shipped | `PerfSensor` (TPS rolling / MSPT / mem% / GC), 5-tier state machine, NMS hook in `tickChildren` |
| **P2** Lag-Machine Protection | ✓ shipped | 8 knobs wired across 5 NMS patches: snowball / firework save fix (default ON), projectile chunk-load throttle (10/tick, 10/projectile), excess minecart/boat/falling-block sweeper (default OFF + per-chunk caps) |
| **P3** Adaptive Entity AI | ✓ shipped | 2 knobs + Mob.aiStep early-return when no live player within distance (0039) |
| **P4** Combat Profiles | ✓ shipped | `CombatProfile` enum (VANILLA / BALANCED / PVP) applied at boot from `combat.profile` yml key; bundles P0..P3 knob defaults per playstyle |
| **P5** Async Chunk Pipeline | ✓ shipped (helper) | `AsyncChunkPipeline` routes read-only chunk/tracker work onto `VirtualExecutor`; complements Moonrise's async chunk system |
| **P6** Async Packet & World | ✓ shipped | non-flush packet (0033) + virtual-thread server pools (0019) + `AsyncDataSaver` helper for off-main IO. Read-side async owned by Moonrise |
| **P7** Self-Tune Controller | ✓ shipped | `SelfTuneController` subscribes to `PerfSensor` transitions; tier policy escalates lag-machine + AI knobs; restores operator baseline on recovery |
| **P8** Operator UX | ✓ shipped (BossBar) | `TierBossBar` per-player opt-in; tier-coloured (GREEN/YELLOW/PINK/RED/PURPLE); refresh on transition |

### Knobs config (`sourbycraft.yml`)

```yaml
perf:
  entity-tick-rate: 20                # P0 reference knob (1=vanilla every-tick; 20=1-in-20)
  sensor:                             # P1
    enabled: true
    cadence-ticks: 20                 # 1s at 20 TPS
    dwell-samples: 3                  # samples in candidate tier required before escalation
    recovery-dwell-multiplier: 2.0    # recovery needs dwell-samples × multiplier
    warmup-ticks: 200                 # ticks to skip at startup before sampling (10s at 20 TPS)
    thresholds:
      tps:           { yellow: 19.5, orange: 18.0, red: 15.0, emergency: 10.0 }
      mspt:          { yellow: 30,   orange: 40,   red: 60,   emergency: 100 }
      mem:           { yellow: 75,   orange: 85,   red: 92,   emergency: 97 }
      gc-ms-per-min: { yellow: 20,   orange: 50,   red: 100,  emergency: 300 }
  lag-machine:                        # P2
    disable-saving-snowballs: true
    disable-saving-fireworks: true
    max-projectile-loads-per-tick: 10
    max-projectile-loads-per-projectile: 10
    remove-excess-minecarts: false
    excess-minecarts-limit: 10
    remove-excess-boats: false
    excess-boats-limit: 10
```

---

## Features

### 🔒 Security (NMS-level)

- **BookSanitizer** — strips `click_event` / `hover_event` from any `WRITTEN_BOOK_CONTENT` delivered via `ServerboundSetCreativeModeSlotPacket`. Blocks the force-op book exploit family where a crafted book carries `click_event:run_command` that fires as the opener. Recursion-capped at depth 64 to block component-bomb DOS.
- **HardeningAdvisor** — boot-time scan of `paper-global.yml` `unsupported-settings`. Logs a per-finding warning if the operator has enabled any of: `allow-headless-pistons`, `allow-permanent-block-break-exploits`, `allow-piston-duplication`, `allow-unsafe-end-portal-teleportation`, `skip-tripwire-hook-placement-validation`, or `book-size.page-max > 4096`. Pure advisory; operator keeps control.
- **YAML SafeConstructor** — every `Yaml.load` site (`SourbyCraftSecurityConfig`, `SourbyCraftConfig`, `PluginManifest`, `PluginCategoryMap`) uses `SafeConstructor` to block `!!tag`-based gadget RCE.
- **PluginDownloader / LibDownloader / SWM PluginInstaller** — https-only on initial + every redirect hop, 100 MB cap with running byte counter, path-traversal containment (`startsWith` root check).
- **SWM FileLoader + SwmCommand + swm.file-dir** — `/swm load <name>` rejects slash/`..`/leading-dot, normalizes and contains within `slime_worlds/`. `swm.file-dir` operator yml rejected if it tries to escape the server root or hit an absolute path.
- **APILoader (remote SWM)** — startup WARN when `ignoreSslCertificate=true`; UTF-8 charset for basic-auth Base64.
- **GeoUtil** (`/ping`) — https to `ip-api.com`, bounded 1024-entry IP cache, `InetAddress.isSiteLocalAddress` for RFC1918 detection.
- **VirtualExecutor** — `init()` synchronized + DCL in `executor()` so a concurrent shutdown cannot return a dead executor.
- **SWPlugin shutdown** — `saveWorldAsync` wait bounded at 30 s per world so a stuck SWM write cannot hang server shutdown.
- **Crash Prevention** — NbtAccounter limits (books, skulls, bundles), sign / anvil length limits, recipe book packet size, creative NBT size. Paper's `CountingOps` codec-depth tracker bounds `ItemStack.CODEC` decode.
- **AntiXray** — Paper engine-mode 1 obfuscator (fluid obscures, all-blocks, entity obfuscation) plus SourbyCraft RayTraceAntiXray port: async ore visibility cache (`RayTraceWorker` on `VirtualExecutor`), sync entity LOS gate (`ChunkMap.TrackedEntity` hook, patch 0040) for mobs / item drops / `TextDisplay` holograms / custom-named armor stands, and per-(player, particle-origin) LOS gate (`ServerLevel.sendParticles` hook, patch 0041). All three toggles default OFF.
- **Command Security** — RCON rate limiting, RCON brute-force protection.
- **Locale.ROOT correctness** — every `toLowerCase` + numeric `String.format` site uses `Locale.ROOT`. Prevents Turkish-locale OS detection breakage and avoids comma-vs-period decimal divergence in TPS / RAM / Ping bar text.

### ⚡ Performance NMS

- **Projectile chunk-load throttle** — caps per-tick + per-projectile chunk-load fan-out. Blocks snowball / fish-hook / arrow lag-machines.
- **Excess vehicle sweeper** — every 10 s each minecart / boat counts siblings in its chunk and discards if over limit. Cascades naturally without coordinated bookkeeping.
- **Excess falling-block sweeper** — same pattern, 2 s cadence. Blocks sand-cascade lag.
- **Virtual-thread server pools** — Java 25 virtual threads replace the legacy I/O thread pool (`Util.java` patch).
- **Improve Player#canSee** — short-circuits NMS-Entity overload to skip the bukkit wrapper; `longUUID` field on `Entity` foundation for future fastutil canSee.
- **Resend more data on locale change** — `PlayerLocaleChangeEvent` triggers throttled refresh of advancements, inventory, entity custom names, TextDisplay text, ItemFrame items.
- **Item localization (`localizeItems`)** — pre-renders translatable display names + lore on serialize via `ItemUtil.packPatchSaves` round-trip; fixes merchant-offer de-sync after locale flips.
- **Optimise non-flush packet sending** (Spottedleaf) — reflectively grabs Netty's `safeExecute` to skip event-loop wakeup on non-flushed packets. ~1.5x entity-tracker tick win.
- **Moonrise chunk system** — optimized chunk loading/saving/ticking (inherited from Paper 26.1.2).

### 🛠 Commands (hex-colored)

Registered with the `sourbycraft` fallback prefix in `DedicatedServer#initServer` (patch 0030). Use `/sourbycraft:<name>` if Paper has claimed the bare slot.

| Command | Description |
|---------|-------------|
| `/tps` | SourbyCraft-colored TPS readout (instant + 1m/5m/15m) with warmup banner |
| `/sys` | Server specs: uptime, CPU, RAM, Java, worlds, SWM hint |
| `/ping [player]` | Latency + client brand + GeoIP location (always emits Location row) |
| `/plugins` | Active plugin list with versions |
| `/speedtest` | Built-in Ookla network speed test (lazy-downloaded multi-OS binary) |
| `/sparkview` (`/sparkv`, `/spk`) | Comprehensive Spark profiler view: TPS 5s/10s/1m/5m/15m + MSPT 10s/1m/5m mean/max/95th + CPU process/system + per-GC totals |
| `/tpsbar` / `/rambar` | Toggle persistent BossBar with 1s live refresh (toggle on/off) |
| `/perf` (`/perfengine`, `/tuner`) | PerfSensor tier + warmup, six signal lines, full KnobRegistry snapshot |
| `/ver` | Version info: SourbyCraft + Minecraft + API + uptime + git (aliases: `version`, `about`) |
| `/swm <list/load/status>` | SlimeWorldManager control |

### 🔬 Profiling

Paper 26.1.2 bundles Spark as a built-in profiler. SourbyCraft adds two integration layers on top:

1. **Auto-install fallback** — if you remove Paper's bundled Spark, the SourbyCraft auto-installer downloads `spark-*-bukkit.jar` from `ci.lucko.me` on first boot.
2. **SourbyCraft Spark viewer** — `/sparkview` (aliases `/sparkv`, `/spk`) renders the full spark-api statistic set through the SourbyCraft hex palette + `▰▱` bar style: rolling TPS windows, MSPT mean/max/95th, CPU process+system, per-GC totals. Toggle the bridge with `spark.enabled: true|false` in `sourbycraft.yml`.

Manual install (only needed for non-Paper deployments):

```sh
# Drop spark plugin JAR into your plugins/ directory
curl -L -o plugins/spark.jar \
  "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-1.10.142-bukkit.jar"
```

After install, use the standard Spark commands:

- `/spark profiler --timeout 120 --thread '*'` — sample all threads for 2 minutes
- `/spark profiler --timeout 60 --thread 'Server thread'` — main-thread only
- `/spark profiler stop` — stop and produce shareable URL
- `/spark health` — live health snapshot
- `/spark heapsummary` — heap usage snapshot

Profile URLs at `https://spark.lucko.me/<code>`.

### 🌐 Speedtest

`/speedtest` runs the Ookla CLI in `--format=json` mode and renders DL/UL/Ping with hex-colored progress bars. Binary auto-downloads on first invocation from `install.speedtest.net`. Supported OS/arch:

- Linux x86-64, Linux aarch64, Linux armhf
- macOS universal (x86-64 + arm64)
- Windows x64, Windows arm64

### 🌍 SlimeWorldManager (SWM v2)

Built-in SlimeWorldManager for `.slime` world format. SRF v13 binary format with Zstd compression.

**Two deployment modes:**

| Mode | Description | When to use |
|------|-------------|------------|
| **Built-in** | Server-internal `SWPlugin` auto-starts with `swm.enabled: true` | Default — worlds load from `slime_worlds/` at startup |
| **External plugin** | Standalone `SourbyCraftSWM.jar` plugin for external plugin API access | When third-party plugins need SWM API |

**Commands:**
- `/swm list` — `.slime` worlds with `[LOADED]` status
- `/swm load <world>` — load a slime world at runtime (path-containment hardened)
- `/swm status` — installed status + worlds-found count

**Configuration** (`sourbycraft.yml`):
```yaml
swm:
  enabled: true            # Enable built-in SWM bootstrap at startup
  auto-install: true       # Auto-download external plugin JAR
  auto-update: true        # Check GitHub for SWM plugin updates on startup
  version: "v6-REL"
  file-dir: "slime_worlds"
```

**API usage** (plugin developers):
```java
AdvancedSlimePaperAPI swm = AdvancedSlimePaperAPI.instance();
SlimeWorld world = swm.readWorld(new FileLoader("slime_worlds"), "myworld", false, new SlimePropertyMap());
swm.loadWorld(world, true);
```

### ⚙️ Infrastructure
- **Dual JRE 21/25** — Java 21 bytecode target, runs on JDK 21+ (build toolchain JDK 25)
- **ZGC generational** — recommended GC on JDK 21+ with heap ≥ 8 GB (sub-ms pauses)
- **NUMA + Virtual Threads + CDS** — max hardware utilization
- **StartupOptimizer** — detects JVM args at boot, prints GC/heap/Java summary + tuning recommendations
- **MemoryOptimizer** — object pool + soft-reference cache

---

## Configuration

`sourbycraft.yml` lives at the server root (NOT `plugins/SourbyCraft/`). Operator overrides JAR-baked defaults.

```yaml
# sourbycraft.yml — main config (operator-edited)

perf:
  entity-tick-rate: 20            # P0 — 1=vanilla every-tick, 20=1-in-20
  sensor:                         # P1 — multi-signal load sensor
    enabled: true
    cadence-ticks: 20
    dwell-samples: 3
    recovery-dwell-multiplier: 2.0
    warmup-ticks: 200
  lag-machine:                    # P2 — see Self-Tuning Perf Engine section
    disable-saving-snowballs: true
    disable-saving-fireworks: true
    max-projectile-loads-per-tick: 10
    max-projectile-loads-per-projectile: 10
    remove-excess-minecarts: false
    excess-minecarts-limit: 10
    remove-excess-boats: false
    excess-boats-limit: 10

entity:
  max-falling-block-per-chunk: 20  # P2 sweep gate

memory:
  skip-empty-sections: true
  pool-entity-data: true

network:
  auto-throttle-view: true
  min-view-distance: 4

settings:
  detailed-brand-info: true
  translate-items: true            # item localization round-trip

swm:
  enabled: true
  auto-install: true
  auto-update: true
  version: "v6-REL"
  file-dir: "slime_worlds"

# anti-xray (per-world)
anticheat:
  anti-xray:
    fluid-obscures: true
    all-blocks: false
    entity-obfuscation: true
    entity-obfuscation-range: 64
```

```yaml
# sourbycraft.yml — anti-xray ray-trace extension (stonar96/RayTraceAntiXray port)
antixray:
  raytrace:
    # Per-(player, ore) line-of-sight gate on top of Paper's engine-mode 1
    # block obfuscator. Hides cave-exposed ores that vanilla Paper would
    # otherwise leak. Async on VirtualExecutor, results cached per-player.
    enabled: false
  entity-raytrace:
    # Hides mobs, item drops, holograms (TextDisplay / custom-named armor
    # stands) when they sit behind solid blocks from the player's POV.
    # Sync check on the entity tracker tick. Liquid surface obfuscation is
    # delegated to Paper's anticheat.anti-xray.fluid-obscures above.
    enabled: false
  particle-raytrace:
    # Drops particle packets whose origin is occluded from the receiver's
    # eye. Closes the wallhack signal from ore-pillar / fire / door-frame
    # particles.
    enabled: false
```

```yaml
# sourbycraft-security.yml — crash prevention
crash-prevention:
  nbt:
    max-bytes: 2097152             # 2MB
    max-depth: 64
    max-string-length: 4096
    max-list-size: 65536
  sign:
    max-line-length: 256
    max-total-chars: 1024
  anvil:
    max-item-name-length: 128
  recipe-book:
    max-packet-size: 20480
  creative-item:
    max-nbt-size: 2048
```

---

## Startup

No tuner scripts. Run paperclip directly with recommended flags. StartupOptimizer reads your JVM args and prints recommendations at boot if missing.

**Recommended: JDK 21+, heap ≥ 8 GB (ZGC generational)**

```bash
java \
  -Xms8G -Xmx8G \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages \
  -XX:+UseNUMA \
  -jar sourbycraft-paperclip-mojmap.jar --nogui
```

**Small heap (< 8 GB): G1**

```bash
java \
  -Xms4G -Xmx4G \
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=8M \
  -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages \
  -jar sourbycraft-paperclip-mojmap.jar --nogui
```

At boot, look for `--- SourbyCraft Performance ---`. If your GC choice is suboptimal, recommended flags are printed.

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout release/26.1.2
./gradlew applyAllPatches
./gradlew assembleReleaseArtifacts
```

Output: `release/SourbyCraft-26.1.2-REL.jar` (slim, ~31 M) + `release/checksums.txt`.

Active patch counts:
- 41 minecraft NMS patches (`patches/minecraft/`)
- 6 paper-server patches (`patches/server/`)
- 14 buildscript-server patches + 2 api patches (`patches/buildscript/`)
- 4 api patches (`patches/api/`)

---

## API

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-api:26.1.2-REL")
}
```

### SWM API

```java
AdvancedSlimePaperAPI swm = AdvancedSlimePaperAPI.instance();
SlimeWorld world = swm.readWorld(new FileLoader("slime_worlds"), "myworld", false, new SlimePropertyMap());
swm.loadWorld(world, true);
```

---

## License

MIT — see [LICENCE.txt](LICENCE.txt)
