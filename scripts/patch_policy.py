#!/usr/bin/env python3
"""Rules a downstream patch must obey, checked against the patch files themselves.

The patch files are the source of truth; the materialized sources under
`src/minecraft/java` are regenerated from them. Checking the patches catches a rule
break at the point it is introduced and needs no build.
"""
import re
from pathlib import Path

PATCH_ROOTS = ("sourbycraft-server/minecraft-patches",
               "sourbycraft-server/paper-patches",
               "sourbycraft-server/canvas-patches")

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
    for directory in PATCH_ROOTS:
        base = Path(root) / directory
        if base.is_dir():
            yield from sorted(base.rglob("*.patch"))


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
