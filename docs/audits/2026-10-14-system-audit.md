# System audit, 2026-10-14

Read-only audit of branch `26.2` at `d97fe247`, in four areas, each by an independent reviewer
(Claude Fable 5.1). Every finding was traced in code (CONFIRMED) or is marked PLAUSIBLE where only a
runtime test can settle it. Line numbers are as of `d97fe247`. Status of each fix is tracked in
`docs/TODO.md` (section *Audit 2026-10-14*); this file records the findings, proposed patches and the
proposals, and is not updated as fixes land.

Owner decisions taken on the day: rebuild the packet/crash guards behind `sourbycraft-security.yml`
(SEC-1); auto-updater safe by default with signed releases (SEC-2/3); remove SourbyClip's geo-IP
lookup (SEC-5). The licence conflict (SEC-13) remains an open owner decision.

Severity: CRITICAL none; HIGH 14; MEDIUM 22; LOW 16; one owner decision (SEC-13).

## 1. Aurora World Fabric (AWF-n)

| id | sev | finding | where | conf | patch |
|---|---|---|---|---|---|
| AWF-1 | HIGH | Managed-world name matched against **every** path element: a world named like an ancestor directory of the server (e.g. `home`) attaches every world to AWF; a template instance named so makes the main world read template chunks | `AwfRegionStorage.java:123-128`, `AwfEngine.java:343-344`, `templateFor/prunes/boundsFor` | CONFIRMED | Match on the path relative to the server directory (`storageId()`) |
| AWF-2 | HIGH | `TicketHolder.pop()` reads outside the monitor and races `propagate()`: a save ticket is lost (save-all never completes; `AuroraWorlds.save` never completes and keeps the world's operation lease until restart); `propagate` throws "Ticket already propagated" when a region did not tick between two `save-all`s | `TicketHolder.java:34-39, 89-95`; `SaveAllCommand.java:108` | CONFIRMED | Atomic pop; coalesce a second ticket (both callbacks, `flush = a or b`) |
| AWF-3 | HIGH | Periodic AWF commits ride the metrics collector and are skipped whenever a collection is late or slow, so the "lose at most one interval" promise does not hold under load | `PerformanceCollector.java:188-212` | CONFIRMED | Independent AWF maintenance driver |
| AWF-4 | MEDIUM | Template names not validated in from-template / delete / read: path traversal including recursive delete | `AuroraWorldsService.java:338-348, 603-622`; `AuroraTemplates.java:66-140` | CONFIRMED | Validate names; confine resolution to the templates root |
| AWF-5 | MEDIUM | Redis leases leaked by `unprepare` and the "already open" path; later a false "lost its Redis lease" error | `AwfEngine.java:276-281, 385-394` | CONFIRMED | Close stores on every removal/throw |
| AWF-6 | MEDIUM | Lease renewal is serial: stores × `timeout-ms` can exceed the lease on a slow Redis | `RedisBackend.java:259-283` | PLAUSIBLE | Pipeline renewals; warn when a pass exceeds period/2 |
| AWF-7 | MEDIUM | FILE commit cost is O(world): full index rewritten, hashed and fsynced per commit; one fsync per object | `AwfWorldStore.java:160-198`; `GenerationStore.java:141-150`; `ObjectStore.java:73-85` | CONFIRMED cost | Delta generations, batched fsync (roadmap). Batched fsync done 2026-10-20 (one force pass before the index; force count still O(objects)); delta generations implemented 2026-10-20 but OPT-IN (`aurora.awf.delta-generations`, default false, RESTART_REQUIRED): flag off writes the exact pre-AWF-7 format; with deltas on, a downgrade needs `/awf compact all` and loses delta-only retained history (back up first); no wall-time gain measured locally (index bytes per commit shrank, force count did not) |
| AWF-8 | LOW | `WorldShutdownThread` can rethrow without calling the unload callback: the unload future never completes, lease held | `WorldShutdownThread.java:89-92, 180-202` | CONFIRMED path | Callback in `finally`; lease watchdog |
| AWF-9 | LOW | Untrusted `.slime`: 1 GiB blob cap, unlimited NBT accounter, whole file in memory | `SlimeImporter.java:62, 276, 317` | CONFIRMED | Configurable cap (256 MiB), bounded accounter |
| AWF-10 | LOW | Doc drift: patch number 0020 (is 0019), hook location vs patch policy, stale `WorldRole` javadoc | `aurora-world-fabric.md:455,468`; `WorldRole.java:7-8` | CONFIRMED | Fix docs |

Verified sound: FILE commit crash consistency (temp, fsync, atomic rename, directory fsync,
read-back verify); commit ordering under `commitLock`; close/discard transition; Redis Lua scripts
after the D2/D3 fixes; credential redaction in AWF logs; Redis TLS hostname verification.

## 2. Region engine and plugin bridge (ENG-n)

| id | sev | finding | where | conf | patch |
|---|---|---|---|---|---|
| ENG-1 | HIGH | Default `guard-severity: LOG` lets cross-region access from another region thread proceed; bridged plugins' violations never reach quarantine | `TickGuard.java:33-59` + 13 call sites | CONFIRMED | Guard sink attributes LOG-mode hits to the running bridged plugin and feeds quarantine; per-(plugin, reason) log rate limit |
| ENG-2 | HIGH (feature default off) | Async pathfinding replaces the mob's live `nodeEvaluator` and loses door/float flags | `PathNavigation.java:171-172`, `GroundPathNavigation.java:29` (+5) | CONFIRMED | Save/restore evaluator, copy flags (amend MC 0006) |
| ENG-3 | HIGH | Region/entity tasks queued on an unloading world are never run, cancelled or retired | `TickRegions.java:677`; `WorldShutdownThread.java:60-203` | CONFIRMED | Drain queues cancel-only and retire schedulers on unload |
| ENG-4 | HIGH | A cancelled queued task in an unloaded section makes a later split throw under the regioniser write lock and stop the server | `RegionizedTaskQueue.java:393-401, 555-588` | PLAUSIBLE | Skip cancelled tasks in split |
| ENG-5 | MEDIUM | A save ticket is reported complete when the region merges or splits mid-save; the parts carry no ticket (AWF flush then misses chunks) | `TickRegions.java:169-179, 473-597` | CONFIRMED | Forward the ticket on merge, fan out on split |
| ENG-6 | MEDIUM | One full `BRIDGE_IO` lane cancels another plugin's repeating async timer for good | `AuroraBridge.java:379-386`; `GovernedLane.java:46-71` | CONFIRMED | Skip, not cancel, repeating tasks |
| ENG-7 | MEDIUM | Async path refusal consumes the recompute slot without a synchronous fallback | `PathNavigation.java:105-111`; `AsyncPathProcessor.java:181-243` | CONFIRMED | Return null on refusal so vanilla runs |
| ENG-8 | MEDIUM | Path worker reads live mob state off-owner | `WalkNodeEvaluator.java:55-91`; `Mob.java:148,186` | CONFIRMED | Immutable snapshot captured on the owner |
| ENG-9 | MEDIUM | CPU oversubscription by default (region threads = all processors plus chunk, async, Netty, path, bridge pools) | `AuroraCpu.java:96`; `MoonriseCommon.java:32-46` | CONFIRMED sizing | Boot-time thread ledger; defaults only with A/B evidence |
| ENG-10 | MEDIUM | `propagateSaveAllTicket` future completed under the regioniser write lock; a synchronous dependent re-entering it deadlocks | `TickRegions.java:315-324` | PLAUSIBLE | Complete asynchronously |
| ENG-11 | LOW | Patch 0016 refusal in `execute if/unless block … run` fork aborts both branches silently | `ExecuteCommand.java:895-908` | PLAUSIBLE | Document or route to the owner |
| ENG-12 | LOW | A quarantined plugin reloaded by name is admitted and every task is silently rejected | `BridgeRuntime.java:172-223` | CONFIRMED | Log once on admit |
| ENG-13 | LOW | Doc drift: `CompatibilityClassifier` javadoc, region-parallelism §4 default, AGENTS.md scheduler config location | various | CONFIRMED | Fix docs |
| ENG-14 | LOW | Async path snapshot taken before refusal is possible; worker failures log unthrottled | `PathNavigation.java:167-168` | CONFIRMED | Check capacity first; rate-limit logs |

## 3. Boot, distribution, network, security (SEC-n)

| id | sev | finding | where | conf | patch |
|---|---|---|---|---|---|
| SEC-1 | HIGH | `sourbycraft-security.yml` has no consumer since `6b774810` (2026-07-10): no packet/NBT/book/sign/payload guards, while docs say they are enforced | `sourbycraft-security.yml`; readers only spark dump and `/sourbycraft` | CONFIRMED | Rebuild as `dev.iyanz.sourbycraft.security` + one feature patch, LOG mode first, a test per limit, policy check that every yml key has a consumer |
| SEC-2 | HIGH | Auto-updater trusts the GitHub API response alone (no signature), default `apply_mode=auto`, CI auto-publishes | `SourbyUpdater.java:362,396-453`; `AutoUpdateSettings.java:27,58` | CONFIRMED | Signed releases (Ed25519, public key in the jar); default `notify` |
| SEC-3 | HIGH | In-place swap keeps no previous jar and auto-crosses Minecraft versions | `UpdateApplier.java:80`; `SourbyUpdater.java:281-283` | CONFIRMED | Keep `.prev`, restore after a failed boot; same-MC-version gate |
| SEC-4 | HIGH | `SourbyBootstrap` is dead code (entry point is SourbyClip, which exits on a clip-version mismatch); CI offline-download guard greps a string that can never appear | `bootstrap/SourbyBootstrap.java`; `build.yml:381` | CONFIRMED (dead code); PLAUSIBLE (brick) | Remove or wire; refuse staging a jar with a different clip version; fix the CI grep and docs |
| SEC-5 | MEDIUM | SourbyClip calls three geo-IP services every boot, two over plain HTTP | SourbyClip `Sourbyclip.java:276-284`, `IPUtil.java:98-106` | CONFIRMED | Remove the lookup in SourbyClip |
| SEC-6 | MEDIUM | `ViaAutoUpdate` overrides the Via pin, may install a version not listing the running MC, trusts the API's hash, deletes operator jars | `ViaAutoUpdate.java:76-163` | CONFIRMED | Only versions listing the MC version; never delete jars not ours; bound size |
| SEC-7 | MEDIUM | ProtocolLib pin targets a mutable `dev-build` asset, never booted | `ProtocolLibProvisioner.java:10-13`; `protocollib-pin.json` | CONFIRMED | FIXED 2026-10-20 (`6e50b735`): pin tagged 5.4.0, load-tested on 26.2 (2 boots, exit 0); soft upstream "not yet tested" caveat; scheduled CI probe snippet pending CI owner |
| SEC-8 | MEDIUM | CI: actions pinned by tag not SHA; `contents: write` for the whole job | `build.yml` | CONFIRMED | Pin by SHA; scope permissions |
| SEC-9 | LOW | `exit` restart mode halts from a shutdown hook | `UpdateApplier.java:152-154` | PLAUSIBLE | Exit after shutdown completes |
| SEC-10 | LOW | Updater download unbounded before size check; unauthenticated polling every 30 min | `SourbyUpdater.java:151-160, 420-421` | PLAUSIBLE | Bound copy; ETag / Retry-After |
| SEC-11 | LOW | Folia baseline drops Paper login guards (`max-joins-per-tick`, sync pre-login, duplicate profile) — inherited, undocumented | `Connection.java.patch:261-266` | PLAUSIBLE | Document; connection-rate limit |
| SEC-12 | LOW | Docker base image not pinned by digest; licence label | `Dockerfile:11,15` (now `docker/Dockerfile`) | CONFIRMED | Pin digest; label after SEC-13 |
| SEC-13 | owner | Distributed jar is a derivative of GPLv3 Paper code under a noncommercial-only licence; no GPL text or source offer in the jar | `LICENSE.md`; jar `META-INF/license/` | CONFIRMED facts | Owner decision (not legal advice) |

Verified sound: SourbyClip library downloads are SHA-256 pinned; Mojang jar and patch hash-checked;
offline mode refuses downloads; Via/ProtocolLib provisioning https-only, size-bounded, verify-then-move;
`/speedtest` binary hash-pinned; reload classification of updater keys.

## 4. Observability, API, tooling, docs (OBS-n)

| id | sev | finding | where | conf | patch |
|---|---|---|---|---|---|
| OBS-1 | HIGH | Redis password in `aurora.awf.redis.uri` leaks into spark reports | `SourbyServerConfigProvider.java:91-112, 203-211` | CONFIRMED | Hide the key; scrub URI userinfo everywhere |
| OBS-2 | HIGH | `/speedtest` hang wedges the command forever and leaks the process | `SpeedtestCommand.java:158-159` | CONFIRMED | Read output concurrently; wait with timeout first |
| OBS-3 | HIGH | Build identity cannot be tied to a tree (no dirty flag, no tree hash; CI never checks `Git-Commit`) | `build.gradle.kts.patch:120-123`; `verify_build_identity.py:30-43` | CONFIRMED | `-dirty`, tree hash, `--commit $GITHUB_SHA` check |
| OBS-4 | MEDIUM | AWF maintenance on the metrics thread; a post-publish failure downgrades a fresh snapshot to STALE | `PerformanceCollector.java:199-228` | CONFIRMED | Same fix as AWF-3; separate try/catch |
| OBS-5 | MEDIUM | 384-sample raw ring breaks 10/15 s windows above 25.6 Hz or under sprint | `RegionTickMetrics.java:31,235-237` | CONFIRMED | Size from tick rate |
| OBS-6 | MEDIUM | Region telemetry generations swept only by the collector; unbounded if metrics failed to start | `RegionMetricsRegistry.java:108-122` | CONFIRMED | Amortised sweep, cap |
| OBS-7 | MEDIUM | Policy scripts miss Paper patches and the engine package; silently skip missing roots | `independence_policy.py:34`; `concurrency_policy.py:24,81-83` | CONFIRMED | Root tuples incl. paper features and engine; assert roots exist |
| OBS-8 | MEDIUM | Async command replies bypass `SourbyReply` (command blocks get exceptions) | `AwfCommand.java:105-109,389-395`; `UpdateCommand`, `SpeedtestCommand`, `PerfCommand` | CONFIRMED | Route through `SourbyReply.run` |
| OBS-9 | MEDIUM | CI gates that pass without proving much: PR CI never runs; smoke greps match any panel header; metrics markers check only non-null; zstd tests skip silently | `build.yml:6-20,294-301`; `TestPlugin.java:33-38`; `SlimeImporterTest` | CONFIRMED | Fix trigger; view-specific asserts; `SOURBY_REQUIRE_ZSTD` |
| OBS-10 | MEDIUM | Release doc / SPEC / README contradict each other on gate state | release doc, SPEC §161, README | CONFIRMED | One consistency pass |
| OBS-11 | MEDIUM | `/awf export|import|convert` accept arbitrary filesystem paths | `AwfCommand.java:87,285,319`; `AuroraWorldsService.java:527-535` | CONFIRMED | Confine to `awf/exports` / `awf/imports` |
| OBS-12 | LOW | `/speedtest` logs before its permission check | `SpeedtestCommand.java:124-132` | CONFIRMED | Move log |
| OBS-13 | LOW | `getTPS()` path allocates and sorts under the region monitor | `RegionTickMetrics.java:251-289` | CONFIRMED cost | Serve from the published snapshot |
| OBS-14 | LOW | Coverage holes (engine, hud, maxplayers, lang, update, bootstrap); timing-sensitive tests | `src/test` | CONFIRMED | Prioritised tests; injected clocks |
| OBS-15 | LOW | `averageGcPauseMs` is collection time, not STW pause | `RuntimeMetrics`; `GcTracker.java:9,126` | CONFIRMED | Javadoc caveat |
| OBS-16 | LOW | Baseline readiness keyed on `Done (`, which can now precede staged spawn terrain | `run_baseline.py:199-210` | PLAUSIBLE | Wait for the boot summary too |

## 5. Proposals (not implemented, not planned until accepted)

Storage
- Delta-indexed FILE generations with compaction (fixes AWF-7): commit bytes and fsyncs proportional to dirty chunks. Implemented 2026-10-20 as OPT-IN (`aurora.awf.delta-generations`, default false, RESTART_REQUIRED); commit index bytes are proportional to changed chunks, the fsync count is not (still O(objects)); no wall-time gain measured locally; downgrade with deltas on needs `/awf compact all` and loses delta-only retained history (back up first).
- AWF maintenance driver with byte-triggered commits: bounded loss window in time and bytes, max dirty age in `/perf awf`.
- Redis `EVALSHA` + pipelined renewals: one round trip per renewal pass.
- Point-in-time rollback API on retained FILE generations for operator recovery.
- Content-addressed object store shared by a template family (dedup across islands; behind a flag).

Engine and bridge
- Bridged-task ownership context feeding every guard hit into quarantine without THROW mode.
- Fair bridged I/O lane: per-plugin weighted sub-queues, skip-not-cancel timers.
- Unload-safe region queues (`drainForUnload()`) with a CI fixture.
- Immutable `MobPathSnapshot` for async pathfinding (closes ENG-8 and the M5 audit). Implemented 2026-10-20 (`dev.iyanz.sourbycraft.perf.MobPathSnapshot`, materialized MC commit "SourbyCraft - immutable mob snapshot for async pathfinding"); member trace and remaining refusals in `docs/async-pathfinding-spec.md` "What the snapshot holds".
- Boot-time CPU ledger in `ResourceGovernor` and `/perf governor`.

Distribution and security
- Signed release manifest with staged rollout percentage and a yank list.
- Boot-health-gated rollback of auto-updates.
- SBOM (CycloneDX) and build provenance attestation per release.
- Reproducible slim jar with an independent rebuild job.
- One owned, tested security module driving all packet limits (SEC-1, accepted).

Observability and tooling
- Continuous perf-regression gate in CI (advisory first, noise-aware).
- Per-region flame summaries for slow regions (opt-in JFR).
- Plugin cost attribution per region.
- Housekeeping lane with its own freshness marker (AWF commits, sampling, history).
- Build provenance manifest (tree hash, dirty flag, patch-dir hashes) printed by `/ver`.
