#!/usr/bin/env python3
"""Derive the Phase 0 baseline metric set from a JFR recording and process samples.

Every metric carries its own source so a reader can tell a measured value from a
derived estimate, and an unsupported metric is reported as unavailable rather than
defaulted to zero. See docs/BASELINE.md.
"""
from datetime import datetime
import math
import json
import re
import subprocess

_DURATION = re.compile(r"^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$")

UNAVAILABLE = {"available": False}


def duration_seconds(text):
    """Convert a JFR ISO-8601 duration such as 'PT0.003590667S' to seconds."""
    match = _DURATION.match(text)
    if match is None or text == "PT":
        raise ValueError(f"Not a JFR duration: {text!r}")
    hours, minutes, seconds = (float(part) if part else 0.0 for part in match.groups())
    return hours * 3600.0 + minutes * 60.0 + seconds


def percentile(values, fraction):
    """Linear-interpolated percentile over an unsorted sample list."""
    if not values:
        raise ValueError("percentile of an empty sample")
    if not 0.0 <= fraction <= 1.0:
        raise ValueError(f"fraction out of range: {fraction}")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = fraction * (len(ordered) - 1)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def distribution(values):
    """Summarize a sample list into the distribution PRD section 9 requires.

    Unreadable samples are dropped and counted rather than summed. A metric the server could
    not compute arrives as null through ``jfr print --json`` — NaN has no JSON spelling — and a
    server is most likely to fail computing one while it is overloaded, which is exactly the run
    worth keeping. Summing those raised a TypeError and destroyed the whole measurement.
    """
    usable = [value for value in values
              if value is not None and not (isinstance(value, float) and math.isnan(value))]
    if not usable:
        return dict(UNAVAILABLE, reason=f"no readable samples in {len(values)} recorded")
    summary = {"available": True, "samples": len(usable),
               "mean": sum(usable) / len(usable), "min": min(usable),
               "p50": percentile(usable, 0.50), "p95": percentile(usable, 0.95),
               "p99": percentile(usable, 0.99), "max": max(usable)}
    if len(usable) != len(values):
        # Stated, not silent: a distribution over half its samples is a different claim.
        summary["unreadable_samples"] = len(values) - len(usable)
    return summary


def read_events(jfr_tool, recording, event):
    """Return the ``values`` mapping of every event of one type in a recording.

    Only small, field-bounded event types belong here; printing a sampled event
    type such as jdk.ObjectAllocationSample also serializes every stack trace.
    """
    output = subprocess.run([str(jfr_tool), "print", "--json", "--events", event, str(recording)],
                            check=True, capture_output=True, text=True).stdout
    return [item["values"] for item in json.loads(output)["recording"]["events"]]


def gc_metrics(pauses, collections):
    """Collector identity plus the stop-the-world pause distribution."""
    if not pauses:
        return dict(UNAVAILABLE, reason="no jdk.GCPhasePause events in recording")
    milliseconds = [duration_seconds(event["duration"]) * 1000.0 for event in pauses]
    collectors = sorted({event["name"] for event in collections})
    return {"available": True, "source": "jdk.GCPhasePause",
            "collectors": collectors, "collections": len(collections),
            "pause_count": len(milliseconds),
            "total_pause_ms": sum(milliseconds),
            "pause_ms": distribution(milliseconds)}


def allocation_metrics(heap_summaries, window_seconds):
    """Estimate the allocation rate from heap occupancy across collection pairs.

    Bytes allocated between two collections are the heap used before a collection
    minus the heap used after the preceding one. This uses bounded events rather
    than jdk.ObjectAllocationSample, whose weights are a sampled extrapolation.
    """
    if window_seconds <= 0:
        raise ValueError("window_seconds must be positive")
    before, after = {}, {}
    for event in heap_summaries:
        (before if event["when"] == "Before GC" else after)[event["gcId"]] = event["heapUsed"]
    identifiers = sorted(before.keys() & after.keys())
    if len(identifiers) < 2:
        return dict(UNAVAILABLE, reason="fewer than two complete collections in recording")
    allocated = sum(max(0, before[current] - after[previous])
                    for previous, current in zip(identifiers, identifiers[1:]))
    return {"available": True, "source": "jdk.GCHeapSummary pairs",
            "collection_pairs": len(identifiers) - 1,
            "allocated_bytes": allocated,
            "bytes_per_second": allocated / window_seconds,
            "heap_used_after_gc": distribution([after[key] for key in identifiers])}


