# Server commands

Operator commands SourbyCraft adds or replaces. The performance views read the SourbyCraft metrics runtime; checking that `/tps`, `/mspt`, `/perf`, the HUD bars and Spark agree is an open Build 48 gate (HUD bars need a real client).

| Command | Purpose |
|---|---|
| `/tps` | SourbyCraft TPS overview |
| `/mspt` | tick-duration / MSPT overview |
| `/ram` | first-party memory information as implementation reaches feature-complete state |
| `/perf` | primary SourbyCraft performance overview |
| `/tpsbar` | TPS performance HUD |
| `/rambar` | RAM performance HUD |
| `/perfbar` | combined performance HUD |
| `/sys` | server/JVM/host diagnostics |
| `/ping [player]` | latency + offline GeoIP where enabled |
| `/ver` · `/version` | SourbyCraft build/runtime information |
| `/plugins` | Plugin list coloured by Aurora compatibility state (native / bridged / failed / disabled) |
| `/maxp [n]` | max-player management |
| `/perf lanes` | where the machine's time went, by execution lane |
| `/spec` | full machine specification: processor, clock, cores, memory and heap allocation |
| `/perf async` | async-path pool: solve times, queue wait and depth, and how many solves saturation refused (they are never run on the region thread) |
| `/perf network` | wire bytes and packets per second in each direction, totals, connections (`aurora.network.counters`) |
| `/perf governor` | Resource Governor lanes: budget, queue, submitted/completed/failed/rejected |
| `/perf storage` | chunk-system storage reads/writes/deletes since start, and each world's pending storage I/O tasks (chunk/POI/entity) |
| `/perf plugins` | plugin counts by compatibility state, bridge mode, and each bridged plugin's redirects/handoffs/rejections/violations |
| `/perf history` | the last hour, one line per minute: worst region TPS and MSPT, process CPU, heap (60-sample ring) |
| `/perf player <name>` | a player's world, block, chunk, ping, view/simulation distance, read on the thread that owns the player |
| `/perf chunks`, `/perf entities` | loaded chunks and regions, entities and players per world, summed from each region's own counters |
| `/perf awf` | Aurora World Fabric storages: resident/dirty chunks, pending commits, commit p50/p95/p99, reads vs region-file fall-throughs, failures |
| `/perf region` | region count and MSPT spread, plus the slowest active region (world/region/generation ids) |
| `/plugins <plugin>` | one plugin's compatibility state and, when bridged, its Aurora Bridge telemetry |
| `/update` | SourbyCraft updater status/check |

Implemented `/perf` views: `tick`, `cpu`, `memory`, `gc`, `lanes`, `async`, `network`,
`governor`, `scheduler`, `incidents`, `storage`, `awf`, `plugins`, `history`, `player <name>`, `chunks`, `entities`, `region`, `health`.
`/perf profile` is planned, not implemented.

Region-threading rules for command authors: [region-threaded-commands.md](region-threaded-commands.md).
