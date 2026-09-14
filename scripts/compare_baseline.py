#!/usr/bin/env python3
"""Compare a candidate baseline against a reference and apply the PRD section 10 gate.

Emits the PRD section 85 benchmark table and exits non-zero when a gated metric
regresses by more than the threshold, so a performance change cannot be merged on an
unexplained regression.

    python3 scripts/compare_baseline.py build/baselines/before build/baselines/after
"""
import argparse
import json
from pathlib import Path

# label -> (metric path, lower value is better, formatter)
METRICS = {
    "MSPT avg": (("tick", "mspt", "mean"), True, "{:.3f} ms"),
    "MSPT p50": (("tick", "mspt", "p50"), True, "{:.3f} ms"),
    "MSPT p95": (("tick", "mspt", "p95"), True, "{:.3f} ms"),
    "MSPT p99": (("tick", "mspt", "p99"), True, "{:.3f} ms"),
    "MSPT max": (("tick", "mspt", "max"), True, "{:.3f} ms"),
    "TPS avg": (("tick", "tps", "mean"), False, "{:.3f}"),
    "CPU": (("cpu", "process_fraction", "mean"), True, "{:.1%}"),
    "RAM (heap after GC)": (("allocation_from_gc", "heap_used_after_gc", "mean"), True, "{:.0f} B"),
    "RAM (RSS)": (("rss", "bytes", "mean"), True, "{:.0f} B"),
    "Allocation": (("allocation", "bytes_per_second"), True, "{:.0f} B/s"),
    "Allocation (GC estimate)": (("allocation_from_gc", "bytes_per_second"), True, "{:.0f} B/s"),
    "GC pause total": (("gc", "total_pause_ms"), True, "{:.1f} ms"),
    "GC pause p95": (("gc", "pause_ms", "p95"), True, "{:.3f} ms"),
    "GC count": (("gc", "collections"), True, "{:.0f}"),
    "Network round-trips": (("network", "round_trips_per_second"), False, "{:.1f}/s"),
}

# Provenance that must match for a comparison to mean anything.
PINNED = ("java_version", "platform", "machine", "cpu_count", "heap_mib", "jvm_args",
          "jfr_settings", "warmup_seconds", "duration_seconds", "connected_players_asserted")


def dig(record, path):
    node = record if path[0] == "network" else record.get("metrics", {})
    for key in path:
        if not isinstance(node, dict) or key not in node:
            return None
        node = node[key]
    if isinstance(node, dict):
        return None
    return node


def load(target):
    """Accept a baseline.json, or a directory holding one."""
    path = Path(target)
    if path.is_dir():
        path = path / "baseline.json"
    record = json.loads(path.read_text())
    if record.get("schema") != "sourbycraft.baseline/1":
        raise SystemExit(f"{path} is not a baseline record")
    return record


def provenance_drift(reference, candidate):
    return [f"{key}: {reference['provenance'].get(key)!r} -> {candidate['provenance'].get(key)!r}"
            for key in PINNED
            if reference["provenance"].get(key) != candidate["provenance"].get(key)]


def compare(reference, candidate, threshold, gated):
    rows, blocking = [], []
    for role, record in (("Reference", reference), ("Candidate", candidate)):
        if not record.get("certified"):
            blocking.append(f"{role} is not a certified baseline: "
                            f"{record.get('certification', 'certification missing')}")
    for label, (path, lower_is_better, form) in METRICS.items():
        before, after = dig(reference, path), dig(candidate, path)
        if before is None or after is None:
            rows.append((label, "unavailable" if before is None else form.format(before),
                         "unavailable" if after is None else form.format(after), None, ""))
            continue
        if before == 0:
            rows.append((label, form.format(before), form.format(after), None, "no reference value"))
            continue
        delta = (after - before) / abs(before)
        regression = delta if lower_is_better else -delta
        note = ""
        if label in gated and regression > threshold:
            note = "**regression**"
            blocking.append(f"{label} regressed {regression:+.1%} (threshold {threshold:.0%})")
        rows.append((label, form.format(before), form.format(after), delta, note))
    return rows, blocking


