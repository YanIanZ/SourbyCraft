# AWF Partitioned World Storage — Specification

**Status: future implementation.** Existing Redis AWF lease is per world/store. This document designs an additive, versioned distributed format; it does not claim the format or API exists.

## Logical state layout

~~~text
world/<world-id>/
  global/metadata/<revision>
  layout/<layout-version>
  territory/<dimension>/<x>/<z>/
    manifests/<revision>
    objects/chunks/<sha256>
    objects/entities/<sha256>
    objects/poi/<sha256>
    objects/scheduled-ticks/<sha256>
    journals/<migration-id>
~~~

Physical backend may be object storage, distributed SQL metadata plus immutable blobs, or another explicitly qualified store. Keys are logical, not present AWF on-disk folder names. Never silently route legacy AWF world storage into this format. Compaction and garbage collection must not delete referenced objects until retention and active-reader epoch guarantees hold.

## Authoritative manifest

~~~text
TerritoryManifest {
 worldId, dimension, territoryKey, layoutVersion,
 ownerEpoch, revision, parentRevision,
 chunkIndexDigest, entityDigest, poiDigest, scheduledTickDigest,
 globalWorldRevision, serializationVersion, createdAt,
 objectReferences[], checksum
}
~~~

A commit snapshots all required state categories at a consistent simulation boundary. Immutable content is uploaded first and verified. **Publication of the manifest** is the atomic commit point and must be conditioned on expected revision **and current fenced epoch** in an authoritative transactional storage operation. If that operation cannot enforce both predicates, it cannot be the live writer backend.

Store ownership metadata and storage manifest authority must not diverge. If coordinator and storage are separate systems, design explicit token-validation and failure handling; do not presume atomicity across Redis+SQL+object store. Coordinator issues/revokes epochs; the manifest authority independently validates their monotonicity.

## Proposal: commit API

~~~java
CompletionStage<CommitResult> commitTerritory(
   TerritoryKey key,
   long expectedEpoch,
   long expectedRevision,
   ImmutableTerritorySnapshot snapshot,
   UUID operationId);
~~~

`CommitResult` distinguishes `CONFIRMED`, `STALE_EPOCH`, `STALE_REVISION`, `RETRYABLE_ERROR` and `UNKNOWN_COMMIT`. A missing reply after a potentially applied commit must be resolved by reading the authoritative manifest keyed by operationId; never retry as a new operation or declare success without evidence.

## Lease and fencing policy

- Each owner holds a fencing token bound to node identity + process incarnation + monotonically advancing epoch.
- Lease heartbeat is observational/admission control. **Every durable mutation** is fenced at storage commit; preflight checks alone do not suffice.
- Old owner loses ability to publish after takeover. If coordinator/cache fails, stop unsafe mutating operations. If published epoch decreases after Redis restore or failover, reject/quarantine until monotonic authority is repaired.
- File-based world write lock is not cross-server fencing and cannot support active multi-owner world partitioning.
- Node identity is stable across restart but every boot gets a distinct incarnation; copied volumes must not inherit live ownership.

## Migration and crash consistency

1. Mark territory DRAINING; prevent new mutations and await/cancel in-flight work through a budgeted barrier on the owning scheduler.
2. Persist exactly one consistent snapshot manifest with all referenced object hashes checked.
3. Mark source writer FENCED and atomically issue a greater epoch in authority; no old-epoch commits accepted.
4. Destination PREPARING loads exact manifest and confirms chunks, entities, POI and scheduled ticks consistent.
5. Destination ACTIVATE publishes ready, route sessions and resume. Retry by operation ID; return to source only through a **new** valid epoch.

Kill injection at every step must yield either old valid owner, new valid owner or safe quarantine, never both. Metadata may be behind the active checkpoint after a crash, but recovery must be deterministic.

## World-wide state and player-data

Separate versioned authority for time/weather/gamerules/worldborder and non-territory state. Do not concurrently run the vanilla global world tick on multiple nodes as if each were the only world. Player inventory/profile is not part of a territory manifest and follows its own transactional handoff, linked by tx IDs where relevant.

## Durability tiers and acknowledgements

Declare storage tier `memory-only` (dev), `AOF-confirmed` (under documented Redis limitations), or `transactional-durable` (manifest backend). A Redis command reply, AOF enabled, `WAITAOF` timeout, and replica failover are distinct durability evidence; do not blur them. This architecture requires a qualifying durable checkpoint for handoff and owner takeover.

## Resource protection

Bound chunk indices, snapshot size, object count, read queue depth, region-I/O lane usage, concurrent migrations and retries. Streaming verification rather than unbounded bulk buffering. Integrity: SHA-256 object digest, manifest checksum/version, safe length validation, no arbitrary Java object deserialization. Track disk pressure, p95/p99 commit latency and oldest dirty state.

## Backward compatibility and tooling

- New storage mode **off by default**, gated by schema-version and capability handshake.
- Explicit `offline export -> verify -> import -> compare -> publish` procedure for old AWF world files.
- Export/backup and snapshot inventory are required before enabling.
- Downgrading to old binaries must fail to mount newer schema writable; never silently create empty world.
- Owner provides the exact rollout policy, backup destination and retention before enabling.

## Test cases

Stale epoch commit, same-revision concurrent writes, kill before manifest publication, kill after manifest publication, lost reply, unreferenced object GC, hash mismatch, missing scheduled ticks, old owner revival, replica/Redis failure, negative region coordinates, giant snapshots and resource cap enforcement. See [OWNER-ACCEPTANCE-PLAN.md](OWNER-ACCEPTANCE-PLAN.md).
