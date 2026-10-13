# SourbyCraft Next Generation

## Development Plan — SourbyCraft Engine, SourbySpark & Performance Observability

**Project:** SourbyCraft
**Update Type:** Major Architecture & Performance Update
**Java:** Java 25
**Architecture Direction:** SourbyCraft-first
**Primary Focus:** Performance, Observability, Profiling, Runtime Identity
**Upstream:** Paper / Canvas components may remain internally where required
**Public Identity:** SourbyCraft

---

# 1. Vision

The next SourbyCraft update must establish SourbyCraft as its own Minecraft server implementation rather than being perceived as:

```text
Canvas
  +
SourbyCraft patches
```

The desired architecture becomes:

```text
                    SOURBYCRAFT
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   Sourby Engine    Sourby Runtime   Sourby Observability
        │                │                │
        │                │         ┌──────┴───────┐
        │                │         │              │
        │                │    SourbySpark    Sourby HUD
        │                │
        └──────────┬─────┘
                   │
            Upstream Layer
                   │
             Paper / Canvas
                   │
               Minecraft
```

Canvas becomes an implementation dependency/upstream source.

It must no longer define SourbyCraft's product identity.

---

# 2. Core Goal

SourbyCraft should become:

> A Java 25 high-performance region-threaded Minecraft server platform with first-party profiling, observability, diagnostics, and performance tooling.

The major pillars are:

1. SourbyCraft-first architecture
2. Sourby runtime telemetry
3. SourbySpark
4. first-party TPS/MSPT/RAM commands
5. first-party HUD
6. advanced `/perf`
7. region-aware diagnostics
8. Java 25 optimization
9. resource efficiency
10. removal of unnecessary Canvas-facing identity

---

# 3. SourbyCraft Identity

## Current conceptual state

```text
Paper
 ↓
Canvas
 ↓
SourbyCraft
```

This is acceptable internally.

But it should NOT remain the user-facing architecture.

---

# 4. New conceptual state

Publicly:

```text
Minecraft
   ↓
SourbyCraft Engine
```

Internally:

```text
Minecraft
   ↓
Paper components
   ↓
Canvas components where still required
   ↓
SourbyCraft engine
```

The distinction is important.

SourbyCraft does not need to pretend Canvas never existed.

Instead, Canvas becomes:

> an upstream implementation source used by SourbyCraft.

Similar to how many server forks depend on previous server projects without presenting themselves as configuration packs for those projects.

---

# 5. Branding Cleanup

Audit all user-visible references to:

```text
Canvas
CanvasMC
Canvas Server
Canvas Engine
```

Categories:

```text
USER_VISIBLE
DEVELOPER_ONLY
UPSTREAM_REQUIRED
REMOVABLE
```

User-facing references should gradually become SourbyCraft equivalents where technically possible.

Examples:

```text
Canvas scheduler
```

becomes conceptually:

```text
Sourby Region Engine
```

while source attribution remains properly maintained.

---

# 6. Do Not Falsify Upstream

Source headers, licenses, copyright notices, and required upstream attribution MUST remain intact.

Rebranding means:

```text
product identity = SourbyCraft
```

not:

```text
pretend all upstream code was originally written by SourbyCraft
```

---

# 7. Sourby Runtime Core

Create a central SourbyCraft runtime architecture.

Concept:

```text
SourbyRuntime
│
├── SourbyTelemetry
├── SourbyMetrics
├── SourbyPerformanceMonitor
├── SourbyRegionMonitor
├── SourbyMemoryMonitor
├── SourbyGcMonitor
├── SourbyNetworkMonitor
├── SourbyChunkMonitor
├── SourbyEntityMonitor
├── SourbySchedulerMonitor
├── SourbyHudService
└── SourbySparkBridge
```

The runtime must provide first-party metrics rather than commands scraping unrelated systems separately.

---

# 8. Single Telemetry Source

All SourbyCraft performance commands should consume a common telemetry system.

Do NOT implement:

```text
/tps calculates TPS itself
/perf calculates TPS differently
/tpsbar calculates TPS again
Spark calculates another TPS
```

Instead:

```text
                  SourbyTelemetry
                        │
        ┌───────────────┼────────────────┐
        │               │                │
      /tps            /perf           SourbySpark
        │                                │
      HUD                           profiler viewer
```

This provides consistent readings.

---

# 9. SourbyTelemetry

Introduce:

