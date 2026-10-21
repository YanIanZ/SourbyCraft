#!/usr/bin/env python3
"""Every guard inherited from Canvas/Folia must carry a SourbyCraft decision.

Owner direction (2026-10-21): SourbyCraft must not keep depending on guards somebody else wrote.
A "guard" here is region-threading code that disables, refuses, reroutes or degrades behaviour:
`if (true) { return/throw ... }` blocks, `if (false && ...)` that switch a Paper feature off,
`throw new UnsupportedOperationException(...)` stubs, marked constant early returns, the
`@Unsupported` (`dev.iyanz.sourbycraft.api.Unsupported`) API annotations, and the ownership-check families
(`TickThread.ensureTickThread`, `TickGuard.guard`, ...). The ledger that explains each one is
`docs/architecture/inherited-guards.md`; this script keeps that ledger honest:

1. `build-data/inherited-guards.json` records every detected guard with a decision
   (KEEP-OWNED, REPLACE, REMOVE or UNDECIDED-with-a-check) and every ownership-check family with
   its marked-site count.
2. The check scans the code and fails when a guard exists that the manifest does not know, when a
   manifest entry no longer exists in the code, when an entry has no valid decision, or when a
   family's site count drifted. `--write-manifest` regenerates the manifest, keeping the decisions
   of entries that still match; entries that vanished are refused unless `--allow-removals` is
   given, and a manifest that does not parse is never overwritten (same spirit as
   `scripts/baseline_policy.py`: a deliberate change is a reviewed manifest diff).

What is scanned, and in which form:

* Minecraft and Paper files touched by the engine baseline patches
  (`sourbycraft-server/minecraft-patches/sources`, `sourbycraft-server/paper-patches/files`,
  `sourbyapi/paper-patches/files`) **and** by the SourbyCraft feature patches
  (`*/features/*.patch`), plus the engine sources SourbyCraft owns outright
  (`sourbycraft-server/src/main/java/dev/iyanz/aurora/engine`). A guard a feature patch adds is
  therefore recorded with origin `SourbyCraft`; `scripts/patch_policy.py` does not look for guard
  shapes, so nothing else would.
* When the materialized sources exist (`applyAllPatches` ran) they are scanned directly. When they
  do not (the pull-request job has no private toolchain, see `scripts/pr_checks.py`) the post-image
  of every patch hunk is scanned instead, one hunk at a time, so a marker in one hunk cannot vouch
  for a line in another. Only lines a patch *adds* are guard candidates in that mode (context lines
  are Paper's and only serve as surroundings), which is also why both modes find the same set;
  `scripts/test_inherited_guard_policy.py` checks that whenever both inputs are present.

How a guard is identified so that line drift does not break the manifest: the key is the file, the
name of the enclosing method when its header is in view (`method`), the two nearest preceding
non-blank lines (`site`; for an annotation, the declaration it annotates), the exact guard line
(whitespace-stripped, `anchor`) and which occurrence of that identical anchor it is there
(`nth`). Lines SourbyCraft inserted above an inherited guard are skipped when computing method and
site, so applying a feature patch does not re-key the guard. Line numbers are recorded for readers
only and never compared. An entry whose method/site no longer matches is a removal plus a new
entry, never a silent remap; entries written before `site` existed are migrated once by
file + anchor + occurrence.

Lines are matched on a code view with string-literal contents and `//` comments removed, so a
commented-out throw is not a guard and a message text never fakes a condition. A condition counts
as constant only when it really is: `true`, `false`, `((true))`, `false && ...`, `true || ...`;
`true && enabled` is not.

Detectors (a line matches at most one entry; a throw directly under a matched `if` belongs to it):

* A: a constant `if` whose surroundings (3 lines before, 2 after, or the enclosing `// X start`
  block within 8 lines) carry a `// Folia`, `// Canvas` or `// SourbyCraft` marker or a region
  throw, unless the `if` line's own comment credits another project (`// Paper - ...`).
* B: `throw new UnsupportedOperationException(...)` with a region message (`Unsupported in region
  threading`, `Must use teleportAsync ...`, `Use tryReference()`, `Use region threading hooks`,
  `Not on any region`), or a marker on the line, or inside a `// X start` block.
* C: in the engine-owned packages (`io/papermc/paper/threadedregions/`,
  `dev/iyanz/aurora/engine/`) every constant `if` and every `UnsupportedOperationException` with
  a message, marker or not.
* D: `@Unsupported` (`dev.iyanz.sourbycraft.api.Unsupported`) annotations in the API.
* E: a bare constant early return (`return;`, `return false/true/null/0;`, `return X.FAIL;`) that
  is the first statement of its block and carries a marker or sits inside a `// X start` block.

Families are counted, not enumerated: a call site counts when its line carries a `// Folia` or
`// Canvas` marker, so Paper's own `TickThread` checks (inherited from Paper, not from the region
engine) stay out of the count. `TickGuard` calls are all engine-owned and count unmarked. Families
are counted over the Minecraft/Paper files only, not the engine sources.

Not detected (listed in the ledger instead): plain `throw new UnsupportedOperationException()`
without a message (collection-view stubs), and whole features Canvas rewrote rather than guarded
(`/datapack`, waypoints).

Run from the repository root:

    python3 scripts/inherited_guard_policy.py                    # check, exit 1 on any problem
    python3 scripts/inherited_guard_policy.py --write-manifest   # regenerate, keeping decisions
    python3 scripts/inherited_guard_policy.py --write-manifest --allow-removals
    python3 scripts/inherited_guard_policy.py --markdown         # the ledger appendix table
"""
import argparse
import json
import re
import sys
from collections import Counter, OrderedDict
from pathlib import Path

