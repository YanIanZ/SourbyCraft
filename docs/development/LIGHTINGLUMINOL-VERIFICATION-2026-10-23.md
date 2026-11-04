# LightingLuminol continuation verification — 2026-10-23

Local functional evidence. Neither branch is qualified or STABLE. No performance comparison, production
plugin acceptance, real-player portal test, soak, published release or panel/VPS deployment is claimed.

## Tested revisions and scope

| Candidate | Tested revision | Result |
| --- | --- | --- |
| `feat/bridge-relaxed-thread-checks` | `f44550cc30cd2c10eaa28a2268036571f0c0a6d2` | Owned SourbyCraft + Aurora server suite: 2,493 tests, 0 failures/errors, 6 skipped; API: 585/0/2 skipped; test-plugin: 21/0/0 skipped |
| `hotfix/26.2-r48.3` core | `ab70880f` | Full server suite: 12,053 tests, 0 failures/errors, 29 skipped; API: 585/0/2 skipped; test-plugin: 14/0/0 skipped |
| Hotfix final code | `897075f7df62dcd155e75c9483fcffec051d67c1` | Adds only the config diagnostic row after the full run; 31 focused config/teleport tests, 0 failures/errors/skips; rebuilt launcher and boots below |
| Python suite | Both final code revisions above | Feature: 720 tests OK; hotfix: 648 tests OK, 1 skipped |
| Patch policies | Both revisions | Patch policy, baseline policy, inherited guard policy, `patch_surface.py --check`: passed; baseline unchanged |
| Packaging | Both revisions | Server build identity/source provenance and launcher API/licence checks passed; `applyAllPatches` and `slimServerJar` passed |

The feature branch still identifies as Build 48 EXP. Its configuration registry changes are intended for Build 49;
this work did not bump it to 49. Hotfix identifies as Build 48.3 REL. Test totals include skips; skips are not passes.
An earlier full feature-server run (12,153 tests) preceded the final portal correction and is not final-revision evidence.
The feature suite intentionally uses `SourbyCraftTestSuite` to cover owned code, rather than claiming another final full Paper run.

Compiler and test launcher: Eclipse Temurin 25.0.5+8-LTS, macOS aarch64. Terminal test precondition:
`JAVA_TOOL_OPTIONS='-Dterminal.ansi=false -Dterminal.jline=false'`. Without it, seven startup-layout tests
failed because terminal ANSI detection selected another format; no assertions or production console behavior were changed.

The suite commands were run through `scripts/gradle_locked.sh` with `-PincludeTestPlugin=true`:

```bash
# Full hotfix suites, followed by legacy fixture packaging and launcher packaging:
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test :legacy-test-plugin:jar slimServerJar -PsourbyChannel=REL
# Feature: same suites/tasks, with a local init script restricting the server test includes to:
# **/SourbyCraftTestSuite.class (set in gradle.projectsEvaluated).
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/patch_policy.py
python3 scripts/baseline_policy.py
python3 scripts/inherited_guard_policy.py
python3 scripts/patch_surface.py --check
```

## Hotfix isolated runtime checks

Harness: `scripts/agent_testserver.py`; flat world, 2,048 MiB heap, 2 active processors, 600-second lifetime cap,
`aurora.cpu.cores = 2`, async pathfinding disabled. These are functional fixture settings, not benchmark results.
All named runs below exited 0 on requested stop, without a kill, and released their harness locks.

| Run | Observation |
| --- | --- |
| `codex-r483-enforced` | 31 required markers; scheduler/context/async-chunk/current-tick checks pass; four unsafe probes refused; no unexpected refusal type or quarantine. Explicit `quarantine-after = 100` keeps this refusal control from terminating later probes. |
| `codex-r483-relaxed` | `thread-checks` omitted to exercise SAFE's owner default; 31 required markers; sweep, foreign-region block write, global unloaded-chunk load and joined async teleport all report OK. No `_WRONG` or quarantine. `/ver`, `/sourbycraft config` and `/perf plugins` show the effective mode. |
| Same relaxed run, AWF FILE | AWF INCREMENTAL, commit interval 5s; storage activity and no failures observed. `save-all flush` logs the fork's `Saved global data`/`Saving 1 regions`, then clean shutdown confirms `AWF-SHUTDOWN-OK`. |
| `codex-r483-persistread` | Copies the relaxed run's world and Aurora config; only a native, read-only fixture loads chunk [9,9] and reads on its region scheduler. `PERSIST_READ_OK actual=GLOWSTONE at=152,78,152` proves that one written block survives reopen without the writing plugin. Clean AWF shutdown. |

