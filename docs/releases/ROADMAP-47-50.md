# SourbyCraft 26.2 release roadmap — Builds 47 to 50

Current Build 50 closure: [BUILD-50-READINESS.md](BUILD-50-READINESS.md). The dated allocations below are historical; they do not close current code or evidence gaps.

Status: PLAN (written 2026-10-21 from the tree at `c0ab0cc4` plus uncommitted work; nothing here is
evidence that an item is done). Owner decision 2026-10-21, recorded in [docs/TODO.md](../TODO.md)
*Keputusan owner*: "kita harus membagi menjadi 4 update, versi 47, 48 penyempurnaan, 49 full
stable, 50 pengoptimalan".

| Build | Theme | Channel | Gate document |
| --- | --- | --- | --- |
| 47 — Aurora Nexus | ship what is done | full release (`release=true`), not STABLE, unsigned (owner 2026-10-21) | [26.2-build-47-aurora-nexus.md](26.2-build-47-aurora-nexus.md) |
| 48 — codename TBD (owner) | refinement (*penyempurnaan*) | prerelease (`release=pre`) | [26.2-build-48.md](26.2-build-48.md) |
| 49 — codename TBD (owner) | full stable | STABLE (`release=true`) | [26.2-build-49.md](26.2-build-49.md) |
| 50 — Aurora Nexus | optimization (*pengoptimalan*) | per its gates | [26.2-build-50-aurora-nexus.md](26.2-build-50-aurora-nexus.md) |

Owner decision 2026-10-24: the next Minecraft line (**26.3**) starts at **Build 100** with
codename **Aurora Forge** (`aurora-forge`); builds 50–99 are therefore reserved for 26.2.
`scripts/release_version.py` refuses a 26.3 build below 100, a 26.2 build at 100 or above, and a
26.3 release whose codename is not `aurora-forge`.

Rules that apply to every build:

- Status words, evidence and performance claims follow
  [docs/DEVELOPMENT.md](../DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy).
  A gate is PASS only when its evidence covers the tree being released.
- Build 47 is published with `release=true` (owner decision 2026-10-21) but is not labelled
  STABLE; Build 48 is planned as a prerelease (`release=pre`); Build 49 is the first build
  declared STABLE.
- An item is allocated to exactly one build. When a later build depends on an earlier one, that
  is listed under *Dependencies across builds*, not by listing the item twice.
- References: `docs/TODO.md:N` is the line of the item's checkbox in `docs/TODO.md` as read on 2026-10-21
  (lines move when docs/TODO.md is edited; the quoted title is what identifies it). Board ids are from
  `.board-queue.json`; the full id→build map was handed to the PM to apply. Many board tasks are
  copies of the same item imported from several source documents; they are grouped under the
  item they duplicate.
- Items marked **DECISION** need an owner decision before the build that owns them can close.

---

## Build 47 — Aurora Nexus (ship what is done)

**Theme.** Publish the current feature set as a full release (`release=true`, owner decision
2026-10-21), honestly listing the gates that did not pass; not labelled STABLE, unsigned. No new
work enters 47; anything committed after the tag commit belongs to 48. Tag commit candidate:
`origin/26.2` head `14e60da6`, or the PM's commit on top of it that only sets `release=true` and
adds the release docs.

**Entry.** Feature set closed (it has been since 2026-10-07); latest green CI on the branch head:
run 39456620331 on `c0ab0cc4` (2026-10-20).

**Exit (concrete gates).**

| Gate | Evidence type |
| --- | --- |
| Tag commit chosen; working-tree work in flight for 48 (startup log, owned command blocks, guard ledger) is either not in it or is committed and covered by the CI run below | `git log` of the tag commit, `AGENT-COORDINATION.md` entry |
| Green CI on the exact tag commit: compile, Java + Python suites, boots, shutdown, `patch_surface.py --check`, `verify_build_identity.py --commit $GITHUB_SHA` with a clean (non-`-dirty`) identity, SBOM step, offline-grep step | CI run id + step list |
| `patch_surface.py --check-rebuild` on the tag commit: no renumbering, no untracked patches | command output in the release doc |
| Claims audit at the tag commit: README, release doc, SPEC §161, `qualification-readiness.md` agree (OBS-10) and contain measured claims only | dated audit note in the release doc |
| EDF patch still parked, not built | `minecraft-patches/parked/` listing; patch absent from `features/` |
| Build 47 release doc updated: full release, not STABLE, unsigned; *What did not pass and where it goes* points to 49/50 (done 2026-10-21) | doc diff |
| Push CI green on the tag commit (39477522388 on `14e60da6` was running on 2026-10-21) with `release=true` (releases publish from `26.2`, owner decision 2026-10-21) | CI run id; branch ref |

**Out of scope.** Every stability gate that needs a soak, real clients, a stable host or an owner
decision (moved to 49); every certified baseline and optimization (moved to 50); new features.

