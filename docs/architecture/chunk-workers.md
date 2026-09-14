# Chunk worker threads — a measured trade, not a bug

The first certified before/after this project has. Both sides met every certification
rule: clean worktree, 300-second window, ten clients connected and moving, HEAD steady,
and a quiet machine (5.5% and 4.4% foreign CPU).

## What prompted it

A loaded profile — fifty moving clients, fifty regions — put **78.5% of all CPU on two
`Paper Common Worker` threads** doing terrain generation (`NoiseBasedChunkGenerator.doFill`,
`ImprovedNoise`, `PerlinNoise`, `Aquifer`, `Mth.lerp2/lerp3`), while the four region
threads sat at roughly 5% each.

Two workers, on an eight-core machine, because of this in `MoonriseCommon`:

```java
int defaultWorkerThreads = OSNuma.getNativeInstance().getTotalCores() / 2;  // 8/2 = 4
if (defaultWorkerThreads <= 4) {
    defaultWorkerThreads = defaultWorkerThreads <= 3 ? 1 : 2;               // 4 -> 2
}
```

That is the same arithmetic patch 0002 already corrected for region tick threads, whose
comment calls it "the whole region-threaded engine ends up serialised onto a single
thread while the remaining cores idle". The obvious conclusion was that chunk workers are
starved the same way and the count should be raised.

**That conclusion was wrong.**

## The measurement

`players-10`, ten moving clients, ten regions, 300 seconds, 4 GiB heap, G1.

| Metric | workers=2 | workers=6 | Delta |
| --- | ---: | ---: | ---: |
| MSPT avg | 3.981 ms | 4.870 ms | **+22.3%** |
| MSPT p50 | 4.030 ms | 5.009 ms | **+24.3%** |
| MSPT p95 | 5.317 ms | 6.622 ms | **+24.6%** |
| MSPT p99 | 11.638 ms | 7.143 ms | **−38.6%** |
| MSPT max | 12.046 ms | 7.641 ms | **−36.6%** |
| TPS | 19.941 | 19.951 | +0.1% |
| CPU | 18.2% | 20.2% | +11.0% |
| GC pause p95 | 49.1 ms | 42.2 ms | −13.9% |
| RSS | 4797 MB | 4865 MB | +1.4% |

More workers make the **typical tick about a quarter slower** and the **worst tick about
a third faster**. Generating in parallel means more completed chunk work lands back on
the region threads every tick, which costs the median; it also means far fewer pile-ups,
which is where the p99 and max come from.

## What follows

This is an operator trade, so the default is left alone. PRD section 5 is explicit that
SourbyCraft does not silently change what an administrator gets, and neither column here
is simply better.

* **Stability and reliability** — prefer more workers. p99 and max are what a player
  experiences as a freeze, and a third off the worst tick is the larger effect.
  `chunk-system.worker-threads: 6` in `paper-global.yml`, or
  `-DPaper.WorkerThreadCount=6`.
* **Median throughput** — prefer the default. The typical tick is meaningfully cheaper.

Neither is a code change. If the trade is later shown to hold across player counts and
hardware, the argument for changing the shipped default can be made then, with the
evidence attached.

## Why the first attempt had to be thrown away

An earlier pass at the same question ran the two sides under 21.1% and 9.2% foreign CPU.
It reported MSPT avg 2.39 against 3.42 — pointing the opposite way on the median — and a
GC p95 drop of 206 ms to 74 ms that the clean run does not reproduce at anything like
that size. Acting on it would have produced a confident and wrong conclusion.

The comparison was refused by the tooling rather than by judgement: both runs were
uncertified for contention and a short window, and the gate blocked. That refusal is the
reason there is a trustworthy number here at all.

## Reproducing

```sh
for W in 2 6; do
  python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
    --workload players-10 --output build/baselines/ab-workers-$W \
    --warmup 120 --duration 300 --heap-mib 4096 --port 2576$W \
    --cache-from build/profiles/build45 --connected-players 10 \
    --property Paper.WorkerThreadCount=$W
done
python3 scripts/compare_baseline.py \
    build/baselines/ab-workers-2 build/baselines/ab-workers-6 --allow-provenance-drift
```

`--allow-provenance-drift` is required and correct here: the JVM property *is* the
variable under test, so the tooling reporting it as drift is the tooling working.

## Not established

* Only ten clients and ten regions. Whether the trade holds at fifty clients, where the
  earlier profile showed p99 at thirteen times the mean, is untested.
* One machine, eight cores, one heap size, G1 only.
* The 78.5% figure comes from a contended profile. The share is reliable; the absolute
  CPU is not.