MANIFEST = "build-data/inherited-guards.json"

# (patch root, prefix inside the patch root to drop, materialized root)
BASELINE_ROOTS = (
    ("sourbycraft-server/minecraft-patches/sources", "", "sourbycraft-server/src/minecraft/java"),
    ("sourbycraft-server/paper-patches/files", "src/main/java/", "paper-server/src/main/java"),
    ("sourbyapi/paper-patches/files", "src/main/java/", "paper-api/src/main/java"),
)
# (feature patch root, prefix after `b/` to drop, materialized root); may be absent for the API.
FEATURE_ROOTS = (
    ("sourbycraft-server/minecraft-patches/features", "", "sourbycraft-server/src/minecraft/java"),
    ("sourbycraft-server/paper-patches/features", "src/main/java/", "paper-server/src/main/java"),
    ("sourbyapi/paper-patches/features", "src/main/java/", "paper-api/src/main/java"),
)
# SourbyCraft-owned engine sources, scanned as plain files in both modes.
ENGINE_SOURCE_ROOT = "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine"
ENGINE_DIRS = ("io/papermc/paper/threadedregions/", "dev/iyanz/aurora/engine/")

DECISIONS = ("KEEP-OWNED", "REPLACE", "REMOVE", "UNDECIDED")
KINDS = ("FEATURE-DISABLED", "API-REFUSED", "OWNERSHIP-CHECK", "DEGRADED", "REROUTED")
ORIGINS = ("Folia", "Canvas", "SourbyCraft")

MARKER = re.compile(r"//\s*(Folia|Canvas|SourbyCraft)\b")
INHERITED_MARKER = re.compile(r"//\s*(Folia|Canvas)\b")
BLOCK_START = re.compile(r"//\s*(Folia|Canvas|SourbyCraft)\s+start\b")
BLOCK_END = re.compile(r"//\s*(Folia|Canvas|SourbyCraft)\s+end\b")
# An `if` whose own comment credits another project is that project's guard, even when a Folia
# marker happens to sit within the window (MinecraftServer `if (true) { // CraftBukkit`).
OTHER_ATTRIBUTION = re.compile(r"//\s*(Paper|CraftBukkit|Spigot|Moonrise|Pufferfish|Purpur)\b")
IF_HEAD = re.compile(r"^\s*(?:\}\s*)?(?:else\s+)?if\s*\(")
# SourbyCraft's owned refusal messages (feature patches 0061 / Paper 0019) replace the inherited
# "Unsupported in region threading" text; the guard still refuses, so it stays in the ledger.
OWNED_REFUSAL = r"throw\s+dev\.iyanz\.sourbycraft\.guards\.RegionRefusal\.\w+\.error\s*\("
THROW = re.compile(r"\bthrow\s+new\s+UnsupportedOperationException\s*\(|\b" + OWNED_REFUSAL)
THROW_WITH_MESSAGE = re.compile(r'\bthrow\s+new\s+UnsupportedOperationException\s*\(\s*"')
REGION_THROW = re.compile(
    r'throw\s+new\s+UnsupportedOperationException\s*\(\s*"(?:Unsupported in region threadin|'
    r"Must use teleportAsync|Use tryReference\(\)|Use region threading hooks|Not on any region)"
    r"|\b" + OWNED_REFUSAL)
EARLY_RETURN = re.compile(
    r"^\s*return\s*(?:(?:false|true|null|0|-1|[A-Z]\w*(?:\.\w+)*\.[A-Z_]+|\w+\.empty\(\))\s*)?;\s*$")
ANNOTATION = re.compile(r"@(?:dev\.iyanz\.sourbycraft\.api\.)?Unsupported\b")
SITE_LINES = 2                      # preceding non-blank lines that make up a guard's `site`
                                    # (3 would exceed a patch hunk's context and split the modes)
SOURBY_START = re.compile(r"//\s*SourbyCraft\s+start\b")
SOURBY_END = re.compile(r"//\s*SourbyCraft\s+end\b")
SOURBY_MARK = re.compile(r"//\s*SourbyCraft\b")
SOURBY_NOTE = re.compile(r"//.*\bSourbyCraft\b")      # SourbyCraft named anywhere in the line's comment
# A method/constructor header line: not a control statement, has a parameter list, opens a body.
DECLARATION = re.compile(
    r"^(?!(?:if|else|for|while|switch|catch|try|synchronized|do|return|new|throw)\b)"
    r"[\w<>\[\],.?@$ ]*?\b\w+\s*\(.*\)\s*(?:throws[\w., ]+)?\{$")
# Engine-root throws count only when the message reads like a refusal, not a parser type check.
ENGINE_REFUSAL = re.compile(r'UnsupportedOperationException\s*\(\s*"(?i:unsupported|not supported|must use|use \w+\(\))')
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
CHAR = re.compile(r"'(?:\\.|[^'\\])'")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/")

# id -> (pattern, marked_only, description)
FAMILIES = OrderedDict((
    ("F-TICKTHREAD-ENSURE", (re.compile(r"TickThread\.ensureTickThread\("), True,
                             "TickThread.ensureTickThread: throws IllegalStateException off the owning region thread")),
    ("F-TICKTHREAD-IS", (re.compile(r"TickThread\.isTickThreadFor\("), True,
                         "TickThread.isTickThreadFor: ownership test that picks a region-safe branch")),
    ("F-GLOBAL-TICK", (re.compile(r"RegionizedServer\.ensureGlobalTickThread\("), True,
                       "RegionizedServer.ensureGlobalTickThread: global-tick-only operations")),
    ("F-TICKGUARD", (re.compile(r"TickGuard\.(guard|ensureGlobalOrStartup|hardThrowIfStarted)\("), False,
                     "TickGuard: Canvas's configurable guards (region-scheduler.guard-severity)")),
    ("F-ASYNCCATCHER", (re.compile(r"AsyncCatcher\.catchOp\("), True,
                        "AsyncCatcher.catchOp re-marked by the region engine")),
))


