# Build 50 readiness and partial-work closure

Updated 2026-10-25. Target `26.2`, Build 50 / Aurora Nexus, `release=pre`.
This is the current closure plan; the 2026-10-21 roadmap remains historical allocation.
**Source changes prepared; not compiled or tested by this agent. Not STABLE/QUALIFIED.**
Owner execution: [Tester Owner](../tester%20owner.md). Existing evidence is not automatically
transferred to this candidate. No benchmark gain or exhaustive vulnerability audit is claimed.

## Changes prepared in this PR

| ID | Problem and change | Remaining evidence |
| --- | --- | --- |
| B50-AWF-SAVE | Add an off-region Moonrise world I/O drain after all region tickets, before final AWF flush. | B50-02, persistence under chunk churn; inherited save tickets still request their own flush. This does not prove that every serialization error propagates. |
| B50-AWF-CLOSE | MC feature 0033 now observes AWF storage handles throughout unload, including newly attached handles. Verify their final commit/discard confirmation before callback SUCCESS, even if inherited engine code swallowed a close exception. | B50-03/04; apply/build and normal/nosave/empty-world/failure paths. No automatic recovery of lost dirty state is claimed. |
| B50-UNLOAD-CALLBACK | A throwing cleanup/release callback now reports FAIL_UNKNOWN instead of abandoning the unload callback. Cleanup is attempted once. | Exceptional callback fixture and unload completion. |
| B50-MP-ACCOUNT | Serialize profile I/O actions and switch admission with the existing revision lock; create cannot overwrite a switch account update. | Concurrent create, delete, save, switch; contention/latency and shared STORAGE saturation. All repository I/O is serialized in-process; not a multi-process transaction. |
| B50-MP-SAVE | Capture account revision and exact owner claim; recheck before publishing a snapshot. An A→B→A switch no longer passes a UUID-only check. | Delayed capture and lease expiry, B50-06/07. |
| B50-MP-RECOVER | Reserve the player while recovery runs; switch/save/delete/second recovery refuse conflicting operations. | B50-08, success/failure cleanup. |
| B50-MP-BIND | Public validated immutable binding request, typed world roles, asynchronous API, admin bind/unbind. Active/claimed/deleted profiles and transitions refuse rebinding. | B50-09/10/11; profile world activation and MP-007 idle unload. |
| B50-MP-IDLE | Failed idle unload retains eligibility for retry; count successful operations instead of scanned candidates. | B50-12 and touch/unload race review. |
| B50-MP-FENCE | Refuse distributed mode because local FILE writes cannot atomically enforce Redis fences. Redis coordination in local mode is not shared snapshot storage. | B50-13 refusal. Distributed implementation remains OPEN, not completed by disabling it. |
| B50-MP-RECORD | Bound record reads themselves, not only a pre-read file-size check. | Oversize/growing/truncated/checksum-corrupt fixtures, B50-14. |

The AWF observer is scoped with try-with-resources, retains only that unload's storage handles,
and is removed on success/failure. It changes neither save durability policy nor Redis TTLs.
The metadata lock does not run on tick threads, but may delay other profile I/O workers during
backend calls; measure shared-lane queue wait before claiming scalability. Public API callbacks
remain on unspecified threads and must be handed back to entity/region schedulers.

## Every partial class has an explicit exit condition

Nothing below becomes complete by changing a checkbox. These groups cover the partial/open
release gates in `docs/TODO.md`, Build 49/50 gates, and the historical 47–50 plan.

| Workstream | State now | Exit condition for Build 50 stability |
| --- | --- | --- |
| AWF save/unload, FILE/Redis crash recovery, leases and deadline | Source fixes; owner retest required | All AWF and failure cases pass on candidate SHA, including dirty shutdown and backend outage; no unconfirmed commit reported as success. |
| MultiProfile local lifecycle / isolation / recovery | Source fixes; partial | Race fixtures + real-client round-trip + restart/soak pass. Advancements/statistics/recipe-book and plugin inventories require adapters or explicit unsupported scope; not full isolation today. |
| World binding / idle lifecycle | API and CLI added, untested | MP-007 and B50-09…12 pass. Review shared-world touch/admission before multiworld scale claims. |
| Distributed profiles / routing / proxy handoff | BLOCKED BY IMPLEMENTATION | Shared authoritative repository with atomic fenced commit/CAS; node identity/session fencing; recovery and two-node fault tests; routing transport plus portal/proxy integration. FILE+Redis validation is insufficient. |
| Async pathfinding / immutable snapshot coverage | Experimental, default off | Audit every supported mob evaluator, stale result/region transition/cancel/saturation tests, sync-vs-async certified benchmark and soak. Unsupported types must retain safe fallback. |
| Region ownership / bridge / task lifecycle | Partial evidence; relaxed mode unsafe | Enforced-guard real-player plugin matrix and >=2h soak; no persistence/ownership regression. Relaxed mode cannot be called qualified. FairLane wiring and plugin-cost attribution remain separate open work. |
| Memory / telemetry / HUD / Spark | Partial evidence | Linux cgroup v1/v2, actual Docker/Pterodactyl, JFR allocation comparison, HUD clients, secrets redaction and Spark viewer verification on this SHA. |
| Entity / chunk / network optimization | Instrumented, no new certified results | Certified Build 49 reference + same-workload candidate pairs, JFR review, gameplay fidelity, p95/p99 and regression thresholds. No default tuning to manufacture improvement. |
| Startup / cold bootstrap / offline / provisioning | Partial; private-tool dependencies | Cold, cached, offline, corrupt-cache, timeout and dependency-plugin boot checks; clean pinned-toolchain build. Cold downloader changes belong in SourbyClip. |
| Signed updater / rollout / rollback | External key and operational gates remain | Owner-controlled signing key configured, valid/invalid/expired/yanked manifest tests, interrupted apply and boot-health rollback. Never fabricate or commit a signing secret. |
| Build provenance / SBOM / reproducibility | Partial evidence | Clean rebuild comparison, identity matching, schema validation and required attestations. Artifact provenance is not automatically a signed attestation. |
| Legacy configuration / Canvas deprecation | OPEN compatibility work | Complete reader inventory, migrate-before-remove policy for persisted data, upgrade-from-48/49 tests. Existing persisted-data migrations are retained in this PR to avoid silently losing config/pearls. |
| License / attribution | Existing owner GPL decision; audit remains | Review retained upstream notices and dependency obligations. This PR is not a legal clearance. |
| SourbyMod / AbyssLink | Opt-in, manual evidence missing | Loader lifecycle/invalid-jar isolation; peer-dependent integration for AbyssLink. Missing peer stays BLOCKED. |
| EDF / storage backends / dedup / housekeeping proposals | Planned/parked | No implementation claim. If included in Build 50 scope, require design, code and applicable owner gates before normal release. |

## Release decision

1. Owner builds and tests this exact candidate; failures become fixes and affected cases rerun.
2. Attach evidence to the owner result sheets. Keep FAIL/BLOCKED/NOT RUN visible.
3. Complete remaining code work above; do not promote merely because this PR merges.
4. Audit README, TODO, release gates and effective configuration against the final tree.
5. Only after mandatory gates pass consider normal release. If `v26.2-r50` already exists,
   changing `release=true` does **not** republish/promote it: CI skips existing tags. Use the
   repository's explicit release-management procedure or a new build/tag; do not overwrite tags.

No automatic tests, builds, boot, benchmark or failure injection were run in this session, per
owner instruction. Only source/diff inspection was performed. GitHub PR checks may run normally.