def instant_seconds(text):
    """Parse a JFR event timestamp into epoch seconds.

    JFR prints nanosecond precision, which datetime cannot parse, so the fractional
    part is truncated to microseconds.
    """
    match = re.match(r"^(.*\.\d{1,6})\d*([+-]\d{2}:\d{2}|Z)$", text)
    if match is None:
        raise ValueError(f"Not a JFR instant: {text!r}")
    stamp, zone = match.groups()
    return datetime.fromisoformat(stamp + ("+00:00" if zone == "Z" else zone)).timestamp()


def thread_allocation_metrics(statistics, top=8):
    """Allocation rate from cumulative per-thread counters, independent of GC.

    ``jdk.ThreadAllocationStatistics`` carries each thread's total allocated bytes and
    no stack trace, so it is cheap to read and — unlike a heap-occupancy estimate — it
    still reports a rate on a server that never collects. It undercounts: a thread that
    starts and exits between two samples is never observed, and only the span actually
    covered by samples is measured.
    """
    if not statistics:
        return dict(UNAVAILABLE, reason="no jdk.ThreadAllocationStatistics events in recording")
    series = {}
    for event in statistics:
        thread = event["thread"]
        key = thread.get("javaThreadId") or thread.get("osThreadId")
        name = thread.get("javaName") or thread.get("osName") or "unknown"
        moment = instant_seconds(event["startTime"])
        first, last = series.get(key, (None, None))
        point = (moment, event["allocated"], name)
        series[key] = (point if first is None or moment < first[0] else first,
                       point if last is None or moment > last[0] else last)
    observed = [(last[2], max(0, last[1] - first[1])) for first, last in series.values()]
    span = (max(last[0] for _, last in series.values())
            - min(first[0] for first, _ in series.values()))
    if span <= 0:
        return dict(UNAVAILABLE, reason="all thread allocation samples share one timestamp")
    allocated = sum(bytes_ for _, bytes_ in observed)
    ranked = sorted(observed, key=lambda entry: -entry[1])[:top]
    return {"available": True, "source": "jdk.ThreadAllocationStatistics",
            "threads_observed": len(series), "observed_seconds": span,
            "allocated_bytes": allocated, "bytes_per_second": allocated / span,
            "undercounts_short_lived_threads": True,
            "top_threads": [{"thread": name, "allocated_bytes": bytes_,
                             "bytes_per_second": bytes_ / span} for name, bytes_ in ranked]}


def cpu_metrics(loads):
    """Process and machine CPU load as fractions of total machine capacity.

    ``foreign_fraction`` is what the machine was doing that this server was not: the
    machine total minus this JVM's own share. On a dedicated box it sits near zero. A
    sustained non-zero value means the measurement was sharing the machine with
    something else, which no other certification rule can see.
    """
    if not loads:
        return dict(UNAVAILABLE, reason="no jdk.CPULoad events in recording")
    process = [event["jvmUser"] + event["jvmSystem"] for event in loads]
    machine = [event["machineTotal"] for event in loads]
    foreign = [max(0.0, whole - mine) for whole, mine in zip(machine, process)]
    return {"available": True, "source": "jdk.CPULoad",
            "process_fraction": distribution(process),
            "machine_fraction": distribution(machine),
            "foreign_fraction": distribution(foreign)}


# MetricState values whose sample carries its own freshly collected values.
# WARMING only means some longer window is not yet fully covered — the collector
# publishes real five-second data from its first minute, and the JFR event reads the
# five-second window. STALE republishes the previous sample's values, so counting it
# would weight a duplicate; UNAVAILABLE carries nothing.
USABLE_STATES = ("AVAILABLE", "WARMING")


