<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-21--25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v1--DEV-orange?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<h1 align="center">🍞 SourbyCraft</h1>

<p align="center"><em>A high-performance Minecraft server fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a></em></p>

---

## Features

### Performance
- **Async multithreading** — configurable thread pool for chunk loading, entity tracking, and pathfinding
- **SIMD acceleration** — vectorized map palette on Java 17-25 with AVX2+
- **Spawn & heightmap arrays** — primitive arrays for O(1) access, no map lookups
- **Brain behavior tracking** — direct behavior list for fast iteration
- **Pufferfish optimizations** — async mob spawning, entity activation range, DAB

### Gameplay
- **Adventure translatable components** — items support Adventure's component system
- **Lore newline splitting** — split lore at protocol level
- **Configurable gossip limits** — per-type villager gossip tuning
- **Instant locale refresh** — refresh data on player locale change
- **Detailed brand info** — version in F3 debug screen

## Commands

### `/tps`
Shows real-time server performance with hex colors:
- TPS (1m, 5m, 15m) with color-coded health
- MSPT (milliseconds per tick)
- CPU model, cores, load percentage (requires oshi)
- GC collector name, total collections, total time
- Player count

Add `mem` argument for memory usage: `/tps mem`

### `/ping [player]`
Shows player connection diagnostics:
- Latency in ms with colored visual bar
- Client brand + protocol version
- Player world + coordinates

## Multithreading

Semi-multithreading (per-dimension) via `sourbycraft.yml`:

```yaml
multithreading:
  enabled: false              # master switch
  dimension-threads: true     # one thread per dimension
  async-threads: 4            # I/O worker pool size
```

When enabled, Overworld, Nether, and End each tick on their own thread.
Defaults OFF — safe to leave disabled.

## Versioning

| Branch | Suffix | Purpose |
|--------|--------|---------|
| `ver/1.21.11` | `vN-REL` | Stable release |
| `ver/1.21.11-dev` | `vN-DEV` | Active development |
| `experimental-feat` | `vN-EXP` | Experimental features |

Bump `releaseVersion` in `gradle.properties` for new releases (v1, v2, v3...).

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout ver/1.21.11-dev
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

Jar: `sourbycraft-server/build/libs/sourbycraft-paperclip-*-mojmap.jar`

## Configuration

Async features in `sourbycraft.yml`:

```yaml
performance:
  async-threads: 4
  async-chunk-load: false
  async-pathfinding: false
```

## License

MIT — see [LICENCE.txt](LICENCE.txt)
