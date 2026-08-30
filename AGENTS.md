# AGENTS.md — SourbyCraft 26.2 Canvas

Region-threading Minecraft 26.2 server fork. **Re-platformed onto [CanvasMC](https://github.com/CraftCanvasMC/Canvas)** via Canvas's own `io.canvasmc.weaver.patcher` toolchain (see `build.gradle.kts:9-11`, "Path B"). Custom `sourbypatcher` is **unused on this branch** — do not reintroduce it without checking PR #12 context in the root build script.

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

Two independent configs (do not conflate):

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
