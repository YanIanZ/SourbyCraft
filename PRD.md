# Product Requirements Document

## SourbyCraft — Java 25 & Performance Architecture Modernization

**Project:** SourbyCraft
**Repository:** `YanIanZ/SourbyCraft`
**Target Platform:** Minecraft 26.2
**Base Architecture:** Paper → CanvasMC → SourbyCraft
**Required Runtime:** Java 25
**Primary Objective:** Full codebase modernization, performance optimization, lower resource usage, and architectural cleanup without automatic configuration or automatic runtime tuning.

---

# 1. Background

SourbyCraft is a region-threaded Minecraft server fork built on top of CanvasMC.

The project already targets Java 25, but simply compiling using Java 25 does not mean the architecture and implementation fully benefit from modern Java.

Over time, SourbyCraft has accumulated:

* upstream Paper architecture
* Canvas-specific architecture
* Folia-derived region-threading components
* SourbyCraft-specific patches
* legacy compatibility code
* old asynchronous patterns
* duplicated abstractions
* unnecessary allocations
* potentially inefficient collections
* synchronization carried over from older architectures
* historical experimental performance patches
* legacy build infrastructure
* compatibility code from previous server bases

The objective of this project is to perform a **repository-wide modernization**.

This is NOT simply:

> Change Java version to 25.

Instead:

> Re-engineer SourbyCraft around Java 25, modern JVM practices, region-threaded execution, measurable performance improvements, explicit configuration, and maintainable architecture.

---

# 2. Core Principles

Every implementation decision MUST follow these principles.

## 2.1 Performance Must Be Measurable

No optimization may be merged solely because it "looks faster".

Performance-sensitive changes must be evaluated using:

* profiler data
* allocation profiling
* CPU profiling
* JFR
* GC statistics
* MSPT measurements
* tick timings
* benchmark workloads
* synthetic stress tests
* real server workload tests where possible

Do not perform speculative micro-optimization without evidence.

---

## 2.2 No Automatic Configuration

SourbyCraft MUST NOT contain a system that automatically changes server configuration.

Strictly prohibited:

* automatic performance configuration
* automatic config optimization
* hardware-based auto configuration
* player-count-based config adjustments
* TPS-based automatic config adjustments
* MSPT-based automatic configuration
* RAM-based configuration adjustment
* CPU-core-based configuration adjustment
* automatic view-distance changes
* automatic simulation-distance changes
* automatic mob limit changes
* automatic chunk configuration changes
* automatic GC/JVM argument modifications
* automatic configuration migration that rewrites operator files
* automatic "recommended settings" application
* configuration values silently changed at runtime

SourbyCraft may provide:

* metrics
* warnings
* diagnostic output
* profiler information
* documentation

But it MUST NOT automatically apply changes.

The final decision always belongs to the server administrator.

---

# 3. Configuration Immutability

Configuration must follow an explicit read-only runtime model.

After configuration loading completes:

```text
CONFIG FILE
    ↓
Parser
    ↓
Validation
    ↓
Immutable Config Snapshot
    ↓
Runtime
```

The runtime MUST NOT modify the operator's configuration file.

Configuration should preferably be represented internally using immutable objects.

Example:

```java
public record NetworkSettings(
        int compressionLevel,
        boolean useNativeTransport,
        int packetLimit
) {}
```

Instead of repeatedly reading mutable static configuration fields throughout hot paths.

Desired architecture:

```text
TOML / YAML
      ↓
Configuration Loader
      ↓
Validation
      ↓
Immutable Configuration
      ↓
Runtime Services
```

Missing or invalid values should result in:

* documented fallback values in memory
* warning messages when appropriate

They MUST NOT result in automatic rewriting of the file.

Configuration migration between releases must be:

* documented
* explicit
* manually controlled

No silent config rewrite.

---

# 4. Java 25 Baseline

Java 25 becomes the single supported Java runtime for this branch.

The whole project must be audited for Java version inconsistencies.

Audit:

* root project
* SourbyCraft server
* SourbyAPI
* SourbyClip
* Metal
* test harness
* utility modules
* CI
* Docker
* build scripts
* Gradle toolchains
* generated source
* patch modules

Required compiler target:

```text
Java Toolchain = 25
--release = 25
```

There is no requirement to maintain Java 17 or Java 21 compatibility for this modernization branch.

---

# 5. Preview Features Policy

Production SourbyCraft core MUST NOT require:

```text
--enable-preview
```

unless a separate architectural proposal explicitly approves it.

