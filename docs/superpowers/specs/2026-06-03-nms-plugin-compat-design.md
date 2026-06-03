# SourbyCraft v12 — NMS Plugin Compatibility Investigation + Fix

**Date**: 2026-06-03
**Status**: Approved (brainstorm), pending implementation plan
**Scope**: 4 named plugins (Citizens, NBTAPI, DecentHolograms, FAWE) against Paper 1.21.11. One PR producing: dual-paperclip release artifacts, investigation matrix, per-plugin fixes, smoke harness.
**Out of scope**: WorldGuard direct testing (covered transitively via FAWE/WorldEdit), other plugins, older Paper versions, multi-plugin interaction tests, Velocity proxy-side tests.

## Goal

Make Citizens, NBTAPI, DecentHolograms, and FAWE work on SourbyCraft v12 by:
1. Shipping both mojmap and reobf paperclip jars so operators choose the mapping that matches their plugin set.
2. Running a structured investigation against the latest 1.21.11-compatible builds of each plugin, against both jar variants, capturing failures in a matrix document.
3. Fixing each failure either by amending a SourbyCraft patch (when the breakage traces to our edits) or by documenting the upstream root cause + operator workaround when it doesn't.
4. Landing a gradle smoke task that re-runs the matrix as a CI gate on PRs that touch patches, paperRef, or release artifacts.

## Non-Goals

- Building a generic NMS shim API (`dev.iyanz.sourbycraft.compat.NMS`). Rejected during brainstorm — broader effort, plugins won't adopt a SourbyCraft-specific API.
- Reverting to ship reobf-only. Rejected — leaves Paper's mojmap path with latent bugs.
- Backwards compatibility with plugins built for Paper versions older than 1.21.11.
- Performance benchmarks for plugin-loaded scenarios. Tracked separately if needed.

## Architecture

The pipeline flows in four phases, each a release-blocking gate before the next:

```
Phase 1: Dual-jar build wiring
  └─ gradle createReobfPaperclipJar → release/SourbyCraft-v12-REL-reobf.jar
  └─ release/checksums.txt: 2 lines (mojmap + reobf)

Phase 2: TestServer compat matrix
  ├─ TestServer-mojmap/  ← boots mojmap jar
  │   plugins/{Citizens, NBTAPI, DecentHolograms, FAWE}-latest-1.21.11.jar
  │   → boot.log captures every plugin's enable/disable + exceptions
  ├─ TestServer-reobf/   ← boots reobf jar
  │   same plugin set, same checks
  └─ docs/superpowers/notes/<date>-nms-compat-matrix-r1.md
      ├─ per-plugin row: mojmap result | reobf result | stack hash | hypothesis
      └─ aggregate symptom histogram

Phase 3: Fix per root-cause class
  ├─ A. Plugin needs newer build → upgrade plugin jar (no SourbyCraft change)
  ├─ B. Paper remapper miss → file Paper bug + ship reobf as workaround
  ├─ C. SourbyCraft patch conflict → audit patches/server, patches/minecraft,
  │     amend offending patch (e.g., ChunkMap tracker hook may break Citizens NPC visibility)
  ├─ D. NMS class moved (1.21.11 vs older) → no SourbyCraft fix; plugin upstream bug
  └─ E. packageVersion mismatch (plugin built for v1_21_R6 not v1_21_R7) → no fix; doc workaround

Phase 4: Smoke harness (CI gate)
  └─ gradle :sourbycraft-server:nmsCompatTest
      ├─ downloadPluginJarsTask (cache to build/test-plugins/)
      ├─ launchServerInBackground (mojmap and reobf, parallel)
      ├─ waitForReady (poll log for "Done (")
      ├─ assertPluginEnabled per target plugin
      ├─ assertSanityMethod per plugin (NBT round-trip, NPC create, holo create, schem load)
      └─ teardown + assert clean shutdown
```

**Invariant**: each target plugin works on at least one of mojmap or reobf. If both fail, the matrix row points to upstream cause + an operator workaround (typically: pin plugin version or wait for upstream fix).

## Components

