#!/usr/bin/env python3
"""Keep SourbyCraft edits out of the engine baseline patch directories.

The engine baseline is what Canvas/Folia used to supply before Paper became the only
upstream (2026-10-05, commits e20df47b and 2554782c):

    sourbycraft-server/minecraft-patches/sources
    sourbycraft-server/paper-patches/files
    sourbyapi/paper-patches/files

Every SourbyCraft change to Minecraft or Paper code must be a feature patch
(`*/features/NNNN-SourbyCraft-*.patch`), because the policy scripts (`patch_policy.py` and
friends) scan only the feature directories. An edit hidden in the baseline would bypass them.

Two independent checks, so that neither can be defeated alone:

1. Manifest. `build-data/engine-baseline.sha256` records the SHA-256 of every baseline file.
   Any file added, removed or changed relative to it is reported. A deliberate baseline change
   (for example re-diffing the baseline against a new Paper pin) regenerates the manifest with
   `--write-manifest`, which puts the change in front of review as a manifest diff.
   Content is hashed with CRLF normalised to LF, so the check does not depend on checkout
   line-ending settings.
2. Content. No added line (`+`, excluding the `+++` header) of a baseline patch may carry
   SourbyCraft's own markers: the word "Sourby" in any case (covers `// SourbyCraft start`,
   `dev.iyanz.sourbycraft`, `SourbyCraftBootstrap`), or a `dev.iyanz.*` reference outside the
   engine package `dev.iyanz.aurora.engine` (the baseline was renamed into that package; other
   Aurora packages such as `dev.iyanz.aurora.cpu` belong to feature patches).
   `--write-manifest` refuses to record a tree that fails this check.

Neither check proves that a baseline hunk is not a SourbyCraft change in disguise (an
unmarked edit with a Canvas marker would pass the content check); the manifest is what makes
such an edit visible in review. Run from the repository root:

    python3 scripts/baseline_policy.py                   # check, exit 1 on any violation
    python3 scripts/baseline_policy.py --write-manifest  # record the current baseline
"""
import argparse
import hashlib
import re
import sys
from pathlib import Path

BASELINE_ROOTS = (
    "sourbycraft-server/minecraft-patches/sources",
    "sourbycraft-server/paper-patches/files",
    "sourbyapi/paper-patches/files",
)

MANIFEST = "build-data/engine-baseline.sha256"

SOURBY_MARKER = re.compile(r"sourby", re.IGNORECASE)
PRODUCT_PACKAGE = re.compile(r"\bdev\.iyanz\.[A-Za-z0-9_.]*")
ENGINE_PACKAGE = "dev.iyanz.aurora.engine"


class BaselineError(Exception):
    """The baseline cannot be checked at all (a root is missing or empty)."""


def baseline_files(root):
    """Repository-relative POSIX paths of every baseline file, sorted.

    A missing or empty root is an error, not a skip: a check that finds nothing to read
    would pass silently.
    """
    root = Path(root)
    found = []
    for directory in BASELINE_ROOTS:
        base = root / directory
        if not base.is_dir():
            raise BaselineError(f"baseline root missing: {directory}")
        files = sorted(p for p in base.rglob("*") if p.is_file())
        if not files:
            raise BaselineError(f"baseline root empty: {directory}")
        found.extend(p.relative_to(root).as_posix() for p in files)
    return sorted(found)


def file_digest(path):
    data = Path(path).read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def current_hashes(root):
    return {relative: file_digest(Path(root) / relative) for relative in baseline_files(root)}


def read_manifest(root):
    path = Path(root) / MANIFEST
    if not path.is_file():
        raise BaselineError(f"manifest missing: {MANIFEST} (create it with --write-manifest)")
    entries = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("  ", 1)
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            raise BaselineError(f"{MANIFEST}:{number}: malformed line: {line!r}")
        entries[parts[1]] = parts[0]
    return entries


def render_manifest(hashes, note):
    lines = [
        "# Engine baseline content manifest, checked by scripts/baseline_policy.py.",
        "# SHA-256 of each file (CRLF normalised to LF). SourbyCraft changes to Minecraft/Paper",
        "# code belong in */features/ patches; regenerate this only for a deliberate baseline",
        "# change: python3 scripts/baseline_policy.py --write-manifest",
    ]
    if note:
        lines.append(f"# {note}")
    lines += [f"{digest}  {relative}" for relative, digest in sorted(hashes.items())]
    return "\n".join(lines) + "\n"


def manifest_drift(root):
    """{"added": [...], "removed": [...], "changed": [...]} relative to the manifest."""
    recorded = read_manifest(root)
    actual = current_hashes(root)
    return {
        "added": sorted(set(actual) - set(recorded)),
        "removed": sorted(set(recorded) - set(actual)),
        "changed": sorted(p for p in set(actual) & set(recorded) if actual[p] != recorded[p]),
    }


def marker_violations(root):
    """Added baseline lines that carry a SourbyCraft marker or a non-engine product package.

    Returns {file, line, text, reason} dicts, line numbers 1-based within the patch file.
    """
    violations = []
    for relative in baseline_files(root):
        text = (Path(root) / relative).read_text(errors="replace")
        for number, line in enumerate(text.splitlines(), 1):
            if not line.startswith("+") or line.startswith("+++"):
                continue
            reasons = []
            if SOURBY_MARKER.search(line):
                reasons.append("SourbyCraft marker")
            for package in PRODUCT_PACKAGE.findall(line):
                if package != ENGINE_PACKAGE and not package.startswith(ENGINE_PACKAGE + "."):
                    reasons.append(f"non-engine package {package.rstrip('.')}")
            for reason in reasons:
                violations.append({"file": relative, "line": number,
                                   "text": line[1:].strip(), "reason": reason})
    return violations


def check(root):
    """Every violation as a printable line; empty when the baseline is clean."""
    problems = []
    drift = manifest_drift(root)
    for kind in ("added", "removed", "changed"):
        for relative in drift[kind]:
            problems.append(f"baseline file {kind} relative to {MANIFEST}: {relative}")
    for v in marker_violations(root):
        problems.append(f"{v['file']}:{v['line']}: {v['reason']}: {v['text']}")
    return problems


def write_manifest(root, note=""):
    violations = marker_violations(root)
    if violations:
        raise BaselineError("refusing to record a baseline with SourbyCraft edits: "
                            + "; ".join(f"{v['file']}:{v['line']}" for v in violations))
    hashes = current_hashes(root)
    (Path(root) / MANIFEST).write_text(render_manifest(hashes, note))
    return len(hashes)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--write-manifest", action="store_true",
                        help="record the current baseline (refused if it carries SourbyCraft edits)")
    parser.add_argument("--note", default="", help="comment line stored in the manifest header")
    args = parser.parse_args(argv)
    try:
        if args.write_manifest:
            count = write_manifest(args.root, args.note)
            print(f"baseline_policy: recorded {count} files in {MANIFEST}")
            return 0
        problems = check(args.root)
    except BaselineError as error:
        print(f"baseline_policy: ERROR: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"baseline_policy: {problem}", file=sys.stderr)
    if problems:
        print(f"baseline_policy: {len(problems)} violation(s); SourbyCraft changes to Minecraft/"
              "Paper code belong in */features/ patches", file=sys.stderr)
        return 1
    print(f"baseline_policy: OK ({len(baseline_files(args.root))} baseline files match {MANIFEST})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