Avoid production dependency on Java 25 preview APIs.

Especially do not introduce preview APIs simply because they are new.

Stable Java features are preferred.

This ensures:

* simpler production deployment
* fewer JVM arguments
* easier maintenance
* predictable upgrades
* easier downstream development

---

# 6. Java Architecture Modernization

The entire SourbyCraft-owned Java codebase should be reviewed.

Modernize old Java patterns where they improve clarity, safety, or runtime behavior.

Candidates include:

### Immutable data

Use records for suitable data carriers.

```java
record ChunkPosition(int x, int z) {}
```

instead of mutable POJO objects where mutation is unnecessary.

### Sealed hierarchies

Use sealed interfaces/classes where a domain has a strictly controlled implementation set.

### Pattern matching

Modernize verbose type-dispatch code when Java 25 stable language features provide clearer implementations.

### Switch expressions

Replace unnecessarily verbose switch statements with expressions where appropriate.

### Local type inference

Use `var` where the type remains immediately obvious.

Do not use it when it makes performance-sensitive code harder to understand.

### Final-by-design architecture

Prefer immutable state and controlled mutation.

Avoid huge global static mutable state.

---

# 7. Dependency Architecture

Move away from uncontrolled static access where practical.

Avoid architectures similar to:

```text
Everything
    ↓
StaticManager
    ↓
StaticManager
    ↓
GlobalConfig
```

Prefer:

```text
Bootstrap
    │
    ├── Config
    ├── Scheduler
    ├── Storage
    ├── Network
    ├── Metrics
    └── Services
```

with explicit ownership.

Services should have:

* clear lifecycle
* clear owner
* clear thread-safety contract
* clear shutdown behavior

Avoid introducing a heavyweight dependency injection framework.

SourbyCraft does not need Spring or similar frameworks.

Use lightweight explicit construction.

---

# 8. Package Architecture

SourbyCraft-owned code should move toward clear functional boundaries.

Suggested conceptual architecture:

```text
dev.iyanz.sourbycraft
│
├── bootstrap
│
├── config
│
├── concurrency
│
├── region
│
├── world
│
├── chunk
│
├── entity
│
├── network
│
├── storage
│
├── scheduler
│
├── performance
│
├── security
│
├── command
│
├── diagnostics
│
└── util
```

Do not move upstream Paper/Canvas classes merely to achieve aesthetic package organization.

Architecture cleanup should primarily apply to SourbyCraft-owned code and patches where changing upstream structure provides measurable value.

---

# 9. Threading Architecture

SourbyCraft is region-threaded.

Any modernization MUST preserve the region-threaded execution model.

The project MUST distinguish between:

### Region-sensitive work

Examples:

* entity mutation
* world mutation
* chunk mutation
* block updates
* entity AI state
* inventory state
* gameplay state

These must follow the appropriate region ownership/thread rules.

### CPU work

Potentially parallelizable work that does not mutate region-owned game state.

### Blocking I/O

Examples:

* HTTP
* database operations
* filesystem operations
* external API calls

These must not block critical region threads.

---

# 10. Virtual Threads

Java virtual threads may be used selectively for blocking I/O workloads.

Suitable candidates include:

```text
HTTP request
database query
external service
filesystem metadata
update metadata checks
non-tick administrative tasks
```

Potential architecture:

```text
Region Thread
     │
     ├── prepare immutable request
     │
     ▼
Virtual Thread
     │
     ├── blocking I/O
     │
     ▼
Result
     │
     ▼
Region Scheduler
     │
     ▼
Apply game state
```

Virtual threads MUST NOT replace:

* region scheduler threads
* tick threads
* latency-sensitive game loops
* Netty event loops
* CPU-bound worker pools

Do not create virtual threads blindly for every asynchronous task.

---

# 11. Executor Audit

Audit all uses of:

```java
Executor
ExecutorService
ForkJoinPool
CompletableFuture
Thread
ScheduledExecutorService
ThreadLocal
synchronized
ReentrantLock
Semaphore
```

Document:

* owner
* workload
* thread type
* shutdown behavior
* queue behavior
* maximum concurrency
* region-thread safety

Remove duplicate thread pools when a shared lifecycle-managed executor is more appropriate.

Avoid unbounded executor queues.

Avoid accidentally scheduling CPU-heavy operations onto the common ForkJoinPool.

---

# 12. CompletableFuture Audit

Audit all `CompletableFuture` pipelines.

Look for:

* unnecessary futures
* excessive object allocation
* nested futures
* blocking `.join()`
* blocking `.get()`
* common pool usage
* region-thread blocking
* excessive callback chains

Simple synchronous work should remain synchronous.

Asynchronous code is not automatically faster.

---

# 13. Lock Contention Reduction

Profile synchronization hot spots.

Inspect:

* synchronized collections
* broad locks
* global locks
* nested locking
* chunk locks
* entity locks
* scheduler locks
* player list locks
* plugin lifecycle locks
* network queues

Goal:

```text
Global coordination
        ↓
Region-local ownership
        ↓
Minimal synchronization
```

Where safe.

Do NOT remove synchronization merely for benchmark gains.

Correctness is mandatory.

---

# 14. Region-Local State

Where architecture allows, prefer region-local state over globally shared state.

Examples may include:

* temporary entity processing buffers
* local scheduler queues
* tick bookkeeping
* region statistics
* reusable iteration buffers

Avoid global contention between independent regions.

---

# 15. Hot Path Policy

Hot paths must avoid unnecessary abstraction.

Critical paths include:

* server tick
* region tick
* entity tick
* mob AI
* block tick
* fluid tick
* chunk ticking
* chunk lookup
* packet handling
* entity tracking
* collision
* pathfinding
* inventory handling
* redstone
* item entities

Hot paths should minimize:

* temporary allocations
* streams
* iterator allocation
* Optional allocation
* lambdas that escape
* string generation
* boxing
* reflection
* unnecessary map lookup
* unnecessary object creation

Readable imperative code is acceptable and preferred in performance-sensitive sections.

---

# 16. Streams Policy

Java streams are allowed in:

* bootstrap code
* administrative code
* commands
* configuration loading
* low-frequency code

Avoid streams inside high-frequency tick loops unless profiling proves they are equivalent or better.

Do not modernize code by mechanically replacing loops with streams.

---

# 17. Allocation Reduction

Perform allocation profiling using JFR or equivalent tools.

Identify high-allocation classes.

Potential targets:

* temporary position objects
* entity iteration collections
* packet objects
* collection wrappers
* pathfinding nodes
* block positions
* chunk keys
* temporary lists
* config lookups
* logging parameters

Target:

> Reduce allocation rate without compromising correctness or maintainability.

---

# 18. Primitive Data Structures

Performance-sensitive structures should be evaluated for primitive-specialized alternatives.

Example:

Instead of:

```java
Map<Long, ChunkData>
```

consider suitable primitive-key structures where already available in the server dependency ecosystem.

Similarly evaluate:

```text
Set<Long>
Map<Integer, ...>
List<Integer>
```

when boxing creates measurable allocation pressure.

Do not introduce a new collection library unless benchmarks justify it.

---

# 19. Collection Sizing

Review frequently allocated:

```text
ArrayList
HashMap
HashSet
ConcurrentHashMap
```

If typical size is reliably known, initialize appropriate capacities to avoid repeated resizing.

Avoid excessive over-sizing.

---

# 20. Cache Architecture

Every cache must have:

* owner
* lifecycle
* invalidation strategy
* size policy
* thread-safety policy
* memory upper bound or naturally bounded domain

No unlimited cache simply for increased benchmark throughput.

A cache that saves CPU but leaks several gigabytes of memory is not considered an optimization.

---

# 21. Entity Performance

Audit entity ticking.

Focus areas:

* unnecessary entity tick execution
* activation checks
* AI execution
* pathfinding frequency
* collision scanning
* nearby entity queries
* repeated world lookup
* inactive entity handling
* tracker updates

Preserve vanilla-compatible behavior where required by the project.

Optimization must not silently disable gameplay systems.

---

# 22. Mob AI

Profile:

```text
GoalSelector
TargetSelector
PathNavigation
PathFinder
Sensor
Brain
Behavior
collision checks
nearby-entity queries
```

Potential improvements:

* avoid recalculating unchanged state
* cache values with safe lifetime
* remove duplicate distance calculations
* reduce unnecessary pathfinding
* reuse immutable results where possible

Do not dynamically modify AI rate based on TPS.

Any reduced AI frequency must be explicit configuration controlled by the administrator.

---

# 23. Chunk System

Chunk handling is a major optimization target.

Audit:

* chunk lookup
* chunk holder access
* chunk loading
* chunk generation
* chunk unloading
* chunk saving
* ticket operations
* player tracking
* chunk sending
* temporary chunk collections

Goals:

* fewer redundant lookups
* reduced contention
* reduced object allocation
* better batching
* lower region-thread blocking

