#!/usr/bin/env python3
"""Rank CPU and allocation hot spots from a JFR recording.

Produces the ranked evidence PRD section 84 and the section F rule "only measured hot
spots should produce new performance patches" both require, so an optimization backlog
can be argued from a profile rather than from intuition.

    python3 scripts/rank_hotspots.py build/baselines/players-100/profile.jfr

Read the caveats it prints. Both rankings are estimates, and a workload with no
connected clients does not exercise mob AI at all.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import subprocess

import baseline_metrics

# Frames that are the profiler or the harness observing the server, not the server working.
OBSERVER_PREFIXES = ("me.lucko.spark", "jdk.jfr", "dev.iyanz.sourbycraft.perf")


def _dotted(name):
    return (name or "unknown").replace("/", ".")


def _frames(values):
    trace = values.get("stackTrace") or {}
    out = []
    for frame in trace.get("frames") or []:
        method = frame.get("method") or {}
        owner = _dotted(((method.get("type") or {}).get("name")))
        out.append(f"{owner}.{method.get('name') or '?'}")
    return out


def _thread_name(values, key):
    thread = values.get(key) or {}
    return thread.get("javaName") or thread.get("osName") or "unknown"


def read(jfr_tool, recording, event, depth):
    """Read one event type at a bounded stack depth.

    Depth matters: a full-depth print of a sampling event serializes every frame of
    every sample and can dwarf the recording itself.
    """
    output = subprocess.run(
        [str(jfr_tool), "print", "--json", "--events", event, "--stack-depth", str(depth),
         str(recording)], check=True, capture_output=True, text=True).stdout
    return [item["values"] for item in json.loads(output)["recording"]["events"]]


def cpu_hotspots(samples, limit):
    """Rank where sampled Java execution actually was.

    ``self`` is the leaf frame: the method executing when the sample was taken.
    ``inclusive`` counts a method anywhere in the sampled stack, so a method that mostly
    calls other things ranks high there and low in self.
    """
    if not samples:
        return dict(baseline_metrics.UNAVAILABLE,
                    reason="no jdk.ExecutionSample events; the recording used settings that "
                           "do not sample execution, or nothing ran")
    self_time, inclusive, by_thread, by_state = (defaultdict(int) for _ in range(4))
    for values in samples:
        frames = _frames(values)
        by_thread[_thread_name(values, "sampledThread")] += 1
        by_state[values.get("state") or "unknown"] += 1
        if not frames:
            continue
        self_time[frames[0]] += 1
        for method in set(frames):
            inclusive[method] += 1
    total = len(samples)

    def rank(counter):
        return [{"name": name, "samples": count, "share": count / total}
                for name, count in sorted(counter.items(), key=lambda kv: -kv[1])[:limit]]

    observed = sum(count for name, count in self_time.items()
                   if name.startswith(OBSERVER_PREFIXES))
    return {"available": True, "source": "jdk.ExecutionSample", "total_samples": total,
            "observer_self_share": observed / total,
            "self": rank(self_time), "inclusive": rank(inclusive),
            "threads": rank(by_thread), "states": rank(by_state)}


def allocation_hotspots(samples, limit, measured_bytes=None):
    """Rank allocated bytes by type and by allocation site.

    ``weight`` is JFR's extrapolation from a sampled allocation to the bytes that sample
    represents, so these are proportions, not exact totals — and the sampler favours large
    objects, so a site allocating big arrays is over-represented relative to one allocating
    many small objects.

    Pass ``measured_bytes`` from a counter-based source (``jdk.ThreadAllocationStatistics``)
    to get an overstatement factor. On a real recording these disagreed sixfold, and the
    site at the top of the ranking turned out not to be a meaningful allocator at all.
    """
    if not samples:
        return dict(baseline_metrics.UNAVAILABLE,
                    reason="no jdk.ObjectAllocationSample events in recording")
    by_class, by_site, by_thread = (defaultdict(int) for _ in range(3))
    for values in samples:
        weight = values.get("weight") or 0
        by_class[_dotted((values.get("objectClass") or {}).get("name"))] += weight
        by_thread[_thread_name(values, "eventThread")] += weight
        frames = _frames(values)
        if frames:
            by_site[frames[0]] += weight
    total = sum(by_class.values())

    def rank(counter):
        return [{"name": name, "bytes": value, "share": value / total if total else 0.0}
                for name, value in sorted(counter.items(), key=lambda kv: -kv[1])[:limit]]

    result = {"available": True, "source": "jdk.ObjectAllocationSample",
              "sampled_events": len(samples), "estimated_bytes": total,
              "by_class": rank(by_class), "by_site": rank(by_site), "by_thread": rank(by_thread)}
    if measured_bytes:
        result["measured_bytes"] = measured_bytes
        result["overstatement_factor"] = total / measured_bytes if measured_bytes else None
    return result


def tick_budget_note(tick, target_tps):
    """How much of the tick budget the profiled run actually used.

    A CPU ranking describes where time went, not whether any of it was a problem. A run
    using a few percent of its tick budget has no bottleneck to find: the top entry is
    simply the largest slice of nearly nothing, and any improvement to it would sit below
    run-to-run noise.
    """
    if not tick or not tick.get("available"):
        return None
    budget_ms = 1000.0 / (target_tps or 20.0)
    used = tick["mspt"]["mean"] / budget_ms
    return {"mspt_mean": tick["mspt"]["mean"], "budget_ms": budget_ms, "fraction_used": used}


def render(recording, cpu, allocation, workload, budget=None):
    lines = [f"# Hot spots — {recording.name}", ""]
    if budget is not None and budget["fraction_used"] < 0.20:
        lines += [f"> **This run used {budget['fraction_used']:.1%} of its tick budget** "
                  f"({budget['mspt_mean']:.2f} ms of {budget['budget_ms']:.0f} ms). There is no "
                  "bottleneck here to find. The entries below show where the little time that "
                  "was spent went, not what is slow; the top one is the largest slice of nearly "
                  "nothing, and improving it would land below run-to-run noise. Load the server "
                  "before using this to justify an optimization.", ""]
    if workload:
        lines += [f"Workload: **{workload.get('name')}** — {workload.get('summary')}", ""]
        for note in workload.get("fidelity") or []:
            lines.append(f"> {note}")
        lines.append("")

    lines += ["## CPU", ""]
    if not cpu["available"]:
        lines += [f"Unavailable: {cpu['reason']}", ""]
    else:
        lines += [f"{cpu['total_samples']} execution samples. "
                  f"{cpu['observer_self_share']:.1%} of self samples are the profiler or "
                  "Sourby telemetry observing the server, not the server working.", "",
                  "### Self (the method that was executing)", "",
                  "| Share | Samples | Method |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['samples']} | `{row['name']}` |" for row in cpu["self"]]
        lines += ["", "### Inclusive (anywhere on the sampled stack)", "",
                  "| Share | Samples | Method |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['samples']} | `{row['name']}` |" for row in cpu["inclusive"]]
        lines += ["", "### By thread", "", "| Share | Samples | Thread |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['samples']} | `{row['name']}` |" for row in cpu["threads"]]
        lines.append("")

    lines += ["## Allocation", ""]
    if not allocation["available"]:
        lines += [f"Unavailable: {allocation['reason']}", ""]
    else:
        lines += [f"{allocation['sampled_events']} allocation samples, "
                  f"{allocation['estimated_bytes'] / 1048576:.0f} MiB estimated. "
                  "Weights are extrapolations, not exact totals.", ""]
        factor = allocation.get("overstatement_factor")
        if factor is not None and factor >= 2.0:
            lines += [f"> **These figures are {factor:.1f}x the allocation the counters actually "
                      f"measured** ({allocation['measured_bytes'] / 1048576:.0f} MiB from "
                      "`jdk.ThreadAllocationStatistics`). JFR's allocation sampler favours large "
                      "objects, so sites allocating big arrays dominate this table out of "
                      "proportion. Treat the ordering as a hint and confirm any target against "
                      "the counter total and the CPU ranking before acting on it.", ""]
        elif factor is not None:
            lines += [f"> Cross-checked against the counters: {factor:.1f}x "
                      f"({allocation['measured_bytes'] / 1048576:.0f} MiB measured).", ""]
        lines += [
                  "### By type", "", "| Share | MiB | Type |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['bytes'] / 1048576:.1f} | `{row['name']}` |"
                  for row in allocation["by_class"]]
        lines += ["", "### By allocation site", "", "| Share | MiB | Method |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['bytes'] / 1048576:.1f} | `{row['name']}` |"
                  for row in allocation["by_site"]]
        lines += ["", "### By thread", "", "| Share | MiB | Thread |", "| ---: | ---: | --- |"]
        lines += [f"| {row['share']:.1%} | {row['bytes'] / 1048576:.1f} | `{row['name']}` |"
                  for row in allocation["by_thread"]]
        lines.append("")

    lines += ["## How to read this", "",
              "* Execution sampling only sees Java frames on threads the JVM sampled. Native "
              "work, GC and JIT compilation are not attributed here.",
              "* Sample counts are proportional to time, not measured time.",
              "* Allocation weights are extrapolated from sampled allocations.",
              "* A ranking is only as representative as its workload. A run with no connected "
              "clients leaves mob AI inactive, so nothing here speaks to AI cost.",
              "* A high rank is a candidate to investigate, not a defect.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--limit", type=int, default=15)
    parser.add_argument("--stack-depth", type=int, default=3,
                        help="Frames to read per sample. 1 is enough for self/site ranking.")
    parser.add_argument("--output", type=Path, help="Write the markdown report here too")
    parser.add_argument("--json", type=Path, dest="json_output", help="Write the raw ranking here")
    args = parser.parse_args()
    recording = args.recording.resolve(strict=True)
    _, _, jfr, _ = __import__("run_baseline").jdk_tools()

    cpu = cpu_hotspots(read(jfr, recording, "jdk.ExecutionSample", args.stack_depth), args.limit)
    workload = None
    measured_bytes = None
    record = recording.parent / "baseline.json"
    if record.is_file():
        captured = json.loads(record.read_text())
        workload = captured.get("workload")
        counters = captured.get("metrics", {}).get("allocation", {})
        if counters.get("available"):
            measured_bytes = counters.get("allocated_bytes")

    allocation = allocation_hotspots(
        read(jfr, recording, "jdk.ObjectAllocationSample", args.stack_depth), args.limit,
        measured_bytes)

    budget = None
    if record.is_file():
        captured = json.loads(record.read_text())
        budget = tick_budget_note(captured.get("metrics", {}).get("tick"),
                                  captured.get("metrics", {}).get("tick", {}).get("target_tps"))
    report = render(recording, cpu, allocation, workload, budget)
    print(report)
    if args.output:
        args.output.write_text(report)
    if args.json_output:
        args.json_output.write_text(json.dumps({"cpu": cpu, "allocation": allocation}, indent=2) + "\n")


if __name__ == "__main__":
    main()
