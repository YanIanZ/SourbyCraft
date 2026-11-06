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
  <img src="https://img.shields.io/badge/license-GPL--3.0--only-blue?style=flat-square" alt="License: GPL-3.0-only">
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

**Release status (2026-11-06).** The latest published GitHub release is
[Build 57 — Aurora Nexus](https://github.com/YanIanZ/SourbyCraft/releases/tag/v26.2-r57)
([release notes](docs/releases/26.2-build-57-aurora-nexus.md)). It is **not STABLE or
owner-qualified**: although CI and compile evidence are documented, the required owner-run
manual tests and performance qualification are not complete. Earlier releases, experimental
features and implementation notes must not be presented as independently validated.
See [release gates and test evidence](docs/releases/26.2-build-57-aurora-nexus.md),
[development policy](docs/DEVELOPMENT.md), and the
[documentation index](docs/README.md).

**Safety note.** The Aurora Compatibility Bridge can relax region ownership checks for
legacy plugins. Running with `aurora.bridge.thread-checks: relaxed` is unsafe and unqualified;
do not interpret fewer errors or improved compatibility as proof of thread safety.
Use `thread-checks: enforced` when strict region-ownership guarantees are required.
Do not use experimental AWF or distributed-profile features in production without
completing the relevant owner qualification and recovery tests.

## Features

| Feature | State |
|---|---|
| Region-threaded ticking (SourbyCraft-owned engine) | Implemented |
| Metrics runtime behind `/tps`, `/mspt`, `/perf`, HUD bars and Spark | Implemented; cross-surface consistency is an open Build 48 gate |
| Auto-updater (`updater.apply-mode: notify` by default; releases signed from Build 48) | Implemented |
| Aurora Compatibility Bridge for non-region-aware plugins | Implemented, off by default, not qualified; with `mode: safe` the region-ownership checks are **relaxed** by default (owner decision 2026-10-22, LightingLuminol parity) -- unsafe, set `aurora.bridge.thread-checks: enforced` to keep them |
| Aurora World Fabric (region storage, templates, instances) | Experimental, off by default |
| AWF MultiProfile and Aurora Redis Fabric | Experimental, off by default; implementation and test status differ by release. Consult the release notes and owner test gates before evaluating distributed deployments; not production-qualified |
| Async pathfinding | Experimental, off by default |
| Cherry server-side mixins (`-Dcherry.enable.mixin=true`) | Off by default; provided by the private SourbyClip launcher, not covered by this repository's tests |

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
| `sourbycraft_config/sourbycraft_global_config.yml` | Console, players, messages, plugin provisioning, auto-updater (Build 49 layout; older layouts are migrated once at boot, copy kept as `*.migrated`) |
| `sourbycraft_config/aurora.yml` | Aurora engine options (CPU budget, bridge, AWF, MultiProfile, Redis Fabric, async pathfinding, AbyssLink) |
| `config/aurora-server.yml`, `config/aurora-worlds.yml` | Region engine options (Build 49 names; `config/canvas-*.yml` are migrated once and renamed `*.migrated`) |
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

## Contributing and community

SourbyCraft is developed in public and accepts appropriately scoped contributions.
Start with [CONTRIBUTING.md](CONTRIBUTING.md) for issue and pull-request guidelines,
testing expectations, and honest limitations of the private maintainer build toolchain.
External plugin authors can start with the
[public SourbyAPI release artifacts and example plugin](docs/guides/developing-with-sourbyapi.md)
without building the full server.

- **Security:** See [SECURITY.md](SECURITY.md) before reporting a vulnerability. Do not
  place exploit details, access tokens or private server logs in public issues.
- **Evidence of use:** The [open-source adoption and evidence guide](docs/community/OPEN-SOURCE-EVIDENCE.md)
  distinguishes shipped capabilities from verified downstream use. Maintainers welcome
  voluntary, public, permission-to-cite reports from plugin authors and server operators;
  none are assumed without evidence.
- **Project status:** Features and benchmarks must follow
  [development truthfulness rules](docs/DEVELOPMENT.md). Public commits and releases
  show activity, but are not proof of external adoption or production safety.

## Credits and license

SourbyCraft uses and derives work from **[Paper](https://github.com/PaperMC/Paper)**, Folia-derived
region-threading work and **[CanvasMC](https://github.com/CraftCanvasMC/Canvas)**. Upstream
attribution, copyright notices and license obligations are preserved.

The plugin bridge includes adaptations of **Luminol /
[LightingLuminol](https://github.com/LuminolCustomArchive/LightingLuminol)** work by **Bacteriawa** and
contributors: the scheduler compatibility port and, since 2026-10-22, its relaxed thread checks, cross-region
world-data pool, teleport join semantics and legacy teleport events; original attribution and GPL v3 license
texts are retained ([port scope and provenance](docs/development/LIGHTINGLUMINOL-BRIDGE-PORT.md)).

Cherry incorporates work inspired by or derived from **[LeavesMC](https://github.com/LeavesMC)** and
**[CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon)** where applicable
([Cherry](https://github.com/YanIanZ/Cherry)). Profiling integration uses
**[spark](https://spark.lucko.me)**.

SourbyCraft is licensed under the [GPL version 3 only](LICENSE.md); upstream components keep their own
licences, listed there. The SourbyCraft and Aurora names and logos are covered by the
[trademark policy](TRADEMARK.md): forks are welcome under the GPL, but must use their own name.

Historical Build 50 planning and test handoffs remain available in the
[documentation index](docs/README.md). For the current release, consult the
[Build 57 release evidence and outstanding owner tests](docs/releases/26.2-build-57-aurora-nexus.md).
No build should be called STABLE or production-qualified without the stated gates.
