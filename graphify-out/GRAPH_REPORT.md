# Graph Report - .  (2026-05-13)

## Corpus Check
- 38 files · ~23,303 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 287 nodes · 436 edges · 26 communities (15 shown, 11 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 69 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_SIMD Map Palette & Item Utils|SIMD Map Palette & Item Utils]]
- [[_COMMUNITY_SourbyCraft Configuration|SourbyCraft Configuration]]
- [[_COMMUNITY_Flare Profiling Integration|Flare Profiling Integration]]
- [[_COMMUNITY_Server Config & Profiling Setup|Server Config & Profiling Setup]]
- [[_COMMUNITY_Pufferfish Config Options|Pufferfish Config Options]]
- [[_COMMUNITY_Command Framework|Command Framework]]
- [[_COMMUNITY_Logging & Error Tracking|Logging & Error Tracking]]
- [[_COMMUNITY_Flare Metric Collectors|Flare Metric Collectors]]
- [[_COMMUNITY_Sentry Log Appender|Sentry Log Appender]]
- [[_COMMUNITY_Caching Data Structures|Caching Data Structures]]
- [[_COMMUNITY_Test Plugin Bootstrap|Test Plugin Bootstrap]]
- [[_COMMUNITY_Cross-Cutting Utilities|Cross-Cutting Utilities]]
- [[_COMMUNITY_SIMD & Sentry API Layer|SIMD & Sentry API Layer]]
- [[_COMMUNITY_Async Execution Engine|Async Execution Engine]]
- [[_COMMUNITY_GC Event Monitoring|GC Event Monitoring]]
- [[_COMMUNITY_Build System (Server)|Build System (Server)]]
- [[_COMMUNITY_Build System (API)|Build System (API)]]
- [[_COMMUNITY_Build System (Test Plugin)|Build System (Test Plugin)]]
- [[_COMMUNITY_Plugin Class Loading|Plugin Class Loading]]
- [[_COMMUNITY_Test Plugin Components|Test Plugin Components]]
- [[_COMMUNITY_Plugin Introspection|Plugin Introspection]]
- [[_COMMUNITY_Profile Categories|Profile Categories]]
- [[_COMMUNITY_Licenses (GPL)|Licenses (GPL)]]
- [[_COMMUNITY_Bitset Structures|Bitset Structures]]
- [[_COMMUNITY_License (LGPL)|License (LGPL)]]

## God Nodes (most connected - your core abstractions)
1. `PufferfishConfig` - 23 edges
2. `SourbyCraftConfig` - 15 edges
3. `PufferfishConfig` - 15 edges
4. `ItemListWithBitset` - 13 edges
5. `SourbyCraftWorldConfig` - 12 edges
6. `ProfilingManager` - 12 edges
7. `State` - 11 edges
8. `AsyncExecutor` - 8 edges
9. `GCEventCollector` - 8 edges
10. `SentryContext` - 8 edges

## Surprising Connections (you probably didn't know these)
- `GNU General Public License v3 (api copy)` --semantically_similar_to--> `GNU General Public License v3`  [INFERRED] [semantically similar]
  sourbycraft-api/buildscript/LICENCE.txt → sourbycraft-server/buildscript/LICENCE.txt
- `CloudPlane` --conceptually_related_to--> `Adventure Translatable Component Localization`  [EXTRACTED]
  README.md → sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
- `CloudPlane` --conceptually_related_to--> `Item Lore Newline Splitting`  [EXTRACTED]
  README.md → sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/ItemUtil.java
- `CloudPlane` --conceptually_related_to--> `Configurable Villager Gossip Limits`  [EXTRACTED]
  README.md → sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
- `SIMDChecker` --calls--> `SIMDDetection`  [EXTRACTED]
  sourbycraft-api/src/main/pufferfish/gg/pufferfish/pufferfish/simd/SIMDChecker.java → sourbycraft-server/src/main/pufferfish/gg/pufferfish/pufferfish/simd/SIMDDetection.java

## Hyperedges (group relationships)
- **Flare Profiling Subsystem** — ProfilingManager, FlareCommand, FlareSetup, TPSCollector, WorldCountCollector, GCEventCollector, StatCollector, CustomCategories, PluginLookup, ServerConfigurations [EXTRACTED 1.00]
- **Pufferfish Configuration Hub** — PufferfishConfig, SentryManager, FlareSetup, FlareCommand, SIMDDetection [EXTRACTED 0.95]
- **Flare Data Collectors** — TPSCollector, WorldCountCollector, GCEventCollector, StatCollector, CustomCategories [INFERRED 0.85]
- **SIMD-Accelerated Map Color Optimization Subsystem** — VectorMapPalette, SIMDDetection, SIMDChecker, simd_map_color_matching [EXTRACTED 1.00]
- **Paper Plugin Bootstrap-Loader-Plugin Chain** — TestBootstrap, TestLoader, TestPlugin, paper_plugin_descriptor, test_plugin_build [EXTRACTED 1.00]
- **Pufferfish Sentry Integration** — SentryContext, sentry_plugin_context_tracking, sourbycraft_api_build, sourbycraft_api_buildscript_build [EXTRACTED 1.00]

