# AGENTS.md — SourbyCraft 26.2 Aurora

Region-threaded Minecraft 26.2 server fork with the **Aurora** runtime/engine architecture. Canvas/Folia/Paper remain upstream implementation inputs where the current branch still depends on them; they are not the product/runtime identity. Do not describe planned Aurora ownership as already independent. The active build/toolchain must be verified from the current branch before repeating historical Path-B or private-toolchain statements.

## Toolchain

- **JDK 25** required (Temurin). Set via Gradle toolchains + `org.gradle.toolchains.foojay-resolver-convention`. CI uses `actions/setup-java@v6` + `temurin` + `java-version: 25`.
- Gradle wrapper at `./gradlew`. Configuration cache is **on** (`org.gradle.configuration-cache=true`). Do not introduce raw `ProcessBuilder` calls in `doLast`; use `providers.exec`.
- Branching still determines the REL/EXP/DEV channel version. Build 47+ public build identity is `Build N` with no Canvas/Folia/Paper suffix. `writeBuildInfo` and the server manifest MUST stay in lockstep.

## Build commands (root)

```bash
./gradlew applyAllPatches            # materialize Paper → Canvas → SourbyCraft sources
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar              # → build/libs/SourbyCraft-slim.jar (~34 MiB; rest fetched on first boot)
```

Slim-jar task: `slimServerJar` strips 19 hard-coded artifact dirs from the paperclip jar (`build.gradle.kts:90-110`); `sourbyclip` re-downloads them by coordinate on first boot via `META-INF/libraries` manifest. If the strip count is 0, the task **fails** — update the prefix list when libraries move.

The CI workflow `.github/workflows/build.yml` (single `Build 26.2 jar` job, push/dispatch only) does:
1. Checks out the private `YanIanZ/SourbyPatcher` and `YanIanZ/SourbyClip` at the revisions in `build-data/private-toolchain.lock.json` (deploy keys) and publishes them to Maven Local with `scripts/private_toolchain.py --publish`. `settings.gradle.kts` refuses to configure without the hash-verified SourbyPatcher `canvas-toolchain` jar. See `docs/development/PRIVATE-TOOLCHAIN.md`.
2. `bash scripts/setup_metal.sh` — Metal is **vendored** at `Metal/`, not a submodule (upstream LuminolMC/Metal went offline). `setup_metal.sh` calls `Metal/gen_sources.sh`.
3. `applyAllPatches`, then `:sourbyapi:test :sourbycraft-server:test :test-plugin:test`, then the Python `scripts/test_*.py` suite, clip probes, then `slimServerJar -PsourbyBuild=N` and `verify_build_identity.py`.
4. Boot test in the same job: boots with the test plugin, `online-mode=false`, `level-type=minecraft:normal`, waits up to 240s for `Done (` plus the metrics markers, sends `stop`, and fails on timeout or unclean exit.
5. Publication runs only for pushes to `release/26.2-canvas`. `release=` in `gradle.properties` decides: `true` normal release, `pre` prerelease (skipped by the auto-updater unless `allow_prerelease`), `false` no release. The auto-updater defaults to `apply_mode=auto`, so a normal release reaches servers automatically.

## Module layout

- `:sourbyapi` — branded API artifact `dev.iyanz.sourbycraft:sourbyapi`. **Zero custom source**; it republishes `paper-api` + `canvas-api` under the SourbyCraft group id (see `sourbyapi/README.md`). Materialized from `canvas-api/build.gradle.kts` via the `upstreams.canvas { patchFile { ... } }` block in root `build.gradle.kts`.
- `:sourbycraft-server` — the server. Source-set merge in `build.gradle.kts.patch` adds `paper-server` + `canvas-server` to the main and test source sets, and `src/log4jPlugins/java` (own log4j2 pattern plugins, e.g. `%scLogger`).
- `:Metal` — vendored Minecraft library-decode codegen. `src/` empty until `setup_metal.sh` runs.
- SourbyPatcher and SourbyClip are **not in this repository**. They live in private repos (`YanIanZ/SourbyPatcher`, `YanIanZ/SourbyClip`; local clones at `~/Sourby/SourbyPatcher` and `~/Sourby/SourbyClip`, each with its own `AGENTS.md` describing the release-into-SourbyCraft procedure), pinned by `build-data/private-toolchain.lock.json` and by `patcherSha256`/`clipSha256` in `gradle.properties`. SourbyClip (`dev.iyanz:sourbyclip:${clipVersion}`) is wired in `sourbycraft-server/build.gradle.kts.patch`.
- `test-plugin/`, `legacy-test-plugin/` — opt-in projects; included when `-PincludeTestPlugin=true` (CI passes it). The pre-26.2 NMS-compat `test-harness/` and its `nms-compat.yml` workflow were removed; history has them if a 26.2 harness is ever rebuilt.

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

Wait for `Done (` in console. Build 47+ `/ver` reports a SourbyCraft-owned identity such as `Build 47 — Aurora Nexus`.

## Things agents commonly miss

- Bump `sourbyBuild` in `gradle.properties` per release. Build 47+ MUST NOT append an upstream-platform letter to public version strings.
- `releaseVersion` defaults to `26.2`; Build 47 codename is `aurora-nexus`.
- Do not commit `paper-server/`, `canvas-server/`, `paper-api/`, `canvas-api/` working-copy edits — those are generated by `applyAllPatches` from the `canvasRef` pin in `gradle.properties` (Canvas `2a3bf65c...`).
- The private toolchain is required: without the pinned SourbyPatcher jar in Maven Local, Gradle fails at settings time. When changing a tool, bump its version and update both the lock file and the SHA-256 in `gradle.properties`.
- Keep `release=pre` in `gradle.properties` until the build passes every gate in its release doc; `release=true` publishes to every server running the default auto-updater.
- The `slimServerJar` task's `externalizeArtifactDirs` list is matched by path prefix against the paperclip layout — when Canvas/weaver bumps versions, jars may move and the task will **fail loudly** with `stripped 0 libraries`. That's the intended signal to update the list.
- `applyAllPatches` is config-cache friendly but `writeBuildInfo` is opted out (`notCompatibleWithConfigurationCache`) because it reads git branch via `providers.exec` at execution time.


## Project to-do list

`TODO.md` is the single prioritized (P0–P3) to-do list. When a task closes, tick it there and in its source doc with evidence.

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
