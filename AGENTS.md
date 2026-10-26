# AGENTS.md — SourbyCraft 26.2 Aurora

Region-threaded Minecraft 26.2 server fork with the **Aurora** runtime/engine architecture. Paper is the only upstream (pinned by `paperRef`). Region threading and the other changes that used to come from Canvas/Folia are SourbyCraft-owned patches and sources since the 2026-10-05 migration; the engine internals live in `dev.iyanz.aurora.engine.*`; the former Canvas plugin API moved to `dev.iyanz.sourbycraft.api.*` in Build 48 (`event`, `event.world`, `region`, `simd`, `Unsupported`, `world.WorldUnloadResult`; `io.canvasmc` was removed outright, no shim — see `sourbyapi/README.md`), and Folia's `io.papermc.paper.threadedregions.*` keeps its name. Neither is the product identity. Do not describe planned Aurora ownership as already independent. The active build/toolchain must be verified from the current branch before repeating historical Path-B or private-toolchain statements.

## Toolchain

- **JDK 25** required (Temurin). Set via Gradle toolchains + `org.gradle.toolchains.foojay-resolver-convention`. CI uses `actions/setup-java@v6` + `temurin` + `java-version: 25`.
- Gradle wrapper at `./gradlew`. Configuration cache is **on** (`org.gradle.configuration-cache=true`). Do not introduce raw `ProcessBuilder` calls in `doLast`; use `providers.exec`.
- Branching still determines the REL/EXP/DEV channel version. Build 47+ public build identity is `Build N` with no Canvas/Folia/Paper suffix. `writeBuildInfo` and the server manifest MUST stay in lockstep.

## Build commands (root)

```bash
./gradlew applyAllPatches            # materialize Paper → SourbyCraft sources
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar              # → build/libs/SourbyCraft-slim.jar (~34 MiB; rest fetched on first boot)
```

Slim-jar task: `slimServerJar` strips 19 hard-coded artifact dirs from the paperclip jar (`externalizeArtifactDirs` in root `build.gradle.kts`); `sourbyclip` re-downloads them by coordinate on first boot via `META-INF/libraries` manifest. If the strip count is 0, the task **fails** — update the prefix list when libraries move.