### Build (`build.gradle.kts`, root)
- Re-add release artifact wiring for both paperclip outputs (single variant, dual mapping).
- After `createReobfPaperclipJar` produces `sourbycraft-server/build/libs/sourbycraft-paperclip-1.21.11-R0.1-SNAPSHOT-reobf.jar`, copy to `release/SourbyCraft-v12-REL-reobf.jar`.
- Mojmap output keeps the name `release/SourbyCraft-v12-REL.jar`.
- `release/checksums.txt`: 2 lines, deterministic order (mojmap first, reobf second).
- New helper task `:assembleReleaseArtifacts` depends on both paperclip tasks, copies jars, regenerates checksums.

### TestServer harness (new dir tree)
```
test-harness/                                ← gitignored except scripts
  test-plugins/                              ← downloaded plugin jars (cached, sha-pinned)
    Citizens-2.x-1.21.11.jar
    item-nbt-api-bukkit-2.x.jar
    DecentHolograms-2.x.jar
    FastAsyncWorldEdit-Bukkit-2.x.jar
    manifest.yml                             ← name + version + sha256 per entry
  scripts/                                   ← committed
    download-test-plugins.sh
    boot-mojmap.sh
    boot-reobf.sh
    capture-matrix.sh
  TestServer-mojmap/                         ← gitignored
    server.jar (copy of release mojmap)
    plugins/  (copies of test-plugins/)
    eula.txt, server.properties (port 25600)
  TestServer-reobf/                          ← gitignored, port 25601
```

### Investigation note format
- Location: `docs/superpowers/notes/<date>-nms-compat-matrix-r<N>.md`
- One row per plugin per jar variant. Columns:
  - Plugin name + version + source URL
  - Jar variant (mojmap | reobf)
  - Boot outcome: `OK` | `DISABLED` | `EXCEPTION` | `ENABLED_NO_API`
  - Stack-trace hash (sha1 of normalized trace) + first 3 frames
  - Root-cause class: A / B / C / D / E (from Architecture taxonomy)
  - Fix applied: PR/commit ref, or `WORKAROUND`, or `UPSTREAM`
- One aggregate paragraph per plugin: known-good version pin, recommended jar (mojmap or reobf), follow-up actions.

### SourbyCraft patch audit list (Phase 3 input)
Patches to audit for conflict with plugins:
- `patches/minecraft/0038-…-entity-tracker-tightening.patch` (former 9002) — caps tracking range; could hide Citizens NPCs or DecentHolograms armor stands. Already gated on `pvpEnabled`.
- `patches/minecraft/0040-…-combat-completion.patch` (former 9004) — sweep gate / hit-delay alter LivingEntity behavior; FAWE block-edit may interact with damage tick logic. Already gated.
- `patches/minecraft/0037-…-netty-tuning.patch` (former 9001) — SO_RCVBUF/SO_SNDBUF override; could break Citizens packet listeners. Already gated.
- `patches/server/0028-…-BossBarTicker-refactor-…patch` — BossBar holography overlaps with DecentHolograms entity tracking.
- `patches/server/0030-…wire-PluginAutoInstaller-into-loadPlugins.patch` — auto-installer runs BEFORE plugin scan; order matters if plugins it adds conflict with operator's set.

### Per-plugin sanity-test fixtures (`sourbycraft-server/src/test/resources/nms-compat/`)
- `nbtapi-sanity.txt` — input/expected for `NBT.getNBTContainer("{...}")` round-trip
- `citizens-sanity.txt` — npc-create command + expected world-state assertion
- `decentholograms-sanity.txt` — `dh create` command + expected armor-stand UUID lookup
- `fawe-sanity.txt` — small schematic file + paste + count blocks placed

### Sanity harness plugin (`test-harness/sanity-harness-plugin/`)
- New Bukkit plugin project: `test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityHarnessPlugin.java` + `plugin.yml`.
- Built as `sanity-harness-plugin.jar` and copied into both TestServer-mojmap/plugins/ and TestServer-reobf/plugins/ alongside target plugins.
- `onEnable()`:
  - For each target plugin name P, look up via `Bukkit.getPluginManager().getPlugin(P)`.
  - If present + enabled, invoke the sanity fixture (loaded from `plugins/sanity-harness-plugin/fixtures/<plugin>-sanity.txt`).
  - Catch any `Throwable`; record `{plugin: P, enabled, sanity_passed, fail_reason, stack_hash}` per row.
  - Write all rows to `nms-compat-result.json` in the server working directory.