## Communities (26 total, 11 thin omitted)

### Community 0 - "SIMD Map Palette & Item Utils"
Cohesion: 0.11
Nodes (4): VectorMapPalette, ItemUtil, ItemListWithBitset, OurNonNullList

### Community 2 - "Flare Profiling Integration"
Cohesion: 0.15
Nodes (25): AsyncExecutor, Buildscript Gradle Config, CustomCategories, EntityType (patched with dabEnabled field), FlareCommand, Flare Profiling Library (com.github.technove:Flare), FlareSetup, GCEventCollector (+17 more)

### Community 3 - "Server Config & Profiling Setup"
Cohesion: 0.14
Nodes (4): ServerConfigurations, FlareSetup, ProfilingManager, SentryContext

### Community 5 - "Command Framework"
Cohesion: 0.1
Nodes (4): Command, FlareCommand, PufferfishCommand, State

### Community 6 - "Logging & Error Tracking"
Cohesion: 0.15
Nodes (5): Logger, PufferfishLogger, SentryManager, SIMDChecker, SIMDDetection

### Community 7 - "Flare Metric Collectors"
Cohesion: 0.19
Nodes (4): StatCollector, TPSCollector, WorldCountCollector, LiveCollector

### Community 8 - "Sentry Log Appender"
Cohesion: 0.27
Nodes (4): AbstractAppender, AbstractFilter, PufferfishSentryAppender, SentryFilter

### Community 10 - "Test Plugin Bootstrap"
Cohesion: 0.27
Nodes (5): JavaPlugin, Listener, PluginBootstrap, TestBootstrap, TestPlugin

### Community 11 - "Cross-Cutting Utilities"
Cohesion: 0.27
Nodes (10): CloudPlane, ItemUtil, SourbyCraftConfig, SourbyCraftWorldConfig, Adventure Translatable Component Localization, Component Patch NBT Serialization, Config Version Migration (v1-v4), Item Lore Newline Splitting (+2 more)

### Community 12 - "SIMD & Sentry API Layer"
Cohesion: 0.29
Nodes (9): SIMDChecker, SIMDDetection, SentryContext, VectorMapPalette, Sentry Plugin/Player/Event Context Tracking, SIMD-Accelerated Map Color Matching, sourbycraft-api build.gradle.kts, sourbycraft-api/buildscript build.gradle.kts (+1 more)

### Community 14 - "GC Event Monitoring"
Cohesion: 0.31
Nodes (3): GCEventCollector, EventCollector, NotificationListener

### Community 15 - "Build System (Server)"
Cohesion: 0.29
Nodes (3): GenerateApiVersioningFile, MockitoAgentProvider, Services

### Community 16 - "Build System (API)"
Cohesion: 0.33
Nodes (3): GenerateApiVersioningFile, MockitoAgentProvider, Services

### Community 19 - "Test Plugin Components"
Cohesion: 0.67
Nodes (4): TestBootstrap, TestLoader, TestPlugin, paper-plugin.yml (Test Plugin)

## Knowledge Gaps
- **16 isolated node(s):** `Services`, `CustomCategories`, `Services`, `AsyncExecutor`, `Long2FloatAgingCache` (+11 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PufferfishConfig` connect `Flare Profiling Integration` to `SIMD & Sentry API Layer`, `Command Framework`, `Logging & Error Tracking`?**
  _High betweenness centrality (0.120) - this node is a cross-community bridge._
- **Why does `PufferfishConfig` connect `Pufferfish Config Options` to `SIMD & Sentry API Layer`?**
  _High betweenness centrality (0.101) - this node is a cross-community bridge._
- **Why does `ItemListWithBitset` connect `SIMD Map Palette & Item Utils` to `Server Config & Profiling Setup`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `PufferfishConfig` (e.g. with `Server Build Gradle Config` and `ServerConfigurations`) actually correct?**
  _`PufferfishConfig` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Services`, `CustomCategories`, `Services` to the rest of the system?**
  _16 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SIMD Map Palette & Item Utils` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._
- **Should `SourbyCraft Configuration` be split into smaller, more focused modules?**
  _Cohesion score 0.12 - nodes in this community are weakly interconnected._