The CI workflow `.github/workflows/build.yml` (push on `26.2`, `main`, `hotfix/26.2-r*`; pull_request; dispatch) has four jobs (audit SEC-8/OBS-9; PR split 2026-10-20, owner decision): `PR checks (no secrets; Java build and tests NOT run)` (pull_request only, `contents: read`, no secrets or deploy keys: gradle wrapper validation, `scripts/pr_checks.py` = the `scripts/test_*.py` suite with the four tests that read private-toolchain outputs reported as skipped, `patch_policy.py`, `baseline_policy.py`, `patch_surface.py --check`, and a step summary saying the Java build/tests did not run), `Build 26.2 jar` (push and dispatch only, never pull_request; `contents: read`; deploy keys), `Rebuild slim jar and compare` (`needs: build`, push and dispatch only, `contents: read`; also checks out SourbyPatcher/SourbyClip with the deploy keys; a fresh checkout of the same commit with the same pins and `SOURCE_DATE_EPOCH` must give a byte-identical slim jar) and `Publish release` (`needs: [build, rebuild]`, `contents: write` plus `id-token`/`attestations: write`, only pushes/dispatches on `26.2` or `hotfix/26.2-r*` with an unpublished tag). A pull request therefore gets no Gradle build, Java test, boot test or jar; those run after merge on push. Every action is pinned by commit SHA with the tag in a comment. A separate scheduled, read-only workflow, `protocollib-pin-probe.yml`, reports ProtocolLib pin drift. The build job does:
1. Checks out the private `YanIanZ/SourbyPatcher` and `YanIanZ/SourbyClip` at the revisions in `build-data/private-toolchain.lock.json` (deploy keys) and publishes them to Maven Local with `scripts/private_toolchain.py --publish`. `settings.gradle.kts` refuses to configure without the hash-verified SourbyPatcher `paper-toolchain` jar and its POM/`.module` metadata (`patcherSha256`/`patcherPomSha256`/`patcherModuleSha256`), which must declare `paperweightVersion`. See `docs/development/PRIVATE-TOOLCHAIN.md`.
2. `bash scripts/setup_metal.sh` — Metal is **vendored** at `Metal/`, not a submodule (upstream LuminolMC/Metal went offline). `setup_metal.sh` calls `Metal/gen_sources.sh`.
3. `applyAllPatches`, then `:sourbyapi:test :sourbycraft-server:test :test-plugin:test`, then the Python `scripts/test_*.py` suite, clip probes, then `slimServerJar -PsourbyBuild=N` and `verify_build_identity.py`.
4. Boot test in the same job: boots with the test plugin, `online-mode=false`, `level-type=minecraft:normal`, waits up to 240s for `Done (` plus the metrics markers, sends `stop`, and fails on timeout or unclean exit.
5. Publication (the `release` job) runs for pushes/dispatches on `26.2` (owner decision 2026-10-21; `release/26.2-canvas` is retired) only while `release=true|pre` and no `v26.2-r<sourbyBuild>` tag exists yet — that build gets `-PsourbyChannel=REL` (jar `SourbyCraft-26.2-REL.jar`); every later push is a development build until `sourbyBuild` is bumped. A `hotfix/26.2-r<N>.<M>` branch (cut from `v26.2-r<N>`, `sourbyBuild=<N>.<M>`) publishes `v26.2-r<N>.<M>` once under the same rule (`scripts/release_version.py --hotfix-branch`; 49.1 shipped this way). It downloads the jar the build job uploaded as the `SourbyCraft-26.2-release` artifact and checks it is the jar the rebuild job reproduced. `release=` in `gradle.properties` decides: `true` normal release, `pre` prerelease (skipped by the auto-updater unless `updater.allow-prerelease`), `false` no release. The auto-updater defaults to `updater.apply-mode: notify` since 2026-10-20 (audit SEC-2; servers whose config says `auto` keep applying automatically, with `<jar>.prev` kept and restored if the next boot does not reach ready — see `docs/development/AUTO-UPDATER.md`).

## Running tests

```bash
# Gradle suites (what CI runs; test-plugin modules need the flag)
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test

# One server test class / method
./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.awf.AwfRegionStorageTest'
./gradlew :sourbycraft-server:test --tests '*AwfRegionStorageTest.someMethod'

# Python policy/tooling suite (scripts/test_*.py), and a single file
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts -p 'test_patch_policy.py'
```

The AWF Redis backend tests start their own `redis-server` and skip without it; CI installs it so they must run there.

## Where code lives

- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/` — SourbyCraft-owned services: `awf` (Aurora World Fabric: region-aware world storage/templates/instances, attached from `RegionFileStorage`; see `docs/architecture/aurora-world-fabric.md`), `bootstrap`/`startup`, `config`, `perf`/`hud`/`spark` (observability), `update` (auto-updater), `bridge`, `command`, `brand`, `awf/multiprofile` (AWF MultiProfile, off by default; `docs/awf/multiprofile/`), `awf/redis` (Redis world backend) and `awf/redis/fabric` (Aurora Redis Fabric), `mod` (SourbyMod loader, `docs/SOURBYMODS.md`), `abysslink` (optional Leviathan control link, `docs/abysslink/`), `security` (packet/connection guards) and `guards` (thread checks, owned region refusals).
- `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/` — the region-threading engine (former Canvas/Folia internals: `threadedregions`, engine configuration behind `config/aurora-*.yml`, formerly `config/canvas-*.yml`).
- Changes to Minecraft or Paper classes cannot be made in these sources; they go through feature patches (`sourbycraft-server/{minecraft,paper}-patches/features/`, numbered `NNNN-SourbyCraft-*.patch`). Rebuild them from the materialized repos with `rebuildMinecraftFeaturePatches` / `rebuildPaperServerPatches` after committing there.
- `scripts/` — Python release, benchmark, policy (`*_policy.py`) tooling, each with a `test_*.py`.

## Module layout

- `:sourbyapi` — branded API artifact `dev.iyanz.sourbycraft:sourbyapi`. Publishes `paper-api` (with `sourbyapi/paper-patches`) plus its own `sourbyapi/src` (the former Canvas plugin API, now `dev.iyanz.sourbycraft.api.*` since Build 48, the SourbyCraft metrics API, `api.world` (`AuroraWorlds`), `api.profile` (`AuroraProfiles`) and `api.scheduler` (`EntityTask`/`RegionTask`)) under the SourbyCraft group id (see `sourbyapi/README.md`). Its build file is materialized from `paper-api/build.gradle.kts` via the `upstreams.paper { patchFile { ... } }` block in root `build.gradle.kts`.
- `:sourbycraft-server` — the server. Source-set merge in `build.gradle.kts.patch` adds `paper-server` to the main and test source sets (the module's own `src/main/java` holds SourbyCraft code plus the region-threading engine sources formerly in `canvas-server`), and `src/log4jPlugins/java` (own log4j2 pattern plugins, e.g. `%scLogger`).
- `:Metal` — vendored Minecraft library-decode codegen. `src/` empty until `setup_metal.sh` runs.
- SourbyPatcher and SourbyClip are **not in this repository**. They live in private repos (`YanIanZ/SourbyPatcher`, `YanIanZ/SourbyClip`; local clones at `~/Sourby/SourbyPatcher` and `~/Sourby/SourbyClip`, each with its own `AGENTS.md` describing the release-into-SourbyCraft procedure), pinned by `build-data/private-toolchain.lock.json` and by `patcherSha256`/`clipSha256` in `gradle.properties`. SourbyClip (`dev.iyanz:sourbyclip:${clipVersion}`) is wired in `sourbycraft-server/build.gradle.kts.patch`.
- `test-plugin/`, `legacy-test-plugin/` — opt-in projects; included when `-PincludeTestPlugin=true` (CI passes it). The pre-26.2 NMS-compat `test-harness/` and its `nms-compat.yml` workflow were removed; history has them if a 26.2 harness is ever rebuilt.

## Patch application

Single-level fork through the private SourbyPatcher `paper-toolchain` plugin, which applies paperweight's patcher (2.0.0-beta.24; `io.papermc.paperweight.core` in `settings.gradle.kts` must be the same release — enforced by the pinned toolchain POM/`.module` hashes plus the `paperweightVersion` property and `scripts/private_toolchain.py`'s equality check) with Paper as the only upstream. `gitFilePatches = false` — file patches are codechicken diffpatch format and go through `java-diff-utils`.

Two kinds of patch, and the policy scripts depend on keeping them apart:

- **Engine baseline** — `sourbycraft-server/minecraft-patches/sources/`, `sourbycraft-server/paper-patches/files/`, `sourbyapi/paper-patches/files/`. These hold what Canvas/Folia used to supply (region threading, Canvas features). `scripts/patch_policy.py` excludes them; `scripts/baseline_policy.py` verifies the baseline against its manifest, while `scripts/inherited_guard_policy.py` scans its guard shapes (and those in feature patches and owned engine sources).
- **SourbyCraft changes** — `sourbycraft-server/minecraft-patches/features/` and `sourbycraft-server/paper-patches/features/`. Every SourbyCraft edit to Minecraft or Paper code goes here as a git-format feature patch, even when the file is also in the baseline, so the policy scripts see it.

Access transformers: `build-data/sourbycraft.at` (includes the former `canvas.at` + `folia.at`), `build-data/paperServer.at`, `build-data/paperApi.at`. Library imports: `build-data/dev-imports.txt` (includes the leafpile classes region threading uses without patching).

The engine baseline is pinned by `build-data/engine-baseline.sha256` and checked by `scripts/baseline_policy.py` (added lines must not name SourbyCraft; a deliberate baseline change regenerates the manifest with `--write-manifest`, so it shows as a reviewed diff). The patch directories are the source of truth — **edit patch files (or the materialized repos followed by `rebuild*Patches`), never commit the materialized sources** under `paper-server/`, `paper-api/`, `sourbycraft-server/src/minecraft/` (git working copies regenerated by `applyAllPatches`). Workflow for a change: edit the materialized repo, commit there, run the matching `rebuild*Patches` task, commit the patch files.

## Configuration surfaces

Configuration surfaces (do not conflate, and verify boot-order consumers before documenting reloadability):

Both `sourbycraft_config` files are YAML since Build 49. An existing `*.toml` is converted once at boot (values and comments kept, old file renamed `*.toml.migrated`); a `*.yml` always wins, and an unparseable old file is left untouched and named with file + near-line. `config/ConfigFormatMigration`.
- **SourbyCraft server settings** → `sourbycraft_config/sourbycraft_global_config.yml` (YAML; Build 49 layout: `console`, `players`, `messages`, `plugins.viaversion`/`plugins.protocollib`, `updater`; the key table is `config/UtilityKeys.java`). The pre-49 names (`ui.*`, `branding.*`, `sourbycraft.*`, `viaversion.*`, `protocollib.*`, `misc.auto_update.*`) are rewritten once at boot by `config/ConfigMigration` (copy kept as `*.migrated`); since PR #62 a leftover old name is no longer read and is ignored with one WARN (the `v26.2-r50` tag still reads them with a deprecation WARN).
- **Aurora services** → `sourbycraft_config/aurora.yml` (`aurora.<service>` sections, key table `config/AuroraKeys.java`; some keys are read before the configuration loads: `aurora.cpu.cores`, `aurora.bridge.mode`, `aurora.awf.*`, chunk-generation metrics). Aurora keys left in the global file are moved here once by the same migration. `aurora.bridge.thread-checks` defaults to `relaxed` (RESTART_REQUIRED; applies while `aurora.bridge.mode: safe`, with the bridge off the checks are enforced; owner decision 2026-10-22; unsafe, unqualified). Added after the `v26.2-r50` tag (PR #62; in no published build yet): the RESTART_REQUIRED keys `aurora.awf.multiprofile.repository` (default `file`; `redis` is EXPERIMENTAL and required by `mode: distributed`), `aurora.awf.multiprofile.redis.require-aof` and `...redis.wait-for-aof` (both default `true`), and the permission `sourbycraft.command.awf.admin` for destructive `/awf` subcommands (implied by `sourbycraft.command.awf` unless set explicitly).
- **Region-threading engine** (formerly Canvas) → `config/aurora-server.yml` + `config/aurora-worlds.yml` since Build 49 (region-scheduler options such as `guard-severity`, tick rate, autosave). `aurora/engine/configuration/EngineConfigFiles` migrates `config/canvas-server.yml` / `canvas-worlds.yml` once at boot (values and comments copied, old file renamed `*.migrated`, one INFO; both present = new wins + one WARN; per-world `canvas-patch.yml` keeps its name). Default `region-scheduler.guard-severity: LOG` (not Canvas's crash-prone `THROW`); pinned with the other engine defaults in `scripts/test_patch_policy.py`. With LOG, a cross-region access inside a bridged plugin's task counts toward that plugin's Aurora Bridge quarantine (`GuardViolations`).
- **Region scheduler sizing** → `config/paper-global.yml` `threaded-regions` (`threads`, `grid-exponent`, `scheduler`), not the `aurora-*.yml` engine files. `threads: -1` resolves through MC patch 0002 to every available processor or `aurora.cpu.cores`. Startup logs a thread ledger of every pool against the processor count (also `/perf governor`); it reports, it changes no default.
- **Crash-prevention + packet-guard limits** → `sourbycraft-security.yml` in the server directory (seeded by the server with a header and per-group comments, `SecurityGuards.seededDocument()`; the checked-in sample `build-data/sourbycraft-security.yml` is byte-identical to it, pinned by `SecurityLimitPinTest`). LIVE on `/sourbycraft reload`; proxy detection RESTART_REQUIRED.

## Cherry mixin engine

Off by default. Enable with `-Dcherry.enable.mixin=true`. Plugin authors drop a `cherry-plugin.json` next to the plugin jar with optional `mixin` and `access-transformers` blocks. **Server-side only** — does not run full Fabric mods, only Fabric-format mixin/AT/access-widener declarations. Plugin-author guide lives at `https://github.com/YanIanZ/Cherry` (not vendored). Cherry is provided by the private SourbyClip launcher at its pinned revision; this repository has no Cherry code and its tests do not cover it.

