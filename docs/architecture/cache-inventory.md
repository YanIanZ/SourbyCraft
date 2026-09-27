# Aurora cache and retention inventory

AURORA-TASKS F: "cache ownership/bounds inventory". This inventory covers every long-lived
collection in `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft`, as of 2026-09-26. It
comes from a source audit (`static`/instance maps, sets, lists, queues), not from a heap dump. A
heap-retention test under player churn is still open.

| Owner | Collection | Owned by / cleared when | Bound |
| --- | --- | --- | --- |
| `hud/HudBars` | `TPS_VIEWERS`, `RAM_VIEWERS`, `PERF_VIEWERS`, `ANY` (UUID) | Removed in `onQuit` (quit listener) and on toggle-off; cleared on shutdown | Online players |
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
| `execution/ResourceGovernor` | `lanes` | Process lifetime; threads released when idle | Number of `Lane` values (2) |
| `startup/StartupTimeline` | `PHASES` | Process lifetime | Boot stages + index phases |
| `startup/StartupIndexStage` | `last`, `lastGraph` | Replaced each boot | Jar count |
| `awf/AwfRegistry` | `worlds` | `unregister` | Library registry; the engine integration does not use it |
| `awf/AwfEngine` | `storages` | Removed when the engine closes the region storage (`AwfRegionStorage.close`) | Open region storages of listed worlds (3 per dimension: chunks, entities, POI) |
| `awf/AwfWorld` | `owned` (chunk → bytes), `lastAccess` | Replaced on write; clean chunks beyond `residentLimit` dropped LRU after each save and read back from the store | `residentLimit` + dirty chunks. The engine integration always passes `aurora.awf.resident-chunks` (default 1024); limit 0 (library default) is unbounded |
| `awf/AwfWorldStore` | `retainedNames` (object names per retained generation), `orphanCandidates` | Oldest generation dropped each commit; candidates cleared after each successful commit | `retained-generations` × chunks in the storage; failed-commit writes until the next success |
| `awf/AwfBackend.Registry` | `BACKENDS` | Process lifetime | Registered backends |
| `awf/AwfWorld` | `dirty` | Cleared by a successful save | ≤ `owned` |
| `awf/AwfWorld` | `pendingSaves` | Removed on completion | ≤ `STORAGE` queue + threads |
| `awf/LatencyRecorder` | ring | Overwritten | 1024 samples |

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
- Still open: player-disconnect, chunk-unload and world-unload retention need a live server and
  a heap comparison. A source audit cannot close them.