def render(reference, candidate, rows, blocking, drift, threshold):
    blocking = list(blocking)
    for role, record in (("Reference", reference), ("Candidate", candidate)):
        if not record.get("certified"):
            blocking.append(f"{role} is not a certified baseline")
    if drift:
        blocking.append("provenance differs; comparison is descriptive only")
    lines = [f"# Baseline comparison — {candidate['workload']['name']}", "",
             f"{candidate['workload']['summary']}", "",
             "| Field | Reference | Candidate |", "| --- | --- | --- |",
             f"| Baseline SHA | `{reference['provenance']['commit'][:12]}` | |",
             f"| Candidate SHA | | `{candidate['provenance']['commit'][:12]}` |",
             f"| Jar SHA-256 | `{reference['provenance']['jar_sha256'][:16]}` "
             f"| `{candidate['provenance']['jar_sha256'][:16]}` |",
             f"| Hardware | {reference['provenance']['platform']} "
             f"({reference['provenance']['cpu_count']} cpu) | "
             f"{candidate['provenance']['platform']} ({candidate['provenance']['cpu_count']} cpu) |",
             f"| Java | {reference['provenance']['java_version'].splitlines()[0]} "
             f"| {candidate['provenance']['java_version'].splitlines()[0]} |",
             f"| JVM flags | `{' '.join(reference['provenance']['jvm_args'])}` "
             f"| `{' '.join(candidate['provenance']['jvm_args'])}` |",
             f"| World | {reference['workload']['level_type']} "
             f"| {candidate['workload']['level_type']} |",
             "| Plugins | "
             f"{', '.join(reference['provenance']['plugins']) or 'none'} | "
             f"{', '.join(candidate['provenance']['plugins']) or 'none'} |",
             f"| Duration | {reference['provenance']['duration_seconds']}s "
             f"| {candidate['provenance']['duration_seconds']}s |",
             f"| Warmup | {reference['provenance']['warmup_seconds']}s "
             f"| {candidate['provenance']['warmup_seconds']}s |", "",
             "| Metric | Before | After | Delta | |", "| --- | ---: | ---: | ---: | --- |"]
    for label, before, after, delta, note in rows:
        lines.append(f"| {label} | {before} | {after} | "
                     f"{'—' if delta is None else f'{delta:+.1%}'} | {note} |")
    lines.append("")
    for record, role in ((reference, "Reference"), (candidate, "Candidate")):
        if not record.get("certified"):
            lines.append(f"> **{role} is not a certified baseline:** {record['certification']}")
    if drift:
        lines.append("")
        lines.append("> **Provenance drift — these runs are not directly comparable:**")
        lines.extend(f"> - {entry}" for entry in drift)
    lines.append("")
    lines.append(f"**Gate ({threshold:.0%}):** "
                 + ("PASS" if not blocking else "BLOCKED — " + "; ".join(blocking)))
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reference")
    parser.add_argument("candidate")
    parser.add_argument("--threshold", type=float, default=0.03)
    parser.add_argument("--gate", action="append", choices=sorted(METRICS),
                        help="Restrict the gate to these metrics; repeatable. Default: all.")
    parser.add_argument("--allow-provenance-drift", action="store_true")
    parser.add_argument("--output", type=Path, help="Write the report here as well as to stdout")
    args = parser.parse_args()

    reference, candidate = load(args.reference), load(args.candidate)
    if reference["workload"]["name"] != candidate["workload"]["name"]:
        raise SystemExit(f"Workload mismatch: {reference['workload']['name']} vs "
                         f"{candidate['workload']['name']}")
    drift = provenance_drift(reference, candidate)
    rows, blocking = compare(reference, candidate, args.threshold,
                             set(args.gate or METRICS))
    report = render(reference, candidate, rows, blocking, drift, args.threshold)
    print(report)
    if args.output:
        args.output.write_text(report)
    if drift and not args.allow_provenance_drift:
        raise SystemExit("Runs differ in pinned provenance; re-run on matched conditions or "
                         "pass --allow-provenance-drift to report anyway")
    if blocking:
        raise SystemExit("Blocked by the performance regression gate")


if __name__ == "__main__":
    main()