```text
dev.iyanz.sourbycraft.telemetry
```

Suggested components:

```text
SourbyTelemetry
TickMetrics
RegionMetrics
MemoryMetrics
GcMetrics
CpuMetrics
ChunkMetrics
EntityMetrics
NetworkMetrics
SchedulerMetrics
ProcessMetrics
```

---

# 10. Telemetry Design

Metrics must be collected cheaply.

Do NOT perform expensive global scanning just to display `/perf`.

Use:

* counters
* rolling windows
* ring buffers
* already-known runtime statistics
* event-based counters
* region-local aggregation

---

# 11. Tick Metrics

Track:

```text
TPS 5s
TPS 10s
TPS 1m
TPS 5m
TPS 15m

MSPT current
MSPT average
MSPT p50
MSPT p95
MSPT p99
MSPT max
```

SourbyCraft should not rely solely on old global TPS concepts.

Region-aware metrics must be included.

---

# 12. Region Performance Metrics

Because SourbyCraft is region threaded, performance information must expose:

```text
active regions
ticking regions
region worker count
slowest region
region average MSPT
region p95
region p99
region task backlog
```

Example:

```text
Regions
Active: 48
Ticking: 37
Workers: 8

Worst Region:
world
chunk: 143, -82
MSPT: 38.4
Entities: 241
Chunks: 19
```

This is significantly more useful than global TPS alone.

---

# 13. SourbySpark

Introduce an integrated profiler system:

```text
SourbySpark
```

It should be based on Spark where useful but deeply integrated with SourbyCraft.

It should NOT simply be:

```text
spark.jar bundled into server
```

---

# 14. SourbySpark Objective

SourbySpark should understand concepts unique to SourbyCraft.

Examples:

```text
Sourby Region Thread
Sourby region ownership
Sourby scheduler
Sourby async I/O
Sourby chunk engine
Sourby entity engine
Sourby network workers
Sourby runtime services
```

---

# 15. Profiler Engines

Support profiler engines similar to upstream Spark where available.

Potential engines:

```text
Native async-profiler
Java sampling profiler
JFR integration
```

Preference on supported Linux environments:

```text
async-profiler
```

Fallback:

```text
Java profiler
```

---

# 16. SourbySpark Thread Classification

Profiler output should classify threads.

Example:

```text
SourbyCraft
│
├── Region Threads
├── Network
├── Async I/O
├── Chunk Workers
├── Scheduler
├── GC
├── Plugins
└── Other JVM
```

---

# 17. Region-Aware Profiling

SourbySpark must be capable of profiling:

```text
all regions

specific region

slowest region

specific world

specific player region
```

Potential command:

```text
/spark profiler start
```

extended with:

```text
/spark profiler start --regions
/spark profiler start --region world:12:-8
/spark profiler start --player YanIanZ
```

Exact syntax may evolve during implementation.

---

# 18. SourbySpark Metadata

Every generated profile should include SourbyCraft metadata.

Example:

```text
SourbyCraft Version
Build
Minecraft Version
Java Version
JVM
Operating System

Uptime

Player Count
Loaded Worlds
Loaded Chunks
Entity Count

Region Count
Region Workers

TPS
MSPT
RAM
CPU
GC
```

---

# 19. SourbySpark Context Events

Profiler data should optionally annotate important events.

Examples:

```text
player join
player quit

world load
world unload

chunk spike

GC pause

MSPT spike

plugin load

plugin exception

region overload
```

This allows a profile to explain:

> what happened when the server slowed down?

---

# 20. SourbySpark Plugin Attribution

Where technically possible, CPU usage should attribute execution to plugins.

Example:

```text
Plugin Time
├── Slimefun        24.4%
├── MythicMobs      14.8%
├── Oraxen           7.1%
├── LuckPerms        1.2%
└── Server Engine   52.5%
```

This MUST be based on profiler samples.

Do not invent CPU percentages.

---

# 21. SourbySpark Region Attribution

Additionally:

```text
Region Cost
├── world 12,-8       18.2%
├── world 13,-8       11.4%
├── world_nether 2,9   8.3%
└── remaining          ...
```

This becomes one of SourbyCraft's key advantages over generic profiling.

---

# 22. SourbySpark Memory

Provide:

```text
heap usage
committed heap
max heap

non-heap
native estimate where available

allocation rate

GC count
GC duration

top allocated classes

heap dump integration
```

Heap dump MUST remain explicit.

