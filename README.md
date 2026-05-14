<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-21--25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v3--REL-orange?style=flat-square">
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
| `/swm <list\|load\|save\|info>` | SlimeWorldManager control |
| `/mods` | Mods folder scanner (Forge/Fabric/Bukkit) |

### SlimeWorldManager (SWM v2)
SourbyCraft has a **built-in** SlimeWorldManager (no plugin needed). Worlds are stored in SRF (Slime Region Format) v13 using Zstd compression, loaded into memory on demand.

- `/swm list` — shows `.slime` worlds in `slime_worlds/` with load status
- `/swm load <world>` — loads a slime world at runtime
- `/swm save <world>` — saves a loaded world back to `.slime`
- `/swm info` — shows loaded/found world counts

### Mod Support (Phase 1)
- `ModScanner` — reads metadata from `mods/` jars
- `/mods` — lists NeoForge, Forge, Fabric, and Bukkit mods

---

## Getting Started with SWM

1. Drop `.slime` world files into `slime_worlds/` folder (created automatically)
2. Run `/swm list` to see available worlds
3. Run `/swm load <worldname>` to load a world, or configure auto-load below
4. Explore the world — chunks load on demand

### Converting an existing world to .slime

Use the [`AdvancedSlimePaper`](https://github.com/InfernalSuite/AdvancedSlimePaper) converter tool or write a plugin using the SWM API.

### Using SWM as a plugin developer

```java
AdvancedSlimePaperAPI swm = AdvancedSlimePaperAPI.instance();

// Read a slime world from disk
SlimeWorld world = swm.readWorld(new FileLoader("slime_worlds"), "myworld", false, new SlimePropertyMap());

// Activate it on the server
swm.loadWorld(world, true);

// Save changes
swm.saveWorld(world);
```

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
  enabled: true               # enable SWM at startup
  auto-install: true          # auto-download SWM plugin from GitHub releases
  version: "v3-REL"           # release tag for auto-install
  file-dir: slime_worlds      # directory to scan for .slime files
```

---

## Versioning

| Branch | Format | Example |
|--------|--------|---------|
| `ver/1.21.11` (main) | `v{major}-REL` | `v3-REL` |
| `experimental-feat` | `v{major}{codename}-EXP` | `v1void-EXP` |

---

## Building

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
git checkout ver/1.21.11
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

Jar: `sourbycraft-server/build/libs/sourbycraft-paperclip-v*-REL-mojmap.jar`

### Building the SWM plugin (standalone)

```bash
cd swm-plugin
./gradlew build
```

Jar: `swm-plugin/build/libs/SourbyCraftSWM-*.jar`

---

## API

Use SourbyCraft API in your plugins via JitPack:

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-api:v3-REL")
}
```

### SWM API for plugins

```kotlin
dependencies {
    compileOnly("com.github.YanIanZ.SourbyCraft:sourbycraft-server:v3-REL") {
        isTransitive = false
    }
}
```

The SWM API lets external plugins create isolated worlds without touching the main server folder — perfect for minigames, skyblock, or any per-island/per-arena world.

#### Practical examples

**SuperiorSkyblock-style islands (one .slime per island):**
```java
import dev.iyanz.sourbycraft.swm.api.*;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;

FileLoader loader = new FileLoader("plugins/MyPlugin/islands");

// Create a blank island world
SlimeWorld blank = swm.createEmptyWorld("island_template", false,
    new SlimePropertyMap(), loader);
swm.loadWorld(blank, true); // activate for players

// Later — clone for each player
SlimeWorld playerIsland = swm.readWorld(loader, "island_player1", false,
    new SlimePropertyMap());
swm.loadWorld(playerIsland, true);
Bukkit.getPlayer("player1").teleport(playerIsland.getBukkitWorld().getSpawnLocation());
```

**BedWars-style arenas (load/save per match):**
```java
FileLoader loader = new FileLoader("plugins/BedWars/arenas");

// Load arena from .slime on game start
SlimeWorld arena = swm.readWorld(loader, "arena_lobby", true, new SlimePropertyMap());
swm.loadWorld(arena, true);

// Save modified arena after game
SlimeWorldInstance inst = swm.getLoadedWorld("arena_lobby");
swm.saveWorld(inst); // writes back to arena_lobby.slime
```

**Per-player mining worlds (isolated, auto-deleted):**
```java
FileLoader loader = new FileLoader("plugins/MiningWorlds");

// Create a temporary world per player (no loader = in-memory only)
SlimeWorld mining = swm.createEmptyWorld("mine_" + player.getName(), false,
    new SlimePropertyMap(), null);
swm.loadWorld(mining, true);
player.teleport(mining.getBukkitWorld().getSpawnLocation());

// When done — discard (no loader means no .slime saved)
```

**Multiple worlds in a single plugin:**
```properties
plugins/MyPlugin/
├── islands/
│   ├── player1.slime
│   ├── player2.slime
│   └── template.slime
├── arenas/
│   ├── lobby.slime
│   └── arena1.slime
└── mining/
    └── (in-memory only, no .slime files)
```

---

## License

MIT — see [LICENCE.txt](LICENCE.txt)