- `onDisable()`: best-effort flush.
- Plugin runs alongside target plugins; isolated failures do not affect target-plugin lifecycle.

### Gradle smoke task `:sourbycraft-server:nmsCompatTest`
- Type: `JavaExec` wrapping a runner in `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarness.java`.
- Runner contract: `main(jarVariant: String, pluginsDir: Path, resultPath: Path)` → exit 0 = all green; nonzero = which step failed.
- Steps:
  1. Launch the test server (mojmap or reobf) in background via `boot-*.sh`.
  2. Wait for the boot log to show `Done (`.
  3. The in-server `sanity-harness-plugin` runs on enable, writes `nms-compat-result.json` to the server workdir.
  4. CompatHarness reads the JSON file, asserts per-plugin results, emits JUnit XML.
  5. SIGTERM the server, wait for clean shutdown.
- Wired into existing `test` task as opt-in (`-PrunNmsCompat=true`) so default `./gradlew test` stays fast.

## Data Flow

### Build → Release

```
gradle :sourbycraft-server:createMojmapPaperclipJar
  → sourbycraft-server/build/libs/sourbycraft-paperclip-<mc>-mojmap.jar
gradle :sourbycraft-server:createReobfPaperclipJar
  → sourbycraft-server/build/libs/sourbycraft-paperclip-<mc>-reobf.jar

gradle :assembleReleaseArtifacts
  ├─ depends on both paperclip tasks
  ├─ copies mojmap.jar → release/SourbyCraft-v12-REL.jar
  ├─ copies reobf.jar  → release/SourbyCraft-v12-REL-reobf.jar
  └─ regenerates release/checksums.txt:
       <sha256>  release/SourbyCraft-v12-REL.jar
       <sha256>  release/SourbyCraft-v12-REL-reobf.jar
```

### TestServer boot → log → matrix

```
test-harness/scripts/boot-mojmap.sh
  └─ cp release/SourbyCraft-v12-REL.jar test-harness/TestServer-mojmap/server.jar
  └─ ensure test-harness/TestServer-mojmap/plugins/ contains
       test-harness/test-plugins/{Citizens, NBTAPI, DecentHolograms, FAWE}.jar
  └─ java -jar server.jar nogui > boot.log 2>&1 &
  └─ poll boot.log until "Done (" or 90s timeout
  └─ send SIGTERM, wait until process exits
  └─ exit code = 0 if "Done (" seen, else 1

test-harness/scripts/boot-reobf.sh → identical, against release/SourbyCraft-v12-REL-reobf.jar

test-harness/scripts/capture-matrix.sh
  ├─ for each plugin name P in {Citizens, NBTAPI, DecentHolograms, FAWE}:
  │     mojmap_result = grep_outcome(TestServer-mojmap/boot.log, P)
  │     reobf_result  = grep_outcome(TestServer-reobf/boot.log,  P)
  │     stack_hash    = sha1(normalize(extract_trace(P)))
  │     append row to docs/superpowers/notes/<date>-nms-compat-matrix-r<N>.md
  └─ grep_outcome lookups:
        "[<P>] Loading"   → no exception in next 50 lines → OK
        "[<P>] Loading" then exception → EXCEPTION + capture trace
        no Loading line   → DISABLED (Paper refused load)
```

### Smoke harness (CI) → pass/fail

```
gradle :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true
  └─ downloadPluginJarsTask: idempotent fetch to build/test-plugins/
  └─ launchServerInBackground(variant=mojmap, workdir=build/test-server-mojmap/)
  └─ launchServerInBackground(variant=reobf,  workdir=build/test-server-reobf/)
       (parallel; ports 25600 + 25601)
  └─ waitForReady poll each boot.log for "Done ("
  └─ for each plugin P in {Citizens, NBTAPI, DecentHolograms, FAWE}:
       for each variant V in {mojmap, reobf}:
         result = CompatHarness.check(V, P)
         //  Implementation: an in-server Bukkit plugin
         //  (sanity-harness-plugin.jar, bundled into both TestServer plugins/ dirs)
         //  runs on the running Paper server. Its onEnable iterates target plugins,
         //  calls each plugin's sanity fixture, and writes results to
         //  ${workdir}/nms-compat-result.json. After boot completes, the gradle
         //  task reads this JSON to drive the assertions.
         //  - Bukkit.getPluginManager().getPlugin(P) != null && isEnabled()
         //  - run plugin-specific sanity fixture
         //  - capture result + stack-trace-on-failure
       record per (P, V): {enabled, sanity_passed, fail_reason}
  └─ teardown: SIGTERM both servers, wait clean shutdown
  └─ assert: for each P, at least ONE of (mojmap, reobf) green
  └─ emit JUnit XML to sourbycraft-server/build/test-results/nms-compat/
```

