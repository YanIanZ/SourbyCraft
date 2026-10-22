# Aurora Instant Startup

> **Role:** Aurora Instant Startup specification and implementation status. **Status:** Active; feature is PARTIAL (no AWF-index caching; no transform exists to cache; no cold/warm measurement).
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

Aurora Instant Startup removes repeated deterministic work from warm restarts without skipping plugin or world lifecycle semantics.

## Cacheable
Plugin descriptor parsing, class index, compatibility analysis, transform outputs (none exist today, see below), dependency graph, AWF metadata/indexes and previous startup timing profile.

## Never cache
Plugin/ClassLoader instances, Bukkit services, worlds/entities, scheduler handles, open files, sockets/channels or database connections.

## Fingerprints
Compatibility-sensitive keys include strong source SHA-256, Minecraft version, SourbyCraft ABI, Aurora Bridge ABI, cache-format version and Java major. mtime/size may only be an early fast-path check.

## Integrity
Cache entries carry source/output hashes and explicit format/ABI versions. Corrupt/stale entry -> WARN once -> discard affected entry -> rebuild -> continue boot.

## Safe parallel startup work
Hashing, descriptor parsing, class indexing, compatibility analysis, transform preparation and AWF index loading may run concurrently under a bounded STARTUP lane.

Do not automatically parallelize plugin onLoad/onEnable, service registration, events or world mutation.

## Implementation status (26.2 branch)

`dev.iyanz.sourbycraft.startup`:

- `CacheEnvironment`: Minecraft version, SourbyCraft ABI, Aurora Bridge ABI, format version (3; 3 added `provides` to the cached descriptor),
  Java major. Any mismatch discards the file.
- `SourceFingerprint`: SHA-256 plus size/mtime. Size and mtime can only rule reuse out.
- `StartupCache`: string payloads only, so no live objects by construction. Each entry has a source
  hash, an output hash and a line hash. Writes go to a temp file, are forced, and are moved
  atomically.
- `PluginStartupIndex`: runs on a bounded `STARTUP` lane and caches, per jar:
  - the descriptor, including `depend`/`softdepend`/`loadbefore`, `provides` and `paper-plugin.yml`
    `dependencies.server`;
  - a `CompatibilityScan`: class and package counts, plus references to the legacy Bukkit
    scheduler, the Folia schedulers and server internals, read from class constant pools by
    `BytecodeScanner` without loading classes.
- `DependencyGraph`: missing hard dependencies, cycles (Tarjan), and a consistent order. A name
  another plugin `provides` satisfies a dependency on it, as in Paper's loader (FastAsyncWorldEdit
  `provides: [WorldEdit]` satisfies WorldGuard's `depend: [WorldEdit]`); a real plugin of that name wins.
  Diagnostic only; the plugin manager's order is unchanged.
- `StartupTimeline`/`StartupProfile`: boot-stage and index phase timings, and JVM start to
  `ServerLoadEvent(STARTUP)`. The profile is compared with the previous boot only when both have
  the same start class.
- Boot stage `startup index`: logs the index, names undeclared plugins with their scan verdict,
  warns about internals the bridge cannot route, and warns about missing dependencies and cycles.
  `/sys` shows the index, the startup profile and dependency problems.
  `-Daurora.startup.cache=false` disables the file (RESTART_REQUIRED).

Not implemented: caching AWF indexes. There is no transform output to cache: SourbyCraft rewrites
no plugin bytecode (the only class reader is the read-only constant-pool scan, already cached as the
compatibility scan; the Aurora Bridge routes at runtime through patched scheduler/provider classes;
the Cherry mixin engine is external and off by default). Whether a transform is intended is an owner
decision; a cache needs a producer first. No cold/warm startup measurement has been taken, so no
startup-time improvement is claimed.

## Telemetry
Measure total startup and phase times plus hit/miss/rebuild counts. Cold and warm startup are separate benchmark classes.
