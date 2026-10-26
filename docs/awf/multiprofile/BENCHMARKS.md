# Benchmarks

**No benchmark was run for this feature on this branch.** This page exists so the absence is explicit
and the method is fixed before anyone quotes a number. The Build 50 PR #62 code it describes (the
profile I/O queue, the Redis repository) is code written and statically reviewed; not built; not
tested by the owner.

## What would be measured

| Scenario | Metric | Why it matters |
|---|---|---|
| 100 / 1,000 / 10,000 stored profiles | startup journal index time, profile listing latency, snapshot directory size (file) or Redis memory (redis) | the file repository indexes journals in memory once at startup (one directory scan; journals of earlier builds are read once) but still reads one file per profile to list an account; the point where that hurts is unknown |
| Concurrent switches (1, 4, 16 players) | p50/p99 switch duration, failed/timed-out count, engine queue wait **and** storage-lane queue depth | profile steps run one at a time through the engine's own FIFO (`ProfileIoQueue`, capacity 4096) and hold at most one slot of the storage lane (one thread and a queue of 64 by default); there is no per-account parallelism |
| World lifecycle under load | time from `switchProfile` to world ready, join world preparation, idle unload cost | world creation is staged on the global tick; joins of players with bound profiles may now load or create worlds |
| Snapshot serialisation | bytes and milliseconds per capture/apply for a full inventory | the 8 MiB bound and SHA-256 cost are untested at scale; quit captures run on the region thread |
| Redis round trip | mean/p99 of claim, validate, fenced commit script, and `WAITAOF` wait | a Redis-backed switch adds claim and commit scripts plus `WAITAOF` on the writes later steps rely on (up to about 1 s each with `appendfsync everysec`) |
| Redis memory | bytes per profile with `repository: redis` | each profile keeps up to 3 snapshots (up to 8 MiB each) plus pinned ones, archives are never pruned, and staging transiently doubles a snapshot |
| Memory | retained heap per idle profile (expected: none for snapshots, which are on disk or in Redis) | avoid claiming a footprint without measuring |
| Backpressure | publish refusals per second at retention, consumer catch-up rate | the stream refuses publishes once a group's lag plus pending (or, without a group, the stream length) reaches retention |

## Method (not yet executed)

1. Build a candidate jar with `./gradlew slimServerJar`.
2. Use the existing Redis test server (`RedisTestServer`) for the fabric scenarios, or a dedicated
   Redis with the same TLS/ACL and AOF settings as production.
3. Generate profiles through `AuroraProfiles.createProfile` on an I/O thread; record wall time with
   `System.nanoTime()` around batches of 100.
4. Drive switches with a fake `PlayerStatePort` for the storage-only numbers, and with real players
   for the end-to-end numbers; label the two separately.
5. Report hardware, JDK, config, Redis version and persistence settings, workload and the exact
   statistic. Never generalise a single run into a percentage claim.

## Current honest statement

The code paths exist; parts of them are unit-tested (historical runs, see [TESTING.md](TESTING.md));
performance is **unmeasured**. No throughput, latency or memory number in this repository should be
attributed to AWF MultiProfile or the Redis profile repository.