### Per-plugin fix loop (Phase 3)

```
For each (plugin P, variant V) with non-OK outcome from Phase 2:
  1. classify root cause (taxonomy A-E from Architecture)
  2. apply fix per class:
     A (plugin outdated)         → bump pinned version in test-plugins/manifest.yml
     B (Paper remapper bug)      → no SourbyCraft fix; document + file Paper issue link
     C (SourbyCraft patch conflict)
                                 → identify offending patch via git bisect-style
                                   enable/disable patches (rename .patch → .patch.disabled)
                                 → amend patch behavior or add escape hatch
     D (NMS class moved)         → no SourbyCraft fix; document
     E (packageVersion mismatch) → no fix; document workaround
  3. re-run boot script for (P, V); confirm OK
  4. update matrix row + commit
```

### Operator decision tree (downstream consumers)

```
Operator wants to run plugin X.
  ├─ Check matrix row for X.
  ├─ If "mojmap: OK"   → pull SourbyCraft-v12-REL.jar
  ├─ If "reobf: OK"    → pull SourbyCraft-v12-REL-reobf.jar
  └─ If both fail      → matrix points to upstream issue + workaround
```

## Error Handling & Edge Cases

### Plugin download failure
- `download-test-plugins.sh` fetches from official sources (GitHub releases for Citizens; Modrinth for NBTAPI, DecentHolograms, FAWE). Network failure or 404:
  - Retry 3× with exponential backoff (5s, 15s, 45s).
  - On final failure: print plugin name + URL, exit non-zero. Smoke harness gates on download success.
  - Cache hit (sha256 match in `test-plugins/`) skips download → repeated CI runs are free after first success.
- Rate-limit (HTTP 429): backoff honors `Retry-After` header. Cap 5 min wait, then fail.

### TestServer boot timeout / hang
- Boot poll loop has a 90s deadline. On timeout:
  - SIGTERM the java process.
  - Wait 30s; if still alive, SIGKILL.
  - Dump last 200 lines of `boot.log`.
  - Exit nonzero with marker `BOOT_TIMEOUT`.
- Distinguishes timeout from crash via presence/absence of `Done (` in log.

### Port conflict (CI parallel boot)
- Mojmap server pins to port 25600, reobf to 25601 via `server.properties` overlay in `test-harness/scripts/`.
- Pre-flight check: `nc -z localhost 25600 || nc -z localhost 25601`. If either taken: exit with `PORT_BUSY`, instruct CI to retry on a different runner.

### Plugin enable but sanity fixture fails
- Outcome class = `ENABLED_NO_API`. Common cause: plugin loaded but its NMS-backed feature reflection-failed silently.
- Sanity fixture wraps reflection in try/catch; on `NoSuchMethodError | NoClassDefFoundError`: records stack hash + first 3 frames, marks fail, continues to next plugin (no cascade).
- Matrix distinguishes from full disable: shows `enabled=true sanity=fail`.

### Stack-trace hash collision
- Normalize before sha1: strip timestamps, line numbers, anonymous class indices (`$1`, `$2`), thread names, hex addresses.
- Two distinct bugs with identical normalized trace are extremely rare; if it happens, manual review unblocks. Document false-positive risk in matrix legend.

### SourbyCraft patch bisect (Phase 3 class C)
- Disable patches by renaming `.patch` → `.patch.disabled` (paperweight skips non-`.patch` files in `applyAllPatches`).
- Re-run `applyAllPatches` + `createReobfPaperclipJar` + boot test → if plugin loads, the renamed patch is the culprit.
- Risk: large patch set, full bisect = O(log N) builds × ~5 min each. Acceptable for one-time investigation; not a CI gate.
- Restore patches after each bisect step.

