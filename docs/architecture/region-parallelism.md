# What Bounds CPU Use on a Region-Threaded Server

Measured on the deployment server (8-core Xeon E3-1245 v5, dedicated, no players) on
2026-09-20, after a report that the server "cannot hold many entities and still does not use
all the CPU".

Two independent limits, and confusing them wastes time — this document exists because it
wasted mine.

```text
CPU used  ≈  (number of DISCONNECTED active regions)  ×  (per-region tick cost)
                        bounded by                          bounded by
              spatial separation of loaded chunks        region tick threads
```

---

## 1. Region tick threads — measured, and the default is not the best value

`threads: -1` in `paper-global.yml` resolves through **our own patch 0002** to
`availableProcessors() / 2`, which is **4** on this host. (Upstream Canvas resolved it to **1**,
which is why the patch exists at all.)

Identical block-tick load, same world, same server, one restart between arms:

| region threads | CPU mean | CPU max | MSPT mean | **MSPT max** | active regions |
|---|---|---|---|---|---|
| 4 (`-1`, the default) | 87.5% | 191.2% | 25.07 ms | **48.7 ms** | 3 |
| **8** (`= cores`) | 78.6% | 86.2% | **17.17 ms** | **18.3 ms** | 3 |

Eight threads cut mean MSPT by **32%** and the tail by **62%**, while using *less* average CPU —
less time contending for four threads. The 48.7 ms maximum under the default is the important
number: the tick budget is 50 ms, so the default was running a tail that nearly missed it under
a load the 8-thread arm absorbed at 18 ms.

**The deployment server is now set to `threads: 8`.** The shipped *default* is unchanged: one
host and one workload is thin evidence for a value every deployment inherits, and more region
threads can in principle starve chunk workers and Netty on a busier box. Raising it is a
per-host decision an operator can make today.

---

## 2. Region count — the harder limit, and the one that idles cores

A Folia region is a **connected component of loaded chunk sections**. Regions tick in parallel;
a single region ticks on exactly one thread. So the number of *disconnected* loaded areas is a
hard ceiling on parallelism, and no thread count can raise it.

| loaded-chunk shape | active regions |
|---|---|
| 1717 forceloaded chunks in broad connected bands | **3** |
| 8 sites of 2×2 chunks, 6000 blocks apart, nothing loaded between | **8** |

Both states were produced minutes apart on the same server. The three-region state was created
by benchmark forceloads that had grown until they joined: sites intended as separate became one
component. **A "region parallelism" problem was, on inspection, chunks that had been connected
by the measurement itself.**

Consequence for the original report: with 3 regions each ~17 ms busy out of a 50 ms budget, the
server uses about one core and idles seven. That is the architecture behaving correctly. Cores
are used when load sits in areas that are *not* joined by loaded chunks in between.

---

## 3. Entity capacity is a third thing again

Entity count is not what bounds entity cost. Two separate gates apply before a thread count
matters:

1. **Activation range.** Mob AI, goals and pathfinding run only for mobs near a connected
   player. With no players, 3750 zombies moved worst MSPT *down* (15.3 → 12.9 ms) — see
   [the entity domain](engine-entity-ai.md#4-the-measurement-constraint-this-domain-has).
2. **One region, one thread.** Entities concentrated in one area are one region regardless of
   count, so they tick on one thread. Ten thousand entities in one place cannot use eight cores;
   the same ten thousand spread across eight disconnected areas can.

---

## 4. What to do with this

- **Per host:** set `threaded-regions.threads` explicitly. On 8 cores, 8 measured better than
  the default 4 for this workload.
- **For load that should use the machine:** separate it. Disconnected areas, not one large one.
- **Before believing a parallelism measurement:** check what is actually loaded between the
  sites. `forceload query` and the active-region count disagree loudly when sites have merged,
  and the region count is the one telling the truth.