class GuardError(Exception):
    """The check cannot run at all (a root or the manifest is missing or unreadable)."""


# --------------------------------------------------------------------------- inputs

def _mapped(relative, drop, source_root):
    if drop:
        if not relative.startswith(drop):
            return None               # src/test, resources, build scripts: not server code
        relative = relative[len(drop):]
    if not relative.endswith(".java"):
        return None
    return f"{source_root}/{relative}"


def baseline_patches(root):
    """[(patch path, materialized relative path)] for every baseline patch, sorted."""
    root = Path(root)
    found = []
    for patch_root, drop, source_root in BASELINE_ROOTS:
        base = root / patch_root
        if not base.is_dir():
            raise GuardError(f"baseline root missing: {patch_root}")
        patches = sorted(p for p in base.rglob("*.patch") if p.is_file())
        if not patches:
            raise GuardError(f"baseline root empty: {patch_root}")
        for patch in patches:
            relative = _mapped(patch.relative_to(base).as_posix()[: -len(".patch")], drop, source_root)
            if relative:
                found.append((patch, relative))
    return found


def feature_patches(root):
    """[(patch path, [materialized relative paths it touches])] for every feature patch, sorted.

    A feature root may be absent (the API has none today); a present one may be empty.
    """
    root = Path(root)
    found = []
    for patch_root, drop, source_root in FEATURE_ROOTS:
        base = root / patch_root
        if not base.is_dir():
            continue
        for patch in sorted(p for p in base.glob("*.patch") if p.is_file()):
            files = []
            for line in patch.read_text(errors="replace").splitlines():
                if line.startswith("+++ b/"):
                    relative = _mapped(line[len("+++ b/"):].strip(), drop, source_root)
                    if relative and relative not in files:
                        files.append(relative)
            found.append((patch, files))
    return found


def engine_sources(root):
    base = Path(root) / ENGINE_SOURCE_ROOT
    if not base.is_dir():
        raise GuardError(f"engine source root missing: {ENGINE_SOURCE_ROOT}")
    return sorted(p.relative_to(root).as_posix() for p in base.rglob("*.java") if p.is_file())


def materialized_available(root):
    root = Path(root)
    return all((root / source_root).is_dir() for _, _, source_root in BASELINE_ROOTS)


def patch_hunks(text, want_file=None, pre_image=False):
    """Hunks of a diff as [(label, [(flag, line)])].

    By default the post-image (context + added lines, ``flag`` = added). With ``pre_image`` the
    pre-image instead (context + removed lines, ``flag`` = removed), which is what a feature patch
    takes away from the baseline. ``want_file`` restricts a multi-file (feature) patch to the hunks
    of that `+++ b/` path. Hunk boundaries are kept: nothing in one hunk is adjacent to anything in
    another.
    """
    hunks = []
    current = None
    in_wanted = want_file is None
    for line in text.splitlines():
        if line.startswith("+++ "):
            in_wanted = want_file is None or line[len("+++ b/"):].strip() == want_file
            current = None
            continue
        if line.startswith("--- "):
            continue
        if line.startswith("@@"):
            current = None
            if in_wanted:
                current = (line, [])
                hunks.append(current)
            continue
        if current is None:
            continue
        if line.startswith("+") and not pre_image:
            current[1].append((True, line[1:]))
        elif line.startswith("-") and pre_image:
            current[1].append((True, line[1:]))
        elif line.startswith(" "):
            current[1].append((False, line[1:]))
        elif line == "":
            current[1].append((False, ""))
        # The other side's lines and "\ No newline" notes carry nothing for this view.
    return hunks


def preceding_site(lines, i, own):
    """The SITE_LINES nearest preceding non-blank lines of ``lines[i]``, joined. For a guard that is
    not SourbyCraft's own, lines SourbyCraft inserted (marked, or inside a `// SourbyCraft start..end`
    block) are skipped, so the site is the same whether or not a feature patch was applied above."""
    parts = []
    skipping = False
    for j in range(i - 1, -1, -1):
        text = lines[j].strip()
        if not text:
            continue
        if not own:
            if SOURBY_END.search(text):
                skipping = True
                continue
            if skipping:
                if SOURBY_START.search(text):
                    skipping = False
                continue
            if SOURBY_MARK.search(text):
                continue
        parts.append(text[:160])
        if len(parts) == SITE_LINES:
            break
    return " | ".join(parts)


def removed_by_features(patch, want):
    """Counter of (site, anchor) for every line a feature patch removes from ``want``'s pre-image:
    a baseline guard a feature deletes must disappear from the patch-mode scan as it does from the
    materialized one."""
    removed = Counter()
    for _, rows in patch_hunks(patch.read_text(errors="replace"), want, pre_image=True):
        lines = [line for _, line in rows]
        for i, (flag, line) in enumerate(rows):
            if flag and line.strip():
                removed[(preceding_site(lines, i, False), line.strip())] += 1
    return removed


