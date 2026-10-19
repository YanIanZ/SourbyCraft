#!/usr/bin/env python3
"""Measure how much of SourbyCraft actually reaches into Canvas.

`docs/architecture/independence.md` keeps a qualitative dependency ledger. This makes
the Canvas half of it countable, so "reduce coupling to Canvas" has a number attached
and so new coupling has to be added deliberately rather than drifting in.

Two surfaces are measured and they are not the same thing:

* **Source coupling** — the region-threading engine (`dev.iyanz.aurora.engine`, formerly
  `io.canvasmc.canvas`, plus the `io.canvasmc` plugin API) reached from SourbyCraft-owned code.
  This is what would have to be rewritten if the engine were replaced.
* **Patch coupling** — engine symbols named by the SourbyCraft feature patches, both the
  Minecraft ones (`minecraft-patches/features`) and the Paper ones (`paper-patches/features`),
  which are integration points into the engine. The file patches in
  `minecraft-patches/sources` and `paper-patches/files` are excluded on purpose: since the
  migration off Canvas they are the inherited engine baseline (region threading included),
  the code the integration points reach into, so counting them would measure the wrong thing.
* **Scheduler coupling** — the Folia *internal* region scheduler reached from
  SourbyCraft-owned code and from the integration patches. This is the surface an Aurora
  execution contract would have to cover, and it is the one that matters for the
  migration roadmap's Stage 1. `threadedregions.scheduler` is excluded on purpose: that
  package is the public, Bukkit-facing Folia API, a contract rather than an internal.

Every root below is explicit and must exist. A missing root is an error, never a skip: a
renamed directory has to fail the policy instead of silently shrinking what it measures
(audit 2026-10-14, OBS-7).
"""
import re
from pathlib import Path

# SourbyCraft-owned sources: what would have to change if the engine were replaced.
SOURCE_ROOTS = (
    "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft",
    "sourbyapi/src/main/java/dev/iyanz/sourbycraft",
)

# The engine package the CANVAS_SYMBOL pattern resolves to. It is not scanned -- the engine
# naming its own classes is not coupling -- but it has to exist: if the package moved, the
# pattern would match nothing and the pinned counts would stay green for the wrong reason.
ENGINE_ROOT = "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine"

# Patches that integrate with the engine: every SourbyCraft feature patch, to Minecraft and to
# Paper (AGENTS.md, "Patch application"). minecraft-patches/sources and paper-patches/files
# are the inherited engine baseline (formerly Canvas/Folia); SourbyCraft's own edits to it are
# feature patches, so scanning the feature directories sees all of them.
INTEGRATION_PATCH_ROOTS = (
    "sourbycraft-server/minecraft-patches/features",
    "sourbycraft-server/paper-patches/features",
)

# The former Canvas engine: its internals moved to dev.iyanz.aurora.engine, its plugin API kept
# io.canvasmc. dev.iyanz.aurora's other packages are SourbyCraft's own engine code, not counted.
CANVAS_SYMBOL = re.compile(r"\b(?:io\.canvasmc|dev\.iyanz\.aurora\.engine)\.[A-Za-z0-9_.]+")
COMMENT = re.compile(r"^\s*(\*|//|/\*)")

# Internal region-scheduler types. `threadedregions.scheduler` is the public Folia API
# and is deliberately absent: depending on a published contract is not coupling.
SCHEDULER_SYMBOL = re.compile(
    r"\b(?:io\.papermc\.paper\.threadedregions\.(?!scheduler\b)[A-Za-z0-9_.]+"
    r"|TickRegionScheduler|RegionizedServer|RegionizedWorldData|ThreadedRegionizer"
    r"|TickRegions)\b")
# A patch header names the file being changed, not a call into it.
PATCH_FILE_HEADER = re.compile(r"^\+\+\+ |^\+\+ [ab]/")


def required_dir(root, relative):
    """The directory ``relative`` under ``root``. Missing is an error, not a skip."""
    base = Path(root) / relative
    if not base.is_dir():
        raise FileNotFoundError(
            f"policy root is missing: {base} -- if the layout changed, update the roots in "
            f"scripts/independence_policy.py rather than letting the measure shrink silently")
    return base


def source_dirs(root):
    """The SourbyCraft-owned source roots, after checking the engine package is where expected."""
    required_dir(root, ENGINE_ROOT)
    return [required_dir(root, source_root) for source_root in SOURCE_ROOTS]


def patch_dirs(root):
    return [required_dir(root, patch_root) for patch_root in INTEGRATION_PATCH_ROOTS]


def _source_sites(root, pattern):
    sites = []
    for base in source_dirs(root):
        for path in sorted(base.rglob("*.java")):
            for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
                if COMMENT.match(line):
                    continue                      # Javadoc naming a type is not coupling.
                for match in pattern.finditer(line):
                    sites.append({"file": str(path.relative_to(root)), "line": number,
                                  "symbol": match.group(0)})
    return sites


def _patches(root):
    for base in patch_dirs(root):
        for patch in sorted(base.rglob("*.patch")):
            yield str(patch.relative_to(root)), patch.read_text(errors="replace").splitlines()


def canvas_source_sites(root):
    """Every engine (`dev.iyanz.aurora.engine`, `io.canvasmc`) reference in SourbyCraft-owned code, excluding comments."""
    return _source_sites(root, CANVAS_SYMBOL)


def canvas_patch_symbols(root):
    """Engine symbols the engine-integration patches name, as a sorted list."""
    symbols = set()
    for _, lines in _patches(root):
        for line in lines:
            if not line.startswith("+") or line.startswith("+++"):
                continue                          # Only what SourbyCraft adds.
            added = line[1:]
            if COMMENT.match(added):
                continue                          # A comment naming what was removed is not a call.
            symbols.update(CANVAS_SYMBOL.findall(added))
    return sorted(symbols)


def scheduler_sites(root):
    """Every internal region-scheduler reference in SourbyCraft-owned code."""
    return _source_sites(root, SCHEDULER_SYMBOL)


def scheduler_patch_sites(root):
    """Internal region-scheduler calls the engine-integration patches add.

    A patch that *edits* `TickRegionScheduler.java` names it in a file header; that is
    the patch's target, not a dependency, so headers are skipped and only added code
    counts. ``patch`` is the repository-relative path, because the Minecraft and Paper
    feature series number their patches independently.
    """
    sites = []
    for patch, lines in _patches(root):
        for line in lines:
            if not line.startswith("+") or PATCH_FILE_HEADER.match(line):
                continue
            added = line[1:]
            if COMMENT.match(added):
                continue
            for match in SCHEDULER_SYMBOL.finditer(added):
                sites.append({"patch": patch, "symbol": match.group(0)})
    return sites
