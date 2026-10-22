#!/usr/bin/env python3
"""Rules a downstream patch must obey, checked against the patch files themselves.

The patch files are the source of truth; the materialized sources under
`src/minecraft/java` are regenerated from them. Checking the patches catches a rule
break at the point it is introduced and needs no build.

Which files are read
--------------------
Every `*.patch` under the three patch trees below is a SourbyCraft change to Minecraft or
Paper, and every rule in this module reads all of them:

    sourbycraft-server/minecraft-patches     (features/, parked/, todo/, resources/, ...)
    sourbycraft-server/paper-patches         (features/)
    sourbyapi/paper-patches                  (features/)   <- Paper API tree

The inherited engine baseline — `minecraft-patches/sources`, `paper-patches/files` and
`sourbyapi/paper-patches/files` (AGENTS.md, "Patch application") — is excluded; it is not a
SourbyCraft change and `scripts/baseline_policy.py` polices it against
`build-data/engine-baseline.sha256` instead.

The roots are the patch *trees*, not a list of leaf directories, so a patch cannot escape the
rules by sitting in a directory nobody thought to list. That is the audit-2026-10-14 OBS-7
failure mode, which was a root tuple that silently skipped what it did not know about. A tree
that is missing entirely is reported by `missing_trees()` and fails `main()` rather than
shrinking the audit quietly.

Consequences of walking the tree, and why they are deliberate:

* `parked/` is scanned. Its patches are held out of the build by owner decision
  (`parked/README.md`), so they ship nothing today — but unparking is a file move back into
  `features/`, not a re-review, and the rules should report a break where it was written
  rather than at the unpark that finally ships it.
* `todo/` (an unapplied Java fragment today) and `resources/` (an empty scratch directory) are
  covered by the same walk. Neither ships, and neither is *required* to exist: git cannot
  track an empty directory, so demanding them would fail a clean checkout. Walking the tree
  means a `*.patch` dropped in either cannot sit outside the rules, which is the part that
  matters.
* A fourth location added later is scanned without editing this file.
"""
import argparse
import re
import sys
from pathlib import Path

# The three patch trees that carry SourbyCraft's own changes. Every `*.patch` under one of
# them is read except under the baseline directories below.
PATCH_TREES = (
    "sourbycraft-server/minecraft-patches",
    "sourbycraft-server/paper-patches",
    "sourbyapi/paper-patches",
)

# The engine baseline inherited from Canvas/Folia when Paper became the only upstream. These
# are base patches, not SourbyCraft changes; `scripts/baseline_policy.py` owns them.
BASELINE_DIRS = (
    "sourbycraft-server/minecraft-patches/sources",
    "sourbycraft-server/paper-patches/files",
    "sourbyapi/paper-patches/files",
)

# Classes whose single instance is reached by more than one region thread. ServerLevel is
# subdivided into many regions by ServerLevel.regioniser and every region thread calls
# level.tick on the same object; MinecraftServer is process-wide. A reusable buffer held
# here is shared mutable state, and per-region state belongs in RegionizedWorldData.
REGION_SHARED_FILES = (
    "net/minecraft/server/level/ServerLevel.java",
    "net/minecraft/world/level/Level.java",
    "net/minecraft/server/MinecraftServer.java",
)

# Types that carry reusable mutable state. Immutable holders and scalars are fine.
MUTABLE_CONTAINER = re.compile(
    r"\b("
    r"List|Set|Map|Collection|Queue|Deque|Iterator|StringBuilder|"
    r"ArrayList|LinkedList|HashMap|HashSet|LinkedHashMap|LinkedHashSet|TreeMap|TreeSet|"
    r"ArrayDeque|ConcurrentHashMap|CopyOnWriteArrayList|"
    r"Object\w*ArrayList|Object\w*OpenHashSet|Reference\w*OpenHashSet|Long\w*OpenHashSet|"
    r"Int\w*OpenHashSet|\w*Object\w*HashMap|\w*Long\w*HashMap|"
    r"MutableBlockPos"
    r")\b")

# A class-level field: four spaces of indentation and an access or storage modifier.
# A local inside a method body sits at eight spaces or deeper in this codebase.
FIELD = re.compile(r"^\+ {4}(?:@\w+\s+)*(private|protected|public|static|final)\b[^;=]*[\s>\]]\w+\s*(=|;)")

