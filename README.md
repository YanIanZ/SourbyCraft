<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v4--REL-orange?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<h1 align="center">🍞 SourbyCraft</h1>

<p align="center"><em>High-performance Paper fork with built-in security, anti-xray, dynamic scaling, SWM, and NeoForge mod support. Fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a>.</em></p>

---

## Features

### 🔒 Security (NMS-level)
- **Crash Prevention** — NbtAccounter limits (books, skulls, bundles), sign/anvil length limits, recipe book packet size, creative NBT size
- **Lag Prevention** — per-chunk entity caps (special, falling block, arrow)
- **AntiXray** — fluid obscures (water/lava as solids), all-blocks mode, entity obfuscation
- **Command Security** — RCON rate limiting (20/sec), RCON brute-force protection
- **Packet/Dupe Protection** — covered by Paper 1.21.11 engine

### ⚡ Performance NMS
- **Dynamic Performance Scaler** — auto-adjusts entity tick rate based on TPS
- **Entity Tick Rate Limiter** — skips entity ticks at configurable intervals
- **Mob AI Distance Cutoff** — skips AI/pathfinding beyond configured range
- **Entity Data Pooling** — reuses SynchedEntityData arrays to reduce GC pressure
- **Packet Buffer Pre-size** — larger initial allocations to reduce buffer reallocation
- **Chunk Compression Cache** — caches compressed chunk data
- **Pufferfish engine** — DAB (Dynamic Activation of Brains), async mob spawning, SIMD
- **Moonrise chunk system** — optimized chunk loading/saving/ticking

### 🛠 Commands (hex-colored)
| Command | Description |
|---------|-------------|
| `/tps` | SourbyCraft-colored TPS readout (1m/5m/15m) + optional `/tps mem` |
| `/perf` | Live performance monitor + `scale on/off` + `rate <1-20>` |
| `/perf scale on` | Enable dynamic performance auto-scaling |
| `/perf scale off` | Disable auto-scaling |
| `/sys` | Server specs: uptime, CPU, RAM, Java, worlds, SWM hint |
| `/ping [player]` | Latency + client brand + GeoIP location |
| `/plugins` | Active plugin list with versions |
| `/speedtest` | Built-in Ookla network speed test |
| `/tpsbar` / `/rambar` | BossBar visual monitors |
| `/ver` | Version info: SourbyCraft + Minecraft + API + uptime (aliases: `version`, `about`) |
| `/swm <list/load/save/info>` | SlimeWorldManager control (save/info require SWM plugin) |
| `/mods` | Mods folder scanner (NeoForge/Forge/Fabric/Bukkit) |

### 🧩 NeoForge Mod Support (Foundation)
- **ModScanner** — reads `mods.toml` / `fabric.mod.json` from JARs in `mods/` folder
- **FmlBootstrap** — auto-detects NeoForge FML on classpath at startup
- **NeoForge 21.1.230** dependency on server classpath
- **/mods** command — lists discovered mods with name/version/type

### 🌍 SlimeWorldManager (SWM v2)
Built-in SlimeWorldManager for `.slime` world format. SRF v13 binary format with Zstd compression.

**Two deployment modes:**

| Mode | Description | When to use |
|------|-------------|------------|
| **Built-in** | Server-internal `SWPlugin` auto-starts with `swm.enabled: true` | Default — worlds load from `slime_worlds/` at startup |
| **External plugin** | Standalone `SourbyCraftSWM.jar` plugin for external plugins | When third-party plugins need SWM API access |

**Commands** (save/info require SWM plugin active):
- `/swm list` — shows `.slime` worlds with `[LOADED]` status
- `/swm load <world>` — loads a slime world at runtime
- `/swm save <world>` — serializes and persists a loaded world
- `/swm info` — loaded/found world counts

**Configuration** (`sourbycraft.yml`):
```yaml
swm:
  enabled: true           # Enable built-in SWM bootstrap at startup
  auto-install: false      # Auto-download external plugin JAR
  version: "v6-REL"       # Plugin version to download
```
Note: `swm.file-dir` is hardcoded to `slime_worlds`. World files go in `slime_worlds/` directory.

