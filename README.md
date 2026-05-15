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
- **Crash Prevention** — NbtAccounter limits (books, skulls, bundles), sign/anvil length limits
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

### 🛠 Commands (hex-colored with visual bars)
| Command | Description |
|---------|-------------|
| `/tps` | Custom TPS bars (1m/5m/15m) + MSPT + RAM + CPU + per-world stats |
| `/perf` | Live performance monitor + `scale on/off` + `rate <1-20>` |
| `/perf scale on` | Enable dynamic performance auto-scaling |
| `/perf scale off` | Disable auto-scaling |
| `/sys` | Full server specs: uptime, CPU, RAM, Java, worlds, SWM |
| `/ping [player]` | Latency bar + client info + GeoIP location |
| `/plugins` | Active plugin list with versions |
| `/speedtest` | Built-in Ookla network speed test |
| `/tpsbar` / `/rambar` | BossBar visual monitors |
| `/ver` | Version info: SourbyCraft + Minecraft + API + uptime |
| `/swm <load/save/list/info>` | SlimeWorldManager control |
| `/mods` | Mods folder scanner (NeoForge/Forge/Fabric/Bukkit) |

### 🧩 NeoForge Mod Support (Foundation)
- **ModScanner** — reads `mods.toml` / `fabric.mod.json` from JARs in `mods/` folder
- **FmlBootstrap** — auto-detects NeoForge FML on classpath at startup
- **NeoForge 21.1.230** dependency on server classpath
- **/mods** command — lists discovered mods with name/version/type

### 🌍 SlimeWorldManager (SWM v2)
Built-in SlimeWorldManager — no plugin needed. SRF v13 binary format with Zstd compression.

- `/swm list` — shows `.slime` worlds with load status
- `/swm load <world>` — loads a slime world at runtime
- `/swm save <world>` — serializes and persists a loaded world
- `/swm info` — loaded/found world counts

### ⚙️ Infrastructure
- **JDK 25 target** — compiled for Java 25, runs on JDK 25+
- **ZGC** — generational Z Garbage Collector (sub-ms pauses)
- **NUMA + Virtual Threads + CDS** — max hardware utilization
- **GC Auto-Tuner** — `scripts/gc-tuner.sh` selects optimal GC + generates config
- **MemoryOptimizer** — object pool + soft-reference cache
- **Startup Optimizer** — prints hardware summary and tuning hints at boot
- **paperclip.conf** — full JVM flags via `@file` format

---

## Configuration

```yaml
# sourbycraft.yml — main config
performance:
  async-threads: 2              # ForkJoinPool workers

entity:
  tick-rate: 1                  # 1/N ticks (1=every tick, 8=every 8th)
  mob-tick-distance: 32         # skip AI > N blocks from player
  max-per-chunk: 10             # hard entity per-chunk limit
  max-specials-per-chunk: 15    # armor stand, frame, painting
  max-falling-block-per-chunk: 20
  max-arrows-per-world: 5000

multithreading:
  enabled: false                # per-dimension threads (experimental)

antixray:
  fluid-obscures: true          # water+lava as solid blockers
  all-blocks: false             # mark all blocks as target
  entity-obfuscation: true      # hide entities behind walls
  entity-obfuscation-range: 64  # range for entity hiding

swm:
  enabled: true
  auto-install: true
  version: "v4-REL"
  file-dir: slime_worlds

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
```

---

## Startup

```bash
# Default (auto RAM)
java @paperclip.conf -jar sourbycraft-paperclip-v4-REL-mojmap.jar --nogui

# Custom RAM
java -Xms4G -Xmx10G @paperclip.conf -jar sourbycraft-paperclip-v4-REL-mojmap.jar --nogui

# Auto-tune GC + start (interactive)
./scripts/gc-tuner.sh paperclip.conf --start
```

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout ver/1.21.11
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

Jar: `sourbycraft-server/build/libs/sourbycraft-paperclip-v4-REL-mojmap.jar`

---

## API

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-api:v4-REL")
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
