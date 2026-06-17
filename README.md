<h1 align="center">⚡ SourbyCraft</h1>

<p align="center"><strong>Lightning Fast Performance · Feature Rich · Self-Tuning</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.1.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-26.1.2--EXP-orange?style=flat-square">
  <img src="https://img.shields.io/badge/jar%20size-31M-green?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<p align="center"><em>High-performance Paper fork on year-based Minecraft 26.1.2 with self-tuning perf engine, slim jar bootstrap, NMS-level security, anti-xray, and SWM. Fork of <a href="https://github.com/PaperMC/Paper">Paper</a>.</em></p>

---

## What's New in 26.1.2-EXP

Major upgrade from `12-EXP` (1.21.11) to year-based Minecraft 26.1.2:

- **Paper 26.1.2 hard-fork migration** — paperweight-patcher 2.0.0-beta.21, mache 26.1.2+build.3, paperclip 3.0.4. Year-based MC versioning (no more 1.x). Spigot mappings + reobf pipeline dropped.
- **Java 25 required** — uses unnamed variables, virtual threads, ZGC generational. JDK 21 no longer supported.
- **Single-variant build** — PVP variant + Pufferfish vendor dropped. Single `SourbyCraft-26.1.2-EXP.jar` artifact.
- **Adventure book force-op exploit blocked** — `BookSanitizer` strips `click_event` + `hover_event` from written-book page components on the creative-slot inject path. Closes the classic rigged-book-clicks-as-op vector.
- **YAML loaders hardened** — every `new Yaml()` site replaced with `SafeConstructor` to block tag-based gadget RCE (`!!javax.script.ScriptEngineManager` etc.).
- **Plugin / library downloaders hardened** — `PluginDownloader`, `LibDownloader`, SWM `PluginInstaller`: https-only (manual redirect re-check), 100 MB cap with running byte counter, path-traversal containment.
- **SWM `FileLoader` path traversal fixed** — `/swm load <name>` rejects slash / `..` / leading-dot, normalizes target, verifies containment inside baseDir.
- **Perf-engine P2 lag-machine wiring** — projectile chunk-load throttle (per-tick + per-projectile), excess minecart/boat sweeper, excess falling-block sweeper. All gated by `Knobs.LAG_MACHINE_*` (default ON for snowball/firework save fixes, OFF for sweepers).
- **Sourby Bootstrap (slim jar)** — release jar 31 M (down from 57 M). 6 optional libs + Ookla speedtest CLI lazy-download on first boot into `libraries/` with SHA-256 verification.

See `release/RELEASE-NOTES-26.1.2.md` for the full patch list.

---

## Build

```bash
./gradlew assembleReleaseArtifacts
```

Output: `release/SourbyCraft-26.1.2-EXP.jar` (~31 M slim) + `release/checksums.txt`.

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
| P3 Adaptive Entity AI | planned | Tier-aware DAB, dynamic-brain, per-entity-type allowlist |
| P4 Combat Profiles | planned | Switchable profile bundles |
| P5 Async Chunk Pipeline | planned | Async chunk packet send, async entity tracker |
| P6 Async Packet & World subsystems | planned | Async packet send, async data save, virtual-thread Bukkit scheduler |
| P7 Self-Tune Controller | planned | Reads `PerfSensor.currentTier()` → applies tier-mapped knob deltas |
| P8 Operator UX + Telemetry | planned | BossBar tier display, `/perf history`, Sentry breadcrumbs |

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

- **BookSanitizer** — strips `click_event` / `hover_event` from any `WRITTEN_BOOK_CONTENT` delivered via `ServerboundSetCreativeModeSlotPacket`. Blocks the force-op book exploit family where a crafted book carries `click_event:run_command` that fires as the opener.
- **YAML SafeConstructor** — every `Yaml.load` site (`SourbyCraftSecurityConfig`, `SourbyCraftConfig`, `PluginManifest`, `PluginCategoryMap`) uses `SafeConstructor` to block `!!tag`-based gadget RCE.
- **PluginDownloader / LibDownloader / SWM PluginInstaller** — https-only on initial + every redirect hop, 100 MB cap with running byte counter, path-traversal containment (`startsWith` root check).
- **SWM FileLoader** — `/swm load <name>` rejects slash/`..`/leading-dot, normalizes and contains within `slime_worlds/`.
- **APILoader (remote SWM)** — startup WARN when `ignoreSslCertificate=true`; UTF-8 charset for basic-auth Base64.
- **GeoUtil** (`/ping`) — https to `ip-api.com`, bounded 1024-entry IP cache, `InetAddress.isSiteLocalAddress` for RFC1918 detection.
- **Crash Prevention** — NbtAccounter limits (books, skulls, bundles), sign / anvil length limits, recipe book packet size, creative NBT size.
- **AntiXray** — fluid obscures (water/lava as solids), all-blocks mode, entity obfuscation.
- **Command Security** — RCON rate limiting, RCON brute-force protection.

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

| Command | Description |
|---------|-------------|
| `/tps` | SourbyCraft-colored TPS readout (1m/5m/15m) |
| `/sys` | Server specs: uptime, CPU, RAM, Java, worlds, SWM hint |
| `/ping [player]` | Latency + client brand + GeoIP location |
| `/plugins` | Active plugin list with versions |
| `/speedtest` | Built-in Ookla network speed test (lazy-downloaded multi-OS binary) |
| `/tpsbar` / `/rambar` | BossBar visual monitors |
| `/ver` | Version info: SourbyCraft + Minecraft + API + uptime + git (aliases: `version`, `about`) |
| `/swm <list/load/status>` | SlimeWorldManager control |

### 🔬 Profiling

Spark profiler bundled (downloaded on first boot via Sourby Bootstrap from `repo.papermc.io`). Use standard Spark commands directly:

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

- **Java 25 required** — virtual threads, unnamed variables, ZGC generational. JDK 21 no longer supported.
- **ZGC generational** — recommended GC on JDK 25 with heap ≥ 8 GB (sub-ms pauses)
- **NUMA + Virtual Threads + CDS** — max hardware utilization

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

No tuner scripts. Run paperclip directly with recommended flags.

**Recommended: JDK 25, heap ≥ 8 GB (ZGC generational)**

```bash
java \
  -Xms8G -Xmx8G \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages \
  -XX:+UseNUMA \
  -jar SourbyCraft-26.1.2-EXP.jar --nogui
```

**Small heap (< 8 GB): G1**

```bash
java \
  -Xms4G -Xmx4G \
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=8M \
  -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages \
  -jar SourbyCraft-26.1.2-EXP.jar --nogui
```

First boot downloads ~28 M of libs from Maven Central + PaperMC + Jitpack. Subsequent boots are silent fast-path.

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout release/26.1.2
./gradlew applyAllPatches
./gradlew assembleReleaseArtifacts
```

Output: `release/SourbyCraft-26.1.2-EXP.jar` (slim, ~31 M) + `release/checksums.txt`.

Active patch counts:
- 39 minecraft NMS patches (`patches/minecraft/`)
- 6 paper-server patches (`patches/server/`)
- 14 buildscript-server patches + 2 api patches (`patches/buildscript/`)
- 4 api patches (`patches/api/`)

---

## API

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-api:26.1.2-EXP")
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