# An array field declared the same way.
ARRAY_FIELD = re.compile(r"^\+ {4}(?:@\w+\s+)*(?:private|protected|public|static|final)\b[^;=]*\[\s*\]\s*\w+\s*(=|;)")


def patch_files(root):
    """Every SourbyCraft patch under the three patch trees, sorted, engine baseline excluded."""
    root = Path(root)
    excluded = [root / directory for directory in BASELINE_DIRS]
    for tree in PATCH_TREES:
        base = root / tree
        if not base.is_dir():
            continue  # reported by missing_trees(); never silently shrinks the audit
        for path in sorted(base.rglob("*.patch")):
            if any(directory == path or directory in path.parents for directory in excluded):
                continue
            yield path


def missing_trees(root):
    """Patch trees that do not exist. Non-empty means the audit just shrank; do not ignore."""
    return [tree for tree in PATCH_TREES if not (Path(root) / tree).is_dir()]


def added_fields_on(patch, targets):
    """Field declarations a patch adds to any of ``targets``.

    Returns (target file, added line, is_array) triples.
    """
    found = []
    current = None
    for line in patch.read_text(errors="replace").splitlines():
        if line.startswith("+++ b/"):
            current = line[6:].strip()
            continue
        if line.startswith("--- ") or line.startswith("diff --git"):
            continue
        if current not in targets or not line.startswith("+"):
            continue
        array = bool(ARRAY_FIELD.match(line))
        if array or FIELD.match(line):
            found.append((current, line[1:].rstrip(), array))
    return found


def shared_mutable_fields(root):
    """Every reusable mutable field a patch adds to a region-shared class."""
    violations = []
    for patch in patch_files(root):
        for target, line, array in added_fields_on(patch, REGION_SHARED_FILES):
            # An array field is reusable mutable state whatever its element type, so it
            # qualifies on shape alone rather than on a type name.
            if array or MUTABLE_CONTAINER.search(line):
                violations.append({"patch": patch.name, "file": target, "declaration": line.strip()})
    return violations


# A downstream patch that changes an upstream default changes what an operator gets
# without them asking. That is allowed — PRD section 5 forbids changing settings behind
# an operator's back at runtime, not shipping a different default — but it has to be
# deliberate and visible. These patches are keyed by target file, one per file, so a
# default change cannot be split into its own patch; pinning the set here is what makes
# it reviewable instead.
# The access modifier is required, not optional: a Java local variable cannot have one,
# so demanding it separates a field default from a local initialiser inside a method body
# that a patch happens to rewrite.
FIELD_DEFAULT = re.compile(
    r"^([+-])\s*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?"
    r"[\w.<>\[\]]+\s+(\w+)\s*=\s*(.+?);\s*(?://.*)?$")


def upstream_default_changes(root):
    """Every upstream field default a patch redefines.

    Keyed by "<patch file>:<field>" rather than by field alone: two patches may redefine
    a same-named field in different classes, and collapsing them would hide one of them.
    """
    changes = {}
    for patch in patch_files(root):
        removed = {}
        for line in patch.read_text(errors="replace").splitlines():
            match = FIELD_DEFAULT.match(line)
            if not match:
                continue
            sign, name, value = match.groups()
            if sign == "-":
                removed[name] = value.strip()
            elif name in removed and removed[name] != value.strip():
                changes[f"{patch.name}:{name}"] = (removed[name], value.strip())
    return changes


# The engine configuration classes came into the tree from Canvas, together with SourbyCraft's
# deliberate default changes to them. There is no upstream patch to diff against any more, so
# those defaults are read straight from the owned source and pinned by the test instead.
SOURCE_FIELD_DEFAULT = re.compile(
    r"^\s*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?"
    r"[\w.<>\[\]]+\s+(\w+)\s*=\s*(.+?);\s*(?://.*)?$")


def source_field_defaults(root, relative):
    """Field name -> initialiser for every field default declared in one source file."""
    defaults = {}
    for line in (Path(root) / relative).read_text(errors="replace").splitlines():
        match = SOURCE_FIELD_DEFAULT.match(line)
        if match:
            defaults.setdefault(match.group(1), match.group(2).strip())
    return defaults