**DECISION — resolved 2026-10-21 (owner).** Build 47 ships as a **full release**
(`release=true`). The STABLE label stays reserved for Build 49. The release notes state which
gates did not pass (region ownership, plugin compatibility partial, stress/soak, cache corruption
partial, licence open) and that they move to 49/50; the release is unsigned because SEC-2 is not
configured (the workflow allows that).

**Allocated items.**

| Item | Reference | Board |
| --- | --- | --- |
| EDF patch stays parked; release without it (owner 2026-10-07) | docs/TODO.md:39 | task_3882d2ebf2b4f45a |
| Re-audit measured claims in README/release notes at the tag commit | docs/TODO.md:419; DEVELOPMENT-TASKS.md:252 | task_e4ef1b04c1e39a79 |
| OBS-9 CI gates: evidence from a CI run — `PR checks` first green run 39477530705 on `14e60da6` (2026-10-20) | docs/TODO.md:487 | task_912dd35a61a064cd |
| OBS-10 consistency pass release doc / SPEC / README at tagging | docs/TODO.md:493 | task_27fc2bfbcf8aeacd |
| This plan | — | task_18e9b47280fa745b |

---

## Build 48 — refinement (*penyempurnaan*)

**Theme.** Finish and polish what exists: clean startup output, replace inherited Canvas/Folia
hard guards with SourbyCraft-owned decisions, close remaining audit refinements, make commands,
UX and docs consistent, fix tooling, and the bridge compatibility fixes still waiting for a
server run. No new subsystems.

**Entry.** Build 47 tagged.

**Exit (concrete gates).**

| Gate | Evidence type |
| --- | --- |
| Startup log boot→`Done` clean: no raw ANSI in non-terminal logs, no `dev.iyanz.*`/`io.canvasmc.*` logger names, repeated lines collapsed | CI boot log excerpt + log4j plugin tests + agent test-server log |
| Inherited guard ledger: every Canvas/Folia hard guard inventoried, each with an owned decision (keep / replace / remove), pinned by a policy script in CI | `docs/architecture/inherited-guards.md`, policy test output in CI |
| Owned command blocks replace the Canvas `if (true)` disable, behind a flag, region-safe | unit tests + agent test-server run + guard-ledger row |
| Bridge compatibility fixes exercised on a server (SSB2 adapter, LightingLuminol port, same-instance re-enable) | boot markers in CI or agent test-server run ids |
| Command/HUD surface consistent: `/tps`, `/mspt`, `/perf`, HUD bars against Sourby metrics; Spark viewer shows SourbyCraft config | agent test-server transcripts; screenshots/transcript with a real client for the bars |
| Docs consistent with code: stale TODO checkboxes ticked or reworded, release doc current | doc diff + claims audit |
| Green CI on the tag commit, clean build identity | CI run id |

**Out of scope.** Soaks, persistence/crash qualification, security activation, reproducibility
(49); baselines, profiles, optimizations, async pathfinding default (50); new storage backends or
architecture programs (Later).

**Allocated items.**

*Owner items 2026-10-21 (in progress)*

| Item | Reference | Board |
| --- | --- | --- |
| Startup log cleanup boot→`Done` (Paper feature 0016, log4j plugins, `BootLog`) | owner 2026-10-21; `AGENT-COORDINATION.md` opus-startuplog | task_2841828a2e9f066a, review task_a9a9c6d45ffceb1c |
| TickRegions "Initialised EDF Folia scheduler" log wording → SourbyCraft-owned — done in worktree `opus-gate48-log` (MC 0075, `Initialised Aurora region scheduler (EDF) ...`; [evidence](../development/build48-log-and-commands-2026-10-22.md)), not yet merged | board | task_d2d28a6f780c9b3d |
| Inherited guard ledger (inventory, owned decision per guard, policy pins). Includes the ENG-11 behaviour decision (`execute if/unless … run` refusal in MC 0016, docs/TODO.md:613, documented only) — **DECISION per guard** | owner 2026-10-21 | task_73a1c6ef0c442449 |
| SourbyCraft-owned region-safe command blocks (MC feature 0055, flag) — **DECISION**: default on/off | owner 2026-10-21 | task_ab3bda9af0b220f4 |

*Bridge compatibility fixes*

| Item | Reference | Board |
| --- | --- | --- |
| SuperiorSkyblock2 2026.3 spawn adapter: server run with the real jar | docs/TODO.md:387 | — |
| LightingLuminol/Luminol global-scheduler port: acceptance cases on a server — board marks it owner-decision | docs/TODO.md:395 | task_329142c70e5e3bec |
| Bridge capacity upgrade: runtime proof of same-instance re-enable (Paper 0012) | docs/TODO.md:401 | task_4813cda071e87608 |
| Fair bridged I/O lane: wire `FairLane` into `BRIDGE_IO` — **DECISION**: setting and weights | docs/TODO.md:722 | — |

*UX, commands, observability polish*

