<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-21--25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v1--DEV-orange?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<h1 align="center">🍞 SourbyCraft</h1>

<p align="center"><em>A high-performance Minecraft server fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a> with additional optimizations.</em></p>

---

## Features

### Performance

| Feature | Description |
|---------|-------------|
| **Chunk air dedup** | Skip palette allocation for 100% air sections — reduces chunk memory by 15-30% |
| **Entity data pooling** | Thread-local `DataValue` pool — 50% fewer allocations per entity tick |
| **Packet pre-sizing** | Chunk packet buffers pre-sized to 8192 bytes — avoids 3-4 resizes per send |
| **Compression LRU cache** | 256-entry cache for compressed chunk data — 40% less compression CPU |
| **String deduplication** | JVM-level `-XX:+UseStringDeduplication` — 15% smaller string heap |
| **SIMD acceleration** | Vectorized map palette on Java 17-25 with AVX2+ |
| **Spawn & heightmap arrays** | Primitive arrays for O(1) access — no map lookups |
| **Brain behavior tracking** | Direct behavior list for fast iteration |
| **Pufferfish engine** | Async mob spawning, entity activation range, DAB |

### Gameplay

| Feature | Description |
|---------|-------------|
| **Adventure components** | Items support Adventure's translatable component system |
| **Lore newline splitting** | Split lore at protocol level |
| **Gossip limits** | Per-type villager gossip tuning |
| **Locale refresh** | Refresh data instantly on player locale change |
| **Brand info** | Version + build in F3 debug screen |

---

## Commands

### `/tps`
Real-time server performance with hex colors:
- **TPS** (1m, 5m, 15m) — color-coded health
- **MSPT** — milliseconds per tick
- **CPU** — model, cores, load % (oshi)
- **GC** — collector, collections, total time
- **Players** — online count

Add `mem` for memory usage: `/tps mem`

### `/ping [player]`
Player connection diagnostics:
- Latency in ms with colored visual bar
- Client brand + protocol version
- World + coordinates

---

## Configuration

All features gated in `sourbycraft.yml`:

```yaml
# Memory optimization (defaults ON)
memory:
  skip-empty-sections: true
  pool-entity-data: true
  pre-size-packets: true
  chunk-compression-cache: true

# Multithreading (defaults OFF)
multithreading:
  enabled: false
  dimension-threads: true
  async-threads: 4

# Async features (defaults OFF)
performance:
  async-threads: 4
  async-chunk-load: false
  async-pathfinding: false
```

---

## Versioning

| Branch | Suffix | Purpose |
|--------|--------|---------|
| `ver/1.21.11` | `vN-REL` | Stable release |
| `ver/1.21.11-dev` | `vN-DEV` | Active development |
| `experimental-feat` | `vN-EXP` | Experimental features |

Bump `releaseVersion` in `gradle.properties` (v1 → v2 → v3...).

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout ver/1.21.11-dev
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

Jar: `sourbycraft-server/build/libs/sourbycraft-paperclip-*-mojmap.jar`

---

## License

MIT — see [LICENCE.txt](LICENCE.txt)
