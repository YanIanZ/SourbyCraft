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

| What | Count | Where |
| --- | ---: | --- |
| Minecraft feature patches | 28 (hook 3, fix 1, logic 24) | `sourbycraft-server/minecraft-patches/features/` |
| Distinct pre-existing files they modify | 35 (44 file entries counting repeats and the 3 new files carried in 0002, 0005, 0016) | |
| Paper feature patches | 10 (hook 1, logic 9) | `sourbycraft-server/paper-patches/features/` |
| API feature patches | 0 | `sourbyapi/paper-patches/features/` |
| Engine baseline file patches (former Canvas/Folia) | 476 + 234 + 16 = **726** | `minecraft-patches/sources`, `paper-patches/files`, `sourbyapi/paper-patches/files` |

Conflict hot spots, meaning files that several feature patches touch:
`CraftScheduler.java` (Paper 0003, 0006–0010: six patches), `MinecraftServer.java`
(0013, 0019, 0024), `TickRegionScheduler.java` (0013, 0026), `TickRegions.java` (0002, 0013),
`ServerLevel.java` (0010, 0013) and `Entity.java` (0009, 0012). Every Minecraft file a feature
patch modifies is also patched by the engine baseline (28 of 28 Minecraft rows, 7 of 10 Paper
rows). A Paper change to one of those files can therefore break the baseline patch first and
the feature patch second.

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
   `2.0.0-beta.24`), and it must be the **same release** that the private SourbyPatcher
   `paper-toolchain` was built against. If Paper's new revision needs a newer paperweight, the
   change goes into SourbyPatcher first: bump its version, update
   `build-data/private-toolchain.lock.json` and `patcherSha256`/`patcherVersion` in
   `gradle.properties` (procedure in `docs/development/PRIVATE-TOOLCHAIN.md` and SourbyPatcher's
   own `AGENTS.md`). Gradle fails at settings time without the hash-verified jar in Maven Local.
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
   scan (patch 0025). Bridged legacy plugins load (`/plugins`, Paper features 0003–0010).
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
   and 0016 MOVE TO SOURBY SOURCE and 0006 and 0013 SPLIT for the same reason.
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

From the script's output on 2026-10-07. "Logic" here means the heuristic found behaviour
written inside an upstream file. It is **not** a judgement that the patch is wrong.
Converting any of these is real work. Each conversion needs its own regression test before
the patch changes, and the allocation patches 0007 and 0009–0012 have no dedicated tests yet
(`patch-classification.md`). The compile-boundary caveat recorded there (classes written
inside patches because of the package the upstream code imports) has to be checked per patch.

| Patch | +/− | Why it is logic | Hook conversion |
| --- | ---: | --- | --- |
| MC 0002 tick-thread count | 137/7 | carries `AuroraCpu` as a new file | **Candidate:** move the class to Sourby source, one call left in `TickRegions` (MOVE TO SOURBY SOURCE) |
| MC 0005 `SnapshotPathRegion` | 222/2 | carries the class | **Candidate:** move the class; hook stays in `PathNavigationRegion` (MOVE TO SOURBY SOURCE) |
| MC 0016 cross-region block test | 49/2 | carries `AuroraCommandErrors` | **Candidate:** move the class; the `ExecuteCommand` check stays (partial move) |
| MC 0006 async pathfinding | 141/1 | 70 code lines over five files | **Candidate:** split per `patch-classification.md`, then push scheduling into `perf/AsyncPath*` |
| MC 0013 tick metrics | 97/14 | 84 code lines over six files | **Candidate:** split telemetry from lifecycle. Most lines are already calls into `dev.iyanz.sourbycraft.perf` |
| MC 0021 storage traffic counters | 108/0 | 84 code lines in `RegionFileStorage` | **Candidate:** counters into an owned class, one call per I/O site |
| MC 0027 chunk-generation stage timing | 22/0 | 19 code lines | **Candidate:** timing into an owned class |
| MC 0024 private Intave lifecycle | 42/9 | three files, packet path | Candidate only after the private-native plan settles. Owned by that work |
| MC 0026 region watchdog off by default | 17/3 | property gate written into upstream | Possible: read the flag from an owned config class. Small gain |
| MC 0003, 0014, 0023 spawn and worldgen rules | 47/0, 25/3, 25/0 | gameplay decisions inline | Possible: move the predicate to an owned static method. The upstream `if` stays either way |
| Paper 0003, 0007 bridge admission and routing | 39/3, 43/1 | scheduler/provider logic in CraftBukkit | Possible: concentrate on `AuroraBridge` calls. `CraftScheduler` is the largest hot spot (six patches) |
| Paper 0002 branded bootstrap line | 37/6 | formatting inline | Branding rule: render the line in an owned class from `BuildInfo` |
| MC 0004, 0007, 0009–0012, 0015, 0017, 0018, 0022, 0028; Paper 0001, 0004, 0005, 0006, 0008, 0009; MC 0020 | small | they replace upstream statements in place (allocation removals, operand order, a gate, a removed registration) | **Not candidates.** A hook cannot replace an upstream statement. These stay as patches, or go upstream |

The classifier's limits: it reads only `+`/`-` lines. It cannot tell a gameplay rule from a
counter, and it uses fixed thresholds (≤ 10 added and ≤ 2 removed code lines plus a
`dev.iyanz` call for a hook). Paper 0008 (8 added, 3 removed, every line calling
`AuroraBridge`) is a near-hook just over the removed-line limit. The rule that fired is
printed per patch so a reviewer can disagree with it.