def segments_for(root):
    """{materialized relative path: [segment]} and the mode used.

    A segment is (where, [(candidate, line)]): ``candidate`` says whether the line may itself be a
    guard (materialized: every line; patch mode: added lines only). ``where`` names the source of
    the segment for messages.
    """
    root = Path(root)
    mode = "materialized" if materialized_available(root) else "patch"
    files = OrderedDict()
    for patch, relative in baseline_patches(root):
        files.setdefault(relative, []).append((patch, None))
    for patch, touched in feature_patches(root):
        for relative in touched:
            files.setdefault(relative, []).append((patch, relative))
    texts = OrderedDict()
    removed = {}
    for relative in sorted(files):
        if mode == "materialized":
            path = root / relative
            if not path.is_file():
                # A patch for a file Paper no longer ships would fail applyAllPatches; treat the
                # same here rather than silently scanning less.
                raise GuardError(f"materialized file missing for {files[relative][0][0].relative_to(root)}: {relative}")
            lines = path.read_text(errors="replace").splitlines()
            texts[relative] = [("", [(True, line) for line in lines])]
        else:
            segments = []
            gone = Counter()
            for patch, wanted in files[relative]:
                want = None
                if wanted is not None:
                    for patch_root, drop, source_root in FEATURE_ROOTS:
                        prefix = source_root + "/"
                        if str(patch).startswith(str(root / patch_root)) and wanted.startswith(prefix):
                            want = drop + wanted[len(prefix):]
                    gone.update(removed_by_features(patch, want))
                for number, (header, lines) in enumerate(patch_hunks(patch.read_text(errors="replace"), want), 1):
                    segments.append((f"{patch.relative_to(root)} hunk {number}", lines))
            texts[relative] = segments
            if gone:
                removed[relative] = gone
    for relative in engine_sources(root):
        lines = (root / relative).read_text(errors="replace").splitlines()
        texts[relative] = [("", [(True, line) for line in lines])]
    return texts, mode, removed


# --------------------------------------------------------------------------- detection

def code_of(line):
    """The code part of one line: string/char literal contents, inline block comments and `//`
    tails removed. Multi-line block comments need `code_lines`, which tracks them across lines."""
    return code_lines([line])[0]


def code_lines(lines):
    """Code view of a sequence of lines: string/char literal contents blanked, `/* ... */` comments
    removed also when they span lines (a guard inside a commented-out method is not code), `//`
    tails cut. Javadoc continuation lines (`* ...`) inside a block comment are part of it."""
    codes = []
    in_block = False
    for line in lines:
        code = STRING.sub('""', line)
        code = CHAR.sub("' '", code)
        out = []
        pos = 0
        while pos < len(code):
            if in_block:
                end = code.find("*/", pos)
                if end < 0:
                    pos = len(code)
                    break
                in_block = False
                pos = end + 2
                continue
            start = code.find("/*", pos)
            cut = code.find("//", pos)
            if cut >= 0 and (start < 0 or cut < start):
                out.append(code[pos:cut])
                pos = len(code)
                break
            if start < 0:
                out.append(code[pos:])
                pos = len(code)
                break
            out.append(code[pos:start])
            in_block = True
            pos = start + 2
        codes.append("".join(out).rstrip())
    return codes


def constant_condition(code):
    """'true' / 'false' when the `if` condition is constant, else None."""
    head = IF_HEAD.search(code)
    if not head:
        return None
    rest = code[head.end() - 1:]
    depth = 0
    for index, char in enumerate(rest):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                condition = rest[1:index].strip()
                break
    else:
        # The condition continues on the next line; `false && ...` / `true || ...` still decide it.
        condition = rest[1:].strip()
    while condition.startswith("(") and condition.endswith(")"):
        inner, depth = condition[1:-1], 0
        balanced = True
        for char in inner:
            depth += (char == "(") - (char == ")")
            if depth < 0:
                balanced = False
                break
        if not balanced or depth != 0:
            break
        condition = inner.strip()
    if condition in ("true", "false"):
        return condition
    if re.match(r"^false\s*&&", condition):
        return "false"
    if re.match(r"^true\s*\|\|", condition):
        return "true"
    return None


def _indent(line):
    return len(line) - len(line.lstrip())


