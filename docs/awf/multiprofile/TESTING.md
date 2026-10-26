# Testing

Build 50, PR #62 (2026-10-25): code written and statically reviewed; not built; not tested by the
owner. No Gradle build, Java test, Python suite, Redis server or boot was run for PR #62, and no new test
was written (owner rule); whether the existing tests still compile and pass against it is unknown.
Evidence and per-item status: [readiness](../../releases/BUILD-50-READINESS.md),
[PM matrix](../../releases/BUILD-50-PM-MATRIX.md) and [Tester Owner](../../tester%20owner.md).

> **Owner-managed testing.** The results below were produced by coding agents during implementation;
> they are implementation evidence, not qualification. No runtime result in this repository is
> accepted until the project owner runs it. The owner-facing procedures and the authoritative status
> per case are in [`docs/testing/owner/00-TESTING-INDEX.md`](../../testing/owner/00-TESTING-INDEX.md);
> distributed mode, real-player switching and any multi-node behaviour are never "passed" there.
> Owner report 2026-10-24: MultiProfile testing complete on the tree of that date, before PR #61 and
> PR #62 (MP-007 and MP-010 were reported BLOCKED then). PR #62 changed the switch order, recovery, quit and join handling, isolation and storage, so
> MP-001 to MP-009 need a run on a build of this PR (the index keeps the 2026-10-24 results visible as
> NEEDS RETEST; MP-007, extended for the PR #61 binding API, is READY FOR OWNER TEST there).
> MP-010 (two nodes) was blocked by the missing shared fenced repository; `RedisProfileRepository` is
> that precondition, and MP-010 now waits for a build of this PR and an owner run. Result sheets belong
> in [`06-RESULTS-TEMPLATE.md`](../../testing/owner/06-RESULTS-TEMPLATE.md).

## Commands

```bash
# Area suite (MultiProfile + Redis Fabric)
./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.awf.multiprofile.AwfMultiProfileTestSuite'

# Full server/API suite (the area suite and these tests are also selected by the main suite)
./gradlew :sourbycraft-server:test --tests 'dev.iyanz.SourbyCraftTestSuite'

# Repository policy/tooling suite
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Redis tests start a real `redis-server` on a free port (`RedisTestServer`). Without the binary they
skip; with `SOURBY_REQUIRE_REDIS=1` they fail instead of skipping.

## Test inventory

The area suite selects `dev.iyanz.sourbycraft.awf.multiprofile` and `dev.iyanz.sourbycraft.awf.redis.fabric`:
44 `@Test` methods on this branch.

| Class | `@Test` | Covers |
|---|---|---|
| `ProfileStateCodecTest` | 7 | all categories round-trip, deterministic bytes and checksum, truncation, trailing bytes, foreign payload refused before allocation, huge declared length, empty state |
| `FileProfileRepositoryTest` | 6 | account/profile/binding round-trip, latest snapshot + retention, corrupt snapshot reported, journal round-trip and listing, tombstone delete, no temp file left |
| `ProfileEngineTest` | 13 | slot limit, first switch commit, old-state snapshot, concurrent switch rejection, failed apply rollback, stale snapshot refusal, recovery forward, recovery rollback, stale-fence recovery refusal, active-profile switch refusal, active-profile delete refusal, concurrent creates keep every profile within the slot limit, public binding persists and refuses active-profile mutation |
| `RedisFabricServicesTest` | 12 | settings parsing, session TTL must exceed the heartbeat, session exclusivity and takeover epoch, refresh/release need the same incarnation, same-node token replacement, lease TTL units (`PTTL`), coordination fencing/monotonic fence/expiry takeover, cache bound/TTL/version invalidation/single flight, routing directory/least-loaded/profile owner, fabric probe and pool sizes |
| `EventFabricTest` | 6 | publish/consume/ack, handler failure stays pending, redelivery idempotency, delivery-budget dead letter, retention backpressure without a consumer group, unknown type acknowledged |
| `AuroraKeysTest` / `ConfigExamplesTest` | — | keys in the table and the golden example at their defaults (outside the area suite) |
| `UnboundedQueueAuditTest` | — | no unbounded queue introduced (existing policy test) |

PR #62 edited one existing assertion: `ProfileEngineTest.recoveryRefusesAJournalOlderThanTheCurrentFence`
now records the journal's target fence with the ownership incarnation, because recovery compares fences
only within one incarnation.

### Not covered by any test (pending for the owner or a test wave)

- `RedisProfileRepository`: `FENCED`/`STALE`/`MISSING` replies, a stale owner refused after a takeover,
  staged-snapshot expiry, retention with pinned snapshots, journal limits, the unmigrated-directory
  write refusal, `WAITAOF` confirmation and fallback, startup refusals (cluster, replica, old Redis,
  AOF off).
- The lost-commit-reply settle path, compensation with a player going offline after the apply, the
  new phase order and recovery rules, the first-switch archive, `ProfileRuntime.validate` refusals.
- `ProfileRepositoryMigration` (copy, verify, refusal when Redis holds data, re-run after a failure).
- `NodeIdentity` predecessor and fingerprint cases; `SessionFabric` predecessor takeover.
- Event publishing past retention with an acknowledging consumer group.
- `ProfileIoQueue` ordering and capacity, the freeze listener, the quit capture, the join return,
  adapter limits and carry-forward, infinite-duration effects in the codec, legacy armor slots.

The owner procedures for these behaviours are in
[03-EXPERIMENTAL-FEATURES-TESTING.md](../../testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) and
[Tester Owner](../../tester%20owner.md).

## Historical results (tree c117739, before PR #61 and PR #62)

Kept as history; they say nothing about the current branch. Those runs had `redis-server` installed,
so the Redis suites executed. The tree was hardened by a second session while that work was verified;
the numbers are from the final run after integrating its changes and the review fixes.

- Area suite: **42 tests, 0 failed**.
- Full `SourbyCraftTestSuite`: **2617 tests, 0 failed, 6 skipped** (2m25s).
- `scripts/test_*.py`: **723 tests, OK**; `scripts/patch_surface.py --check` passed.
- An earlier full run also passed (2614/0/6); one intermediate run failed only
  `VanillaFixesConfigurationTest.checkMojiraIssueStatuses` (external Mojira HTTP timeout), which
  passed in the final run and is unrelated to these changes.
- `:sourbyapi:compileJava`, `:sourbycraft-server:compileJava`, `compileTestJava`: success.
- `./gradlew slimServerJar`: success (`build/libs/SourbyCraft-slim.jar`, 41,557,420 bytes); the jar
  was not booted and the CI boot markers were not exercised.
- `verify_build_identity.py` was not run against that jar: the slim/paperclip layout does not expose
  `META-INF/sourbycraft-build.properties` at the top level, and that change did not touch build
  identity.

Fault injection covered then: truncated/corrupt snapshot; stale snapshot revision; failed target apply
(rollback); concurrent switch; duplicate session claim; takeover with a stale epoch; expired
coordination lease followed by a takeover (old fence rejected); handler exception/`false`; redelivery
of the same event id; delivery budget exceeded (dead letter); retention backpressure; cache
invalidation across two cache instances; Redis `DEL` of a lease key.

## Not run (explicit)

- **Nothing for PR #62**: no build, no test, no boot (see the top of this page).
- **No multi-node run.** Distributed mode and `repository: redis` are therefore EXPERIMENTAL and not
  qualified.
- **No benchmark.** See [BENCHMARKS.md](BENCHMARKS.md).
- **No server boot / CI boot gate with MultiProfile enabled** by an agent; the owner's 2026-10-24
  boot/join/switch/reconnect run predates PR #61 and PR #62.
- **No proxy/portal integration**; `RoutingFabric` has no transport.
- **No MultiProfile probe plugin**: the test plugins do not exercise `AuroraProfiles`.

These omissions are why the feature is documented as implemented but not production-qualified.