Never automatically dump large heaps.

---

# 23. SourbySpark Viewer

Phase 1 may continue using a compatible Spark viewer.

Long-term:

```text
Sourby Performance Viewer
```

could be created.

Possible future URL:

```text
spark.sourby.my.id
```

or:

```text
perf.sourby.my.id
```

The viewer should eventually understand:

* regions
* SourbyCraft services
* plugin attribution
* tick spikes
* GC
* chunks
* entities

---

# 24. Upstream Spark Compatibility

Keep compatibility with Spark profiler formats where practical.

This provides:

* known tooling
* easier development
* existing viewer support

Sourby-specific fields should be extensions.

---

# 25. TPS Command

Implement SourbyCraft-owned:

```text
/tps
```

Example:

```text
SourbyCraft Performance

TPS
5s    20.00
1m    19.98
5m    19.96
15m   19.99

MSPT
Current  8.42 ms
Avg      9.14 ms
P95     13.82 ms
P99     19.44 ms

Regions
Active   42
Slowest  17.21 ms

Status: EXCELLENT
```

---

# 26. TPS Interpretation

Status thresholds:

```text
EXCELLENT
GOOD
WARNING
CRITICAL
```

Thresholds must be static defaults or explicit config.

They must NOT change server performance configuration.

---

# 27. MSPT Command

Implement:

```text
/mspt
```

Example:

```text
SourbyCraft MSPT

Current:   8.42 ms
Average:   9.11 ms

P50:       8.64 ms
P95:      13.82 ms
P99:      19.44 ms
Maximum:  31.12 ms

Tick Budget:
50.00 ms

Headroom:
40.89 ms
```

---

# 28. MSPT Region Information

Optional:

```text
/mspt regions
```

Example:

```text
Top Slow Regions

1. world 12,-8
   24.2 ms

2. world 14,-8
   18.7 ms

3. world_nether 4,1
   14.6 ms
```

---

# 29. RAM Command

Implement:

```text
/ram
```

Example:

```text
SourbyCraft Memory

Heap
Used:       4.2 GB
Committed:  6.0 GB
Maximum:    8.0 GB

Usage:      52.5%

Non-Heap:
312 MB

Process RSS:
5.1 GB

Allocation:
184 MB/s

GC
Young: 18
Old:    0

Last GC:
42 ms
```

Only report metrics that can be measured reliably.

---

# 30. `/tpsbar`

Create first-party TPS HUD.

Command:

```text
/tpsbar
```

Toggle:

```text
/tpsbar on
/tpsbar off
```

Potential modes:

```text
/tpsbar bossbar
/tpsbar actionbar
```

---

# 31. TPS Bar Example

Bossbar:

```text
TPS 20.00 │ MSPT 8.4 ms │ P95 13.8 ms
```

Health indicator:

```text
20 TPS

████████████████████
```

Do not update every tick.

Recommended default refresh:

```text
1 second
```

---

# 32. `/rambar`

Implement RAM HUD:

```text
/rambar
```

Example:

```text
RAM 4.2 / 8.0 GB │ 52% │ GC 42 ms
```

Bossbar progress:

```text
used heap / max heap
```

---

# 33. Combined Performance HUD

Add:

```text
/perfbar
```

Example:

```text
TPS 20.0 │ MSPT 8.4 │ RAM 52% │ CPU 34%
```

This may become the recommended operator HUD.

---

# 34. HUD Modes

Support:

```text
BOSSBAR
ACTIONBAR
```

Optional later:

```text
SIDEBAR
```

Bossbar should be default.

---

# 35. HUD Player Preferences

HUD enable state can be stored per player.

Example:

```text
/tpsbar
/rambar
/perfbar
```

Each player controls their own diagnostic HUD where permissions allow.

---

# 36. HUD Permission

Examples:

```text
sourbycraft.command.tps
sourbycraft.command.mspt
sourbycraft.command.ram
sourbycraft.command.perf

sourbycraft.hud.tps
sourbycraft.hud.ram
sourbycraft.hud.perf
```

---

# 37. HUD Efficiency

HUD rendering MUST NOT become a new source of server lag.

Architecture:

```text
SourbyTelemetry
      ↓
shared snapshot
      ↓
HUD service
      ↓
players
```

Do NOT calculate full server metrics once for every player.

---

# 38. Shared Performance Snapshot

Create:

```java
PerformanceSnapshot
```

Conceptually containing:

