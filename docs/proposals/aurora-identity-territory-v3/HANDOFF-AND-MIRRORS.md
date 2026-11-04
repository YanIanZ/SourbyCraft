# Cross-node Handoff, Border Routing and Read-Only Mirrors

**Status:** RFC. Leviathan's current multi-region *edge* feature is not territory transport. The proposed control messages and remote views do not exist in public build.

## Delivery levels

**Level A / first releasable tier:** trusted proxy-assisted player backend handoff, with prefetch, state checkpoint and brief boundary pause. Normal Minecraft backend switch can require a world/session reload; never promise zero visible transition.

**Level B / research:** proxy routes client chunk/entity views from several JVMs over one logical session. Requires packet entity-ID namespace, chunk subscription, signed-chat/session correctness, command/action routing and a consistent simulation model; not the first production gate.

The Minecraft Java transfer packet opens a new connection and follows login process. It is a different mechanism from backend switching behind an existing authenticated proxy connection (see official 1.20.5 notes).

## Player transfer protocol

All messages carry protocol version, operation ID, account UUID, profile UUID, identity revision, source/destination node+incarnation, session fencing epoch, territory key, deadline and authenticated transport binding. Never trust client-supplied fields.

| Phase | Source | Coordinator | Destination |
|---|---|---|---|
| DISCOVER | get next owner from directory | prove epoch/ready state | advertise capacity/address |
| PREPARE | announce player/character and destination | reserve handoff tx | prefetch world, plugin data/schema |
| QUIESCE | freeze relevant mutations | preserve original active session | remain non-mutable |
| SNAPSHOT | durable player/required plugin state | record snapshot hash/revision | validate snapshot access |
| FENCE | stop source session writes | atomically advance account session epoch | acquire new exclusive session |
| RESTORE | never apply post-fence state | validate tx/epoch | apply snapshot + AIE presentation |
| SWITCH | request proxy switch | authorize destination | acknowledge ready |
| COMMIT/ACK | release old runtime entity | close tx in authority | resume gameplay |

Unknown commit reply requires authoritative reconciliation. Failed PREPARE returns player to source. Failed after FENCE enters recovery; source can resume only after explicit new ownership epoch. Every operation idempotent by tx ID. No user-controllable command can bypass this barrier.

## Border detection and anti-thrashing

- Compute territory from authoritative player chunk, not client-reported position.
- Prefetch within configurable radius of boundary; never pre-authorize destination from mere proximity.
- Cluster adjacent/highly coupled territories using affinity when crossing rate is excessive. Hysteresis only applies to planning: do not let an out-of-bounds node continue simulating unauthorized chunks.
- Unknown destination or unavailable state -> prevent transition with safe message/hold; never send player into a backend that has not admitted their session.

## Read-only mirrors (experimental)

A node may subscribe to immutable neighbour chunk snapshots and ordered deltas for **display only**. Format includes world/dimension, chunk coordinates, territory owner epoch, snapshot revision, stream sequence and tombstones. On missing delta, owner takeover or digest mismatch, invalidate view and full-resync. Mirror must not become a normal tickable chunk holder, spawn authoritative entities or accept block edits.

`mirrorLag` and `resyncCount` must be observable. Stale render is permitted only within explicit UX tolerance; **stale interaction is not permitted**. Remote action requests are asynchronous, authorized, rate-limited, revision-checked and execute on the real owner thread. If not supported, deny rather than create local side effects.

## Entity and inventory migration

- Entity ID is stable as a logical world ID; internal runtime entity IDs can be remapped. Transfer is `STOPPED_SOURCE -> SNAPSHOT -> DESTINATION_PREPARED -> EPOCH_TRANSFER -> DESTINATION_ACTIVE`; duplicate runtime entity on both nodes is forbidden.
- Projectiles, mounts, leads, passengers, villagers, boss fights and area effects demand explicit compound handoff or affinity restrictions.
- Hopper/container transfer across territory owners is **not supported** in first production tier. Later design must use durable transfer intent and exactly-once ledger, not two uncoordinated inventory changes.
- Cross-owner redstone/fluids/explosion requires co-location or explicit bounded coordinator with gameplay semantics proven; do not hide missing ticks.

## Reconnect and client identity

The authenticated account UUID is unchanged. Reapply character presentation on destination only after state restoration. Duplicate player session on a second node must be fenced before it can interact. Proxy needs backend identity verification and private transport. Do not expose raw game state or cryptographic tokens in logs.

## References

- https://feedback.minecraft.net/hc/en-us/articles/26136167989005-Minecraft-Java-Edition-1-20-5-Armored-Paws
- https://docs.papermc.io/folia/reference/region-logic/