def detect_guards(relative, segments, removed=None):
    """Guard sites in one file as dicts {file, where, line, anchor, site, nth, detector, origin}.

    ``removed`` (patch mode) counts the (site, anchor) pairs feature patches delete from this file;
    a baseline guard matching one is dropped, as the materialized scan would never see it.
    """
    engine = any(d in relative for d in ENGINE_DIRS)
    removed = Counter(removed or {})
    found = []
    for where, rows in segments:
        lines = [line for _, line in rows]
        codes = code_lines(lines)
        candidates = [flag for flag, _ in rows]
        n = len(lines)

        def window(i, before, after):
            return range(max(0, i - before), min(n, i + after + 1))

        def in_block(i, reach=8):
            for j in range(i - 1, max(-1, i - reach - 1), -1):
                if BLOCK_END.search(lines[j]):
                    return False
                if BLOCK_START.search(lines[j]):
                    return True
            return False

        def marker_near(i):
            return (any(MARKER.search(lines[j]) for j in window(i, 3, 2))
                    or any(REGION_THROW.search(lines[j]) for j in window(i, 0, 2))
                    or in_block(i))

        def origin_of(i):
            """(project, certain): the project whose marker owns the guard. Its own line first, then
            the enclosing `// X start` block above (closed blocks in between are skipped), then an
            `// X end` just below, then the nearest plain marker in the window. Without any marker
            the origin is a guess (Folia, whose phrasing the region throws use) and `certain` is
            False, so a hand-recorded origin in the manifest wins over it."""
            m = MARKER.search(lines[i])
            if m:
                return m.group(1), True
            skipping = None
            closed = set()                    # indexes inside closed blocks above
            for j in range(i - 1, max(-1, i - 13), -1):
                end = BLOCK_END.search(lines[j])
                if end and skipping is None:
                    skipping = end.group(1)
                    closed.add(j)
                    continue
                start = BLOCK_START.search(lines[j])
                if skipping is not None:
                    closed.add(j)
                    if start and start.group(1) == skipping:
                        skipping = None
                    continue
                if start:
                    return start.group(1), True
            for j in range(i + 1, min(n, i + 4)):
                end = BLOCK_END.search(lines[j])
                if end:
                    return end.group(1), True
            for j in list(range(i - 1, max(-1, i - 4), -1)) + list(range(i + 1, min(n, i + 3))):
                if j in closed:
                    continue
                m = MARKER.search(lines[j])
                if m and not BLOCK_END.search(lines[j]) and not BLOCK_START.search(lines[j]):
                    return m.group(1), True
            return "Folia", False

        def site_before(i, own):
            return preceding_site(lines, i, own)

        def site_after(i):
            for j in range(i + 1, n):
                if codes[j].strip() and not codes[j].strip().startswith("@"):
                    return codes[j].strip()[:120]
            return ""

        def method_of(i, own):
            """Name of the nearest enclosing method/constructor header above (within the segment),
            skipping SourbyCraft-inserted lines for a guard that is not SourbyCraft's own; "" when
            the header is outside the segment (then `site` alone tells guards apart)."""
            skipping = False
            for j in range(i - 1, -1, -1):
                text = lines[j].strip()
                if not own:
                    if SOURBY_END.search(text):
                        skipping = True
                        continue
                    if skipping:
                        if SOURBY_START.search(text):
                            skipping = False
                        continue
                    if SOURBY_MARK.search(text):
                        continue
                code = codes[j].strip()
                if code.endswith("{") and DECLARATION.match(code):
                    name = re.match(r"[^(]*?\b(\w+)\s*\(", code)
                    return name.group(1) if name else ""
            return ""

        def method_after(i):
            for j in range(i + 1, n):
                code = codes[j].strip()
                if code and not code.startswith("@"):
                    name = re.match(r"[^(]*?\b(\w+)\s*\(", code)
                    return name.group(1) if name else code.split()[-1].rstrip("{").strip()
            return ""

        def declaration_site(i):
            """True when the nearest preceding code line opens a method/constructor body."""
            for j in range(i - 1, -1, -1):
                code = codes[j].strip()
                if code:
                    return code.endswith("{") and bool(DECLARATION.match(code))
            return False

        taken = set()
        sites = []
        for i in range(n):
            code = codes[i]
            constant = constant_condition(code)
            if constant is not None:
                if OTHER_ATTRIBUTION.search(lines[i]) and not MARKER.search(lines[i]):
                    continue
                if marker_near(i) or engine:
                    # The `if` itself is an entry only where it may be one (a context line of a
                    # feature hunk is not), but the throw/return under it always belongs to it, so
                    # a feature patch that rewrites only the throw does not produce a second entry.
                    if candidates[i]:
                        sites.append((i, "C" if engine and not marker_near(i) else "A"))
                    taken.add(i)
                    for j in range(i + 1, min(n, i + 3)):
                        if THROW.search(codes[j]) or EARLY_RETURN.match(codes[j]):
                            taken.add(j)
                            break
                        if codes[j].strip() not in ("", "{"):
                            break
        for i in range(n):
            if i in taken or not candidates[i] or not THROW.search(codes[i]):
                continue
            if REGION_THROW.search(lines[i]) or MARKER.search(lines[i]) or in_block(i):
                sites.append((i, "B"))
                taken.add(i)
            elif engine and ENGINE_REFUSAL.search(lines[i]):
                sites.append((i, "C"))
                taken.add(i)
        for i in range(n):
            if i in taken or not candidates[i] or not EARLY_RETURN.match(codes[i]):
                continue
            if (MARKER.search(lines[i]) or in_block(i, 3)) and declaration_site(i):
                sites.append((i, "E"))
                taken.add(i)
        for i in range(n):
            if candidates[i] and ANNOTATION.search(lines[i]):
                sites.append((i, "D"))
        sites.sort()
        for i, detector in sites:
            origin, certain = origin_of(i)
            # A line SourbyCraft rewrote in place ("... - SourbyCraft: owned refusal message") keeps
            # its inherited origin but is keyed like SourbyCraft's own code: its neighbours are what
            # the feature patch shows, so a short hunk context and the full file agree.
            own = origin == "SourbyCraft" or bool(SOURBY_NOTE.search(lines[i]))
            if detector == "D":
                site, method = site_after(i), method_after(i)
            else:
                site, method = site_before(i, own), method_of(i, own)
            found.append({"file": relative, "where": where, "line": i + 1 if not where else None,
                          "offset": i + 1, "anchor": lines[i].strip(), "site": site,
                          "method": method, "detector": detector, "origin": origin,
                          "origin_certain": certain})
    if removed:
        # Exact (site, anchor) first; then by anchor alone when that anchor is unique in this file
        # (a feature hunk's short pre-image context cannot always reproduce the baseline site).
        by_anchor = Counter()
        for (_, anchor), count in removed.items():
            by_anchor[anchor] += count
        anchors_here = Counter(g["anchor"] for g in found)
        kept = []
        for g in found:
            key = (g["site"], g["anchor"])
            if removed[key] > 0:
                removed[key] -= 1
                by_anchor[g["anchor"]] -= 1                   # deleted by a feature patch
                continue
            if anchors_here[g["anchor"]] == 1 and by_anchor[g["anchor"]] > 0:
                by_anchor[g["anchor"]] -= 1
                continue
            kept.append(g)
        found = kept
    seen = Counter()
    for g in found:
        seen[(g["site"], g["anchor"])] += 1
        g["nth"] = seen[(g["site"], g["anchor"])]
    return found


