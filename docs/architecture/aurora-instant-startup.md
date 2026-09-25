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

## Telemetry
Measure total startup and phase times plus hit/miss/rebuild counts. Cold and warm startup are separate benchmark classes.
