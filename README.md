<h1 align="center">⚡ SourbyCraft — 26.2 Survival</h1>

<p align="center"><strong>Maximum performance · Resource-efficient · Self-tuning · Built for 200+ players</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/line-survival-orange?style=flat-square">
  <img src="https://img.shields.io/badge/version-26.2--REL-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/players-200%2B-blueviolet?style=flat-square">
  <img src="https://img.shields.io/badge/jar-31M%20(SourbyLoader)-green?style=flat-square">
  <img src="https://img.shields.io/badge/deploy-Docker%20·%20Pterodactyl%20·%20Pelican-2496ed?style=flat-square">
  <img src="https://img.shields.io/badge/license-PolyForm--NC--1.0.0-lightgrey?style=flat-square">
</p>

<p align="center"><em>High-performance <a href="https://github.com/PaperMC/Paper">Paper</a> fork on Minecraft 26.2, tuned for large survival servers. Vanilla region worlds (no SWM), a self-tuning performance engine, resource-pack-proof anti-xray, hardened item stacker, native mod loader, and async multithreading — all kept lean on RAM and CPU.</em></p>

---

## Two release lines

SourbyCraft ships as two parallel tracks:

| Line | Focus | Worlds | Branch |
|---|---|---|---|
| **26.1.2** | Skyblock / minigames | SWM (Slime World Manager) in-memory worlds | `release/26.1.2` |
| **26.2** *(this branch)* | **Survival** | Vanilla region storage, no SWM | `release/26.2` |

26.2 carries forward everything from the 26.1.2 performance + hardening work **minus SWM**, and adds the survival-scale tuning + security fixes in this release.

---

## What 26.2 delivers