| Item | Reference | Board |
| --- | --- | --- |
| `/tps`, `/mspt`, `/perf`, `/perfbar`, `/tpsbar`, `/rambar` unified with Sourby metrics — validation (bars need a real client) | docs/TODO.md:344; DEVELOPMENT-TASKS.md:69-75 | task_d4041de63b0b943a, task_9a9ecd24cb8988e5, task_e3a7ee2684706bc8 |
| SourbyCraft/Aurora config renders correctly in the Spark web viewer; Spark Aurora metadata | docs/TODO.md:352; AURORA-ROADMAP.md:82 | task_4aefbb3aa94798c9 |
| Expose region context to profiles where supported | DEVELOPMENT-TASKS.md:103 | task_c7b3c7df5a637f4e |
| AWF dirty-age metric in `/perf awf` (byte-triggered commit is already opt-in) | docs/TODO.md:711 | — |
| ASP→AWF port review leftovers (B76: PDC dropped by `.awf` conversion; service wiring untested on a server) | docs/TODO.md:687 | — |

*Independence and migration refinements*

| Item | Reference | Board |
| --- | --- | --- |
| M-1 identity: crash reports from `BuildInfo`, profiler identity; source identity cleanup | docs/TODO.md:527; AURORA-ROADMAP.md:83 | task_5135dd7a47e1fe90, task_6801badcef4b6f5d |
| M-2 isolation: move `SnapshotPathRegion` out of 0005, split 0006/0013, AWF branch in 0021; isolate the Canvas Spark bridge — **DECISION**: adapter vs fork for the Spark part | docs/TODO.md:529; DEVELOPMENT-TASKS.md:199, 217; AURORA-ROADMAP.md:87 | task_77d34473082b955a, task_ef25c196aa768279, task_439f5d43873f5adf |
| Keep implementation in Sourby-owned source; regression tests before a patch is removed (0009–0012 still have none) | docs/TODO.md:581 | — |
| Release branch name — RESOLVED 2026-10-21: releases publish from `26.2` | docs/TODO.md:548 | task_e8fd9c62594cb59b |

*Tooling and harness*

| Item | Reference | Board |
| --- | --- | --- |
| Private toolchain dependency update (Clip 3.1.1 / Patcher 3.1.0 candidates: Gradle 9.8.0, JUnit 6.1.3): push, release, re-pin — **DECISION** (board: owner-decision) | docs/TODO.md:594 | task_78a057480086e8bb |
| `agent_worktree` apply-state + paperweight cache copy (in review) | board | task_2c3e9e5dc339aa7d, task_0e0f3bae3fa31234, review task_4f4b8c84bf7700f9 |
| `agent_testserver --config-file` review; per-command output correlation | board | task_248d736c39ea7b66, task_55149b165f52d435 |

*Docs consistency (stale checkboxes — implemented, the box was never ticked)*

| Item | Reference | Board |
| --- | --- | --- |
| "`MobPathSnapshot` immutable for async pathfinding" — implemented as MC 0039 (ENG-8, docs/TODO.md:448) | docs/TODO.md:726 | — |

---

## Build 49 — full stable

**Theme.** Every stability and correctness gate passes on the Build 49 tree, then `release=true`.
No new features and no optimization work; a fix is allowed only when a gate finds a defect.

**Entry.** Build 48 tagged; its guard-ledger decisions and command-block patch landed, so the
soaks below exercise the final code. Owner decisions listed for 49 taken or explicitly scheduled.

**Exit (concrete gates).**

| Gate | Evidence type |
| --- | --- |
| Persistence and crash recovery on the 49 tree, region files and AWF, including with clients | `verify_persistence*.py` / `verify_crash*.py` run ids, jar hash, host |
| Region ownership: watchdog acceptance cases 1–4, no ownership violation over a soak, unload-with-queued-tasks fixture in CI | test-server runs + CI marker + soak log |
| Multi-hour (2 h+) soak with real clients and the `plugins-10` plugin set; heap/thread/queue stable after load removal (stability budget) | soak artefacts: TPS/MSPT series, heap series, thread/queue counts, error count |
| Real player flow with bridged plugins (EssentialsX/Vault) on Sourby Demo (`online-mode=true`) | panel log + `/plugins` stats + operator note |
| Shutdown: AWF overall shutdown deadline (D6), shutdown under client load | test-server run with frozen Redis; stop time recorded |
| Backend failure: D4 closed or accepted; Redis over a real network | scenario log per `aurora-world-fabric.md` § *Backend failure* |
| Security: SEC-2 signing key + CI signing active, signed manifest enabled, `.prev` rollback exercised on a real boot, security guards booted behind a proxy | CI signing step, test-server apply/rollback run, proxy boot log |
| Bootstrap: SourbyClip whole-transfer deadline + size cap, cold download from a remote host, cached/offline boot | SourbyClip release + re-pin, remote boot log |
| CI gates: every CI step asserts what it claims (OBS-9 done in 47); reproducible build from a clean checkout and an independent slim-jar rebuild job; SBOM + provenance attestation | CI run ids, hash comparison |
| Licence decision taken and applied (GPL text / source offer in the jar if required; Docker licence label) | owner decision record + jar listing |
| Claims audit at the tag commit; `release=true` set by the owner | release doc + `gradle.properties` diff |

