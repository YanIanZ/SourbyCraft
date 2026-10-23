# Benchmarks

**No benchmark was run for this feature on this branch.** This page exists so the absence is explicit
and the method is fixed before anyone quotes a number.

## What would be measured

| Scenario | Metric | Why it matters |
|---|---|---|
| 100 / 1,000 / 10,000 stored profiles | index time, profile listing latency, snapshot directory size | the repository is a directory scan per account, not an index; the point where that hurts is unknown |
| Concurrent switches (1, 4, 16 players) | p50/p99 switch duration, failed/timed-out count, queue depth on the storage lane | the lane has one thread by default |
| World lifecycle under load | time from `switchProfile` to `WORLD_READY`, idle unload cost | world creation is staged on the global tick |
| Snapshot serialisation | bytes and milliseconds per capture/apply for a full inventory | the 8 MiB bound and SHA-256 cost are untested at scale |
| Redis round trip | mean/p99 of claim/validate/commit | distributed mode adds two or three round trips per switch |
| Memory | retained heap per idle profile (expected: none — snapshots are on disk) | avoid claiming a footprint without measuring |
| Backpressure | publish refusals per second at retention, consumer catch-up rate | the stream cap is a hard refusal, not a slow path |

## Method (not yet executed)

1. Build a candidate jar with `./gradlew slimServerJar`.
2. Use the existing Redis test server (`RedisTestServer`) for the fabric scenarios, or a dedicated
   Redis with the same TLS/ACL settings as production.
3. Generate profiles through `AuroraProfiles.createProfile` on an I/O thread; record wall time with
   `System.nanoTime()` around batches of 100.
4. Drive switches with a fake `PlayerStatePort` for the storage-only numbers, and with real players
   for the end-to-end numbers; label the two separately.
5. Report hardware, JDK, config, Redis version, workload and the exact statistic. Never generalise a
   single run into a percentage claim.

## Current honest statement

The code paths exist and are unit-tested; performance is **unmeasured**. No throughput, latency or
memory number in this repository should be attributed to AWF MultiProfile.