# Patch 0012 replaces `boundingBox.move(-blockX, -blockY, -blockZ)` with an inline AABB
# built from scalars. Twice the translation went missing — once as a dangling reference to
# the deleted variable, once as bounds left in world space — and it took three follow-up
# commits to settle. The Java test IsInWallBoundsTest pins the arithmetic *contract*; it
# cannot see the patch itself drifting, which is what this covers.
TO_COLLIDE = re.compile(r"toCollide\s*=\s*new\s+AABB\s*\((.*?)\)\s*;", re.DOTALL)


def translated_bounds(root):
    """The inline block-local AABB expressions any patch builds for `toCollide`.

    Returns a list of {patch, expression, translated_axes}. Patch lines keep their leading
    '+' and diff context, so the expression is normalised before inspection.
    """
    results = []
    for patch in patch_files(root):
        text = patch.read_text(errors="replace")
        added = "\n".join(line[1:] for line in text.splitlines() if line.startswith("+"))
        for match in TO_COLLIDE.finditer(added):
            expression = " ".join(match.group(1).split())
            results.append({
                "patch": patch.name,
                "expression": expression,
                "translated_axes": {axis: expression.count(f"- block{axis.upper()}")
                                    for axis in ("x", "y", "z")},
            })
    return results


# DEV-H P0 "no adaptive compression/config tuning" (docs/DEVELOPMENT-TASKS.md, AURORA.md §16):
# operator-visible compression settings stay operator-owned. No SourbyCraft patch or owned
# source may touch the protocol compression setup — the threshold is `server.properties
# network-compression-threshold`, applied by `Connection.setupCompression` through
# `CompressionEncoder`/`CompressionDecoder` and announced by `ClientboundLoginCompressionPacket`.
# Identifiers, not the word "compression": AWF's zstd and comments about wire bytes are not policy.
NETWORK_COMPRESSION = re.compile(
    r"\b(?:CompressionEncoder|CompressionDecoder|setupCompression|compressionThreshold|"
    r"ClientboundLoginCompressionPacket)\b|network-compression-threshold")
OWNED_SOURCE = "sourbycraft-server/src/main/java"


def network_compression_references(root):
    """Every added patch line and every owned-source line that reaches the protocol compression setup.

    Returns (location, line) pairs; location is "<patch>" or "<source path>:<line number>".
    """
    found = []
    for patch in patch_files(root):
        for line in patch.read_text(errors="replace").splitlines():
            if line.startswith("+") and not line.startswith("+++") and NETWORK_COMPRESSION.search(line):
                found.append((patch.name, line[1:].rstrip()))
    source = Path(root) / OWNED_SOURCE
    if source.is_dir():
        for path in sorted(source.rglob("*.java")):
            for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
                if NETWORK_COMPRESSION.search(line):
                    found.append((f"{path.relative_to(root).as_posix()}:{number}", line.rstrip()))
    return found


def violations(root):
    """Every rule break as one printable line; empty when the patch set is clean."""
    problems = [f"patch tree missing: {tree} -- the audit would silently shrink; restore it or "
                f"update PATCH_TREES in scripts/patch_policy.py" for tree in missing_trees(root)]
    for location, line in network_compression_references(root):
        problems.append(f"{location}: touches the protocol compression setup ({line.strip()}); "
                        f"compression settings are operator-owned (DEV-H P0 no adaptive compression)")
    for found in shared_mutable_fields(root):
        problems.append(f"{found['patch']}: reusable mutable state on a region-shared class: "
                        f"{found['file']}: {found['declaration']} (per-region state belongs in "
                        f"RegionizedWorldData)")
    for key, (before, after) in sorted(upstream_default_changes(root).items()):
        problems.append(f"{key}: upstream default changed {before!r} -> {after!r}; record it and "
                        f"the reason in test_patch_policy.APPROVED, or drop the change")
    for found in translated_bounds(root):
        if found["translated_axes"] != {"x": 2, "y": 2, "z": 2}:
            problems.append(f"{found['patch']}: inline toCollide box is not translated on every "
                            f"axis: {found['translated_axes']} in {found['expression']}")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = parser.parse_args(argv)
    problems = violations(args.root)
    for problem in problems:
        print(f"patch_policy: {problem}", file=sys.stderr)
    if problems:
        print(f"patch_policy: {len(problems)} violation(s)", file=sys.stderr)
        return 1
    print(f"patch_policy: OK ({len(list(patch_files(args.root)))} patches under "
          f"{len(PATCH_TREES)} patch trees)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