def guard_key(entry):
    # `method` is informational: a method header is often outside the patch hunk, so it would make
    # the key depend on the scan mode. `site` (the preceding lines) is present in both.
    return (entry["file"], entry["site"], entry["anchor"], int(entry["nth"]))


def legacy_key(entry):
    """Key of a manifest written before `site` existed: file + anchor + occurrence in the file."""
    return (entry["file"], entry["anchor"], int(entry["nth"]))


def scan(root):
    """(guards, families, mode). guards: list of site dicts; families: {id: count}."""
    texts, mode, removed = segments_for(root)
    guards = []
    families = {family: 0 for family in FAMILIES}
    for relative, segments in texts.items():
        guards.extend(detect_guards(relative, segments, removed.get(relative)))
        if relative.startswith(ENGINE_SOURCE_ROOT):
            continue
        for _, rows in segments:
            codes = code_lines([line for _, line in rows])
            for (candidate, line), code in zip(rows, codes):
                if not candidate:
                    continue
                for family, (pattern, marked_only, _) in FAMILIES.items():
                    if pattern.search(code) and (not marked_only or INHERITED_MARKER.search(line)):
                        families[family] += 1
    return guards, families, mode


def describe(g):
    place = f"{g['file']}:{g['line']}" if g.get("line") else f"{g['file']} ({g.get('where')} +{g.get('offset')})"
    return (f"{place}: {g['anchor']} [method: {g.get('method') or '-'}; site: {g['site'] or '-'}; "
            f"occurrence {g['nth']}]")


# --------------------------------------------------------------------------- manifest

def read_manifest(root):
    path = Path(root) / MANIFEST
    if not path.is_file():
        raise GuardError(f"manifest missing: {MANIFEST} (create it with --write-manifest)")
    try:
        data = json.loads(path.read_text())
    except ValueError as error:
        raise GuardError(f"{MANIFEST}: not valid JSON ({error}); fix it by hand, it is never overwritten")
    if not isinstance(data, dict):
        raise GuardError(f"{MANIFEST}: top level must be an object")
    for field in ("guards", "families"):
        if not isinstance(data.get(field), list):
            raise GuardError(f"{MANIFEST}: missing list {field!r}")
    for entry in data["guards"]:
        if not isinstance(entry, dict) or not all(isinstance(entry.get(k), str) for k in ("id", "file", "anchor")) \
                or not isinstance(entry.get("nth"), int):
            raise GuardError(f"{MANIFEST}: malformed guard entry {entry!r}"[:300])
    return data


def entry_problems(entry):
    """Why a manifest guard entry is not an owned decision; empty when it is."""
    problems = []
    decision = entry.get("decision")
    if decision not in DECISIONS:
        problems.append(f"decision must be one of {', '.join(DECISIONS)} (got {decision!r})")
    if entry.get("kind") not in KINDS:
        problems.append(f"kind must be one of {', '.join(KINDS)} (got {entry.get('kind')!r})")
    if entry.get("origin") not in ORIGINS:
        problems.append(f"origin must be one of {', '.join(ORIGINS)} (got {entry.get('origin')!r})")
    if not str(entry.get("note", "")).strip():
        problems.append("note is empty: say what the guard does to a server and why the decision")
    if decision == "UNDECIDED" and not str(entry.get("check", "")).strip():
        problems.append("UNDECIDED needs a `check`: what must be verified to decide")
    return problems


def check(root):
    """Every problem as a printable line; empty when code and manifest agree."""
    problems = []
    guards, families, mode = scan(root)
    manifest = read_manifest(root)
    found = {guard_key(g): g for g in guards}
    ambiguous = {k for k, v in Counter((g["file"], g["site"], g["anchor"]) for g in guards).items() if v > 1}
    legacy = [entry["id"] for entry in manifest["guards"] if "site" not in entry]
    if legacy:
        # Nothing else can be compared until the manifest is migrated; say only that.
        return [f"{len(legacy)} manifest entries predate the `site` key ({', '.join(legacy[:5])}"
                f"{', ...' if len(legacy) > 5 else ''}); run --write-manifest once to migrate them "
                "(decisions are kept)"]
    recorded = {}
    for entry in manifest["guards"]:
        key = guard_key(entry)
        if key in recorded:
            problems.append(f"{MANIFEST}: duplicate guard entry {entry['id']!r}")
        recorded[key] = entry
        for problem in entry_problems(entry):
            problems.append(f"{entry['id']}: {problem}")
        # Two identical guards behind identical surroundings are told apart only by order; when
        # the enclosing method is known on both sides it must still be the same one.
        g = found.get(key)
        if g and (key[0], key[1], key[2]) in ambiguous and entry.get("method") and g.get("method") \
                and entry["method"] != g["method"]:
            problems.append(f"{entry['id']}: ordinal entry now sits in method {g['method']} (recorded "
                            f"{entry['method']}); methods were reordered, re-decide it "
                            f"(--write-manifest --allow-removals)")
    for key in sorted(set(found) - set(recorded)):
        problems.append(f"guard not in manifest ({mode} scan): {describe(found[key])}")
    for key in sorted(set(recorded) - set(found)):
        entry = recorded[key]
        problems.append(f"manifest entry {entry['id']} no longer exists in the code: {describe(entry)}")
    recorded_families = {f.get("id"): f for f in manifest["families"]}
    for family, count in families.items():
        entry = recorded_families.get(family)
        if entry is None:
            problems.append(f"family not in manifest: {family} ({count} sites)")
            continue
        if entry.get("sites") != count:
            problems.append(f"family {family}: {count} sites in the code, manifest says {entry.get('sites')}")
        if entry.get("decision") not in DECISIONS or not str(entry.get("note", "")).strip():
            problems.append(f"family {family}: needs a decision and a note")
    for family in set(recorded_families) - set(families):
        problems.append(f"manifest family unknown to the policy: {family}")
    return problems