**Out of scope.** New features; certified baselines; performance claims; changing defaults for
performance (all 50). A failing stability gate is fixed, not waived, unless the owner accepts it
in writing in the gate doc.

**Allocated items.**

*Persistence and region ownership*

| Item | Reference | Board |
| --- | --- | --- |
| No region-ownership regression / no persistence regression (release gate) | docs/TODO.md:236; AURORA-TASKS.md:209-210; DEVELOPMENT-TASKS.md:244-245 | task_d2577a5de98e8d47, task_d90afef008e3617f |
| Region watchdog default-off: watchdog acceptance (`REGION-WATCHDOG.md` cases 1–4) | docs/TODO.md:166 | — |
| AWF crash at server level with clients, sustained load, panel 529-chunk commit investigation; AWF engine integration and qualification | docs/TODO.md:244; AURORA-TASKS.md:233, 235 | task_caac47d9d0572a42, task_b64fca8a5dac0ed2 |
| Restart/crash persistence qualification on the 49 tree | AURORA-TASKS.md:70, 110, 204 | task_db2387af0b7c2aa1, task_2ad51975d7687429, task_f7af16a24dfd6edf |
| Retain region ownership and Bukkit/Paper semantics for all changes (standing gate) | DEVELOPMENT-TASKS.md:140 | task_eedbf982ab416960 |
| Unload-safe region queues: CI fixture that queues tasks then unloads | docs/TODO.md:724 | task_f1bda184f5c92428 |

*Concurrency and soaks*

| Item | Reference | Board |
| --- | --- | --- |
| Multi-hour (2 h+) concurrency soak | docs/TODO.md:251; DEVELOPMENT-TASKS.md:34, 52, 248; AURORA-TASKS.md:68, 207; AURORA-ROADMAP.md:85 | task_4a399b8b7adf1401, task_c5c1bbbb158f3533, task_c14bfe84e0fa281e, task_4c983803aba6377d, task_7c7c491ef1d78693, task_7a3b14c538a1f64a, task_9100b5dc1a699b79 |
| Multi-hour soak of the release tree with `plugins-10` | docs/TODO.md:43 | task_dfc4997d2bd63357 |
| Heap recovery after load; heap/thread/queue stabilization | docs/TODO.md:309; DEVELOPMENT-TASKS.md:249; AURORA-TASKS.md:69, 208 | task_633142eca8b94d67, task_1669fc202eb91e16, task_802abb1642d08e85, task_bfaa5e54375d2d9b |
| Player-disconnect retention with real clients (unit retention tests exist) | DEVELOPMENT-TASKS.md:182; AURORA-TASKS.md:148 | task_bb19d1ae651e7875, task_152444ffbfd248e0 |
| Runtime proof that region threads never block on external I/O (with plugins, disk stall) | docs/TODO.md:265 | — |

*Plugin compatibility with real clients*

| Item | Reference | Board |
| --- | --- | --- |
| Real player flow with bridged EssentialsX/Vault on Sourby Demo; latest build on the panel | docs/TODO.md:74, 291 | task_889dfda2c127e768, task_f4767e1b53ee7d01 |
| Representative legacy plugins with player flow and soak | docs/TODO.md:415; AURORA-TASKS.md:226 | task_bc00101995bc4051, task_d0021f4115231057 |
| Per-plugin bridged task timing validated in a real player flow (same run) | docs/TODO.md:321 | task_63a1058f9f086ad0 |
| ProtocolLib qualification (auto-provision defaults to `true`): official artifact, boot, dependents, region soak | docs/TODO.md:508 | task_07d1744c2be696b9 |

*Shutdown and backend failure*

