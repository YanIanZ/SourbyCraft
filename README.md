<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-21--25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v1.1.1--DEV-orange?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<h1 align="center">🍞 SourbyCraft</h1>

<p align="center"><em>Optimized for speed and efficiency — keeps your server running smoothly with high player counts. Fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a>.</em></p>

---

## Features

### Performance
- **G1GC optimized** — tuned garbage collection for <2GB survival servers
- **SIMD acceleration** — vectorized map palette on Java 17-25 with AVX2+
- **Spawn & heightmap arrays** — primitive arrays for O(1) access
- **Brain behavior tracking** — direct behavior list for fast iteration
- **Pufferfish engine** — async mob spawning, entity activation range, DAB
- **ForkJoinPool async** — work-stealing thread pool for pathfinding/chunk I/O

### Gameplay
- **Adventure components** — translatable item names and lore
- **Lore newline splitting** — protocol-level lore formatting
- **Configurable gossip limits** — per-type villager gossip tuning
- **Instant locale refresh** — data refresh on player locale change
- **Detailed brand info** — version + build in F3 debug screen

### Commands (hex-colored with visual bars)

| Command | Description |
|---------|-------------|
| `/tps` | TPS bars + MSPT + RAM + CPU + GC |
| `/tpsbar` | BossBar: TPS + MSPT + RAM visual bars |
| `/rambar` | BossBar: RAM usage visual bar |
| `/sys` | Full server specs: uptime, CPU, RAM, Java, worlds, SWM |
| `/ping [player]` | Latency bar + client info + world location |
| `/plugins` | Active plugin list with versions |
| `/speedtest` | Built-in Ookla network speed test |
| `/ver` | Version info: SourbyCraft + Minecraft + API + uptime |
| `/swm <list\|load\|status>` | SlimeWorldManager control |
| `/mods` | Mods folder scanner (Forge/Fabric/Bukkit) |

### SlimeWorldManager
- Auto-installs SWM plugin on first run
- `/swm list` — shows `.slime` worlds with load status
- `/swm load <world>` — loads a slime world

### Mod Support (Phase 1)
- `ModScanner` — reads metadata from `mods/` jars
- `/mods` — lists NeoForge, Forge, Fabric, and Bukkit mods

---

## Configuration

```yaml
# sourbycraft.yml
multithreading:
  enabled: false              # per-dimension threads (experimental)

performance:
  async-threads: 2            # ForkJoinPool workers
  async-pathfinding: false    # async entity AI (experimental)

swm:
  enabled: true               # SlimeWorldManager auto-install
  auto-install: true
  version: "2.2.1"
```

---

## Versioning

| Branch | Format | Example |
|--------|--------|---------|
| `ver/1.21.11` | `v{major}-REL` | `v1-REL` |
| `ver/1.21.11-dev` | `v{major}.{minor}.{patch}[letter]-DEV` | `v1.1.1-DEV` |
| `experimental-feat` | `v{major}{codename}-EXP` | `v1void-EXP` |

Bumps: `1.1.0` (feature) → `1.1.1` (fix) → `1.1.1a` (hotfix) → `1.2.0`

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
