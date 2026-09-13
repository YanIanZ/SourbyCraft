# Build 44 architecture audit

| Component | Owner and execution | Bound / shutdown | Classification |
| --- | --- | --- | --- |
| Weaver patches | Gradle, Paper → Canvas → SourbyCraft | Regenerated; patch files authoritative | Active |
| Sourby metrics | MetricsRuntime; collector daemon | One-second immutable publication; close after plugin disable | Active |
| Region metrics | RegionMetricsRegistry; region-owned counters | Active generations plus 15-minute retention/grace | Active; preserve existing topology contracts |
| GC metrics | GcTracker; daemon sampler | 21 slots; immutable publication; explicit stop | Repaired |
| Administrative I/O | VirtualExecutor; Java 25 virtual threads | 64 admitted tasks, no queue, 30s graceful deadline then interrupt | Repaired |
| Async pathfinding | AsyncPathProcessor; platform workers | Existing quarter-core pool and 1024 queue; cancelled queued futures on stop | Optional; default off; full pathfinding audit remains separate |
| HUD | Shared bars; global scheduler; player scheduler for show/hide | One refresh per 20 global ticks; disconnect cleanup; task cancelled on stop | Extended; actionbar remains future |
| Utility TOML | SourbyCraftConfig; explicit load/reload/save | Immutable flattened snapshot; no automatic save of existing config | Repaired |
| Canvas config | Upstream engine config | Existing format retained; explicit reload | Active upstream interface |
| SmartSwap / AutoSwap | Legacy automatic memory services | Removed; operator keys retained | Retired by no-auto-tuning requirement |
| Legacy CDS launcher | SourbyBootstrap | Off by default; explicit fork preserves heap/GC arguments | Legacy, conservative compatibility |
| SourbyClip | Separate bootstrap project; checked-in Maven artifact | Existing downloads and compatibility launcher | Active; separate downloader/pool audit still required |
| Metal | Vendored build-time codegen | `scripts/setup_metal.sh` | Active build infrastructure |
| sourbypatcher | Legacy Folia toolchain | Kept for existing CI step | Legacy-required; not reintroduced as Canvas build plugin |
| Spark | Existing upstream integration and telemetry bridge | Upstream lifecycle | Active; no claim of a new SourbySpark fork |

Hot-path allocation changes beyond the required compile repair were not introduced
without load-profile evidence. Existing pools, ThreadLocals, entity/AI, chunk/save,
network, and plugin paths need workload-specific profiling before further changes.
No broad package move or upstream source ownership change is included.

Regression contracts and causes are appended to SPEC.md (§152–154). Tests cover
snapshot immutability, command/HUD single reads, unsupported metric values, resident
memory parsing, executor admission/shutdown/cancellation, GC lifecycle, queued path
cancellation, JFR event contents, config file preservation, and release/JAR identity.