An initial reopen check using console `execute if block` was refused with `Cannot test a block cross-region`.
A console `fill` check was also unsuitable. These failed checks are not persistence-failure evidence; the
region-scheduled reader above resolves the check. A first save-command assertion expected vanilla's
`Saved the game`; the fork emits different messages. That assertion failed, and the actual save/shutdown markers
were checked separately. Nothing here establishes general persistence safety under relaxed concurrent access.

The feature launcher (`codex-luminol-main`, Build 48 EXP at `f44550cc`) also passed all 31 default-relaxed
fixture markers, displayed relaxed/unqualified in `/sourbycraft config`, and stopped with exit 0 and released
locks. Same heap/CPU/flat fixture constraints; record: `boot-main-final.json`.

## Evidence and artifact

Local evidence root: `/Volumes/Ext/DevCache/agent-skills/cache/SourbyCraft/luminol-resume-20261023/`.
Raw server logs and `run.json`: `/Volumes/Ext/Sourby/agent-testservers/<run>/`.
Key records: `main-final-runtime.log`, `main-final-java-results.json`, `hotfix-final-build.log`,
`hotfix-full-java-results.json`, `hotfix-final-diagnostics.log`, `hotfix-tested-artifact.json`,
`main-python-final.log`, `hotfix-python-final.log`, `main-final-policies.log`, `hotfix-final-policies.log`,
`main-identity-final.log`, `hotfix-identity-tested.log`, both mode runs' `bridge-check.json`,
`awf-reopen-result.json`, and `persistence-reader/PersistRead.java`.

The tested hotfix launcher belongs to code revision `897075f7df62dcd155e75c9483fcffec051d67c1`:

```text
SHA-256 263b71b230fd73be6ebfbf695bdc77bd3aa7316d8eda35fe66cd61f7a83d8c5a
```

Documentation-only descendants do not change that artifact's embedded revision. Verify its identity against
that tested revision, rather than pretending it was built from a later documentation commit.
The candidate, checksum and concise manifest are retained locally under `release/candidates/48.3/` in the hotfix
worktree. They are not a published release. Pushing `hotfix/26.2-r48.3` can trigger automatic publication through
`.github/workflows/build.yml`; that branch has not been pushed as part of this preparation.

## Status 2026-10-25 (PR #62)

- The relaxed thread checks verified here shipped from `v26.2-r49` (`guards/ThreadChecks` is first in that tag). No
  `v26.2-r48.3` tag exists, so the hotfix candidate above was never published. The paragraph on pushing
  `hotfix/26.2-r48.3` still matches the workflow: its `release` job runs for pushes to `26.2` and to
  `hotfix/26.2-r*` branches and needs both the `build` and the `rebuild` job.
- PR #62 (Build 50 branch; code written and statically reviewed, not built, not tested by the owner) changed
  behaviour these runs covered: relaxed-hit log lines are rate-limited by base reason with bounded memory,
  `TickGuard`'s LOG-mode line is rate-limited and off-tick-thread hits are counted once, repeating bridged async
  timers keep at most one queued run and skip (not cancel) an overlap over `max-running-async-tasks-per-plugin`,
  and MC 0084 again refuses the owning thread a reentrant entity add, remove or status change. The 31-marker
  results above stay as Build 48 evidence for revisions `f44550cc` and `897075f7`; on the PR #62 tree they are
  NEEDS RETEST. See [aurora-plugin-bridge.md](../architecture/aurora-plugin-bridge.md).

## Remaining owner acceptance

Real plugins and players (including portal cancel/redirect and cross-world movement), representative Epic Skyblock
flows, sustained soak and broader AWF concurrency remain open. The regression tests for portal placement,
client-dimension correction, rotation/metadata, joined motion flags and early-settings deadlock provide structural
or unit evidence; they do not replace those gates. Bridge mode defaults to OFF. SAFE's RELAXED/BRIDGED ownership
modes remain unsafe and unqualified; settings and the teleport-event escape hatch require restart.