---

# 24. Chunk I/O

Chunk disk I/O MUST NOT stall region execution unnecessarily.

Review:

```text
serialization
compression
disk read
disk write
autosave
region files
flush behavior
```

Batch operations where safe.

Do not silently alter autosave interval or server configuration.

---

# 25. World Saving

Optimize saving architecture without changing the operator's configured persistence semantics.

Potential improvements:

* batching
* dirty-state tracking
* avoiding duplicate serialization
* asynchronous disk operations where safe
* reduced allocation

No automatic save interval tuning.

---

# 26. Block and Fluid Ticks

Profile:

* scheduled block ticks
* fluid ticks
* random ticks
* block entity ticking
* neighbor updates

Focus on:

* queue efficiency
* locality
* duplicate work
* object allocations
* lookup overhead

---

# 27. Block Entity Performance

Review high-volume tile/block entities such as:

```text
hopper
furnace
chest
redstone components
```

Look for repeated inventory scans and repeated world queries.

Optimization must preserve plugin/event semantics expected by SourbyCraft.

---

# 28. Entity Lookup Architecture

Repeated broad entity scans should be identified.

Replace O(n) scans with spatial or region-local lookups where the existing Canvas/Paper infrastructure supports it.

Do not maintain duplicate indexes without proving they are faster overall.

---

# 29. Network Performance

Profile:

* packet encoding
* packet decoding
* compression
* packet queues
* flush behavior
* entity tracking packets
* chunk packets
* player join synchronization

Do not move CPU-heavy operations onto Netty event loops.

Netty event loops should remain responsive.

---

# 30. Packet Allocation

Audit repeated packet construction where immutable data can safely be shared.

Avoid packet reuse where packet state can be mutated.

Correctness takes priority over allocation reduction.

---

# 31. Compression

Compression configuration remains explicit.

SourbyCraft must NOT dynamically adjust compression based on:

* player count
* MSPT
* CPU usage
* bandwidth usage

Optimization should happen in implementation, not by secretly modifying operator settings.

---

# 32. Serialization

Review repeated serialization paths:

* NBT
* chunk data
* entity data
* player data
* configuration
* network payloads

Reduce:

* duplicate buffer copies
* unnecessary temporary byte arrays
* unnecessary string conversion

---

# 33. Buffer Management

Review ByteBuffer/ByteBuf usage.

Prefer:

* clear ownership
* bounded reuse
* pooled buffers where the underlying framework already supports them

Avoid custom pooling unless profiling proves it beneficial.

Memory safety is more important than tiny allocation gains.

---

# 34. Item Entity Performance

Review:

* item merge searches
* pickup checks
* collision checks
* despawn handling
* metadata updates
* item movement

Any object pooling previously introduced into SourbyCraft must be re-evaluated using Java 25 profiling.

Object pooling MUST NOT be retained simply because it historically sounded efficient.

Modern generational garbage collectors can make allocation cheaper than complex pools.

---

# 35. Object Pool Audit

Audit every custom pool.

Classify it:

```text
KEEP
REMOVE
REDESIGN
BENCHMARK REQUIRED
```

Pools that increase:

* synchronization
* retained memory
* stale object risk
* reset complexity

should be removed unless they provide measurable benefits.

---

# 36. Garbage Collection

Do not hardcode one "magical" garbage collector configuration into SourbyCraft.

SourbyCraft should run correctly using standard Java 25 runtime behavior.

Benchmark at minimum the project's recommended production configuration.

Metrics:

```text
allocation rate
young GC frequency
pause time
old-gen growth
live-set size
retained heap
RSS
```

Performance work should prioritize reducing garbage production before introducing unusual JVM flags.

---

# 37. Memory Efficiency

Target lower:

* heap usage
* retained heap
* temporary allocations
* thread stack usage
* duplicated caches
* collection overhead

Memory optimizations must not substantially increase CPU usage unless justified.

---

# 38. Memory Leak Audit

Use long-running soak tests.

Potential leak domains:

* player references
* chunk references
* entity references
* plugin references
* executor tasks
* ThreadLocal
* static collections
* event listeners
* caches
* region state

Test repeated:

```text
join
leave
world load
world unload
chunk load
chunk unload
plugin lifecycle
```

---

# 39. ThreadLocal Audit

ThreadLocal usage must be inspected carefully in a region-threaded architecture.

For each ThreadLocal determine:

* lifetime
* retained objects
* region-thread interaction
* memory retention
* virtual-thread compatibility

