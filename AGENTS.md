# AGENTS.md — SourbyCraft 26.2 Aurora

Region-threaded Minecraft 26.2 server fork with the **Aurora** runtime/engine architecture. Canvas/Folia/Paper remain upstream implementation inputs where the current branch still depends on them; they are not the product/runtime identity. Do not describe planned Aurora ownership as already independent. The active build/toolchain must be verified from the current branch before repeating historical Path-B or private-toolchain statements.

## Toolchain

- **JDK 25** required (Temurin). Set via Gradle toolchains + `org.gradle.toolchains.foojay-resolver-convention`. CI uses `actions/setup-java@v4` + `temurin` + `java-version: 25`.
- Gradle wrapper at `./gradlew`. Configuration cache is **on** (`org.gradle.configuration-cache=true`). Do not introduce raw `ProcessBuilder` calls in `doLast`; use `providers.exec`.
- Branching → version suffix (`gradle.properties` `releaseVersion` + `sourbyBuild` + git branch): `release/*` → `REL`, `feat/*`/`experimental/*` → `EXP`, else `DEV`. Stamped into `META-INF/sourbycraft-build.properties` (via `writeBuildInfo` in `build.gradle.kts:246`) **and** the jar manifest `Implementation-Version` (in `sourbycraft-server/build.gradle.kts.patch`). These MUST stay in lockstep.

## Build commands (root)

```bash
./gradlew applyAllPatches            # materialize Paper → Canvas → SourbyCraft sources
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar              # → build/libs/SourbyCraft-slim.jar (~34 MiB; rest fetched on first boot)
```

Slim-jar task: `slimServerJar` strips 19 hard-coded artifact dirs from the paperclip jar (`build.gradle.kts:90-110`); `sourbyclip` re-downloads them by coordinate on first boot via `META-INF/libraries` manifest. If the strip count is 0, the task **fails** — update the prefix list when libraries move.

The CI workflow `.github/workflows/build.yml` does:
1. `cd sourbypatcher && ./gradlew publishToMavenLocal` — **legacy step for the Folia patcher; on this branch sourbypatcher is unused but the step remains in CI**. Do not delete without reworking the workflow.
2. `bash scripts/setup_metal.sh` — Metal is **vendored** at `Metal/`, not a submodule (upstream LuminolMC/Metal went offline). `setup_metal.sh` calls `Metal/gen_sources.sh`.
3. `applyAllPatches` then `compileJava`, then `slimServerJar -PsourbyBuild=N`, copy to `release/SourbyCraft-26.2-REL.jar`.
4. `boot-test` job: boots with `eula=true`, `online-mode=false`, `level-type=minecraft:flat`, waits 240s for `Done (` in `server.log`, sends `stop`, gives 60s to shut down, then `SIGTERM`. Fails CI on timeout.

## Module layout

- `:sourbyapi` — branded API artifact `dev.iyanz.sourbycraft:sourbyapi`. **Zero custom source**; it republishes `paper-api` + `canvas-api` under the SourbyCraft group id (see `sourbyapi/README.md`). Materialized from `canvas-api/build.gradle.kts` via the `upstreams.canvas { patchFile { ... } }` block in root `build.gradle.kts`.
- `:sourbycraft-server` — the server. Source-set merge in `build.gradle.kts.patch` adds `paper-server` + `canvas-server` to the main and test source sets, and `src/log4jPlugins/java` (own log4j2 pattern plugins, e.g. `%scLogger`).
- `:Metal` — vendored Minecraft library-decode codegen. `src/` empty until `setup_metal.sh` runs.
- `sourbypatcher/` — own paperweight fork. **Not used on this branch.** Do not edit unless you are reviving the Folia line.
- `sourbyclip/` — Leavesclip fork (paperclip bootstrap). Replaces upstream paperclip; coordinates `dev.iyanz:sourbyclip:${clipVersion}` are wired in `sourbycraft-server/build.gradle.kts.patch`.
- `test-harness/` — NMS-compat smoke harness. Only used by `.github/workflows/nms-compat.yml`, which is **`workflow_dispatch` only** (predates 26.2 rebase, marked stale in the workflow header).
- `test-plugin/`, `luminol-generator/` — opt-in projects; activated by uncommenting `include(":test-plugin")` in their `*.settings.gradle.kts`.

## Patch application

Three-stage fork via weaver's own `ForkConfig`: Paper → Canvas (own `base/` git-format foundational patches + `sources/` codechicken diffpatch + `features/` git-format) → SourbyCraft (`paper-patches/`, `canvas-patches/`, `folia-patches/`, `minecraft-patches/`, plus `log4jPlugins/`). `gitFilePatches = false` project-wide — sources/ diffpatches go through `java-diff-utils` instead of `git apply`.

Critical gotcha in `sourbycraft-server/build.gradle.kts.patch`: `mergeMinecraftATs` MUST read Canvas's own `.at`, not `paperweight.activeFork` (activeFork is now `sourbycraft`, which has no `canvas.at` — silently breaks canvas-server minecraft-patches). Same patch adds an `afterEvaluate { ... }` to override `importCanvasLibraryFiles.devImports` to `build-data/canvas-dev-imports.txt` (upstream's list is empty; canvas.base/ uses `ca.spottedleaf.concurrentutil.*` classes that need explicit vendoring). And `sortFoliaATs` is made an explicit dependency of `mergeMinecraftATs` to avoid out-of-order execution under `rebuildMinecraftFeaturePatches`.

