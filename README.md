<p align="center">
  <img src=".github/assets/SourbyCraft.png" alt="SourbyCraft" width="380">
</p>

<h1 align="center">SourbyCraft</h1>

<p align="center">A region-threaded Minecraft 26.2 server built on Paper, with the Aurora engine and first-party observability.</p>

<p align="center">
  <a href="https://github.com/YanIanZ/SourbyCraft/releases"><img src="https://img.shields.io/github/v/release/YanIanZ/SourbyCraft?style=flat-square&label=release" alt="Latest release"></a>
  <a href="https://github.com/YanIanZ/SourbyCraft/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/YanIanZ/SourbyCraft/build.yml?branch=26.2&style=flat-square&label=build" alt="Build status"></a>
  <img src="https://img.shields.io/badge/minecraft-26.2-brightgreen?style=flat-square" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square" alt="Java 25">
  <img src="https://img.shields.io/badge/license-PolyForm--NC--1.0.0-lightgrey?style=flat-square" alt="License">
</p>

---

## About

SourbyCraft runs each region of a world on its own tick thread, so a server can use more than one
core for gameplay. Paper is its only upstream. The region-threading engine, originally derived from
Folia and Canvas, has been owned and maintained by SourbyCraft since 2026-10-05 and lives under
`dev.iyanz.aurora`.

Aurora optimizes in this order: correctness, reliability, stability, predictable latency, resource
efficiency, scalability, observability, then peak throughput. A faster result that weakens region
ownership, persistence, shutdown or plugin semantics is not accepted.

**Status.** [Build 47 — Aurora Nexus](https://github.com/YanIanZ/SourbyCraft/releases/tag/v26.2-r47)
is the latest release (full, not STABLE). Build 48 is in development; its open release gates are in
[docs/releases/26.2-build-48.md](docs/releases/26.2-build-48.md). STABLE is planned for Build 49
([roadmap](docs/releases/ROADMAP-47-50.md)).

## Features

| Feature | State |
|---|---|
| Region-threaded ticking (SourbyCraft-owned engine) | Implemented |
| Metrics runtime behind `/tps`, `/mspt`, `/perf`, HUD bars and Spark | Implemented; cross-surface consistency is an open Build 48 gate |
| Auto-updater (`apply_mode=notify` by default; release signing starts with Build 48) | Implemented |
| Aurora Compatibility Bridge for non-region-aware plugins | Implemented, off by default, not qualified |
| Aurora World Fabric (region storage, templates, instances) | Experimental, off by default |
| Async pathfinding | Experimental, off by default |
| Cherry server-side mixins (`-Dcherry.enable.mixin=true`) | Off by default |

States use the vocabulary in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): *implemented* means
functionally verified, not performance-qualified.

## Quick start

Requires **Java 25**. Download `SourbyCraft-26.2-REL.jar` from
[Releases](https://github.com/YanIanZ/SourbyCraft/releases), check it against the `.sha256` file,
then run:

```bash
java -Xmx4G -jar SourbyCraft-26.2-REL.jar --nogui
```

The first boot needs internet once: the launcher downloads the externalized libraries and the
Mojang server jar and verifies each by SHA-256. After that the server can run offline
(`-Dsourbyclip.offline=true`).

### Docker

```bash
docker build -f docker/Dockerfile --build-arg JAR=release/SourbyCraft-26.2-REL.jar -t sourbycraft:26.2 .
docker compose -f docker/docker-compose.yml up -d --build
```

Run both from the repository root. Compose stores server data in `data/`.

## Configuration

| File | Purpose |
|---|---|
| `sourbycraft_config/sourbycraft_global_config.toml` | Messages, max players, auto-updater, ViaVersion/ProtocolLib provisioning |
| `sourbycraft_config/aurora.toml` | Aurora engine options (CPU budget, bridge, AWF, async pathfinding) |
| `config/canvas-server.yml`, `config/canvas-worlds.yml` | Region scheduler options (file names kept for existing servers) |
| `config/paper-global.yml` → `threaded-regions` | Region thread count and grid size |
| `sourbycraft-security.yml` | Crash-prevention and packet-guard limits (all default to `LOG`) |

SourbyCraft never tunes gameplay or performance settings on its own; diagnostics recommend, the
operator decides. Details and reload behavior: [docs/AURORA-CONFIG.md](docs/AURORA-CONFIG.md).

## Commands

`/tps`, `/mspt`, `/perf <view>`, `/tpsbar`, `/rambar`, `/perfbar`, `/sys`, `/spec`, `/plugins`,
`/ver`, `/maxp`, `/update` and more. Full list: [docs/guides/commands.md](docs/guides/commands.md).

## Building from source

The build needs JDK 25 and the private SourbyPatcher/SourbyClip toolchain published to Maven Local
([setup](docs/development/PRIVATE-TOOLCHAIN.md)). Without it Gradle stops at settings time.

```bash
python3 scripts/private_toolchain.py --publish .private-toolchain
./gradlew applyAllPatches
./gradlew slimServerJar        # build/libs/SourbyCraft-slim.jar
```

Changes to Minecraft or Paper code go through feature patches. [AGENTS.md](AGENTS.md) covers the
patch workflow, tests and release rules.

## Documentation

- [Developer reading path and docs index](docs/README.md)
- [Aurora architecture](docs/architecture/AURORA.md): entry point and current state, verified against code
- [Development rules](docs/DEVELOPMENT.md): truthfulness policy, evidence requirements, testing matrix
- [Release notes and gates](docs/releases/)
- [Performance baseline](docs/BASELINE.md) and [qualification readiness](docs/architecture/qualification-readiness.md)

## Credits and license

SourbyCraft uses and derives work from **[Paper](https://github.com/PaperMC/Paper)**, Folia-derived
region-threading work and **[CanvasMC](https://github.com/CraftCanvasMC/Canvas)**. Upstream
attribution, copyright notices and license obligations are preserved.

The plugin bridge includes an adaptation of **Luminol /
[LightingLuminol](https://github.com/LuminolCustomArchive/LightingLuminol)** scheduler compatibility
work by **Bacteriawa** and contributors; original attribution and GPL v3 license texts are retained
([port scope and provenance](docs/development/LIGHTINGLUMINOL-BRIDGE-PORT.md)).

Cherry incorporates work inspired by or derived from **[LeavesMC](https://github.com/LeavesMC)** and
**[CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon)** where applicable
([Cherry](https://github.com/YanIanZ/Cherry)). Profiling integration uses
**[spark](https://spark.lucko.me)**.

SourbyCraft's own license is in [LICENSE.md](LICENSE.md). Third-party components remain under their
respective licenses.
