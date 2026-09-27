# Testing Aurora World Fabric

For operators who want to try AWF storage on SourbyCraft 26.2 Build 47. AWF is **off by
default** and **not qualified**: storage-layer tests pass in-process and CI boots a small world
through it, but nothing has been run with players, under load, or through a crash. **Back up the
world first** and test on a copy.

## 1. Enable it for one world

`sourbycraft_config/aurora.toml`:

```toml
[aurora.awf]
worlds = ["world_test"]          # folder names; every dimension/storage under it
commit-interval-seconds = 30     # how long writes may wait in memory
resident-chunks = 1024           # committed chunks kept in memory per storage
persistence = "incremental"      # or "checkpoint" / "full"
```

All AWF keys are RESTART_REQUIRED. On start the log says:

```
Aurora World Fabric: storing [world_test] (INCREMENTAL, commit every 30s, 1024 resident chunks per storage)
```

Existing `.mca` files become the read-only base. Chunks written from now on go to
`<storage>.awf/` directories beside each `region`, `entities` and `poi` folder.

## 2. What to test

1. Load the world, walk and fly around to generate chunks, build and break blocks, place
   entities, then `/stop`. The log should end with
   `Aurora World Fabric: committed and closed N storage(s) at shutdown`.
2. Start again. Your changes must be there, and no new `.mca` files appear under the world.
3. `/perf awf` while playing: resident/dirty chunks, pending commits, commit p50/p95/p99, reads
   vs region-file fall-throughs, failures.
4. `/perf storage`: chunk-system reads/writes/deletes and each world's pending I/O.
5. Crash test (on a copy!): `kill -9` the server a few seconds after building something. On the
   next start, the world is as of the **last commit**. Up to `commit-interval-seconds` of changes
   are expected to be lost; region files lose less. Report anything else.

## 3. Leaving AWF

```toml
[aurora.awf]
worlds = []
export = ["world_test"]
```

On the next start every AWF chunk and deletion is written back into `.mca` files, and the store
is renamed to `<storage>.awf.exported-<millis>` (kept, not deleted). The log says
`Aurora World Fabric exported ...`. After checking the world, remove `export` and delete the
`.exported-*` directories yourself. If a store exists but the world is not listed in either key,
AWF keeps using it and warns. The older region files underneath are never trusted again without
an export.

## 4. What to report

The `/perf awf` output, the log lines above, world size and player count, and whether any chunk
was missing, reverted or regenerated after a restart.