```text
timestamp

TPS
MSPT

CPU
RAM

GC

regions

chunks
entities

network
```

HUD and commands consume the snapshot.

---

# 39. `/perf`

`/perf` becomes SourbyCraft's primary health command.

It must provide significantly more information than TPS alone.

---

# 40. `/perf` Default View

Example:

```text
SourbyCraft Performance
────────────────────────────

Server
Version      26.x
Build        43c
Java         25
Uptime       8h 42m

Players
Online       74 / 500

Tick
TPS          19.99
MSPT         11.42 ms
P95          17.81 ms
P99          26.14 ms

CPU
Process      41%
System       57%

Memory
Heap         4.2 / 8.0 GB
RSS          5.1 GB
Allocation   180 MB/s

Regions
Active       53
Workers       8
Slowest      24.3 ms

World
Chunks       8,421
Entities     12,441

Network
Inbound      4.2 MB/s
Outbound     11.8 MB/s

GC
Last Pause   18 ms
Total Pause  1.4s

Status
GOOD
```

---

# 41. `/perf tick`

Provide:

```text
/perf tick
```

Information:

```text
TPS
MSPT
tick percentiles

slow ticks

tick budget

region timings
```

---

# 42. `/perf cpu`

Provide:

```text
/perf cpu
```

Information:

```text
process CPU
system CPU

available processors

region worker utilization

top server thread categories
```

Never pretend Java can reliably provide per-core hardware readings when unavailable.

---

# 43. `/perf memory`

Provide:

```text
/perf memory
```

Information:

```text
heap
committed heap
maximum heap

non-heap

RSS

allocation rate

GC
```

---

# 44. `/perf gc`

Provide:

```text
/perf gc
```

Information:

```text
GC implementation

young collections

old/full collections

pause count

average pause

p95 pause

maximum pause

last pause
```

---

# 45. `/perf region`

Provide:

```text
/perf region
```

Information:

```text
region count
worker count
region backlog
top slow regions
```

---

# 46. `/perf region <world> <x> <z>`

Inspect specific region:

```text
Region
World       world
Coordinates 12,-8

MSPT        17.44
Entities    184
Chunks      14

Players     8

Tasks
Pending     3

Status      GOOD
```

---

# 47. `/perf player <player>`

Locate the player's current region.

Example:

```text
Player: YanIanZ

World:
world

Chunk:
432,-221

Region:
27,-14

Region MSPT:
11.2 ms

Nearby:
Entities 82
Chunks 17
```

Useful for:

> "Why is this player's area lagging?"

---

# 48. `/perf chunks`

Display:

```text
loaded chunks
ticking chunks

chunk load rate
chunk unload rate

generation rate

save backlog

average load latency
```

Only expose metrics with reliable instrumentation.

---

# 49. `/perf entities`

Display:

```text
entities total
ticking entities

mobs
items
projectiles
block entities
```

Potential future extension:

```text
highest entity regions
```

---

# 50. `/perf network`

Display:

```text
connections

incoming packets/s
outgoing packets/s

inbound bytes/s
outbound bytes/s

compression statistics

network queue health
```

---

# 51. `/perf scheduler`

Display:

```text
region task queues

Sourby async tasks

I/O tasks

CPU worker tasks

queue depths

task latency
```

---

# 52. `/perf plugins`

Where profiling information is available:

```text
Plugin Execution

Plugin           Time
MythicMobs       12.4%
Slimefun          7.8%
Oraxen            3.1%
Other             ...
```

Must be based on actual samples/instrumentation.

Never estimate plugin CPU use arbitrarily.

---

# 53. `/perf health`

Provide concise diagnostics:

```text
SourbyCraft Health

Tick          GOOD
CPU           GOOD
Memory        GOOD
GC            EXCELLENT
Regions       WARNING
Network       GOOD

Primary concern:
Region world 12,-8
P95 MSPT 42.8 ms
```

---

# 54. Health Engine

Create:

```text
SourbyHealthEvaluator
```

Its job:

```text
telemetry
   ↓
classification
   ↓
diagnostic message
```

NOT:

```text
telemetry
   ↓
change configuration
```

---

# 55. Recommendations

`/perf health` may provide suggestions.

Example:

```text
High entity activity detected in world region 12,-8.
Use /perf region world 12 -8 for details.
```

Allowed.

NOT allowed:

```text
Automatically reduced mob spawning.
```

---

# 56. No Auto-Tuning

