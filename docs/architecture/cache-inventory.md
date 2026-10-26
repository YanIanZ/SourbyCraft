# Aurora cache and retention inventory

AURORA-TASKS F: "cache ownership/bounds inventory". This inventory covered every long-lived
collection in `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft` as of 2026-09-26. It
comes from a source audit (`static`/instance maps, sets, lists, queues), not from a heap dump. A
heap-retention test under player churn is still open.

**Re-check 2026-10-25 (Build 50 branch, static reading).** A grep of class-level `Map`/`Set`/`List`/
`Queue`/`Deque` fields found the collections added since; the rows marked *2026-10-25* below were read
for their bound. The AWF, Redis and MultiProfile internals added since 2026-09-26 (`AwfEngine` maps
besides `storages`, `AwfRegionStorage`, `ObjectStore`, `AuroraWorldsService`, `WorldOperationGate`,
`RedisBackend`, `RedisClient`, `RedisStore`, `CacheFabric`, `ProfileEngine`, `FileProfileRepository`,
`LocalProfileOwnership`, `ProfileIoQueue`) are **not** re-verified in this pass, so the inventory is
not complete for them yet (PM-412 stays partial).

| Owner | Collection | Owned by / cleared when | Bound |
| --- | --- | --- | --- |
| `hud/HudBars` | `TPS_VIEWERS`, `RAM_VIEWERS`, `PERF_VIEWERS`, `ANY` (UUID) | Removed in `onQuit` (quit listener) and on toggle-off; cleared on shutdown | Online players |
| `hud/HudBars` | `TPS_ACTIONBAR`, `RAM_ACTIONBAR`, `PERF_ACTIONBAR` (UUID → session token) | Set with a fresh token on each actionbar enable or mode re-select; removed on toggle-off, switch to boss bar, perf retirement, `onQuit` and `close()` | Online players |
| `hud/HudBars` | `SESSIONS` (`AtomicLong` token counter) | Never reset, not even by `close()`; each actionbar subscription takes the next value | One `long`; no per-player storage |
| `util/GeoUtil` | `cache` (IP → location) | Cleared on shutdown | `MAX_CACHE` 1024; halves when full |
| `brand/PluginLoadDiagnostics` | `ENTRIES` (load failures) | Never cleared (process lifetime) | 16, oldest evicted |
| `brand/PluginLoadDiagnostics` | `ENABLE_FAILURES` (display names) | Never cleared | Plugins that failed to enable in this run |
| `SourbyCraftConfig` | `WARNED_KEYS` | Never cleared | Distinct malformed config keys |
| `command/SourbyCraftCommands` | `OURS` | Registration | Command count |
| `spark/SourbyServerConfigProvider` | `FILES` | Static, immutable | Fixed file list |
| `perf/RegionMetricsRegistry` | `generations` | Removed on retirement once the retention window expires | Live regions + recently retired |
| `bridge/BridgeRuntime` | `admitted` | Process lifetime | Admitted legacy plugins |
| `bridge/BridgeRuntime` | `scheduled` (task id → handle) | One-shot tasks removed after running; repeating tasks on cancel, disable or quarantine | Pending bridged tasks (the `BRIDGE_IO` queue bounds async bodies, not timers) |
| `bridge/BridgeTelemetry` | `plugins` | Process lifetime | Admitted legacy plugins |
| `execution/ResourceGovernor` | `lanes` | Process lifetime; threads released when idle | Number of `Lane` values (3: `BRIDGE_IO`, `STORAGE`, `PATHFINDING`; corrected 2026-10-25) |
| `startup/StartupTimeline` | `PHASES` | Process lifetime | Boot stages + index phases |
| `startup/StartupIndexStage` | `last`, `lastGraph` | Replaced each boot | Jar count |
| `awf/AwfRegistry` | `worlds` | `unregister` | Library registry; still unused by the engine integration (2026-10-25: no caller outside the class; `/perf awf` counts loaded AWF worlds from the open storages) |
| `awf/AwfEngine` | `storages` | Removed when the engine closes the region storage (`AwfRegionStorage.close`) | Open region storages of listed worlds (3 per dimension: chunks, entities, POI) |
| `awf/AwfWorld` | `owned` (chunk → bytes), `lastAccess` | Replaced on write; clean chunks beyond `residentLimit` dropped LRU after each save and read back from the store | `residentLimit` + dirty chunks. The engine integration always passes `aurora.awf.resident-chunks` (default 1024); limit 0 (library default) is unbounded |
| `awf/AwfWorldStore` | `retainedNames` (object names per retained generation), `orphanCandidates` | Oldest generation dropped each commit; candidates cleared after each successful commit | `retained-generations` × chunks in the storage; failed-commit writes until the next success |
| `awf/AwfBackend.Registry` | `BACKENDS` | Process lifetime | Registered backends |
| `awf/AwfWorld` | `dirty` | Cleared by a successful save | ≤ `owned` |
| `awf/AwfWorld` | `pendingSaves` | Removed on completion | ≤ `STORAGE` queue + threads |
| `awf/LatencyRecorder` | ring | Overwritten | 1024 samples |
| `bridge/BridgeRuntime` | `running`, `workers` *(2026-10-25)* | Released in `finally` when each async body ends | Running bridged bodies |
| `guards/LogWindows` (in `BridgeRuntime`, `ThreadChecks`, `GuardViolations`) | `windows` *(2026-10-25, PR #62)* | Expired windows dropped when a new key arrives at the cap (at most one sweep per 60 s) | 1024 (bridge), 256 (`ThreadChecks`), 256 (`GuardViolations` engine line) windows, plus one overflow window each |
| `guards/LegacyListenerNotice` | `SEEN` (event, plugin), `WALKED` (event → listener array, weak) *(2026-10-25)* | Never cleared outside tests | Event types × plugins; `WALKED` values weakly held so no plugin class loader is pinned |
| `guards/UndrainedQueue` | `reported` *(2026-10-25)* | Never cleared | `MAX_REPORTED_CALLERS` 64 |
| `perf/AsyncPathTypeStats` | `TYPES` *(2026-10-25)* | Process lifetime | `MAX_TYPES` 64, then one shared "other" slot |
| `perf/EntityTickCounters` | `worlds` *(2026-10-25)* | Process lifetime | Worlds loaded this run |
| `perf/RegionFileSaveLatency` | `recorders` (world, dimension, type) *(2026-10-25)* | Process lifetime | Dimensions × storage types loaded this run |
| `perf/IncidentLog` | `open` *(2026-10-25)* | Removed when the breach clears | `MAX_OPEN` 64 |
| `commandblock/CommandBlockDiagnostics` | `seen` (block + command) *(2026-10-25)* | Process lifetime | `MAX_KEYS` 4096, then counted only |
| `command/PingCommand` | `ACTIVE` (sender → ticket) *(2026-10-25)* | Expired tickets (30 s) swept on each new request; released when the ping finishes | 128 concurrent senders |
| `abysslink/AbyssLinkRuntime` | `players` (UUID → token) *(2026-10-25)* | Removed on quit; cleared when the runtime stops | Online players |
| `abysslink/AbyssLinkClient` | `pending`, `byPlayer`, `cache` *(2026-10-25)* | Answered or timed-out (`timeoutMillis`) requests leave `pending`/`byPlayer`; `cache` evicts its oldest entry when full | `maxPendingRequests` (AbyssLink setting) each |
| `mod/ModLoader` | `LOADED`, `CLASSLOADERS` *(2026-10-25)* | Cleared after the mods' `onUnload` (class loaders closed) and on reset | Mod jars loaded at boot |
| `execution/region/AnchorCache` | `entries` (region id → anchor, world weak) *(2026-10-25)* | Cleared at the cap; an entry whose world was collected is dropped on lookup | 4096 |
| `entity/teleport/AsyncFallbackLog` | `windows` *(2026-10-25)* | Process lifetime | 256 keys, then one overflow window |
| `security/AdmissionRateLimiter` | `peers` (address → window, access-ordered) *(2026-10-25)* | Least recently used entry evicted at capacity (at most one per admission, see `engine-network.md` §6) | Fixed `capacity` given to the constructor |
| `startup/PluginTimings` | `timings` *(2026-10-25)* | Process lifetime | Plugins loaded this run |

## Findings

- Every collection in this table is either bounded or tied to something bounded (online
  players, plugins, config keys, lanes). `AwfWorld.owned` is bounded when a `residentLimit` is
  set: clean chunks are dropped LRU after a save. Dirty chunks are never dropped, so unsaved
  work can exceed the limit until the next save. The engine integration always sets a limit.
- Completed work does not accumulate:
  - A one-shot bridged task leaves `scheduled` when it runs (`BridgeRuntimeTest`
    `aOneShotTaskIsForgottenAfterItRuns`).
  - Async path solves are accounted until completion and then released
    (`AsyncPathShutdownTest` `everyAdmittedSolveIsAccountedForUntilItCompletes`).
  - AWF pending saves leave the deque on completion (`AwfWorldTest`
    `aWriteDuringASaveStaysDirty`).
- AWF chunk-unload retention, unit level (2026-10-20): `AwfChunkUnloadRetentionTest` (4) tracks the
  exact byte arrays the world hands to `AwfStore.commit` with weak references. Chunks the resident
  limit drops (via `AwfWorld` and via `AwfRegionStorage` after `flush`) become collectable while the
  chunks it keeps stay reachable (control); a rewritten chunk releases its previous bytes without an
  eviction; 50 read-back cycles over evicted chunks leave the resident set at the limit with nothing
  dirty or pending. Mutation check: making the eviction count a chunk without removing it fails three
  of the four. It covers only the AWF layer, FILE backend: not the Minecraft side of an unload (chunk
  holders, entities, block entities), the Redis backend, or a heap comparison on a running server.
- Still open: player-disconnect (unit level: `HudBarsRetentionTest`, `BridgeRetentionTest`) and
  world-unload (unit level: `AwfEngineRetentionTest`) retention on a live server, and chunk-unload
  on a live server, all need a heap comparison. A source audit cannot close them.