| Item | Reference | Board |
| --- | --- | --- |
| Redis disconnect / lost lease on a running server: Redis over a network, SIGSTOP rerun under player load; AWF database backend qualification. Done 2026-10-22 (MC 0071/0072): D6 overall AWF shutdown deadline and D4 save-all durability (aurora-world-fabric.md "Fixes 2026-10-22"). Already done: the Moonrise read-failure log flood is bounded without a feature patch (`AwfRegionStorage` fence → `ReadFailureLog`, 10 s window; `RepeatedReadFailureLogFilter` drops Moonrise's records of repeats) | docs/TODO.md:60; AURORA-TASKS.md:237 | task_6483e86c005c6f38 |

*Security and distribution*

| Item | Reference | Board |
| --- | --- | --- |
| SEC-2 signed releases: key pair, CI secret, signing step — **DECISION/ACTION** (owner generates the key with `scripts/release_signing_key.py`) | docs/TODO.md:116 | task_5ee7d969f0ea348b |
| Signed release manifest with staged rollout and yank list — activates with the SEC-2 key; rollout percentage — **DECISION** | docs/TODO.md:729 | — |
| Boot-health-gated auto-update rollback: `.prev` restore exercised on a real boot (SEC-3 code exists, unit-tested only) | docs/TODO.md:732 | task_b3138e3cc4484f48 |
| Security module soak / proxy boot for SEC-1 guards and SEC-11 connection rate limit (item text itself is SEC-1, implemented) | docs/TODO.md:737 | — |
| SBOM (CycloneDX) + signed provenance attestation | docs/TODO.md:734 | — |

*Bootstrap and network guards*

| Item | Reference | Board |
| --- | --- | --- |
| SourbyClip downloader audit: whole-transfer deadline and size cap in the launcher (private repo, release, re-pin) — **DECISION** | docs/TODO.md:277; DEVELOPMENT-TASKS.md:225-228; AURORA-TASKS.md:185-188 | task_37a111d15512e81c, task_57278e875b75d7d2, task_c8000bca448fb0bc, task_a6359a8646c9e92c, task_f54ae662d2bf418b, task_a4f45f5d98fa81fd |
| Cold-download qualification from a remote (Linux) host, hash-mismatch injection | docs/TODO.md:283 | — |
| Cached/offline boot as an independence gate | docs/TODO.md:573 | — |

*CI, reproducibility, licence, release*

| Item | Reference | Board |
| --- | --- | --- |
| Reproducibility from a clean checkout | docs/TODO.md:572; AURORA-TASKS.md:179; DEVELOPMENT-TASKS.md:203 | task_84c17bc8625b4def, task_2ce4850b635db6b7, task_188edf0fca6cd34f |
| Reproducible slim jar with an independent rebuild job | docs/TODO.md:736 | task_affc958f7a89dfc2 |
| Licence: PolyForm Noncommercial vs GPLv3 Paper server code in the jar (SEC-13; Docker label SEC-12) — **DECIDED 2026-10-22: GPL-3.0-only + trademark policy** | docs/TODO.md:28, 143; AURORA-TASKS.md:212; DEVELOPMENT-TASKS.md:251 | task_ea0e5cef6500a22b, task_21901c62e3078576, task_69899e64f3d8c503, task_ad90c0ad2b1ba31d |
| Declare STABLE only after every gate passes (Build 47 already used `release=true` without the STABLE label, owner 2026-10-21) — **DECISION** (owner declares STABLE) | docs/TODO.md:418 | task_e134ba42bdc51922 |

---

## Build 50 — optimization (*pengoptimalan*)

**Theme.** Certified baselines first, then measured optimizations. Every claim names commit,
workload, hardware, config, certification state and statistic (docs/DEVELOPMENT.md §3, §7).

**Entry.** Build 49 tagged (the certified references are taken on the stable tree); a quiet host
that meets the certification rule (foreign CPU < 10 % for the window,
`qualification-readiness.md` §6) exists; the representative-workload decision is taken.

**Exit (concrete gates).**

| Gate | Evidence type |
| --- | --- |
| Certified comparable reference pair (`idle` + `chunk-stress`) on the Build 49 tree | `run_baseline.py` certified artefacts |
| Certified workloads: players-10/50/100 with connected clients, entity stress, chunk generation/traversal, network, `plugins-10` | certified artefacts per workload |
| JFR reviewed: CPU, allocation, contention and I/O hot spots ranked | `rank_hotspots.py` reports from certified runs |
| Each optimization: before/after on the same certified workload, stability budget held, regression gate (>3 % investigate, >5 % justify, >10 % block) | paired certified runs + soak for concurrency changes |
| Async pathfinding: sync vs async benchmark, mob behaviour soak, owner decision on the default | benchmark report + soak + decision record |
| Memory: allocation MB/s matches JFR; RSS/cgroup verified on Linux/container | JFR + cgroup readings |
| Representative benchmark report; advisory CI performance gate | report doc + CI job |

**Out of scope.** Any optimization without a certified before/after; defaults changed for
performance without A/B evidence (ENG-9 rule); reductions of gameplay work presented as engine
gains (docs/DEVELOPMENT.md §5).

**Allocated items.**

*Certified baselines (first)*

| Item | Reference | Board |
| --- | --- | --- |
| Certified reference pair `idle` + `chunk-stress` on a quiet machine (moved from the Build 47 gate list) | docs/TODO.md:41 | task_305b0f0c8f54e1a6 |
| Representative product workload: "one player exploring new terrain" vs "10 players settled" — **DECISION** | docs/TODO.md:153 | task_cc74fcdfb06bfd4f |
| Bench panel with real clients, pre-generated bench world, players-50, entity-stress | docs/TODO.md:298 | task_7f86455cbbf6ff43 |
| Idle baseline | docs/TODO.md:303; DEVELOPMENT-TASKS.md:44; AURORA-TASKS.md:61; AURORA-ROADMAP.md:532 | task_84cba3f9edb7c4aa, task_1e1e41222aa5ba8d, task_d1a81fe36a594565, task_e46ea4e16833db28 |
| 10 / 50 / 100 players | docs/TODO.md:304; DEVELOPMENT-TASKS.md:45-47; AURORA-TASKS.md:63-64; AURORA-ROADMAP.md:533-535 | task_383b870f1e445f66, task_9c824b5be4018621, task_30899210e1f951d0, task_00c078d0b632ded3, task_1dde2ec060c9b7a3, task_d3221ce7bf1e96a9, task_aec43755fb605b30, task_e90fed81cdeb3dde, task_b7cb5d7167e35c2f |
| Gameplay load with connected clients | docs/TODO.md:305; DEVELOPMENT-TASKS.md:48 | task_dbafdd642eefaf85, task_c12ecf0b7dc6253d |
| Entity stress, mob AI, tracking, combat, block activity, inventory/plugin interaction | docs/TODO.md:306; DEVELOPMENT-TASKS.md:49; AURORA-TASKS.md:65; AURORA-ROADMAP.md:536-538, 541-542 | task_1effae32a4113496, task_3ac2f47dcc2466f7, task_17b4afca56cedcd8, task_ddfb10beb5e1b6b6, task_4935309c5d0221dc, task_4c2ab9013051f6aa, task_f3150853c354400d, task_f97985c4facc0fc8 |
| Chunk generate/load/unload, traversal, save stress | docs/TODO.md:307; DEVELOPMENT-TASKS.md:50; AURORA-TASKS.md:66; AURORA-ROADMAP.md:539-540, 544 | task_f3b6e147fc3ff2b5, task_8614ea2afd61fab6, task_33111e3d89fca7f3, task_08120927790f29a0, task_9b8bb1f08f507a20, task_1ab38d2aaa68c74f |
| Network gameplay baseline / throughput | docs/TODO.md:308; DEVELOPMENT-TASKS.md:51; AURORA-TASKS.md:67; AURORA-ROADMAP.md:543 | task_b2022cfe71a3663d, task_903470379bf979e2, task_2d41e12fa1a1ff4e, task_fe7265cabb69da75 |
| `plugins-10` certified on another host | docs/TODO.md:312 | task_4def5a5a4232b134 |
| JFR review incl. contention and I/O; rank CPU and allocation hot spots | docs/TODO.md:310; DEVELOPMENT-TASKS.md:54-55, 247; AURORA-TASKS.md:73, 206 | task_03865cb83e0fa428, task_5c5fe9b3c66b1bcb, task_136299df8e6bf690, task_1321f4a3692b0a56, task_e6f673be220af473, task_ae0247a3c404fb3f |
| Representative benchmark report (release gate for performance claims) | docs/TODO.md:311; DEVELOPMENT-TASKS.md:246; AURORA-TASKS.md:205; AURORA-ROADMAP.md:84 | task_65e3b2a648fab21e, task_b6eabb5ed38a7a60, task_3a7273a3f87938f0, task_e93040824c998a4e |
| Startup cold vs warm benchmark | docs/TODO.md:587; AURORA-TASKS.md:230 | task_2476b7d79acc9b41, task_247eab6fd0b581b7 |

*Pathfinding*

| Item | Reference | Board |
| --- | --- | --- |
| Benchmark sync vs async pathfinding | docs/TODO.md:329; DEVELOPMENT-TASKS.md:120; AURORA-TASKS.md:135; AURORA-ROADMAP.md:399 | task_475413b3bf29d848, task_057f849b0a70240a, task_398e8d66f10a76a9, task_1023fbc9efd80846 |
| Mob behaviour compatibility soak (flag on); per-type stats; soak-3 villagers; spec fact-check; flag-on audit run — then **DECISION**: default on/off | docs/TODO.md:330; DEVELOPMENT-TASKS.md:121; AURORA-TASKS.md:136; AURORA-ROADMAP.md:81 | task_41d331920a0ba021, task_834c9d41c197b076, task_528dce54352331a2, task_f15244122f4e4381, task_36d45425b96e49e1, task_75edbe3e9166ed83, task_5be0cfb20866dd6c |

*Scheduler*

| Item | Reference | Board |
| --- | --- | --- |
| EDF scheduler (LeafPile-derived `AuroraEdfScheduler`, parked): unpark only after its tests, independence policy and boot pass, measured against the certified baseline — **DECISION** (held by owner 2026-10-07) | docs/TODO.md:162, 376; AURORA-TASKS.md:125 | task_6a5a6407f9eddb31, task_0ccf16507fa7ef45, task_3f6e1efce23facf5 |

*Entity / chunk / network NMS profiles and optimizations*

| Item | Reference | Board |
| --- | --- | --- |
| Profile Entity/LivingEntity/Mob tick, GoalSelector, Brain/Sensor, navigation, collision, entity queries, tracker, item merge/pickup; reusable entity buffers | docs/TODO.md:366; DEVELOPMENT-TASKS.md:131-139; AURORA-TASKS.md:86-95 | task_3c9e4e7842bce2db, task_651b0d7ae04ac4cf, task_5f422d933b83b90b, task_31acc5b4f9939fae, task_a129ff9e3ae4f82e, task_075797e867bd6112, task_3ae9cc3a029ef3ca, task_590f99426badf716, task_5530de30e850c30e, task_c9f7f3b81ff49045, task_1ef243d952f32df4, task_f55f36f2f1e32ced, task_ce94bc2274571c2f, task_99bd87ddd0be982f, task_471c0fe3a237673b, task_ced804ef7bb6c0f9, task_dbb56097f49956ca, task_41c7e11622c69d82, task_04d48998f0d463b8, task_ad14b8d7789003d2 |
| New-terrain generation stutter: run with metrics on, profile, A/B | docs/TODO.md:367; DEVELOPMENT-TASKS.md:150; AURORA-TASKS.md:103 | task_e07d65b43710ee00, task_6329ac831e5e4034 |
| Profile chunk holder lookup, tickets, region-file I/O, player chunk tracking, chunk packet construction; load/save latency and backlog | docs/TODO.md:382; DEVELOPMENT-TASKS.md:148-149; AURORA-TASKS.md:101-102, 104-108 | task_104c4780942b3ddf, task_1b22de99d1398846, task_fffd96334f77289b, task_9dec114cee9478c2, task_9c780675ea087a88, task_bd6ef074568bb37d, task_4766703208acbbbc, task_cfd06498bfd0c814, task_6b492e75a8ac85a9, task_75f2a4d15751c98f |
| Profile encode/decode, compression, chunk-send, packet allocation/copy | docs/TODO.md:383; DEVELOPMENT-TASKS.md:165-167; AURORA-TASKS.md:116-118 | task_c7834d5edd5d2ba5, task_e49f22c48fc6ef37, task_345d4916b19b639d, task_f56045a53d0f8d4e, task_f717c64b6556b506, task_f76b70183e5bc229, task_e56a12a1644160b7 |
| First measured Aurora optimization per domain (entity, chunk, network); direct NMS optimization program | docs/TODO.md:384; AURORA-TASKS.md:96, 109, 121; AURORA-ROADMAP.md:86 | task_5d741478222b876d, task_2b3c7d720295528b, task_a6733a4424ea6107, task_d2532e924874628e, task_51bc075cde734592 |
| AWF Redis over a network: neighbour prefetch / pipelining | docs/TODO.md:695 | task_048e2be0b752d008 |

*Memory*

| Item | Reference | Board |
| --- | --- | --- |
| Allocation MB/s verified against JFR | docs/TODO.md:336; DEVELOPMENT-TASKS.md:178; AURORA-TASKS.md:144 | task_6ad0c75f5dae5026, task_9120f0b84d16b23a, task_0a422f57486f0bf2 |
| RSS/cgroup on Linux/container | docs/TODO.md:337; DEVELOPMENT-TASKS.md:179; AURORA-TASKS.md:145 | task_56b78dd40acb5e62, task_43f83cc97db31638, task_f51cfb7f0b4d2c12 |

*Performance observability*

| Item | Reference | Board |
| --- | --- | --- |
| Continuous performance regression gate in CI (advisory first, noise-aware; accepted by the owner 2026-10-20) | docs/TODO.md:739 | task_e10def08f1589a1f |
| Per-region flame summary for slow regions (JFR opt-in) | docs/TODO.md:740 | task_589c37ecc9675d26 |

---

## Later / not planned (P3 innovation and end-state architecture)

Not allocated to 47–50. Each needs an owner decision to enter a build.

| Item | Reference | Board | Reason |
| --- | --- | --- | --- |
| M-5: drop Paper as upstream | docs/TODO.md:565 | task_8cb5f89e782043ed | Owner chose "Paper stays upstream" (M-0, 2026-10-05) |
| Remove legacy build dependency after clean-build proof | docs/TODO.md:574; DEVELOPMENT-TASKS.md:202 | task_8ca727fb64ca4615, task_bf2afc076a0a2c46 | n/a while SourbyPatcher is the patch applier; nothing to remove |
| Runtime lifecycle authority, execution/scheduler contract, compatibility layer; Aurora Entity/AI/Chunk/World/Network/Storage/Memory engines; build/bootstrap independence; final Canvas/Folia isolation; final cutover; lifecycle of execution/Spark/scheduler bridges | docs/TODO.md:584; AURORA-ROADMAP.md:91-104, 290, 295, 299 | task_ea785e333f6b6cff, task_251f4e2abbb492d0, task_03051392eeeb6680, task_1fbf9631b7a0d0ce, task_64519a957cfd06a0, task_ef2b161ac84ba756, task_03883d36b5d77646, task_083eacc12e2f67c6, task_31a14d6a9cf6c431, task_bf25315f94e1a742, task_b5c5c85e185d0b90, task_41e6bb5c383fd5b9, task_4c52bb0b020ef5cd, task_f86528f998557aff, task_6117f41dd4171010, task_d048c6c74fa25714, task_b1723517a7215a41, task_e3ac77217af0f6b2 | Planned subsystems / end state; not refinement, stability or measured optimization. Build 48's guard ledger is the first slice of Canvas/Folia isolation |
| Transform output caching | docs/TODO.md:588; AURORA-TASKS.md:231 | task_a2897a1b0402c148, task_d9cca6e3b1b56298 | No plugin bytecode transform exists to cache |
| Spark: adapter vs SourbySpark fork; GPL review if forking; `Aurora Engine` label in the viewer and viewer presentation | docs/TODO.md:591, 669; DEVELOPMENT-TASKS.md:105-106; AURORA-TASKS.md:164-166 | task_703368b1ec0dabeb, task_d6ee3f8300831c0d, task_4cacf56ef22d6a81, task_d5ae6e5c3d1f8078, task_1e43bee0af941492, task_233d8d2e9ae4d7d7, task_7397faaf95ffc79e | Only after the need is proven; the label needs an own viewer |
| AWF MongoDB/MySQL backends | docs/TODO.md:698 | task_26ca07b825a3aff3 | New feature |
| Existing executors under the Resource Governor | docs/TODO.md:699; AURORA-TASKS.md:242 | task_8449c0065dbdff0e, task_4da8e4a82c79f985 | Deferred; each already bounded (`executor-inventory.md`) |
| `aurora.performance/chunk/memory/ai/world` namespaces | AURORA-TASKS.md:40-45; AURORA-ROADMAP.md:217-223 (docs/TODO.md:701 is already `[x]` "n/a until a key exists") | task_5e09e05139084368, task_d05ea20e8e79737e, task_765c1c3f627dceb6, task_1242fc6bffcc0d07, task_afe12657066c64f3, task_6ac4b9e8aeb98def, task_5f9120bec64abe72, task_663e4b3e5727b7f7, task_b7d57f3acb7e588a | Added only when a real key exists |
| AWF point-in-time rollback operator command (store-level API exists) | docs/TODO.md:716 | — | New operator feature; owner decision on mapping |
| Content-addressed object store shared by a template family | docs/TODO.md:718 | task_01c86f61c9f31e06 | New feature, needs a design |
| Plugin cost attribution per region | docs/TODO.md:741 | task_34019620da849799 | Proposal |
| Housekeeping lane with its own freshness marker | docs/TODO.md:742 | task_d77e19bf4a43cf03 | Proposal |

---

## Dependencies across builds

- **47 → 48.** The tag commit decides what 47 contains. Startup-log (Paper 0016), owned command
  blocks (MC 0055) and the guard ledger are uncommitted 48 work in the shared tree today.
- **48 → 49.** The 49 soaks and persistence runs must run on code that already contains the 48
  guard decisions and command-block patch, or they must be repeated. The release-branch name
  (docs/TODO.md:548) must be decided before 49 publishes, because the auto-updater and the publication
  workflow key on it.
- **49 needs, from outside the code:** real clients against Sourby Demo (`online-mode=true`
  refuses headless clients); a stable host for a 2 h+ soak (the 2026-10-07 demo soak was cut by
  a local network drop); the owner's licence decision; the owner-generated SEC-2 key and CI
  secret; a SourbyClip release for the transfer deadline. A stability soak does not need
  certification, but it does need a host that does not drop.
- **50 needs:** a quiet machine meeting the certification rule (none exists today,
  `qualification-readiness.md` §6), real clients for `players-*` and `entity-stress`, the
  representative-workload decision (docs/TODO.md:153), and the Build 49 tree as the reference. The CI
  performance gate depends on at least two certified runs of the same workload.
- **SEC-2 → signed manifest / rollout.** The manifest code is dormant until the jar carries the
  public key; both close together in 49.

## Counts (2026-10-21)

docs/TODO.md items (open, partial or blocked checkboxes, plus stale ones listed above): 47: 4 ·
48: 14 · 49: 27 · 50: 27 · Later: 12. Board tasks not done: see the PM's applied tags
(build-tags.json); 47: 5 · 48: 24 · 49: 47 · 50: 116 · Later: 45 (237 tasks) at the time of
writing. Tasks added to the board later need a tag when they are created.

Items needing an owner decision: 47: 0 (channel decided 2026-10-21: full release, not STABLE) · 48: 6 (guard
decisions incl. ENG-11, command-block default, FairLane weights, Spark-bridge adapter vs fork,
release-branch name, private toolchain re-pin) · 49: 5 (licence, SEC-2 key, rollout percentage,
SourbyClip transfer bounds, `release=true`) · 50: 3 (representative workload, EDF unpark,
async-pathfinding default) · Later: all.