def patch_for(relative):
    for patch_root, drop, source_root in BASELINE_ROOTS:
        prefix = source_root + "/"
        if relative.startswith(prefix):
            return f"{patch_root}/{drop}{relative[len(prefix):]}.patch"
    return None


CARRIED = ("kind", "effect", "decision", "note", "check", "test")


def render_manifest(guards, families, previous):
    """(manifest dict, vanished entries). Decisions carry over by key; old entries by legacy key."""
    by_key, by_legacy = {}, {}
    for entry in (previous or {}).get("guards", []):
        if "site" in entry:
            by_key[guard_key(entry)] = entry
        else:
            by_legacy[legacy_key(entry)] = entry
    kept_families = {f.get("id"): f for f in (previous or {}).get("families", [])}
    in_file = Counter()
    matched = set()
    paired = []
    ambiguous = {k for k, v in Counter((g["file"], g["site"], g["anchor"]) for g in guards).items() if v > 1}
    for g in guards:
        in_file[(g["file"], g["anchor"])] += 1
        old = by_key.get(guard_key(g))
        if old is None:
            old = by_legacy.get((g["file"], g["anchor"], in_file[(g["file"], g["anchor"])]), {})
        if old and (g["file"], g["site"], g["anchor"]) in ambiguous and old.get("method") and g["method"] \
                and old["method"] != g["method"]:
            old = {}          # an ordinal entry whose method moved: not the same guard, re-decide
        if old:
            matched.add(id(old))
        paired.append((g, old))
    # Carried entries keep their ids; new entries take the first number never used for their file
    # (a retired id is not reused, so ledger history keeps pointing at the right guard).
    used_ids = {e.get("id") for e in (previous or {}).get("guards", []) if e.get("id")}
    used_ids |= {old["id"] for _, old in paired if old and old.get("id")}
    entries = []
    for g, old in paired:
        base = Path(g["file"]).stem
        entry = OrderedDict()
        if old.get("id"):
            entry["id"] = old["id"]
        else:
            number = 1
            while f"{base}#{number}" in used_ids:
                number += 1
            entry["id"] = f"{base}#{number}"
            used_ids.add(entry["id"])
        entry["file"] = g["file"]
        entry["line"] = g["line"]
        entry["method"] = g["method"]
        entry["site"] = g["site"]
        entry["anchor"] = g["anchor"]
        entry["nth"] = g["nth"]
        entry["detector"] = g["detector"]
        # A marker is evidence and wins; without one the scan only guesses and a recorded origin stays.
        entry["origin"] = g["origin"] if g.get("origin_certain") or not old.get("origin") else old["origin"]
        entry["patch"] = patch_for(g["file"])
        for field in CARRIED:
            if field in old:
                entry[field] = old[field]
        entry.setdefault("decision", None)
        entries.append(entry)
    vanished = [e for e in list(by_key.values()) + list(by_legacy.values()) if id(e) not in matched]
    family_entries = []
    for family, (_, marked_only, description) in FAMILIES.items():
        old = kept_families.get(family, {})
        entry = OrderedDict(id=family, description=description, marked_only=marked_only,
                            sites=families[family])
        for field in ("kind", "decision", "note", "test"):
            if field in old:
                entry[field] = old[field]
        entry.setdefault("kind", "OWNERSHIP-CHECK")
        entry.setdefault("decision", None)
        family_entries.append(entry)
    data = OrderedDict((
        ("_comment", "Inherited Canvas/Folia (and SourbyCraft-added) guards, checked by "
                     "scripts/inherited_guard_policy.py. Key = file + site + anchor + nth identical "
                     "anchor at that site; line is informational. Regenerate with --write-manifest "
                     "(decisions of unchanged entries are kept; vanished entries need --allow-removals); "
                     "ledger: docs/architecture/inherited-guards.md"),
        ("guards", entries),
        ("families", family_entries),
    ))
    return data, vanished


def write_manifest(root, allow_removals=False):
    """Regenerate the manifest. Returns (guard count, mode, removed ids)."""
    guards, families, mode = scan(root)
    path = Path(root) / MANIFEST
    previous = read_manifest(root) if path.is_file() else None
    data, vanished = render_manifest(guards, families, previous)
    if vanished and not allow_removals:
        raise GuardError("refusing to drop manifest entries that no longer match the code "
                         "(pass --allow-removals after reviewing them): "
                         + "; ".join(f"{e['id']} ({e['file']}: {e['anchor']})" for e in vanished))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    return len(guards), mode, [e["id"] for e in vanished]


