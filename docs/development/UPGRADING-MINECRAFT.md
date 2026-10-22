# Upgrading Minecraft / Paper (`paperRef` bump)

The playbook for moving SourbyCraft to a new Paper revision, whether that is a routine Paper
update on the same Minecraft version or a new Minecraft version. Sources: `AGENTS.md`,
root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.github/workflows/build.yml`,
[`rebase-log.md`](../architecture/rebase-log.md) and the patch directory incident of 2026-10-06
in `AGENT-COORDINATION.md`.

Status: **PLANNED procedure, not yet exercised end to end.** No bump has been done with this
playbook. The last two rows in `rebase-log.md` (2026-10-05) predate it. Gradle task names
below come from the repository docs and the incident notes. They were **not** re-listed for
this document, because writing it ran no Gradle. Confirm them once with
`./gradlew tasks --all | grep -iE 'apply|rebuild|fixup'` before the first bump.

## The surface you are moving

Counted by `python3 scripts/patch_surface.py` on branch `26.2` at `1fe984ed` plus the working
tree on **2026-10-07**. Re-run the script for current numbers. These go stale with the next
patch.

> 2026-10-13: the old MC 0024 was removed by owner decision and MC 0025–0029 were renumbered to 0024–0028. The counts and hot spots in this section are the
> 2026-10-07 snapshot and predate that change; patch numbers in the procedure and in the
> hook-candidate table below use the new numbering. Dated check results keep the numbering of
> their date.
>
> Historical, as of 2026-10-07: the counts table below. Current file counts (2026-10-14): 29
> Minecraft and 14 Paper feature patches; re-run `patch_surface.py` for the rest.

| What | Count | Where |
| --- | ---: | --- |
| Minecraft feature patches | 28 (hook 3, fix 1, logic 24) | `sourbycraft-server/minecraft-patches/features/` |
| Distinct pre-existing files they modify | 35 (44 file entries counting repeats and the 3 new files carried in 0002, 0005, 0016) | |
| Paper feature patches | 10 (hook 1, logic 9) | `sourbycraft-server/paper-patches/features/` |
| API feature patches | 0 | `sourbyapi/paper-patches/features/` |
| Engine baseline file patches (former Canvas/Folia) | 472 + 234 + 16 = **722** | `minecraft-patches/sources`, `paper-patches/files`, `sourbyapi/paper-patches/files` |

Conflict hot spots, meaning files that several feature patches touch:
`CraftScheduler.java` (Paper 0003, 0006–0010: six patches; eight since 0011 and 0019, see the
Build 50 note below), `MinecraftServer.java`
(0013, 0019, and the since-removed old 0024), `TickRegionScheduler.java` (0013, old 0026, now 0025), `TickRegions.java` (0002, 0013),
`ServerLevel.java` (0010, 0013) and `Entity.java` (0009, 0012). Every Minecraft file a feature
patch modifies is also patched by the engine baseline (28 of 28 Minecraft rows, 7 of 10 Paper
rows). A Paper change to one of those files can therefore break the baseline patch first and
the feature patch second.

> Build 50 (2026-10-22, `patch_surface.py` on the hook-scheduler worktree at `6176d517`):
> `CraftScheduler.java` is still touched by eight Paper feature patches (0003, 0006, 0007, 0008,
> 0009, 0010, 0011, 0019), but every site is now one line calling `AuroraBridge` /
> `FoliaSchedulerCompatibility` (or `RegionRefusal` for 0019), plus 0003's `bridgeTask` adapter,
> whose methods are one-line accessors of package-private `CraftTask` state or one delegating
> call each. The inline bridge logic (the no-op cancels of 0006/0009, the draining
> `isCurrentlyRunning` tri-state and ID reservation of 0008, the worker merge, the pending-task
> cast, the body dispatch and the global-callback checks of 0007) moved to
> `bridge/AuroraBridge` and `bridge/FoliaSchedulerCompatibility` (`CraftSchedulerHooksTest`).
> No patch touches `CraftTask` or `CraftAsyncScheduler`. Net `CraftScheduler` diff against Paper
> (9 hunks either way): 68 added / 3 removed before, 30 added / 3 removed after; summed over the
> eight patches' `CraftScheduler` sections (lines a later patch rewrites count twice): 70/5 before,
> 33/6 after. A conflict on a Paper update is now on call lines; re-apply each line where its
> upstream method moved.

## 1. Pre-flight

Do all of this before touching `paperRef`.

1. **Check the patch surface.**

   ```bash
   python3 scripts/patch_surface.py --check-rebuild
   ```

   `--check` exits 1 when any patch file is untracked by git or a patch number is used twice.
   `--check-rebuild` also exits 1 when a `rebuild*Patches` run would **delete** a patch file
   because the materialized repository has no commit for it. Both must pass. The report's
   "What a rebuild*Patches run would do now" section lists deletions, additions and renumbering.

   On 2026-10-07 this check fails. Eight patch files are untracked (Minecraft 0024–0028 and
   Paper 0007, 0008, 0010). Minecraft 0024 and 0025 and Paper 0010 have no materialized commit,
   so a rebuild would delete them and renumber 0026–0028 to 0024–0026. That is the
   2026-10-06 incident waiting to repeat. Do not bump until the check is clean.

2. **Track every patch file.** A rebuild regenerates the whole directory from the materialized
   commits: it deletes files with no commit and renumbers the rest. Git is the only way back,
   so every patch file must be committed (or at least tracked) before any `rebuild*Patches`
   run. This applies to the bump too: commit the pre-bump patch set first, so `git diff` after
   the bump shows exactly what the bump changed.

3. **Start from a clean worktree.** `git status` must be clean apart from files you
   deliberately leave aside. You will want `git diff` and `git checkout -- <patch>` to mean
   one thing.

4. **Know what gets regenerated.** `applyAllPatches` rewrites these working copies, and none
   of them is committed:
   - `sourbycraft-server/src/minecraft/java`: its own git repository. Its commits after
     `sourbycraft File Patches` are the Minecraft feature patches. The parent
     `src/minecraft` resolves to the root repository, so run git commands inside `java/`.
   - `paper-server/`: its own repository. Feature commits follow `sourbycraft paperServer File Patches`.
   - `paper-api/`: same layout, after `sourbycraft paperApi File Patches`.

   Uncommitted edits in these trees are lost. Work you want to keep must already be a patch
   file in the root repository.

5. **Run both test suites green on the old ref** (see step 6), so a red result after the bump
   is caused by the bump. Known exception on 2026-10-07: `test_independence_policy.py` has two
   failures caused by patches 0026 and 0028 (`AGENT-COORDINATION.md`, "FOR CODEX"). Resolve or
   record them first.

## 2. The bump

1. **`gradle.properties` → `paperRef`.** This is the one upstream pin. Paper is the only
   upstream since 2026-10-05; the former `canvasRef` no longer exists. Note the old and new
   revisions and count the upstream commits (`git rev-list --count old..new` in a Paper
   checkout) for the rebase-log row.
2. **For a new Minecraft version**, also change `mcVersion`, `apiVersion` and `releaseVersion`
   in `gradle.properties` (and `codename` if the release gets one). `26.2` is also hard-coded on
   24 lines of `.github/workflows/build.yml` (branch filters, jar names), in branch names
   (`release/26.2-canvas`), in the Docker/README jar paths and in several `scripts/*.py`.
   `grep -rn '26\.2'` is the inventory. A Paper-only update on the same Minecraft version
   changes none of these.
3. **Paperweight version.** `settings.gradle.kts` pins `io.papermc.paperweight.core` (currently
   `2.0.0-beta.24`) via the `paperweightVersion` property, and it must be the **same release** that
   the private SourbyPatcher `paper-toolchain` was built against — the published
   `paper-toolchain-<v>.pom` and `.module` are SHA-256-pinned (`patcherPomSha256`/
   `patcherModuleSha256` in `gradle.properties`) and `scripts/private_toolchain.py` fails unless
   both declare exactly `paperweightVersion`. If Paper's new revision needs a newer paperweight,
   the change goes into SourbyPatcher first: bump its version, update
   `build-data/private-toolchain.lock.json` and `patcherSha256`/`patcherVersion` **plus the two
   metadata hashes and `paperweightVersion`** in `gradle.properties` (procedure in
   `docs/development/PRIVATE-TOOLCHAIN.md` and SourbyPatcher's own `AGENTS.md`). Gradle fails at
   settings time without the hash-verified jar, POM and module metadata in Maven Local.
4. **Access transformers:** `build-data/sourbycraft.at`, `build-data/paperServer.at`,
   `build-data/paperApi.at`. An AT entry naming a field or method that upstream renamed or
   removed fails the apply. Fix the entry, or delete it if nothing needs it any more.
5. **Library imports:** `build-data/dev-imports.txt` lists the leafpile classes (and any library
   files) that the patches use without patching. If a listed file moved or disappeared in the
   new library version, the import step fails.
6. **Metal codegen** is vendored at `Metal/` and generated by `scripts/setup_metal.sh`, the
   same as in CI. Re-run it after the bump.

## 3. `applyAllPatches` and reading failures

```bash
bash scripts/setup_metal.sh
./gradlew applyAllPatches --stacktrace 2>&1 | tee build/apply.log
```

Apply order and how each layer fails:

| Layer | Format | Failure looks like |
| --- | --- | --- |
| Build-script patches (`sourbycraft-server/build.gradle.kts.patch`, `sourbyapi/build.gradle.kts.patch`) | single-file patch | hunk failure on the generated build file. The 2026-08-30 bump dropped two hunks here |
| AT / imports | `build-data/*.at`, `dev-imports.txt` | unresolved member, or a missing file in the library jar |
| Engine baseline file patches (`minecraft-patches/sources`, `paper-patches/files`, `sourbyapi/paper-patches/files`) | diffpatch (`gitFilePatches = false`, applied through java-diff-utils) | rejected hunks reported per file |
| Feature patches (`*/features/`) | git-format, applied as one commit each | `git am` stops at the first patch that does not apply, in the materialized repository |

Rules for reading the log:

- **Fix the first failure first.** Feature patches apply in number order, and one failure
  stops the rest. Later "failures" are often not failures at all.
- A baseline file patch failing usually means Paper changed region-threading-adjacent code.
  In the 2026-08-30 bump, upstream had moved a file into its own region-threading patch. The
  SourbyCraft feature patch for that file (waypoint manager) was parked in `.skipped/`.
- Write down **every** patch file that fails or needs a rebuild **before** fixing anything. That
  list is the rebase-log number (step 8).
- Check whether the change now exists upstream. Patch 0008 (Projectile ticket typo) and patch
  0015 (landed arrow) are recorded as upstream candidates in `patch-classification.md`. When
  upstream contains the fix, delete the patch rather than porting it.

## 4. Fixing: edit the materialized repository, then rebuild

Never hand-edit a generated patch file into shape. Edit the code instead:

1. Resolve the failed patch in the materialized repository (`sourbycraft-server/src/minecraft/java`,
   `paper-server/` or `paper-api/`). For a feature patch, finish the stopped `git am` there,
   so the fix ends up in **that patch's commit**, not in a new commit on top. For a baseline
   file patch, edit the file in the tree.
2. **Before running any `rebuild*Patches`:**
   - every file in the patch directories must be tracked in the root repository
     (`python3 scripts/patch_surface.py --check-rebuild` passes);
   - the materialized repository must hold one commit per patch file, in patch order. The
     check reports "DELETES ..." for a patch file whose commit is missing.

   A rebuild regenerates the directory from those commits. It deletes patch files with no
   commit and renumbers the rest. This deleted four patch files on 2026-10-06.
3. Run the matching rebuild task, for example `rebuildMinecraftFeaturePatches`,
   `rebuildMinecraftSourcePatches` or `rebuildPaperServerPatches` (confirm the names with
   `./gradlew tasks --all`).
4. `git diff --stat` the patch directories. Expect only context and index-line churn in
   patches you did not touch. A deleted or renamed patch file you did not intend is a stop
   signal. Restore it from git before going further.
5. Run `python3 scripts/patch_surface.py --check-rebuild` again, then
   `./gradlew applyAllPatches` from clean, to prove the regenerated set applies on its own.

## 5. Build and the slim-jar signal

```bash
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar -PsourbyBuild=N
```

`slimServerJar` (root `build.gradle.kts`) strips the library directories listed in
`externalizeArtifactDirs` from the paperclip jar. It matches them by path prefix under
`META-INF/libraries/`. When Paper moves or renames a library, the task **fails** with
`stripped 0 libraries`. This is the intended signal: update the prefix list against the new
`META-INF/libraries.list`, and do not relax the check. A partial move does not fail. Compare
the slim jar's size with the last build (about 34 MiB) to catch it.

## 6. Test gates: both suites

Two independent suites. One green suite says nothing about the other. A policy violation once
reached `origin` because only the Gradle suite was run (2026-09-20, patch 0016).

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The Python suite carries the architecture policy (`test_independence_policy.py`,
`test_patch_policy.py`, `test_engine_policy.py`), including the pinned upstream default
changes (`guard-severity: LOG` and others). A bump that changes an upstream default shows up
there. CI also runs the clip probes, `verify_build_identity.py` and four boot tests
(`.github/workflows/build.yml`). Push to the working branch and let that job run. It is the
only place all gates run together.

## 7. Version-sensitive commands in scripts

The harness drives the server with vanilla console commands, and their syntax changes between
Minecraft versions. 26.2 renamed gamerules to snake_case (`doMobSpawning` → `spawn_mobs`,
`doDaylightCycle` → `advance_time`, `doWeatherCycle` → `advance_weather`; see
`docs/BASELINE.md`, "Version-sensitive commands"). Over the console API a rejected command can
fail **silently**. After a Minecraft version change, check at least:

- `scripts/baseline_workloads.py`. `run_baseline.py` fails the run on a rejected setup
  command. Fix the command; `--allow-command-errors` is for diagnosis only.
- `scripts/verify_persistence.py` (`PROBE_GAMERULE`, the `time query` form) and
  `scripts/verify_crash.py`.
- `scripts/baseline_client.py`. The headless client speaks a fixed protocol version observed
  with `scripts/protocol_probe.py`, so a new Minecraft version needs a new probe.

## 8. Record the bump

Add the row to [`rebase-log.md`](../architecture/rebase-log.md) **in the same commit** that
moves `paperRef`: date, commit, old → new ref, upstream commit count, the number of patch
files that needed a human (from step 3's list), and one clause per file. A patch parked in
`.skipped/` stays listed until it is ported or deliberately dropped. Also update
`patch-classification.md` and the doc references to any renumbered patch.

## 9. Verify on Sourby Demo

Sourby Demo is the Pterodactyl test server. Its quirks are in the operator notes:
`online-mode=true`, no `-D` JVM flags, EssentialsX overrides `time`/`kill`, and it keeps the
old jar as `SourbyCraft-slim.<tag>.jar`. After the CI jar passes:

1. Swap the jar, keeping the previous one, and wait for panel state `running`. Do not wait
   for `Done (`: `latest.log` keeps the previous boot's line until rotation.
2. `/ver` reports the SourbyCraft identity (`Build N — <codename>`) with no upstream-platform
   suffix.
3. Existing world loads and persists: `scripts/verify_persistence_panel.py`.
4. Plugins: ViaVersion/ViaBackwards/ProtocolLib provisioning still runs before the plugin
   scan (patch 0024). Bridged legacy plugins load (`/plugins`, Paper features 0003 and 0006–0012).
5. New-terrain generation runs without "Detected unsafe terrain read during worldgen"
   (patch 0023's case).
6. Clean `stop`.

Bot tests on the demo use guarded mode (whitelist-only offline mode, settings restored) and
must never run while a player is online.

## Keeping the surface small

Every line inside an upstream file is a line that can conflict on the next bump. The rules:

1. **Logic lives in `dev.iyanz.*` classes. Upstream gets a thin hook.** Patch 0001 (one call into
   `SourbyCraftBootstrap.init()`) is the model, and `patches.md` calls it "Exemplary thin
   hook". Its other KEEP verdicts are exemplary integrations of the same shape:
   `FoliaSparkPlugin` ("three lines, all delegation") and 0019 (one call). The REWORK verdict
   on the `isInWall` patch (now 0012), which "needed three follow-up fixes after landing", is
   what logic inside an upstream method costs. `patch-classification.md` gives 0002, 0005
   and 0016 MOVE TO SOURBY SOURCE and 0006 and 0013 SPLIT for the same reason (both split on 2026-10-22).
2. **Prefer `paper-patches` over `minecraft-patches`** when the Bukkit/CraftBukkit layer is
   enough. Paper's layer changes less between Minecraft versions than `net.minecraft` does.
   This is a tendency, not a measured fact for this repository.
3. **Never add a patch only for branding** when a resource, config value or owned class can
   do it. The branding patches already present are `paper` 0002 (bootstrap line), 0004
   (`log4j2.xml` logger prefix) and Minecraft 0020 (`/canvas` tree). Each is a candidate for
   that test.
4. **Fix upstream bugs upstream.** 0008 and 0015 are recorded upstream candidates. Each one
   accepted upstream is one less patch to port.
5. **Run `patch_surface.py` on the change.** A new patch classified `logic`, or one that adds
   a row to the hot-spot tables, should be a deliberate choice. Say why in the patch message.

### Current `logic` patches as hook candidates

From the script's output on 2026-10-07 (patch numbers updated to the 2026-10-13 numbering). "Logic" here means the heuristic found behaviour
written inside an upstream file. It is **not** a judgement that the patch is wrong.
Converting any of these is real work. Each conversion needs its own regression test before
the patch changes; the allocation patches 0009–0012 have their contract tests listed in `patch-classification.md` (0010 since 2026-10-20: `RandomTickPosEscapeTest`; 0007 has `GliderSlotsTest` and is a hook since 2026-10-20)
(`patch-classification.md`). The compile-boundary caveat recorded there (classes written
inside patches because of the package the upstream code imports) has to be checked per patch.

| Patch | +/− | Why it is logic | Hook conversion |
| --- | ---: | --- | --- |
| MC 0002 tick-thread count | 24/7 | replaces the upstream default formula (7 removed lines) | **Done 2026-10-20:** `AuroraCpu` moved to Sourby source; one call plus the log line left in `TickRegions`. Stays `logic` only because it removes upstream lines |
| MC 0005 `SnapshotPathRegion` | 222/2 | carries the class | **Candidate:** move the class; hook stays in `PathNavigationRegion` (MOVE TO SOURBY SOURCE) |
| MC 0016 cross-region block test | 18/2 | the `ExecuteCommand` ownership check | **Done 2026-10-20:** `AuroraCommandErrors` moved to Sourby source; the check stays and the patch is now a hook |
| MC 0006 async pathfinding | 44/0 (was 181/1) | 27 code lines, all in `PathNavigation`: one flag-gated call plus the seams that touch private state (pending flag, two lazy reads, isolated solver, result write) | **Split 2026-10-22:** offload logic and the refusal list moved to `perf/AsyncPathRecompute` (`AsyncPathRecomputeTest`); `Mob`, `Sniffer`, `AmphibiousPathNavigation` hunks removed; `PathfindingContext` guard -> MC 0082 (2/1); 0039/0042/0047 lost the lines that followed the kick. Still `logic` by size: the seams would need access transformers to leave `PathNavigation` |
| MC 0013 tick metrics | 9/11 (was 97/14) | removed lines are one-for-one replacements of upstream statements (`final` modifier, five window constructors, five per-window inserts folded into one call) | **Split 2026-10-22:** 0013 keeps the handle wiring; `TickData`'s shared-owner logic moved to `perf/RegionTickDataView`; lifecycle calls -> MC 0079 (hook, 8/0), percentile-order fix -> MC 0080 (`fix`), bootstrap close -> MC 0081 (hook, 1/0). 0013 cannot reach hook shape without keeping five per-window collectors: a hook cannot replace an upstream statement |
| MC 0021 storage traffic counters + AWF routing | 9/0 | One `RegionFileStorageHooks` delegation at each affected I/O/attachment site, plus the AWF field; upstream fallback and locks remain | **Extracted 2026-10-22:** AWF routing, attachment, counters and codec handoff live in the ledgered `awf/RegionFileStorageHooks` adapter; `AwfChunkNbt` remains the codec. `patch_surface` = hook. Scan routing is separate in MC 0053 |
| MC 0053 AWF structure scans | 1/0 | One `RegionFileStorageHooks.scanChunk` delegation | Owned adapter calls `AwfRegionStorage.scanRead` once, with the existing refusal, deletion, parse and region-file fallback behavior; `patch_surface` = hook |
| MC 0026 chunk-generation stage timing | 16/0 | 13 code lines (two fields, the `run()` try/finally split, three calls) | **Partly done 2026-10-20:** stamps and the record decision moved to `ChunkGenerationMetrics.stampQueued`/`stampRunStart`/`runFinished` (`ChunkGenerationMetricsTest`); still `logic` because the try/finally wrapper around `runTask()` has to stay in the task |
| MC 0025 region watchdog off by default | 17/3 | property gate written into upstream | Possible: read the flag from an owned config class. Small gain |
| MC 0003, 0014, 0023 spawn and worldgen rules | 47/0, 25/3, 25/0 | gameplay decisions inline | Possible: move the predicate to an owned static method. The upstream `if` stays either way |
| Paper 0003, 0007 bridge admission and routing | 39/3, 43/1 (Build 50: 23/3, 40/1) | scheduler/provider logic in CraftBukkit | **Done for `CraftScheduler` (Build 50, 2026-10-22):** every `CraftScheduler` site of 0003 and 0006–0011 is one call into `AuroraBridge`/`FoliaSchedulerCompatibility` (`CraftSchedulerHooksTest`); 0006, 0008, 0009 are hooks now. 0003 stays `logic` (20 code lines: the three loader gates, six one-line sites and the 11-line `bridgeTask` adapter that has to read package-private `CraftTask` state). 0007 stays `logic` because of its LightingLuminol port in `FoliaGlobalRegionScheduler` and `PaperPluginInstanceManager` (not changed); its `CraftScheduler` part is +2/−1 |
| Paper 0002 branded bootstrap line | 37/6 | formatting inline | Branding rule: render the line in an owned class from `BuildInfo` |
| MC 0004, 0009–0012, 0015, 0017, 0018, 0022, 0027; Paper 0001, 0004, 0005; MC 0020 (Paper 0006, 0008, 0009 became hooks in Build 50) | small | they replace upstream statements in place (allocation removals, operand order, a gate, a removed registration) | **Not candidates.** A hook cannot replace an upstream statement. These stay as patches, or go upstream |

The classifier's limits: it reads only `+`/`-` lines. It cannot tell a gameplay rule from a
counter, and it uses fixed thresholds (≤ 10 added and ≤ 2 removed code lines plus a
`dev.iyanz` call for a hook). Paper 0008 (8 added, 3 removed, every line calling
`AuroraBridge`) was a near-hook just over the removed-line limit; since Build 50 it is a hook
(2 added, 2 removed). The rule that fired is
printed per patch so a reviewer can disagree with it.

## Rebasing the engine baseline onto a new upstream

This offline tooling is a planning aid for the Build 50 / Minecraft 26.3 port, not
an end-to-end qualified upgrade. The manifest's 722 baseline patches are the
inventory; feature patches remain a separate layer. Markers are evidence, not
proof of authorship. Canvas may never ship 26.3, so canvas-origin files are ours
to port when there is no suitable new upstream implementation.

1. Run `python3 scripts/engine_baseline_origin.py --write`, then `--check`.
   Review `build-data/engine-baseline-origin.json`: marker/package classifications,
   confidence, import hashes and subsequent commits identify porting candidates.
   Files renamed since import have a null same-path import hash; `renamed_from`
   records detected ancestry. Manifest/directory differences are reported.
2. Prepare old and new Canvas/Folia/Paper materialized source trees locally.
   No command here downloads upstreams. Use repository-shaped trees with Minecraft
   under `sourbycraft-server/src/minecraft/java/`, Paper under `paper-server/`, and
   API under `paper-api/`; standalone source roots are also accepted as upstream
   inputs. Current files are read from this repository's materialized trees
   (`--root` selects another repository). Missing materialized files require review.

   ```bash
   python3 scripts/upstream_rebase.py plan --old-upstream /local/old-tree --new-upstream /local/new-tree --out /local/rebase-plan.json
   ```

   The default ordered literal renames are `io.canvasmc.canvas` to
   `dev.iyanz.aurora.engine`, then remaining `io.canvasmc` to
   `dev.iyanz.sourbycraft.api`. They transform upstream contents and corresponding
   package paths before comparison. `--rename-map file.json` replaces this list
   with an ordered JSON array, for example `[["old.package", "new.package"]]`.
   Text replacement is deliberately literal, not a Java-aware refactor.
3. Review the plan and every MANUAL path (unknown origin, missing counterparts,
   or merge conflicts). VERBATIM takes the transformed new upstream when ours
   matches the transformed old upstream; MERGE3_CLEAN embeds a clean Git 3-way
   merge. Review DELETED entries carefully: applying removes the target even if
   it contains local changes. ADDED entries are informational and never applied.
   A clean merge does not prove correct runtime semantics or region ownership.
4. Preview, then apply the reviewed plan to a repository-shaped materialized tree:

   ```bash
   python3 scripts/upstream_rebase.py apply --plan /local/rebase-plan.json --target /local/target-tree
   python3 scripts/upstream_rebase.py apply --plan /local/rebase-plan.json --target /local/target-tree --yes
   ```

   The first command refuses and lists proposed writes/deletions. The target
   must still match the recorded current-file hashes. Apply leaves MANUAL and
   ADDED entries alone and refuses protected paths or symlinks. It never edits
   patch files or the baseline manifest.
5. Resolve remaining ports by editing patch files or the appropriate materialized
   repository. Keep SourbyCraft feature changes in feature patches. Follow the
   rebuild safeguards above, commit materialized changes as appropriate, and run
   the matching Gradle `rebuild*Patches` tasks yourself. Inspect the regenerated
   patch diff and verify that it applies; the Python tools never run Gradle.
6. After deliberate baseline changes pass review, run
   `python3 scripts/baseline_policy.py --write-manifest`, then regenerate with
   `python3 scripts/engine_baseline_origin.py --write` and verify with `--check`.
   `--check` ignores `change_commits` (a commit cannot list its own hash, so that
   field is a snapshot as of the last `--write`) and needs the import commit
   `e20df47b` in the clone; the PR job's shallow checkout skips the repository check.
   Run the normal upgrade correctness/build/boot gates separately. None of these
   classifications certifies a port or a release.