**API usage** (for plugin developers):
```java
AdvancedSlimePaperAPI swm = AdvancedSlimePaperAPI.instance();
SlimeWorld world = swm.readWorld(new FileLoader("slime_worlds"), "myworld", false, new SlimePropertyMap());
swm.loadWorld(world, true);
```

### ⚙️ Infrastructure
- **JDK 25 target** — compiled for Java 25, runs on JDK 25+
- **ZGC** — generational Z Garbage Collector (sub-ms pauses)
- **NUMA + Virtual Threads + CDS** — max hardware utilization
- **GC Auto-Tuner** — `scripts/gc-tuner.sh` selects optimal GC + generates flags
- **MemoryOptimizer** — object pool + soft-reference cache
- **Startup Optimizer** — prints hardware summary and tuning hints at boot

---

## Configuration

```yaml
# sourbycraft.yml — main config
performance:
  async-threads: 2              # ForkJoinPool workers
  async-chunk-load: false      # Async chunk loading
  async-pathfinding: false     # Async pathfinding

entity:
  tick-rate: 20                # 1/N ticks (1=every tick, 20=every 20th)
  tick-rate-limit: true        # Enable tick rate limiting
  mob-tick-distance: 32        # skip AI > N blocks from player
  mob-pathfind-interval: 20    # pathfind every N ticks
  max-per-chunk: 10            # hard entity per-chunk limit
  max-specials-per-chunk: 15   # armor stand, frame, painting
  max-falling-block-per-chunk: 20
  max-arrows-per-world: 5000
  max-redstone-updates-per-tick: 2000
  redstone-optimize: true
  hopper-batch: true
  item-merge-optimize: true
  item-despawn-rate: 6000      # ticks before item despawn
  item-merge-radius: 3

item:
  max-stack-size: 99           # max stack size (overrides vanilla 64)
  unlimited-drop-stack: true   # bypass stack cap on drops
  drop-stack-cap: 2147483647   # cap when unlimited (Integer.MAX_VALUE)
  owner-protection-enabled: true  # anti-snatch: items return to dropper
  owner-protection-time: 10    # protection ticks (seconds × 20)
  no-durability-except: false  # skip durability for all except elytra/trident

multithreading:
  enabled: false                # per-dimension threads (experimental)
  dimension-threads: false

memory:
  skip-empty-sections: true
  pool-entity-data: true
  pre-size-packets: false
  chunk-compression-cache: false

network:
  auto-throttle-view: true
  min-view-distance: 4
  compression-level: 4

chunk:
  async-save-batch: true

settings:
  detailed-brand-info: true
  translate-items: true
  disable-communication-commands: false
  allow-surface-rules-for-default-fluids: false

server:
  idle-timeout: 0

# per-world config (sourbycraft-world.yml)
anticheat:
  anti-xray:
    fluid-obscures: true        # water+lava as solid blockers
    all-blocks: false           # mark all blocks as target
    entity-obfuscation: true    # hide entities behind walls
    entity-obfuscation-range: 64  # range for entity hiding

swm:
  enabled: true
  auto-install: false
  version: "v6-REL"
```

```yaml
# sourbycraft-security.yml — crash prevention
crash-prevention:
  nbt:
    max-bytes: 2097152          # 2MB
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

```bash
# Auto-tune GC and start (recommended)
./scripts/gc-tuner.sh --apply

# Generate flags only (no start)
./scripts/gc-tuner.sh --flags-only

# Start with custom JAR name
./scripts/gc-tuner.sh --apply --jar my-server.jar

# Manual start with generated flags
./scripts/gc-tuner.sh --flags-only
java @start.flags -jar sourbycraft-paperclip-v6-REL-mojmap.jar --nogui
```

The `gc-tuner.sh` script auto-detects system specs (CPU cores, RAM) and selects the optimal GC strategy (ZGC, Shenandoah, or G1) with tuned flags.

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout ver/1.21.11
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

Jar: `sourbycraft-server/build/libs/sourbycraft-paperclip-v6-REL-mojmap.jar`

---

## API

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-api:v6-REL")
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