Remove ThreadLocal where simple local state or explicit context is better.

---

# 40. Logging Performance

Avoid expensive debug message construction when debug logging is disabled.

Hot paths must not generate unnecessary strings.

Use parameterized logging.

Avoid log spam from high-frequency server events.

---

# 41. Exception Performance

Exceptions must not be used as normal control flow in hot paths.

Exceptions remain appropriate for actual exceptional situations.

Repeated caught exceptions should be profiled.

---

# 42. Reflection Audit

Find avoidable reflection in repeated runtime paths.

Reflection used during startup is generally acceptable.

Reflection inside high-frequency tick paths should be minimized.

---

# 43. Startup Performance

Profile startup stages independently.

Examples:

```text
bootstrap
library loading
configuration
registry initialization
world loading
plugin loading
network initialization
```

Target:

* no startup regression
* reduced unnecessary work
* deterministic initialization

Startup optimization must not introduce lazy initialization into critical runtime paths without evidence.

---

# 44. Build Architecture

Clean up build infrastructure while preserving required patch tooling.

Current SourbyCraft uses Canvas Weaver.

The modernization should audit legacy build infrastructure left from older bases.

Classify each component as:

```text
ACTIVE
LEGACY-BUT-REQUIRED
DEPRECATED
REMOVABLE
```

Do not blindly delete legacy components that are still required by release workflows.

---

# 45. CI Architecture

CI should verify Java 25 consistently.

Required stages:

```text
Apply patches
       ↓
Compile
       ↓
Unit tests
       ↓
Static checks
       ↓
Server boot test
       ↓
Smoke test
       ↓
Performance regression test
```

Performance regression testing may use a separate scheduled or manual workflow if runtime is too expensive for every commit.

---

# 46. Benchmark Framework

Create a reproducible benchmark suite.

At minimum test:

### Idle

```text
0 players
loaded spawn
normal server tick
```

### Light

```text
10 simulated players
normal chunk activity
moderate entities
```

### Medium

```text
50 simulated players
entity AI
chunk loading
block updates
```

### Heavy

```text
100+ simulated players
high entities
chunk movement
redstone
network activity
```

### Entity Stress

Large numbers of:

```text
mobs
items
projectiles
```

### Chunk Stress

Repeated:

```text
generation
load
unload
save
player movement
```

---

# 47. Benchmark Environment

Performance comparisons MUST use:

* identical hardware
* identical JVM distribution
* identical Java version
* identical JVM arguments
* identical world
* identical plugins
* identical configuration
* identical workload
* identical warmup

Every report must include baseline and candidate commit hashes.

---

# 48. Performance Metrics

Capture:

```text
TPS
average MSPT
p50 MSPT
p95 MSPT
p99 MSPT
max MSPT

CPU average
CPU peak

heap average
heap peak
RSS

allocation MB/s

GC count
GC pause p95
GC pause max

chunk load latency
chunk generation latency

entity tick cost
network throughput

startup duration
shutdown duration
```

---

# 49. Performance Gates

No optimization PR may cause an unexplained regression exceeding approximately 3% in a relevant controlled benchmark.

Primary project goals:

```text
p95/p99 MSPT: meaningful reduction under load
CPU/player: lower
allocation rate: lower
retained heap: lower
idle CPU: lower
GC pressure: lower
throughput: higher
```

Recommended project-level targets:

```text
≥10% lower CPU usage in at least one major server workload
≥10% lower p95 MSPT under heavy workload
≥15% lower allocation rate in identified hot paths
≥10% lower retained memory or equivalent improved player-per-GB efficiency
```

These are optimization goals, not permission to break vanilla behavior or plugin compatibility.

---

# 50. Profiling

Provide standardized scripts/documentation for:

```text
JFR recording
heap dump
thread dump
GC log
spark profiling
```

JFR events should be sufficient to analyze:

* CPU hotspots
* monitor contention
* allocation pressure
* file I/O
* socket I/O
* thread activity
* GC behavior

---

# 51. Performance Diagnostics

SourbyCraft may expose diagnostic information.

Example command concept:

```text
/sourby perf
```

Possible output:

```text
TPS
MSPT
region count
active entities
loaded chunks
heap
GC
thread count
```

This command is diagnostic only.

It MUST NOT provide a button or command that automatically modifies server configuration.

---

# 52. Architectural Boundaries

SourbyCraft-specific functionality must have clear boundaries from upstream Canvas/Paper code.

Where possible:

