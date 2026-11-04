# Testing Aurora World Fabric

For operators who want to try AWF storage on SourbyCraft 26.2 (Build 49 layout and later; checked
against the Build 50 candidate, PR #62, on 2026-10-25). AWF is **off by default** and **not
qualified**. Storage-layer tests pass in-process, CI boots a small world through it, and synthetic
crash runs (`scripts/verify_crash.py --awf`: SIGKILLs under save traffic, with scripted protocol clients
in the 2026-10-13 run) passed on a local machine and the agent test server between 2026-10-05 and
2026-10-20, on trees older than `v26.2-r49` and uncertified ([Build 49 gates](../releases/26.2-build-49.md));
nothing has been run with real players, under sustained load or with a network Redis as qualification
evidence. The owner's AWF results of 2026-10-24 were made on a tree before PR #61/#62 and are NEEDS
RETEST on the candidate ([Tester Owner](../tester%20owner.md) §7). **Back up the world first** and test
on a copy.

## 1. Enable it for one world

`sourbycraft_config/aurora.yml` (YAML; a `aurora.toml` left beside it is never read, and the Build 50
candidate logs one WARN about it at boot and on every reload):

```yaml
aurora:
  awf:
    worlds: ["world_test"]          # folder names; every dimension/storage under it
    commit-interval-seconds: 30     # how long writes may wait in memory
    resident-chunks: 1024           # committed chunks kept in memory per storage
    persistence: "incremental"      # or "checkpoint" / "full"
    shutdown-deadline-ms: 30000     # one budget for every AWF wait at shutdown
```

All AWF keys are RESTART_REQUIRED. On start the log says:

```
Aurora World Fabric: storing [world_test] (INCREMENTAL, commit every 30s, 1024 resident chunks per storage)
```

Existing `.mca` files become the read-only base. Chunks written from now on go to
`<storage>.awf/` directories beside each `region`, `entities` and `poi` folder.

## 2. What to test

1. Load the world, walk and fly around to generate chunks, build and break blocks, place
   entities, then `/stop`. The log shows
   `Aurora World Fabric: shutdown began; every AWF commit and release must finish within 30000 ms (aurora.awf.shutdown-deadline-ms)`
   and ends with `AWF-SHUTDOWN-OK: Aurora World Fabric confirmed N storage(s) at shutdown in <ms> ms (deadline 30000 ms)`;
   the process exits 0. An `AWF-SHUTDOWN-INCOMPLETE: …` alert lists every storage whose last writes were
   not confirmed, and the process exits **70**: never treat that stop as saved.
2. Start again. Your changes must be there, and no new `.mca` files appear under the world.
3. `save-all flush` with AWF storages open prints
   `Aurora World Fabric: waiting for <n> storage(s) to commit what this save wrote (pending; …)`, then
   `Saved the game` and `Aurora World Fabric: <n> storage(s) durable, <m> chunk(s) committed` only once every
   storage confirmed the commit; otherwise `Save NOT durable: …` naming each storage and no `Saved the game`.
   Plain `save-all` (no flush) does not claim durability.
4. `/perf awf` while playing: resident/dirty chunks, pending commits, commit p50/p95/p99, reads
   vs region-file fall-throughs, failures (the Build 50 candidate adds oldest dirty age, AWF loaded worlds
   and refused writes).
5. `/perf storage`: chunk-system reads/writes/deletes and each world's pending I/O.
6. Crash test (on a copy!): `kill -9` the server a few seconds after building something. On the
   next start, the world is as of the **last commit**. Up to `commit-interval-seconds` of changes
   are expected to be lost; region files lose less. Report anything else.

## 3. Storing worlds in Redis

```yaml
aurora:
  awf:
    backend: "redis"
    redis:
      uri: "redis://:password@redis.internal:6379/0"   # or env SOURBYCRAFT_AWF_REDIS_URI
      key-prefix: "sourbycraft:awf:"                    # different per network that must not share
      lease-seconds: 60
      wait-for-aof: true                                # needs appendonly yes on Redis 7.2+
```

The `aurora.awf.redis.*` keys are not seeded; write them yourself (a `uri` in the file takes precedence
over the environment variable). Redis needs `appendonly yes` for durable commits and
`maxmemory-policy noeviction`. The start log says `Aurora World Fabric: stores on redis …` and whether
commits wait for the AOF. In the Build 50 candidate an invalid URI refuses the backend instead of falling
back to a default server, so worlds stored there fail to load until it is fixed, and a commit waits for
`WAITAOF` on its own connection. `/awf list` and `/awf info <world>` show the storage. Only one server can
have a world open; a second one gets `held by another server`. Details and limits:
[aurora-world-fabric.md](../architecture/aurora-world-fabric.md).

## 4. Moving worlds: `.awf` files

`/awf export <world> <world>.awf` (unload it first) writes `awf/exports/<world>.awf` under the
server directory. Copy the file into the other server's `awf/imports/` and run
`/awf import <world>.awf <name>`. Old Slime files go into `awf/imports/` too:
`/awf import <file.slime> <name>`, or convert once with `/awf convert <file.slime> <file.awf>`
(written to `awf/exports/`). The command accepts only names inside those two folders -- no
absolute paths, no `..`. In the Build 50 candidate `import`, `export` and `convert` (like `delete`,
`clone`, `set`, `template delete` and `unload … nosave`) also check `sourbycraft.command.awf.admin`,
which `sourbycraft.command.awf` implies unless it is set explicitly
([commands.md](commands.md#awf-subcommands-and-permissions)).

## 5. Leaving AWF

```yaml
aurora:
  awf:
    worlds: []
    export: ["world_test"]
```

On the next start every AWF chunk and deletion is written back into `.mca` files, and the store
is renamed to `<storage>.awf.exported-<millis>` (kept, not deleted). The log says
`Aurora World Fabric exported ...`. After checking the world, remove `export` and delete the
`.exported-*` directories yourself. If a store exists but the world is not listed in either key,
AWF keeps using it and warns. The older region files underneath are never trusted again without
an export.

## 6. What to report

The `/perf awf` output, the log lines above (including the shutdown line and the exit status), world
size and player count, and whether any chunk was missing, reverted or regenerated after a restart.