### Plugin updates upstream during investigation
- Plugin author releases a new build mid-investigation. Matrix may go stale. Mitigation:
  - Each matrix row pins a specific plugin version (sha256 of jar).
  - `download-test-plugins.sh` reads versions from `test-plugins/manifest.yml` (sha-pinned).
  - Bump versions deliberately in a follow-up commit, not silently.

### Paper version skew
- Spec targets Paper 1.21.11. If `paperRef` in `gradle.properties` bumps mid-investigation, the matrix invalidates.
- Smoke harness re-runs full matrix on paperRef-bump PRs.

### Smoke harness false-green
- Plugin loads but errors only on specific gameplay paths (e.g., NPC AI tick, hologram refresh interval) — not on plugin enable or one-shot sanity call.
- Mitigation: each sanity fixture runs at least one tick of plugin-driven game state where feasible (e.g., spawn NPC → tick world 5× → assert NPC entity still present + not flagged invalid).
- Acknowledged limit: tick-driven coverage is shallow. Documented as known-gap in spec.

### Reobf jar plugin remapper conflict
- Paper has a built-in plugin remapper that converts reobf-built plugins to mojmap. On the reobf jar (no mojmap mapping), the remapper is disabled.
- Edge case: a plugin built against mojmap targets won't load on the reobf jar (inverse problem).
- Matrix captures this case explicitly: `mojmap: OK | reobf: EXCEPTION_NoClassDef` → plugin is mojmap-only-built.
- Operator chooses the matching jar.

### Smoke harness CI cost
- Two parallel servers × ~30s boot + ~30s sanity + ~30s shutdown = ~90s wall per run.
- Plugin download cache + paperweight cache reduce subsequent runs to ~60s.
- Gate set to `--rerun-tasks` policy: smoke runs only on PRs that touch `patches/`, `release/`, or `gradle.properties` paperRef.

### Investigation note file conflicts
- `docs/superpowers/notes/<date>-nms-compat-matrix.md` may collide across investigation rounds.
- Naming convention: `<date>-nms-compat-matrix-r<N>.md` where N increments. Append-only history; rounds reference each other (e.g., "this round addresses Phase 3 fix C from round r1").

## Testing

### Phase-level gates

**Phase 1 — Dual-jar build**:
- `./gradlew :sourbycraft-server:createMojmapPaperclipJar :sourbycraft-server:createReobfPaperclipJar` succeeds.
- `./gradlew :assembleReleaseArtifacts` produces exactly 2 jars in `release/` (`SourbyCraft-v12-REL.jar`, `SourbyCraft-v12-REL-reobf.jar`).
- `wc -l release/checksums.txt` = 2; sha256 in checksums matches actual file digest.
- Both jars boot a server to `Done (` standalone (no plugins) within 60s.

**Phase 2 — TestServer compat matrix**:
- `test-harness/scripts/download-test-plugins.sh` exits 0; `test-harness/test-plugins/` contains all 4 target plugin jars with sha256 matching `manifest.yml`.
- `boot-mojmap.sh` and `boot-reobf.sh` each exit 0 (reach `Done (` within 90s).
- `capture-matrix.sh` produces `docs/superpowers/notes/<date>-nms-compat-matrix-r1.md` with exactly 8 rows (4 plugins × 2 variants), no `TBD` cells.
- Matrix legend present + interpretable.

**Phase 3 — Per-plugin fixes**:
- For every plugin where Phase 2 showed `EXCEPTION` or `ENABLED_NO_API` on at least one variant, a follow-up row in matrix round r2 (or later) shows that variant resolved (`OK`) or documented as upstream/external.
- Invariant: at the end of Phase 3, every plugin has at least one variant marked `OK`.
- For each Class C (SourbyCraft patch conflict): the offending patch is identified by name, the fix commit references the matrix row, and the smoke harness regression-tests the fix.

**Phase 4 — Smoke harness CI**:
- `./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true` exits 0 from a fresh checkout.
- Generated `build/test-results/nms-compat/*.xml` JUnit XML parses + shows 8 test cases (4 plugins × 2 variants), at least 4 green (one per plugin), zero unexpected failures.
- Default `./gradlew test` does NOT trigger the harness (opt-in flag respected).
- Re-running with cached plugin jars + cached paperweight completes in < 120s wall time.

### Test artifacts