`paper-patches/`, `canvas-patches/`, `folia-patches/`, `minecraft-patches/` under `sourbycraft-server/` are the actual patch source of truth — **edit patch files there, never the materialized sources** under `paper-server/`, `canvas-server/`, `paper-api/`, `canvas-api/` (those are git working copies, regenerated by `applyAllPatches`).

## Configuration surfaces

Configuration surfaces (do not conflate, and verify boot-order consumers before documenting reloadability):

- **SourbyCraft utility layer** → `sourbycraft_config/sourbycraft_global_config.toml` (nightconfig). Messages, `/maxp` persistence, auto-updater, ViaVersion auto-provision.
- **Canvas engine** → `config/canvas-server.yml` + `config/canvas-worlds.yml` (region scheduler, tick rate, autosave). Default `region-scheduler.guard-severity: LOG` (not Canvas's crash-prone `THROW`).
- **Crash-prevention + packet-guard limits** → `sourbycraft-security.yml` (checked in; sampled at `sourbycraft-security.yml`).

## Cherry mixin engine

Off by default. Enable with `-Dcherry.enable.mixin=true`. Plugin authors drop a `cherry-plugin.json` next to the plugin jar with optional `mixin` and `access-transformers` blocks. **Server-side only** — does not run full Fabric mods, only Fabric-format mixin/AT/access-widener declarations. Plugin-author guide lives at `https://github.com/YanIanZ/Cherry` (not vendored).

## Boot-time network

First boot needs internet once:
- `SourbyLoader`/`SourbyClip` fetches the externalized libraries into the paperclip cache.
- `ViaVersion` + `ViaBackwards` jars auto-provisioned into `plugins/` (SHA-256-verified, https-only) **before** the plugin manager scans. Toggle: `[viaversion] auto-provision` in the global TOML (default `true`). Idempotent — never re-downloads verified jars, never overwrites user config.

If the boot host has no internet, `SourbyLoader` prints the exact URLs + target paths. Offline-immutable; subsequent boots run fully offline.

## Docker

```bash
docker build --build-arg JAR=release/SourbyCraft-26.2-REL.jar -t sourbycraft:26.2 .
docker compose up -d --build
```

Image: `eclipse-temurin:25-jre` (NOT jdk), non-root user `sourby`, `/data` volume, `LANG=C.UTF-8` (without this, stdout.encoding defaults to US-ASCII and garbles the branded box-drawing banner). `mem_limit: 8g` in compose with `MEMORY: 6144` MiB heap.

## Verifying a build locally

```bash
java -Xmx2G -XX:+UseG1GC -jar release/SourbyCraft-26.2-REL.jar --nogui
```

Wait for `Done (` in console. `/ver` reports the channel + `build Nc` (e.g. `build 41c`).

## Things agents commonly miss

- Bump `sourbyBuild` in `gradle.properties` per release; the `c` suffix is appended by `writeBuildInfo` and the server `build.gradle.kts.patch` independently — both must change together (they read the same property).
- `releaseVersion` defaults to `26.2`. `gradle.properties` `codename` is currently `cookies` (per README build-id `38c` line is stale — current `sourbyBuild=41`).
- Do not commit `paper-server/`, `canvas-server/`, `paper-api/`, `canvas-api/` working-copy edits — those are generated by `applyAllPatches` from the `canvasRef` pin in `gradle.properties` (Canvas `df0f2ebb...`).
- `sourbypatcher/` is unused but kept in tree and CI for the Folia line. Do not delete without coordinating.
- The `slimServerJar` task's `externalizeArtifactDirs` list is matched by path prefix against the paperclip layout — when Canvas/weaver bumps versions, jars may move and the task will **fail loudly** with `stripped 0 libraries`. That's the intended signal to update the list.
- `applyAllPatches` is config-cache friendly but `writeBuildInfo` is opted out (`notCompatibleWithConfigurationCache`) because it reads git branch via `providers.exec` at execution time.
- The Dockerfile's LABEL still says "Paper 26.2" in the description — known minor copy lag, not your bug to fix unless touching the Dockerfile.


## Non-misleading development rules

Agents working on this branch MUST follow `DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy`.

In short:

- Never call a feature **qualified** because it compiles, boots, passes unit tests or has one favorable profile.
- Never turn an uncertified A/B observation into a release-wide percentage claim.
- Never describe a historical benchmark setting as the current default without checking current branch code.
- Never describe a planned subsystem (Resource Governor, unified execution fabric, independent scheduler, etc.) as implemented.
- When code and docs disagree, inspect the current runtime path and fix the stale doc in the same change.
- State whether a setting is LIVE, RESTART_REQUIRED or merely planned.
- For performance work, include workload, hardware, config, certification/noise state and the exact statistic being compared.
- Correctness, plugin semantics, region ownership, persistence and shutdown behavior outrank throughput.
- A concurrency change must account for shared CPU capacity; adding executors/threads is not evidence of scalability.
- Do not hide regressions by changing workload fidelity, player/entity activation, world-generation conditions or gameplay settings.