def snapshot_metrics(snapshots):
    """Tick metrics from the server's own one-per-second telemetry publication.

    ``mspt`` is the distribution of the worst region's average MSPT across the
    published samples; it is not a per-tick histogram, and the separately reported
    ``reported_estimated_p95``/``p99`` are the server's own in-window estimates.
    """
    if not snapshots:
        return dict(UNAVAILABLE,
                    reason="no dev.iyanz.sourbycraft.PerformanceSnapshot events; "
                           "server predates build 44 or the event was disabled")
    usable = [event for event in snapshots if event["state"] in USABLE_STATES]
    if not usable:
        states = sorted({event["state"] for event in snapshots})
        return dict(UNAVAILABLE, reason=f"no usable telemetry samples; states seen: {states}")
    counts = {state: sum(1 for event in snapshots if event["state"] == state)
              for state in sorted({event["state"] for event in snapshots})}
    targets = sorted({event["targetTps"] for event in usable})
    return {"available": True, "source": "dev.iyanz.sourbycraft.PerformanceSnapshot",
            "builds": sorted({event["build"] for event in usable}),
            "published_samples": len(snapshots), "usable_samples": len(usable),
            "samples_by_state": counts,
            "long_windows_covered": counts.get("WARMING", 0) == 0,
            "target_tps": targets[0] if len(targets) == 1 else targets,
            "active_regions": distribution([event["activeRegions"] for event in usable]),
            "tps": distribution([event["worstTps"] for event in usable]),
            "mspt": distribution([event["worstAverageMspt"] for event in usable]),
            "reported_estimated_p95_mspt": distribution([event["estimatedP95Mspt"] for event in usable]),
            "reported_estimated_p99_mspt": distribution([event["estimatedP99Mspt"] for event in usable]),
            "heap_used_bytes": distribution([event["heapUsedBytes"] for event in usable])}


def rss_metrics(samples_kib):
    """Resident set size sampled from the operating system, not from the JVM."""
    if not samples_kib:
        return dict(UNAVAILABLE, reason="no resident memory samples collected")
    return {"available": True, "source": "ps -o rss=",
            "bytes": distribution([value * 1024 for value in samples_kib])}


def drift(samples, label):
    """Compare the first quarter of a series against the last.

    A soak asks a different question from a benchmark: not "how fast" but "does it stay
    the same". A leak, a growing queue or a thread that is never released shows up as a
    series that climbs and does not come back, which a distribution over the whole window
    hides completely — the mean of a climbing series looks unremarkable.
    """
    if len(samples) < 8:
        return dict(UNAVAILABLE, reason=f"too few {label} samples for a trend ({len(samples)})")
    quarter = max(1, len(samples) // 4)
    early = samples[:quarter]
    late = samples[-quarter:]
    first = sum(early) / len(early)
    last = sum(late) / len(late)
    return {"available": True, "samples": len(samples),
            "first_quarter_mean": first, "last_quarter_mean": last,
            "change": last - first,
            "change_fraction": (last - first) / first if first else None,
            "peak": max(samples)}


def collect(jfr_tool, recording, window_seconds, rss_samples_kib):
    """Assemble the full baseline metric set for one workload run."""
    return {
        "tick": snapshot_metrics(read_events(jfr_tool, recording, "dev.iyanz.sourbycraft.PerformanceSnapshot")),
        "cpu": cpu_metrics(read_events(jfr_tool, recording, "jdk.CPULoad")),
        "gc": gc_metrics(read_events(jfr_tool, recording, "jdk.GCPhasePause"),
                         read_events(jfr_tool, recording, "jdk.GarbageCollection")),
        "allocation": thread_allocation_metrics(
            read_events(jfr_tool, recording, "jdk.ThreadAllocationStatistics")),
        # Heap-occupancy estimate, kept as an independent cross-check. It is unavailable
        # whenever the run did not collect at least twice — which an idle server at a large
        # heap does not, even over ten minutes.
        "allocation_from_gc": allocation_metrics(
            read_events(jfr_tool, recording, "jdk.GCHeapSummary"), window_seconds),
        "rss": rss_metrics(rss_samples_kib),
        # Trends, for soak runs. Retained heap and resident memory that climb across the
        # window and do not return are what a soak is looking for.
        "drift": {
            "rss_bytes": drift([value * 1024 for value in rss_samples_kib], "resident memory"),
            "heap_after_gc_bytes": drift(_heap_after_gc(
                read_events(jfr_tool, recording, "jdk.GCHeapSummary")), "heap-after-GC"),
            "mspt": drift(_snapshot_series(
                read_events(jfr_tool, recording,
                            "dev.iyanz.sourbycraft.PerformanceSnapshot")), "tick"),
        },
    }


def _heap_after_gc(summaries):
    """Heap occupancy after each collection, in collection order."""
    after = [(event["gcId"], event["heapUsed"]) for event in summaries
             if event.get("when") == "After GC"]
    return [used for _, used in sorted(after)]


def _snapshot_series(snapshots):
    """Worst-region average MSPT per published sample, in publication order."""
    usable = [event for event in snapshots if event["state"] in USABLE_STATES]
    usable.sort(key=lambda event: event["sequence"])
    return [event["worstAverageMspt"] for event in usable]