This remains an absolute requirement.

SourbyCraft MUST NOT automatically:

```text
change view distance
change simulation distance
change entity limits
change AI intervals
change chunk limits
change RAM values
change JVM args
change GC
change compression
disable mechanics
rewrite config
```

Telemetry diagnoses.

Operators decide.

---

# 57. Performance Incident Detection

SourbyCraft may detect abnormal performance events.

Example:

```text
MSPT > configured warning threshold
```

It may record:

```text
timestamp
region
entities
chunks
GC state
CPU
RAM
```

---

# 58. Performance Incident Ring Buffer

Keep a small bounded incident history.

Example:

```text
Last 20 performance incidents
```

Command:

```text
/perf history
```

---

# 59. `/perf history`

Example:

```text
Last Performance Events

01:43:12
MSPT spike 78.2 ms
Region world 12,-8

01:42:48
GC pause 63 ms

01:39:01
Region task backlog 84
```

This data must be bounded.

---

# 60. SourbySpark Link

`/perf` may provide:

```text
Profile required?
Run:
/spark profiler start
```

or Sourby equivalent:

```text
/perf profile start
```

---

# 61. `/perf profile`

Eventually wrap SourbySpark:

```text
/perf profile start
/perf profile stop
/perf profile status
/perf profile region
```

This creates one integrated SourbyCraft troubleshooting experience.

---

# 62. Command Architecture

Target command tree:

```text
/tps
/mspt
/ram

/tpsbar
/rambar
/perfbar

/perf
    tick
    cpu
    memory
    gc
    region
    player
    chunks
    entities
    network
    scheduler
    plugins
    health
    history
    profile
```

---

# 63. Unified Formatting

All diagnostic commands use one renderer.

Example components:

```text
PerformanceRenderer
MetricFormatter
HealthColor
DurationFormatter
MemoryFormatter
PercentFormatter
```

Avoid each command having unrelated formatting.

---

# 64. MiniMessage

User-facing output should use Adventure/MiniMessage where appropriate.

Example conceptual palette:

```text
SourbyCraft        aqua/blue
Excellent          green
Good               green
Warning            yellow
Critical           red
Secondary           gray
```

No hard requirement on exact colors.

---

# 65. Hover Information

Where supported, metrics should expose deeper details using hover text.

Example:

```text
MSPT 8.4ms
```

Hover:

```text
P50 7.8ms
P95 12.2ms
P99 18.6ms
```

---

# 66. Clickable Diagnostics

Example:

```text
[VIEW REGION]
```

click:

```text
/perf region world 12 -8
```

---

# 67. Performance Status Model

Define:

```text
EXCELLENT
GOOD
WARNING
CRITICAL
```

for:

* tick
* CPU
* memory
* GC
* network
* region

Thresholds should be explicitly configurable if exposed.

No automatic tuning.

---

# 68. SourbyCraft Performance API

Expose read-only telemetry API.

Example concept:

```text
SourbyPerformance
```

Providing:

```text
tps()
mspt()
memory()
cpu()
regions()
gc()
chunks()
entities()
network()
```

---

# 69. API Read-Only Principle

Plugins may read SourbyCraft telemetry.

Plugins MUST NOT use the performance API to internally mutate SourbyCraft configuration unless a separate explicit API exists for a legitimate purpose.

---

# 70. SourbyCraft Server Branding

Startup should emphasize:

```text
SourbyCraft
Java 25
Minecraft version
Build
Region Engine
Profiler status
```

Example:

```text
SourbyCraft 27.x
Build 1s

Java 25
Region Engine initialized
SourbySpark initialized
Performance telemetry initialized
```

---

# 71. Build Identifier

Consider replacing Canvas-era suffix:

```text
42c
```

with a SourbyCraft-native build identifier.

For example:

```text
43s
```

or simply:

```text
build 43
```

Recommended:

```text
SourbyCraft 27.x
Build 43
```

Remove architectural upstream letters from the public version.

---

# 72. `/version`

Expected output:

```text
SourbyCraft 27.x

Minecraft   ...
Build       ...
Java        25
Channel     Stable
Commit      abcdef1

Performance Engine
Sourby Region Runtime

Profiler
SourbySpark
```

Canvas should not be the headline product.

---

# 73. Upstream Information

Optionally:

```text
/version upstream
```

can provide engineering information:

```text
Paper revision
Canvas revision
Minecraft mappings
```

