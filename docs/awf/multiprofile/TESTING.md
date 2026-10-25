# Testing

Build 50 candidate update (2026-10-25): distributed startup is refused because the FILE profile
repository cannot atomically fence its writes with Redis ownership. Distributed protocols below
are design requirements, not available production behavior. Use `mode: standalone` with an
exclusive profile directory. Current changes/evidence: [readiness](../../releases/BUILD-50-READINESS.md)
and [Tester Owner](../../tester%20owner.md). No tests/build were run for this candidate by the agent.

> **Owner-managed testing.** The results below were produced by coding agents during implementation;
> they are implementation evidence, not qualification. No runtime result in this repository is
> accepted until the project owner runs it. The owner-facing procedures and status dashboard are in
> [`docs/testing/owner/00-TESTING-INDEX.md`](../../testing/owner/00-TESTING-INDEX.md); distributed
> mode, real-player switching and any multi-node behaviour are `READY FOR OWNER TEST`/`BLOCKED`
> there, never "passed". Owner report 2026-10-24: MultiProfile testing is complete; MP-007 and
> MP-010 remain BLOCKED and the result sheets belong in `docs/testing/owner/06-RESULTS-TEMPLATE.md`.

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
skip; with `SOURBY_REQUIRE_REDIS=1` they fail instead of skipping. The runs below had
`redis-server` installed, so the Redis suites executed.

## Test inventory

| Class | Covers |
|---|---|
| `ProfileStateCodecTest` | all categories round-trip, deterministic bytes, checksum, truncation, trailing bytes, corrupt length |
| `FileProfileRepositoryTest` | account/profile/binding round-trip, latest snapshot + retention, checksum refusal, journal round-trip, tombstone delete, no temp file left |
| `ProfileEngineTest` | slot limit, first switch commit, old-state snapshot, concurrent switch rejection, failed apply rollback, stale snapshot refusal, recovery forward, recovery rollback, stale-fence recovery refusal, active-profile switch refusal, active-profile delete refusal |
| `RedisFabricServicesTest` | settings parsing, session exclusivity/takeover/stale validation, lease TTL units (`PTTL`), same-node re-claim, coordination fencing/monotonic fence/expiry takeover, cache bound/TTL/version invalidation/single flight, routing directory/least-loaded/profile owner, fabric probe and pool sizes |
| `EventFabricTest` | publish/consume/ack, handler failure stays pending, redelivery idempotency, delivery-budget dead letter, retention backpressure, unknown type acknowledged |
| `AuroraKeysTest` / `ConfigExamplesTest` | new keys are in the table and the golden example matches defaults (existing tests, extended expectations) |
| `UnboundedQueueAuditTest` | no unbounded queue was introduced (existing policy test) |

## Results on this tree

- Area suite: **42 tests, 0 failed**.
- Full `SourbyCraftTestSuite`: **2617 tests, 0 failed, 6 skipped** (2m25s).
- `scripts/test_*.py`: **723 tests, OK**; `scripts/patch_surface.py --check` passed.
- An earlier full run also passed (2614/0/6); one intermediate run failed only
  `VanillaFixesConfigurationTest.checkMojiraIssueStatuses` (external Mojira HTTP timeout), which
  passed in the final run and is unrelated to these changes.
- The tree was hardened by a second session while this work was verified; the numbers above are from
  the final run after integrating its changes and the review fixes.
- `:sourbyapi:compileJava`, `:sourbycraft-server:compileJava`, `compileTestJava`: success.
- `./gradlew slimServerJar`: success (`build/libs/SourbyCraft-slim.jar`, 41,557,420 bytes).
- `verify_build_identity.py` was not run against that jar: the slim/paperclip layout does not expose
  `META-INF/sourbycraft-build.properties` at the top level, and this change does not touch build
  identity.

## Fault injection covered

Truncated/corrupt snapshot; stale snapshot revision; failed target apply (rollback); concurrent switch;
duplicate session claim; takeover with a stale epoch; expired coordination lease followed by a
takeover (old fence rejected); handler exception/`false`; redelivery of the same event id; delivery
budget exceeded (dead letter); retention backpressure; cache invalidation across two cache instances;
Redis `DEL` of a lease key.

## Not run (explicit)

- **Agent-implementation runs did not boot with `aurora.awf.multiprofile.enabled: true`.** The owner
  reports the real boot/join/switch/reconnect run complete on 2026-10-24 (see
  `docs/testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md` §1); the result sheets are the owner's.
- **No multi-node run.** Distributed mode is therefore not qualified.
- **No benchmark.** See `BENCHMARKS.md`.
- **No server boot / CI boot gate** for this change. `slimServerJar` was built, but the jar was not
  booted and the CI boot markers were not exercised.
- **No proxy/portal integration**; `RoutingFabric` has no transport.

These omissions are why the feature is documented as implemented but not production-qualified.