```text
UPSTREAM
    ↓
small integration patch
    ↓
SourbyCraft service
```

rather than inserting large SourbyCraft systems directly into many unrelated upstream classes.

This reduces:

* merge conflicts
* rebase complexity
* future Minecraft upgrade cost

---

# 53. Patch Size Reduction

Review SourbyCraft patches for:

* duplicate patches
* obsolete patches
* functionality now provided upstream
* patches superseded by Canvas
* patches from old Folia base
* old experiments

Remove only after proving they are unnecessary.

Goal:

> Maintain fewer, smaller, better-isolated patches.

---

# 54. Upstream-First Policy

Before maintaining a custom optimization, check whether modern:

* Paper
* Folia
* Canvas

already provides an equivalent or better implementation.

Prefer upstream implementation when it:

* performs equally or better
* reduces maintenance
* preserves SourbyCraft requirements

Avoid carrying redundant performance patches forever.

---

# 55. Correctness

Performance must never compromise:

* world integrity
* chunk integrity
* entity state
* player inventory
* persistence
* plugin API behavior
* region-thread safety
* crash safety

A 30% performance improvement that introduces rare world corruption is unacceptable.

---

# 56. Plugin Compatibility

Maintain compatibility with the APIs SourbyCraft intentionally supports.

Performance optimization must not silently bypass:

* Bukkit events
* Paper APIs
* scheduler semantics
* expected plugin lifecycle

Where behavior intentionally differs from Paper, document it.

---

# 57. API Stability

Internal refactoring may be aggressive.

Public API changes require greater caution.

Classify code into:

```text
PUBLIC API
INTERNAL API
IMPLEMENTATION
```

Internal classes may be redesigned freely when safe.

---

# 58. Null Handling

Review unnecessary null-heavy internal APIs.

Prefer contracts where absence is explicit.

Do not automatically replace every nullable value with `Optional`, particularly in hot paths.

`Optional` should primarily be used for API clarity, not as a universal replacement.

---

# 59. Data-Oriented Design

For very high-volume runtime data, consider data-oriented layouts.

Example targets:

* entity metadata
* tick queues
* region statistics
* chunk bookkeeping

Prefer layouts that improve:

* CPU cache locality
* iteration speed
* allocation behavior

Only apply when benchmarked.

---

# 60. Utility Cleanup

Audit utility classes.

Remove:

* dead methods
* duplicate helpers
* unnecessary wrapper layers
* one-use abstraction
* outdated compatibility methods

A smaller codebase is generally easier for the JIT and developers to reason about.

---

# 61. Dead Code Removal

Identify:

* unreachable classes
* obsolete config
* deprecated experiments
* disabled performance systems
* old compatibility paths
* unused fields
* unused executor infrastructure

Removal must be verified before deletion.

---

# 62. Config Fossil Removal

Old configuration keys that no longer control any runtime behavior should be removed from code/documentation.

However:

SourbyCraft MUST NOT automatically delete them from an administrator's existing file.

Instead log/document:

```text
This setting is no longer used.
```

The administrator decides whether to remove it.

---

# 63. No Hidden Optimization

Every behavior-affecting performance optimization must be one of:

```text
semantics-preserving implementation optimization

OR

explicit operator-controlled configuration
```

Never:

```text
if TPS < 18:
    secretly reduce behavior
```

No hidden adaptive degradation.

---

# 64. Hardware Detection Policy

SourbyCraft may detect hardware for diagnostics.

Example:

```text
CPU: Ryzen ...
Cores: ...
RAM: ...
Java: 25
```

But hardware detection MUST NOT trigger configuration changes.

Allowed:

```text
Detected 8 cores.
```

Forbidden:

```text
Detected 8 cores.
Automatically changed region threads to 6.
```

---

# 65. Resource Efficiency Goal

Performance should be expressed as efficiency rather than only maximum throughput.

Main metric:

```text
useful server work
------------------
CPU / memory
```

Target better:

```text
players per core
players per GB
chunks per CPU-second
entities per MSPT
packets per CPU-second
```

---

# 66. Java 25 JVM Validation

Validate SourbyCraft specifically on Java 25.

Test:

* Temurin 25
* production JRE image
* Linux x86_64

Optional additional validation:

* Linux ARM64
* macOS development environment

The official production support target remains explicitly documented.

---

# 67. JVM Flags

SourbyCraft MUST NOT generate or rewrite JVM flags automatically.

Documentation may provide optional examples.

Server owners remain responsible for selecting JVM options.

No launcher-side hardware auto-tuning.

