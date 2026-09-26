# Aurora Instant Startup

Aurora Instant Startup removes repeated deterministic work from warm restarts without skipping plugin or world lifecycle semantics.

## Cacheable
Plugin descriptor parsing, class index, compatibility analysis, transform outputs, dependency graph, AWF metadata/indexes and previous startup timing profile.

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

`dev.iyanz.sourbycraft.startup` implements the cache mechanics and one consumer:

- `CacheEnvironment` — Minecraft version, SourbyCraft ABI, Aurora Bridge ABI, format version,
  Java major. Any mismatch discards the file.
- `SourceFingerprint` — SHA-256 plus size/mtime; size/mtime can only rule reuse out.
- `StartupCache` — string payloads only (no live objects by construction); per-entry source hash,
  output hash and line hash; corrupt/torn entries are dropped individually; writes go to a temp
  file, are forced, then atomically moved.
- `PluginStartupIndex` — hashes jars and parses `paper-plugin.yml`/`plugin.yml` on a bounded
  `STARTUP` lane (`SourbyCraft-Startup-N`, at most 4 threads, queue sized to the jar count,
  AbortPolicy). It evaluates `folia-supported`/`canvas-supported` exactly as the base does and never
  loads plugin classes.
- Boot stage `startup index` (before `CraftServer#loadPlugins`) logs the result and names jars the
  base will refuse; `/sys` shows the last build. `-Daurora.startup.cache=false` disables the file
  (RESTART_REQUIRED).

Not implemented: class index, compatibility analysis beyond the support flags, transform outputs,
dependency graph, AWF indexes, previous startup timing profile. No cold/warm startup measurement
has been taken, so no startup-time improvement is claimed.

## Telemetry
Measure total startup and phase times plus hit/miss/rebuild counts. Cold and warm startup are separate benchmark classes.