Useful for debugging without dominating product identity.

---

# 74. Canvas Configuration Migration

Long-term goal:

```text
Canvas-owned user-facing configuration
                ↓
SourbyCraft-owned configuration
```

But DO NOT perform a huge destructive migration immediately.

Use staged migration.

---

# 75. Config Direction

Long-term conceptual layout:

```text
config/
└── sourbycraft/
    ├── server.toml
    ├── performance.toml
    ├── network.toml
    ├── security.toml
    └── worlds/
```

This is a future architecture target.

---

# 76. No Automatic Config Rewrite

Existing Canvas configuration must not be silently rewritten.

Migration should be explicit.

For example:

```text
Legacy Canvas config detected.
See migration documentation.
```

---

# 77. Phase 0 — Baseline

Before architecture changes:

Capture:

```text
TPS
MSPT
CPU
RAM
allocation
GC
regions
chunks
entities
network
```

under reproducible workloads.

Create JFR profiles.

This is the baseline.

---

# 78. Phase 1 — SourbyCraft Identity

Tasks:

* clean public Canvas branding
* update server banner
* update `/version`
* establish SourbyCraft runtime naming
* establish build naming
* document upstream relationship
* create Sourby runtime package architecture

Goal:

```text
SourbyCraft feels like SourbyCraft.
```

---

# 79. Phase 2 — SourbyTelemetry

Implement the first-party metrics engine.

Required first metrics:

```text
TPS
MSPT

CPU

heap
RSS

GC

regions

chunks
entities
```

Everything else depends on this phase.

---

# 80. Phase 3 — Core Commands

Implement:

```text
/tps
/mspt
/ram
```

All read from SourbyTelemetry.

Remove duplicated calculations.

---

# 81. Phase 4 — Performance HUD

Implement:

```text
/tpsbar
/rambar
/perfbar
```

Architecture:

```text
shared telemetry snapshot
        ↓
HUD renderer
        ↓
players
```

---

# 82. Phase 5 — `/perf`

Implement the SourbyCraft performance command framework.

Initial:

```text
/perf
/perf tick
/perf cpu
/perf memory
/perf gc
/perf region
/perf health
```

---

# 83. Phase 6 — SourbySpark

Integrate/fork Spark.

First objectives:

* Sourby branding
* Sourby runtime awareness
* region thread classification
* Sourby metadata
* Java 25 compatibility
* async-profiler support
* memory profiler
* server health
* Sourby telemetry bridge

---

# 84. Phase 7 — Region Profiler

Add:

```text
region CPU attribution
slow region identification
region profile filtering
region metadata
```

This should become one of SourbyCraft's signature performance features.

---

# 85. Phase 8 — Advanced `/perf`

Add:

```text
/perf player
/perf chunks
/perf entities
/perf network
/perf scheduler
/perf plugins
/perf history
/perf profile
```

---

# 86. Phase 9 — Performance Incident Engine

Implement bounded event recording.

Detect:

```text
slow ticks
slow regions
GC pauses
queue backlog
memory pressure
```

Do not modify gameplay.

---

# 87. Phase 10 — Deep Performance Optimization

Once telemetry exists, use SourbyCraft's own measurements to optimize:

```text
entity ticking
AI
chunk system
network
scheduler
memory allocation
world saving
packet processing
region balancing internals
```

Performance work becomes data-driven.

---

# 88. Phase 11 — SourbyCraft Config Ownership

Gradually move SourbyCraft-specific operator settings into SourbyCraft-owned config.

Do not automatically rewrite Canvas files.

Provide migration documentation.

---

# 89. Phase 12 — Remove Redundant Legacy Layers

Audit:

```text
old Folia code
old Canvas wrappers
Sourby legacy systems
duplicate profiler code
duplicate metrics
unused config
unused executor infrastructure
```

Remove only after validation.

---

# 90. SourbySpark Packaging

Recommended project structure:

```text
SourbyCraft
│
├── sourbyapi
├── sourbycraft-server
│
├── sourby-telemetry
│
├── sourby-performance
│
├── sourby-hud
│
└── sourby-spark
```

Exact Gradle module boundaries may differ.

Avoid unnecessary modules if they make patch management harder.

---

# 91. SourbySpark License Requirement

If SourbySpark directly forks or incorporates GPLv3 Spark implementation code:

* preserve copyright notices
* preserve GPLv3 requirements
* provide corresponding source as required
* document modifications
* clearly identify Sourby-specific changes