---

# 68. Modern JVM Features

Evaluate stable JVM improvements available in Java 25.

Adoption must be benchmark-based.

Possible areas:

* modern GC improvements
* virtual-thread improvements
* JIT improvements
* class-data sharing
* compact object representation where production-ready
* improved diagnostics

Experimental JVM flags must not become mandatory for SourbyCraft.

---

# 69. Security

Performance optimization must not weaken existing packet/security protections.

Limits designed to prevent:

* packet floods
* malformed data attacks
* decompression abuse
* crash exploits

must remain effective.

---

# 70. Testing Requirements

Every subsystem modernization requires tests where practical.

Required categories:

```text
unit tests
integration tests
boot tests
region-thread tests
world persistence tests
plugin compatibility tests
performance tests
long-running soak tests
```

---

# 71. Region Thread Safety Tests

Create tests that intentionally exercise:

```text
cross-region entity access
cross-region teleport
async world access
chunk transitions
player movement between regions
scheduler handoff
```

Invalid ownership must fail safely or be caught by existing scheduler protections.

---

# 72. Soak Testing

Run long-duration workloads designed to reveal:

* memory leaks
* deadlocks
* thread leaks
* queue growth
* entity leaks
* chunk leaks
* scheduler starvation

Recommended scenarios should support runs of several hours.

---

# 73. Shutdown Correctness

Every SourbyCraft-owned service must stop deterministically.

Shutdown sequence should:

```text
reject new work
finish/cancel appropriate tasks
flush persistence
stop executors
close resources
release network resources
terminate
```

No lingering non-daemon threads.

---

# 74. Observability

Important services should expose enough telemetry to diagnose problems.

Metrics may include:

```text
queue length
task duration
region tick time
cache size
active I/O tasks
allocation samples
chunk backlog
```

Observability itself must remain lightweight.

---

# 75. Documentation

Create/update documentation covering:

```text
Java 25 requirement
threading model
region ownership
configuration policy
performance architecture
benchmark methodology
profiling
developer guidelines
```

---

# 76. Developer Performance Guidelines

Add developer guidance similar to:

```text
Do not block region threads.

Do not use CompletableFuture commonPool implicitly.

Do not introduce global mutable state.

Do not create new executor pools without lifecycle documentation.

Do not use automatic server configuration.

Do not use streams blindly in tick hot paths.

Do not add caches without lifecycle and bounds.

Do not pool objects without benchmark evidence.

Do not introduce preview Java APIs into production core without approval.

Benchmark before and after performance-sensitive changes.
```

---

# 77. Implementation Phases

## Phase 1 — Baseline

Before refactoring:

* build current server
* capture baseline performance
* capture JFR
* capture heap statistics
* capture benchmark results

Store benchmark metadata.

---

## Phase 2 — Architecture Audit

Create inventory of:

* executors
* synchronization
* static state
* config access
* caches
* object pools
* hot paths
* async operations
* legacy code
* SourbyCraft patches

Classify each.

---

## Phase 3 — Java 25 Modernization

Modernize SourbyCraft-owned code.

Focus on:

* immutable models
* records
* clearer ownership
* lifecycle management
* Java 25 idioms
* removal of legacy compatibility
* API cleanup

No performance semantics changes yet unless independently benchmarked.

---

## Phase 4 — Concurrency Modernization

Refactor:

* executors
* async I/O
* CompletableFuture chains
* region handoff
* thread ownership

Introduce virtual threads only where suitable.

---

## Phase 5 — Allocation & Memory

Use JFR to identify allocation hot spots.

Optimize highest-impact areas first.

---

## Phase 6 — Entity & AI

Profile and optimize:

* entity tick
* AI
* pathfinding
* collision
* entity lookup

---

## Phase 7 — Chunk & World

Optimize:

* chunk lifecycle
* save/load
* lookup
* serialization
* world tick

---

## Phase 8 — Networking

Optimize:

* packet processing
* compression implementation
* buffering
* tracking

without changing user configuration automatically.

---

## Phase 9 — Patch Cleanup

Remove proven redundant:

* old Folia patches
* superseded optimizations
* duplicate Canvas functionality
* unused experiments

---

## Phase 10 — Benchmark & Regression

Repeat the exact baseline workloads.

Produce comparison tables.

---

# 78. Required Benchmark Report

Each major optimization milestone must produce:

```text
Baseline Commit:
Candidate Commit:
Java:
Hardware:
JVM Args:
Server Config:
World:
Plugins:
Test Duration:
Warmup:
```

