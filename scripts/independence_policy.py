#!/usr/bin/env python3
"""Measure how much of SourbyCraft actually reaches into Canvas.

`docs/architecture/independence.md` keeps a qualitative dependency ledger. This makes
the Canvas half of it countable, so "reduce coupling to Canvas" has a number attached
and so new coupling has to be added deliberately rather than drifting in.

Two surfaces are measured and they are not the same thing:

* **Source coupling** — `io.canvasmc` reached from SourbyCraft-owned code. This is what
  would have to be rewritten if Canvas were replaced.
* **Patch coupling** — `io.canvasmc` symbols named by `minecraft-patches`, which are
  integration points into the engine. `canvas-patches/` is deliberately excluded: those
  patches exist only to modify Canvas and would be deleted with it, so counting them
  would measure the wrong thing.
* **Scheduler coupling** — the Folia *internal* region scheduler reached from
  SourbyCraft-owned code and from the integration patches. This is the surface an Aurora
  execution contract would have to cover, and it is the one that matters for the
  migration roadmap's Stage 1. `threadedregions.scheduler` is excluded on purpose: that
  package is the public, Bukkit-facing Folia API, a contract rather than an internal.
"""
import re
from pathlib import Path

SOURCE_ROOTS = (
    "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft",
    "sourbyapi/src/main/java/dev/iyanz/sourbycraft",
)

# Patches that integrate with the engine. canvas-patches/ modifies Canvas itself.
INTEGRATION_PATCH_ROOT = "sourbycraft-server/minecraft-patches"

CANVAS_SYMBOL = re.compile(r"\bio\.canvasmc\.[A-Za-z0-9_.]+")
COMMENT = re.compile(r"^\s*(\*|//|/\*)")

# Internal region-scheduler types. `threadedregions.scheduler` is the public Folia API
# and is deliberately absent: depending on a published contract is not coupling.
SCHEDULER_SYMBOL = re.compile(
    r"\b(?:io\.papermc\.paper\.threadedregions\.(?!scheduler\b)[A-Za-z0-9_.]+"
    r"|TickRegionScheduler|RegionizedServer|RegionizedWorldData|ThreadedRegionizer"
    r"|TickRegions)\b")
# A patch header names the file being changed, not a call into it.
PATCH_FILE_HEADER = re.compile(r"^\+\+\+ |^\+\+ [ab]/")


def canvas_source_sites(root):
    """Every `io.canvasmc` reference in SourbyCraft-owned code, excluding comments."""
    sites = []
    for source_root in SOURCE_ROOTS:
        base = Path(root) / source_root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
                if COMMENT.match(line):
                    continue                      # Javadoc naming a type is not coupling.
                for match in CANVAS_SYMBOL.finditer(line):
                    sites.append({"file": str(path.relative_to(root)), "line": number,
                                  "symbol": match.group(0)})
    return sites


def canvas_patch_symbols(root):
    """`io.canvasmc` symbols the engine-integration patches name, as a sorted list."""
    base = Path(root) / INTEGRATION_PATCH_ROOT
    if not base.is_dir():
        return []
    symbols = set()
    for patch in sorted(base.rglob("*.patch")):
        for line in patch.read_text(errors="replace").splitlines():
            if not line.startswith("+") or line.startswith("+++"):
                continue                          # Only what SourbyCraft adds.
            added = line[1:]
            if COMMENT.match(added):
                continue                          # A comment naming what was removed is not a call.
            symbols.update(CANVAS_SYMBOL.findall(added))
    return sorted(symbols)


def scheduler_sites(root):
    """Every internal region-scheduler reference in SourbyCraft-owned code."""
    sites = []
    for source_root in SOURCE_ROOTS:
        base = Path(root) / source_root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
                if COMMENT.match(line):
                    continue
                for match in SCHEDULER_SYMBOL.finditer(line):
                    sites.append({"file": str(path.relative_to(root)), "line": number,
                                  "symbol": match.group(0)})
    return sites


def scheduler_patch_sites(root):
    """Internal region-scheduler calls the engine-integration patches add.

    A patch that *edits* `TickRegionScheduler.java` names it in a file header; that is
    the patch's target, not a dependency, so headers are skipped and only added code
    counts.
    """
    base = Path(root) / INTEGRATION_PATCH_ROOT
    if not base.is_dir():
        return []
    sites = []
    for patch in sorted(base.rglob("*.patch")):
        for line in patch.read_text(errors="replace").splitlines():
            if not line.startswith("+") or PATCH_FILE_HEADER.match(line):
                continue
            added = line[1:]
            if COMMENT.match(added):
                continue
            for match in SCHEDULER_SYMBOL.finditer(added):
                sites.append({"patch": patch.name, "symbol": match.group(0)})
    return sites