def render_markdown(root):
    """The ledger appendix: one row per manifest guard, from the manifest (not a rescan)."""
    manifest = read_manifest(root)
    rows = ["| id | origin | kind | decision | file:line | anchor |", "| --- | --- | --- | --- | --- | --- |"]
    for entry in manifest["guards"]:
        short = entry["file"]
        for prefix in [root_ for _, _, root_ in BASELINE_ROOTS] + ["sourbycraft-server/src/main/java"]:
            if short.startswith(prefix + "/"):
                short = short[len(prefix) + 1:]
        anchor = entry["anchor"].replace("|", "\\|")
        if len(anchor) > 90:
            anchor = anchor[:87] + "..."
        rows.append(f"| `{entry['id']}` | {entry.get('origin') or '?'} | {entry.get('kind') or '?'} | "
                    f"{entry.get('decision') or '?'} | `{short}:{entry.get('line')}` | `{anchor}` |")
    rows.append("")
    rows.append("| family | sites | decision | description |")
    rows.append("| --- | --- | --- | --- |")
    for family in manifest["families"]:
        rows.append(f"| `{family['id']}` | {family['sites']} | {family.get('decision') or '?'} "
                    f"| {family['description']} |")
    return "\n".join(rows) + "\n"


SUMMARY_BEGIN = "<!-- summary:begin -- generated by `python3 scripts/inherited_guard_policy.py --summary`; do not edit by hand -->"
SUMMARY_END = "<!-- summary:end -->"


def render_summary(root):
    """The ledger's numbers, generated from the manifest and the scan so the prose cannot drift.

    `test_inherited_guard_policy.RepositoryTest` checks that the ledger contains this block verbatim.
    """
    root = Path(root)
    manifest = read_manifest(root)
    guards, families, mode = scan(root)
    baseline = baseline_patches(root)
    per_root = Counter()
    for patch, _ in baseline:
        for patch_root, _, _ in BASELINE_ROOTS:
            if str(patch).startswith(str(root / patch_root)):
                per_root[patch_root] += 1
    features = feature_patches(root)
    touched = sorted({f for _, files in features for f in files})
    engine = engine_sources(root)
    entries = manifest["guards"]
    kinds = Counter(e.get("kind") for e in entries)
    decisions = Counter(e.get("decision") for e in entries)
    origins = Counter(e.get("origin") for e in entries)
    detectors = Counter(e.get("detector") for e in entries)
    cross = Counter((e.get("kind"), e.get("decision")) for e in entries)
    rows = [SUMMARY_BEGIN, ""]
    rows.append("| scanned input | count |")
    rows.append("| --- | --- |")
    rows.append(f"| engine baseline patches | {len(baseline)} ("
                + " + ".join(f"{per_root[r]} `{r}`" for r, _, _ in BASELINE_ROOTS)
                + f"), {len({rel for _, rel in baseline})} Java files |")
    rows.append(f"| SourbyCraft feature patches | {len(features)}, touching {len(touched)} Java files |")
    rows.append(f"| engine source files ({ENGINE_SOURCE_ROOT}) | {len(engine)} |")
    rows.append(f"| guards found by the scan | {len(guards)} (manifest has {len(entries)}; both scan modes agree, "
                "see RepositoryTest) |")
    rows.append("")
    rows.append("| detector | A | B | C | D | E |")
    rows.append("| --- | --- | --- | --- | --- | --- |")
    rows.append("| entries | " + " | ".join(str(detectors[d]) for d in "ABCDE") + " |")
    rows.append("")
    rows.append("| kind \\ decision | " + " | ".join(DECISIONS) + " | total |")
    rows.append("| --- | " + " | ".join("---" for _ in DECISIONS) + " | --- |")
    for kind in KINDS:
        rows.append(f"| {kind} | " + " | ".join(str(cross[(kind, d)]) for d in DECISIONS) + f" | {kinds[kind]} |")
    rows.append("| **guards** | " + " | ".join(f"**{decisions[d]}**" for d in DECISIONS) + f" | **{len(entries)}** |")
    rows.append("")
    rows.append("| origin | " + " | ".join(ORIGINS) + " |")
    rows.append("| --- | " + " | ".join("---" for _ in ORIGINS) + " |")
    rows.append("| entries | " + " | ".join(str(origins[o]) for o in ORIGINS) + " |")
    rows.append("")
    rows.append("| family | sites |")
    rows.append("| --- | --- |")
    for family in manifest["families"]:
        rows.append(f"| `{family['id']}` | {family['sites']} |")
    rows.append(f"| **total** | **{sum(f['sites'] for f in manifest['families'])}** |")
    rows += ["", SUMMARY_END]
    return "\n".join(rows) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--write-manifest", action="store_true",
                        help="regenerate the manifest, keeping the decisions of unchanged entries")
    parser.add_argument("--allow-removals", action="store_true",
                        help="with --write-manifest: drop entries that no longer match the code (they are listed)")
    parser.add_argument("--markdown", action="store_true", help="print the ledger appendix table")
    parser.add_argument("--summary", action="store_true", help="print the ledger's generated numbers block")
    args = parser.parse_args(argv)
    try:
        if args.write_manifest:
            count, mode, removed = write_manifest(args.root, args.allow_removals)
            print(f"inherited_guard_policy: recorded {count} guards ({mode} scan) in {MANIFEST}")
            for removed_id in removed:
                print(f"inherited_guard_policy: removed entry {removed_id}")
            return 0
        if args.markdown:
            sys.stdout.write(render_markdown(args.root))
            return 0
        if args.summary:
            sys.stdout.write(render_summary(args.root))
            return 0
        problems = check(args.root)
    except GuardError as error:
        print(f"inherited_guard_policy: ERROR: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"inherited_guard_policy: {problem}", file=sys.stderr)
    if problems:
        print(f"inherited_guard_policy: {len(problems)} problem(s); every guard needs an entry with a "
              f"SourbyCraft decision in docs/architecture/inherited-guards.md and {MANIFEST}",
              file=sys.stderr)
        return 1
    guards, _, mode = scan(args.root)
    print(f"inherited_guard_policy: OK ({len(guards)} guards, {mode} scan, match {MANIFEST})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