- **Unit-level**: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/NmsCompatHarnessTest.java`
  - Asserts the harness's stack-trace normalizer is deterministic (same input → same sha1).
  - Asserts plugin classification taxonomy mapping (Exception type → root-cause class A-E).
- **Integration**: `nmsCompatTest` gradle task.
- **Boot fixtures**: each plugin's sanity fixture file (`*-sanity.txt`) lives in `sourbycraft-server/src/test/resources/nms-compat/` and is read at runtime by `CompatHarness`.

### Manual smoke (operator verification)

A human-run smoke checklist added to `docs/superpowers/notes/<date>-nms-compat-operator-checklist.md`:

1. Download both jars from `release/`.
2. For each jar:
   - Drop into a fresh data dir + accept EULA.
   - Place latest Citizens, NBTAPI, DecentHolograms, FAWE jars in `plugins/`.
   - Boot. Verify "Done (" within 60s, no FATAL/SEVERE in log.
   - In console: `/npc create TestNPC`, `/dh create test [0,0,0] hello`, `/fawe schem list`, `/version <plugin>`.
   - Expected outputs documented in checklist.
3. Report any mismatch via the matrix issue tracker.

### Negative tests

- **Plugin removed**: with `plugins/Citizens-*.jar` absent, harness reports `Citizens: NOT_LOADED` (not error) — matrix marks `n/a`, doesn't fail the run.
- **Plugin corrupted**: a 0-byte jar in `plugins/` → harness reports `LOAD_ERROR`, captures Paper's "could not load plugin" log line, doesn't crash.
- **Both jars fail same plugin**: harness reports the plugin as `FAIL_BOTH`, exits nonzero, prints the matrix row with both stack hashes side-by-side.

### Regression coverage

- Smoke harness becomes a CI gate on PRs touching:
  - `patches/server/*.patch`, `patches/minecraft/*.patch`
  - `gradle.properties` (specifically `paperRef`, `mcVersion`, `packageVersion`)
  - `sourbycraft-server/build.gradle.kts`
  - `release/*` (catches accidental mapping swaps)
- A green run on these paths is required for merge. Other PRs skip the harness.

### Coverage gaps acknowledged

- Sanity fixtures touch only first-order plugin API. Deep gameplay-loop bugs (e.g., Citizens NPCs misbehave after 30 min of player interaction) not caught.
- No multi-plugin-interaction test (e.g., FAWE + WorldGuard region overlap). Tracked as follow-up spec.
- No Velocity/BungeeCord proxy-side test. SourbyCraft proxy-kick patch (former 9005, runtime-gated) untested in this harness.

## Implementation Phases

Four phases in a single PR. Each phase leaves the tree green; subsequent phases depend on earlier ones.

1. **Phase 1 — Dual-jar build**: wire `:assembleReleaseArtifacts`, produce both paperclip outputs to `release/`, update `checksums.txt` to 2 lines. Standalone boot test (no plugins) passes for both jars.

2. **Phase 2 — TestServer compat matrix**: create `test-harness/` dir + scripts, write `manifest.yml` with pinned plugin versions, run download + boot scripts for both variants, produce `docs/superpowers/notes/<date>-nms-compat-matrix-r1.md` with 8 rows.

3. **Phase 3 — Per-plugin fixes**: iterate the fix loop until every plugin has at least one variant marked `OK`. Each fix is its own commit; class C fixes amend or escape-hatch the offending patch. Produces matrix round r2 + onwards.

4. **Phase 4 — Smoke harness CI**: implement `CompatHarness.java` + unit test + gradle `:nmsCompatTest` task. Wire opt-in flag. Add CI gate config in `.github/workflows/` (or equivalent) covering patch/release/gradle.properties paths.

## Out-of-Scope Reminders

These follow-up specs explicitly NOT covered by this plan:

1. **UniverseSpigot config import (~200 keys)** — tracked from earlier brainstorm.
2. **Generic NMS shim API** — rejected during this brainstorm.
3. **WorldGuard, EssentialsX, LuckPerms** explicit testing — covered transitively (WorldGuard via FAWE) or unnecessary (permissions plugins do not touch NMS).
4. **Velocity proxy-side compat** — proxy-kick patch (former 9005) is runtime-gated; out of scope.
5. **Multi-plugin interaction tests** — separate follow-up spec.
6. **Performance benchmarks under plugin load** — separate follow-up spec.
