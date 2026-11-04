# Aurora Region Fabric — Territory Sharding Architecture

**Status:** future-state RFC. **Not implemented** by AWF or the region scheduler. Current AWF storage owns one writable world/store per node, and Folia-derived regionizers dynamically merge/split regions *within one JVM*. A static territory is a cross-JVM ownership unit, not an existing Folia threaded region.

## Primary design choice

Partition by deterministic, immutable **territory key**: `(worldUUID, dimensionKey, territoryX, territoryZ, layoutVersion)`. Choose a fixed number of chunks per side, configurable per world at creation; initial research candidate: 256 × 256 chunks (4096 × 4096 blocks), subdivided into 32 × 32 chunk indexing tiles. These are **examples**, not performance-qualified tuning.

~~~java
int territoryX = Math.floorDiv(chunkX, territorySizeChunks);
int territoryZ = Math.floorDiv(chunkZ, territorySizeChunks);
~~~

Use floor division at negative coordinates. Territory IDs, layout version, dimension keys, world UUID and coordinate transform must persist across node changes. Once created, topology cannot be resized casually: changing the layout requires an offline rebalance/migration protocol and versioned manifest.

## Topology

- **Control plane:** persistent territory registry with owner node, incarnation, monotonically advancing fencing epoch, manifest revision, desired placement, lifecycle state and membership/affinity group. Expose read-only discovery. Redis cache is never authority.
- **Data plane:** SourbyCraft node runs Folia-local region ticks only for writable chunks it owns; normal foreign chunks are not installed as tickable holders. Each node may own many disconnected territories.
- **Persistence:** AWF Partitioned Storage: chunk/entity/POI/scheduled-tick snapshot + immutable object set, atomic authoritative manifest publish conditioned on fencing epoch/revision. Existing AWF FILE/Redis backends are not this storage format.
- **Global world authority:** independently versioned ownership of time/weather/gamerules/worldborder/raid/dragon and other global world state. Nodes subscribe; only authority can mutate. Cross-dimension/portal traffic has explicit destination ownership.
- **Proxy:** Leviathan routes authorized player sessions to the simulation owner. Its existing edge-region cluster and ingress protections do not imply gameplay sharding.

## State machine and invariant

`UNASSIGNED -> PREPARING -> ACTIVE -> DRAINING -> SNAPSHOTTING -> FENCED -> TRANSFERRING -> ACTIVATING -> ACTIVE`; errors enter `RECOVERING` or `QUARANTINED`. Every state transition has transaction ID and compare-and-swap preconditions.

For each territory, **no more than one valid simulation writer** and no stale commit. Node clocks do not confer ownership. Lease expiry permits takeover evaluation, not continued writes by both nodes. During partition, either pause simulation that cannot safely commit or decline unsafe operations; never fail open into a local writable copy.

## Three delivery levels

| Tier | Scope | Semantics |
|---|---|---|
| ARF-1 isolated world/island | Whole world instance has single writer; node placement between worlds | Maintain today’s AWF world-lease contract; first deliverable |
| ARF-2 territory handoff | One coordinate-contiguous world partitioned across nodes; proxy routes players at boundary | Explicit loading/handoff pause permitted, unsupported cross-border ticks declared |
| ARF-3 remote-view / transparent research | Read-only neighbouring visual chunks and remote entity representations, possibly packet-multiplexed player views | Not equivalent to vanilla semantics or seamless gameplay; cross-owner actions require strict routing |

Never describe ARF-2 as fully vanilla-seamless. Zero-reload transfer may require substantial proxy/packet stack work and remains research until verified.

## Border interaction policy

| Mechanic | Early safe policy | Research direction |
|---|---|---|
| Players | prefetch + freeze + proxy-authorized handoff | transparent client proxy |
| Entity/mobs | do not tick across boundary; transfer with identity/epoch or deny crossing | ordered entity migration |
| Redstone, pistons, block/fluid/scheduled ticks | co-locate dependent territories; otherwise refuse unsupported cross-owner changes | deterministic coordinated dependencies |
| Hoppers / containers | never double-authorize inventory transfer | durable transfer intent + pair commit |
| Explosion/projectiles | stop or co-locate when effect crosses ownership boundary | ordered owner RPC |
| Plugin edits | run on owner scheduler, authorize world/territory permission | asynchronous remote operation API |
| Weather, world time, gamerules | global world authority | versioned pub/sub with deterministic correction |
| Nether portals, End events | explicit destination shard; can fall back to single owner for boss areas | cross-dimension authority |

**Do not solve hazards by placing a writable ghost chunk on the neighbour JVM.** Folia regionizer enforces local ownership/adjacency, not cross-JVM exclusivity.

## Elastic territory affinity

Co-locate neighbouring territories with synchronous gameplay dependencies. Candidate telemetry: border interactions, synchronous redstone, hopper flows, active player path. Placement planner groups and recommends migration only when resource budget and migration cost justify it. Large connected machines may require one oversized group; do not claim linear horizontal scaling.

Affinity migration is safe only through drain -> durable snapshot -> epoch handover -> replay/verify -> resume. Pre-existing transactions referencing the old owner must be settled or rejected.

## Critical feasibility questions before ARF-2 coding

1. How does existing chunk lifecycle/ticket scheduling avoid ticking chunks whose ownership is remote?
2. How are level-wide metadata and global region data partitioned or proxied without NMS global data races?
3. How are player-specific view/entity tracking, inventory changes and signed chat state preserved across a backend change?
4. Which boundary events can be contained without violating vanilla expectations, and how are unsupported mechanics surfaced to operators?
5. Does on-demand storage prefetch obey latency and memory bounds on target hardware?
6. How do plugins compiled against `World#getChunkAt` behave in a world spanning nodes?

**Gate:** executable two-node local experiment with a fixed boundary and denial of foreign writes before any live user traffic. No measured throughput/hand-off target is claimed here.

## External references

- https://docs.papermc.io/folia/reference/overview/
- https://docs.papermc.io/folia/reference/region-logic/
- https://github.com/PureGero/MultiPaper (independent architectural reference, not a drop-in implementation)