### 🛡️ Resource-pack-proof anti-xray (default ON)
Anti-xray is enabled out of the box and hardened so a transparent-block ("x-ray") resource pack no longer reveals ores:
- **Paper HIDE engine (engine-mode 1), `max-block-height: 320`** — enclosed ores are rewritten to stone/deepslate **in the chunk packet**, so the real ore data never reaches the client. An x-ray texture pack sees the replacement stone, not ore, at every altitude (copper → y112, mountain emerald, deepslate → -64).
- **SourbyCraft raytrace layer** — hides *cave-exposed* ores (the gap Paper's engine leaves) until the player has genuine line-of-sight, then reveals them via an async raytrace on virtual threads. No real ore data for ores a player can't legitimately see.

### 🧱 Hardened item stacker
- **Anti-siphon:** items are never merged across distinct pickup-owners, so a player can't drop an item beside another player's owner-protected drop to siphon it.
- **No item-x-ray via holograms:** stack-count holograms are render-range-clamped (≈13 blocks) and occluded behind blocks, so distant players can't scout stack contents through walls.
- Line-of-sight gate on every merge path (spawn scan, periodic sweep, vanilla merge) — no through-wall merging.

### 🚀 Performance for 150+ players
- **Self-tuning engine** — a multi-signal `PerfSensor` (TPS / MSPT / heap / GC) drives a 5-tier state machine (GREEN→EMERGENCY). `SelfTuneController` escalates entity-tick-rate + adaptive AI throttle automatically under load and relaxes them on recovery: vanilla feel when idle, headroom when full.
- **Async mob spawning** (pufferfish semantics) — the per-tick density/mob-cap scan runs off-main on virtual threads; spawns + Bukkit events stay on the main thread (plugin-safe).
- **Multithread chunk generation** — Moonrise workers, exposed + smart-auto-sized (`performance.threads.chunk-workers`).
- **Baseline adaptive AI throttle** — mobs >80 blocks from any player tick AI every 2nd tick (near-zero gameplay impact, real CPU headroom at high pop).
- **Lag-machine guards** — projectile-load caps, snowball/firework save skips, per-chunk/world entity + item + arrow caps.
- No new platform threads: async work rides ~1 KB virtual threads; worker pools park when idle. Design goal: **≥80% resource efficiency vs. naive multithreading.**

### 🧩 Native mod loader (`mods/`)
Server-side extension jars via a first-party `SourbyMod` API — `sourbymod.yml` descriptor, per-mod classloader with full NMS visibility, lifecycle managed by the module registry. **Not** a Fabric/Forge bridge (Paper fork); non-SourbyMod jars are warned + ignored. See [`docs/SOURBYMODS.md`](docs/SOURBYMODS.md).

### 📦 SourbyLoader — slim jar (~31M)
Heavy optional libraries (zstd, adventure, configurate, snakeyaml, jline, JDBC drivers, spark, flare, protobuf, sentry) are stripped from the shipped jar and fetched on first boot into the paperclip library cache. The download list is SHA-256-verified. The Paper 26.2 server patch (~21.7M) is the hard floor — it can't be externalized — so the jar lands at ~31M rather than smaller. First boot needs internet once; after that it runs fully offline.

### ⚡ Auto-CDS — 30–50% faster startup, container-safe
Class Data Sharing memory-maps the JVM's class metadata (`cache/sourbycraft.jsa`, self-healing on jar/JDK change) instead of re-parsing it every boot. Unlike a naive fork-a-helper-JVM approach, the bootstrap is **environment-aware**:
- **Docker / Pterodactyl / Pelican, or any committed `-Xms`** → it does **not** fork. A second JVM there double-commits the heap (OOM-kill under a cgroup limit) and hides the real server behind an orchestrator PID (breaks panel memory graphs + stop signals). Instead it boots inline and prints the one flag to add for single-JVM CDS.
- **Bare metal, no committed heap** → it forks one child with `-XX:+AutoCreateSharedArchive` (JDK 19+ single-pass) and forwards console + stop.
- Tunable via `sourbycraft.cds.mode = auto|flag|fork|off`. Full matrix + per-panel flags in [`docs/CDS.md`](docs/CDS.md).

### Carried forward (from 26.1.2 r48)
Security enforcement (NBT/sign/anvil/packet guards), entity/item config caps with Spigot/Paper bridges, ViewThrottle, compression bridge, redstone budget, DAB-lite activation overrides, module registry + PerWorldHolder.

---

## Tuning for 150+ players

SourbyCraft auto-tunes under load, but the operator config that dominates large-server performance lives in Paper's files. Recommended starting point for ~150 players (adjust to hardware):

**`config/paper-global.yml`**
```yaml
chunk-loading-basic:
  player-max-chunk-send-rate: 100.0
  player-max-chunk-load-rate: 120.0
chunk-loading-advanced:
  player-max-concurrent-chunk-loads: 0    # auto per-player
```

**`config/paper-world-defaults.yml`**
```yaml
chunks:
  max-auto-save-chunks-per-tick: 12
entities:
  spawning:
    per-player-mob-spawns: true           # fair caps, avoids one area starving spawns
anticheat:
  anti-xray:
    enabled: true                          # SourbyCraft default; keep it on
    engine-mode: 1
    max-block-height: 320
```

**`server.properties`**
```properties
view-distance=7
simulation-distance=5
```

**JVM** (Aikar-style flags, sized to host RAM):
```
-Xms<half RAM> -Xmx<half RAM> -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions
```

Run a proxy (Velocity, `network.proxy-mode: velocity-modern`) and split load across backends for the smoothest 150+ experience.

---

## Concurrency model — parallelism without breaking plugins

Folia reaches its throughput by **regionizing world ticking across threads**, which breaks the single-main-thread contract every Bukkit plugin relies on. SourbyCraft takes the opposite trade: **async *compute*, main-thread *apply*.**

- Heavy read-only work runs off the main thread — chunk generation, chunk I/O and lighting (Moonrise), mob-spawn density scans (async spawning), anti-xray raytraces — on virtual threads or the Moonrise worker pool.
- Every result is **applied back on the main thread**, and **all Bukkit events + plugin callbacks still fire on the main thread.** A plugin never observes off-thread world state, so the entire plugin ecosystem stays compatible.

This is the "safer, exclusive" path: you get real multi-core parallelism for the expensive parts (which dominate large-server CPU) while keeping 100% plugin compatibility. It is **not** Folia-level region parallelism for gameplay ticking — that cannot be done without breaking plugins. On strong hardware (8+ fast cores, NVMe, a proxy splitting load) this design holds a stable tick for 150–200 players.

---

## Running in Docker / Pterodactyl / Pelican

A reference `Dockerfile` + `docker/entrypoint.sh` + `docker-compose.yml` ship in the repo. The container runs the server as **PID 1** (`exec java …`, so `SIGTERM` = a clean world-saving `/stop`), as a **non-root** user, with single-JVM Auto-CDS and Aikar G1 flags baked in. The `/data` volume persists worlds, the SourbyLoader lib cache, and the CDS archive.

```bash
./gradlew applyAllPatches :sourbycraft-server:compileJava assembleReleaseArtifacts
docker compose up -d --build     # set EULA=true + MEMORY in docker-compose.yml first
```

Heap is sized from `MEMORY` (MiB) or auto-derived from the container's cgroup limit. First boot needs internet once for SourbyLoader; after that it runs offline.

**Pterodactyl / Pelican:** add one flag to the Startup command in front of `-jar` for zero-overhead single-JVM CDS — the wings/daemon then tracks the real `java` PID, so the memory graph and Stop button behave:

```
-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa
```

SourbyCraft detects a panel/container and reminds you once in the console if it's missing. Full per-environment setup (incl. the JDK 24+ AOT cache) is in [`docs/CDS.md`](docs/CDS.md).

---

## Building

Requires **Java 25** and git history (paperweight applies patches as commits).

```bash
./gradlew applyAllPatches          # rebase feature patches onto Paper/MC 26.2
./gradlew :sourbycraft-server:compileJava
./gradlew assembleReleaseArtifacts # → release/SourbyCraft-26.2-REL.jar
```

CI (`.github/workflows/build.yml`) applies patches, compiles, assembles the jar, and **boots the server to `Done` then stops it** on every push/PR.

---

## License

[PolyForm Noncommercial 1.0.0](LICENSE). Fork of [PaperMC/Paper](https://github.com/PaperMC/Paper).