Do not relicense upstream Spark implementation as proprietary Sourby code.

---

# 92. Performance Overhead Budget

Telemetry must have extremely low overhead.

Target:

```text
< 0.5% CPU under normal workload
```

for core telemetry where feasible.

HUD + metrics combined should target negligible impact.

Profiler overhead is treated separately because profiling is an explicit diagnostic operation.

---

# 93. Collection Frequency

Recommended baseline:

```text
TPS/MSPT       tick-derived

CPU            1 second

Memory         1 second

GC             event based

HUD            1 second

Region summary 1 second

Disk           5-10 seconds
```

Avoid reading expensive OS metrics every tick.

---

# 94. Rolling Metrics

Use bounded rolling windows.

Example:

```text
TickDurationRingBuffer
```

Maintain enough observations for:

```text
p50
p95
p99
maximum
```

No unbounded historical collection.

---

# 95. Snapshot Architecture

Suggested:

```text
Live counters
     ↓
Metric Aggregator
     ↓
PerformanceSnapshot
     ↓
Commands / HUD / SourbySpark
```

Snapshots should be immutable.

---

# 96. Example Java Model

Conceptually:

```java
public record PerformanceSnapshot(
        TickSnapshot tick,
        CpuSnapshot cpu,
        MemorySnapshot memory,
        GcSnapshot gc,
        RegionSnapshot regions,
        WorldSnapshot world,
        NetworkSnapshot network
) {}
```

Implementation may vary.

---

# 97. Thread Safety

Metrics should prefer:

```text
thread confinement
region-local counters
LongAdder where justified
immutable snapshot publication
```

Avoid giant synchronized metric maps.

---

# 98. Avoid Metric Distortion

Performance monitoring must never materially influence the thing being measured.

For example:

Do not iterate every entity once per second solely to count entities if the server already maintains entity counters.

---

# 99. Primary Success Criteria

The update succeeds when:

1. SourbyCraft is clearly the public server identity.
2. Canvas is treated as upstream, not product identity.
3. Java 25 remains the required runtime.
4. SourbyCraft owns its telemetry architecture.
5. `/tps` is SourbyCraft-native.
6. `/mspt` is SourbyCraft-native.
7. `/ram` is SourbyCraft-native.
8. `/tpsbar` works.
9. `/rambar` works.
10. `/perfbar` works.
11. `/perf` becomes the central performance diagnostic command.
12. `/perf region` understands region threading.
13. SourbySpark understands SourbyCraft runtime threads.
14. SourbySpark profiles regions.
15. SourbySpark exposes SourbyCraft metadata.
16. memory and GC information is available.
17. CPU information is available.
18. chunk/entity information is available.
19. performance incident history exists.
20. monitoring overhead remains minimal.
21. no auto config exists.
22. no automatic gameplay degradation exists.
23. no hidden tuning exists.
24. performance optimization is based on telemetry.
25. existing plugin compatibility remains intact.

---

# 100. Release Theme

Suggested major-update identity:

```text
SourbyCraft Performance Core
```

or:

```text
SourbyCraft Next
```

or:

```text
SourbyCraft Performance Engine
```

Internal codename possibility:

```text
Project Pulse
```

because this update gives SourbyCraft visibility into the "pulse" of the entire server.

---

# 101. Final Target

After this update, server administrators should be able to type:

```text
/perf
```

and immediately understand:

```text
Is the server healthy?

Is TPS bad?

Is MSPT bad?

Which region is slow?

Is CPU saturated?

Is memory under pressure?

Is GC causing pauses?

Are chunks the problem?

Are entities the problem?

Is networking overloaded?

Is a plugin consuming CPU?

Should I start a profiler?
```

without installing several unrelated diagnostic plugins.

The performance pipeline becomes:

```text
                    SOURBYCRAFT
                         │
                  Runtime Engine
                         │
                  SourbyTelemetry
                         │
       ┌─────────────────┼─────────────────┐
       │                 │                 │
     Commands           HUD           SourbySpark
       │                 │                 │
 /tps /mspt /ram     /tpsbar        CPU Profiler
 /perf               /rambar        Memory
                     /perfbar        Region Profiler
                                      │
                                      ▼
                              Sourby Performance
                                  Analysis
```

SourbyCraft should no longer merely inherit a high-performance engine.

It should **understand, measure, expose, profile, and optimize its own runtime**.