Then:

| Metric          | Baseline | Candidate | Difference |
| --------------- | -------: | --------: | ---------: |
| Average MSPT    |          |           |            |
| p95 MSPT        |          |           |            |
| p99 MSPT        |          |           |            |
| CPU             |          |           |            |
| Heap            |          |           |            |
| RSS             |          |           |            |
| Allocation MB/s |          |           |            |
| GC pause        |          |           |            |
| Startup         |          |           |            |

---

# 79. Pull Request Strategy

Do NOT implement the entire modernization as one enormous unreviewable commit.

Preferred PR structure:

```text
PR 1 — Benchmark infrastructure
PR 2 — Java 25 architecture cleanup
PR 3 — Configuration architecture
PR 4 — Executor/concurrency cleanup
PR 5 — Allocation optimizations
PR 6 — Entity/AI optimizations
PR 7 — Chunk/world optimizations
PR 8 — Network optimizations
PR 9 — Build/CI cleanup
PR 10 — Legacy patch cleanup
```

Large phases may be divided further.

---

# 80. Commit Requirements

Performance commits should explain:

```text
Problem
Root cause
Old architecture
New architecture
Benchmark
Compatibility impact
Thread-safety impact
Memory impact
```

Avoid commit messages such as:

```text
optimize stuff
performance improvements
cleanup
```

without explanation.

---

# 81. Non-Goals

This project is NOT intended to:

* automatically configure servers
* automatically tune configs
* automatically tune server behavior
* replace Canvas region threading
* rewrite Minecraft from scratch
* break Bukkit/Paper compatibility for tiny gains
* blindly use every Java 25 feature
* blindly use virtual threads
* automatically change JVM flags
* automatically reduce gameplay quality under load
* create fake benchmark numbers
* optimize code without profiling

---

# 82. Definition of Done

The modernization is considered complete when:

1. Entire SourbyCraft build uses Java 25 consistently.
2. No production dependency requires Java preview mode.
3. SourbyCraft-owned architecture has clear lifecycle and ownership.
4. Executor/threading architecture has been audited.
5. Blocking I/O does not unnecessarily block region threads.
6. Config files are never automatically rewritten for performance tuning.
7. No automatic performance configuration exists.
8. No hidden TPS-based adaptive configuration exists.
9. Significant hot paths have profiling evidence.
10. High-allocation areas have been reviewed.
11. Entity/AI systems have been profiled.
12. Chunk/world systems have been profiled.
13. Network systems have been profiled.
14. Legacy/redundant patches have been reviewed.
15. Server passes boot and integration tests.
16. Region-thread correctness is maintained.
17. World persistence is validated.
18. Plugin compatibility is maintained.
19. Soak tests show no new memory/thread leaks.
20. Performance benchmarks demonstrate measurable improvement or equivalent performance with substantially improved architecture.
21. No unexplained performance regression exceeds the agreed threshold.
22. Documentation is updated.

---

# 83. Final Architectural Goal

The desired SourbyCraft architecture should conceptually become:

```text
                    SOURBYCRAFT
                         │
               ┌─────────┴─────────┐
               │                   │
          Bootstrap           Diagnostics
               │
        Immutable Config
               │
       Runtime Services
               │
 ┌─────────────┼──────────────┐
 │             │              │
Region       Network        Storage
Engine       Engine          I/O
 │             │              │
 │             │        Virtual Threads
 │             │       where appropriate
 │             │
 └─────────────┼──────────────┘
               │
          Canvas Engine
               │
             Paper
               │
           Minecraft
```

With these characteristics:

```text
Java 25-native architecture
Region-thread safe
Low allocation
Low contention
Explicit configuration
No auto-tuning
No hidden runtime configuration changes
Efficient I/O
Efficient memory use
Measurable performance
Maintainable patches
Easy profiling
Predictable behavior
```

---

# 84. Absolute Requirement

The following rule overrides any optimization proposal:

> SourbyCraft must optimize the implementation, not silently optimize the administrator's configuration.

If an optimization requires reducing gameplay behavior, simulation distance, entity limits, chunk behavior, tick frequency, compression configuration, or similar administrator-controlled behavior, it must remain an explicit configuration option.

There must be **no Auto Config, Auto Optimize Config, Dynamic Config, Smart Config, Adaptive Config, or automatic runtime configuration modification system** introduced by this modernization.

The server should become faster because its **engine and code are better**, not because SourbyCraft secretly reduces workload configured by the server owner.