## Boot-time network

First boot needs internet once:
- `SourbyClip` (the launcher, `dev.iyanz:sourbyclip`) reads `META-INF/libraries.list` and fetches the externalized libraries into the paperclip cache by coordinate, SHA-256-verifying each against the bundled hash (on-disk copy, then embedded/original-JAR bytes, then Maven mirrors). On a cold boot it also fetches the Mojang server jar.
- `ViaVersion` + `ViaBackwards` jars auto-provisioned into `plugins/` (SHA-256-verified, https-only) **before** the plugin manager scans. Toggle: `plugins.viaversion.auto-provision` in the global file (default `true`, RESTART_REQUIRED; since PR #62 a pre-49 `[viaversion]` key alone, an invalid value or an unreadable file skips provisioning for that boot, fail-closed; `v26.2-r50` still reads the pre-49 table). Idempotent — never re-downloads verified jars, never overwrites user config.
- ProtocolLib uses independent `[plugins.protocollib] auto-provision` (default `true`, RESTART_REQUIRED; same fail-closed rule for a pre-49 `[protocollib]` key), official asset pin in `build-data/protocollib-pin.json`. Feature patch 0024 calls provisioning from the active SourbyClip-loaded server before Paper's plugin scan; `--plugins` selects the JAR/config folder. Clip offline mode forbids plugin downloads. See `docs/development/PROTOCOLLIB.md`.

Offline: `-Dsourbyclip.offline=true` refuses launcher downloads (the geo-IP lookup was removed in SourbyClip 3.1.2); verified cached or embedded libraries remain usable, so after a first successful boot the server runs fully offline. A missing/corrupt library on a cold offline boot fails non-zero, naming the coordinate it could not fetch.

## Docker

```bash
docker build -f docker/Dockerfile --build-arg JAR=release/SourbyCraft-26.2-REL.jar -t sourbycraft:26.2 .
docker compose -f docker/docker-compose.yml up -d --build
```

All Docker files live in `docker/`; run both commands from the repository root (the build context is the root, and BuildKit applies `docker/Dockerfile.dockerignore` to it; the legacy non-BuildKit builder ignores that file). Compose mounts the root `data/` directory.

Image: `eclipse-temurin:25-jre` (NOT jdk), non-root user `sourby`, `/data` volume, `LANG=C.UTF-8` (without this, stdout.encoding defaults to US-ASCII and garbles the branded box-drawing banner). `mem_limit: 8g` in compose with `MEMORY: 6144` MiB heap.

## Verifying a build locally

```bash
java -Xmx2G -XX:+UseG1GC -jar release/SourbyCraft-26.2-REL.jar --nogui
```

Wait for `Done (` in console. Build 47+ `/ver` reports a SourbyCraft-owned identity such as `Build 47 — Aurora Nexus`.

## Things agents commonly miss

- Bump `sourbyBuild` in `gradle.properties` per release. Build 47+ MUST NOT append an upstream-platform letter to public version strings.
- `releaseVersion` defaults to `26.2`; the 26.2 codename is `aurora-nexus` (Aurora Nexus). Owner decision 2026-10-24: the **26.3** line starts at **Build 100** with codename **`aurora-forge`** (Aurora Forge); 26.2 and earlier stay below Build 100. `scripts/release_version.py` enforces both the range and the 26.3 codename.
- Do not commit `paper-server/`, `paper-api/` or `sourbycraft-server/src/minecraft/` working-copy edits — those are generated by `applyAllPatches` from the `paperRef` pin in `gradle.properties` (Paper `9240f586...`).
- The private toolchain is required: without the pinned SourbyPatcher jar in Maven Local, Gradle fails at settings time. When changing a tool, bump its version and update both the lock file and the SHA-256 in `gradle.properties`.
- `release=true` publishes a normal release to every server running the auto-updater (default `updater.apply-mode: notify`; `auto` servers install it). Owner decision 2026-10-21: Build 47 shipped with `release=true` and its open gates listed in its release doc; STABLE was planned for Build 49, but Builds 48, 49 and 49.1 shipped as normal releases without it (48 by plan; 49 and 49.1 because gates stayed open). Which build carries STABLE is an owner decision (PM-181), only after every gate passes. Bump `sourbyBuild` before the next release from `26.2`.
- Build 50 is `release=pre`. Tag `v26.2-r50` (`5f5a167`) predates PR #61 and PR #62, so their work is in no published build; how it ships (`hotfix/26.2-r50.1` or Build 51) is owner decision PM-011. Do not describe branch work as released or tested.
- On the Build 50 branch, PR #62 stopped reading non-persisted legacy names (`-DCanvas.*` flags, the `canvas.broadcasting.receiver` permission, pre-49 utility keys, `perf.ai.async-pathfinding`; one WARN each that it is ignored) and keeps the one-time on-disk rewrite; the published `v26.2-r50` still honours them with a deprecation WARN. Persisted readers (`Canvas.Distance` level data, `data/canvas/pearls.dat`, the `canvas-*.yml` migration) stay pending owner decision PM-179.
- The `slimServerJar` task's `externalizeArtifactDirs` list is matched by path prefix against the paperclip layout — when Paper/paperweight bumps versions, jars may move and the task will **fail loudly** with `stripped 0 libraries`. That's the intended signal to update the list.
- `applyAllPatches` is config-cache friendly but `writeBuildInfo` is opted out (`notCompatibleWithConfigurationCache`) because it reads git branch via `providers.exec` at execution time.


## Documentation map

- `docs/README.md` — developer reading path and index of every active doc. Pre-Aurora plans/specs (formerly `docs/superpowers/`) were removed; they are only in git history.
- `docs/architecture/AURORA.md` — entry point for all Aurora architecture docs, current state verified against code, and known contradictions between docs.
- `docs/DEVELOPMENT.md` — governing rules (truthfulness policy, performance evidence requirements, stability budget, testing matrix).
- `docs/TODO.md` — the single prioritized to-do list and status source; `docs/releases/` — per-build release gates and evidence; `docs/releases/BUILD-50-PM-MATRIX.md` — generated per-item Build 50 work matrix (do not hand-edit); `docs/tester owner.md` — owner manual test handoff (Indonesian).
- `docs/awf/multiprofile/` — AWF MultiProfile and Aurora Redis Fabric: architecture, lifecycle, isolation,
  distributed ownership, configuration, API, security, operations, testing, benchmarks. Both features
  ship disabled by default; distributed mode is documented as experimental and unqualified.
- Repository root follows PaperMC/Paper's layout: Docker files in `docker/`, the README image in `.github/assets/`, the security-limits sample in `build-data/`. `CLAUDE.md`, `opencode.json`, `.run/` and the `*.settings.gradle.kts` opt-in files are local and git-ignored; a local `CLAUDE.md` should import this file (`@AGENTS.md`) rather than copy it.

## Concurrent agents

When `AGENT-COORDINATION.md` exists (untracked, never committed), another agent is editing the same working tree. Read it first, don't edit files another agent owns, keep edits to shared files small and targeted, and record your own owned/shared files there.

## Project to-do list

`docs/TODO.md` is the single prioritized (P0–P3) to-do list. When a task closes, tick it there and in its source doc with evidence.

## Non-misleading development rules

Agents working on this branch MUST follow `docs/DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy`.

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

For offline engine-baseline provenance and upstream rebase planning, see [Rebasing the engine baseline onto a new upstream](docs/development/UPGRADING-MINECRAFT.md#rebasing-the-engine-baseline-onto-a-new-upstream). Use `scripts/engine_baseline_origin.py` and `scripts/upstream_rebase.py`; review manual ports before applying, then regenerate patches, the baseline manifest and origin JSON as documented.
