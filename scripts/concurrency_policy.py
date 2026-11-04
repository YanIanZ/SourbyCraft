#!/usr/bin/env python3
"""PRD section 45: SourbyCraft-owned async work must name its executor.

An async call with no executor argument runs on ForkJoinPool.commonPool(), a
process-wide pool SourbyCraft does not own, size, name or shut down. The same applies
to a parallel stream. This finds those call sites.

Argument lists are parsed by balancing parentheses rather than by line matching,
because these calls routinely span many lines:

    CompletableFuture.supplyAsync(() -> {
        ...
    }, executor);

A line-based rule reads the first line, sees no comma, and reports a false positive.

Two kinds of root are scanned. Source roots are read as Java. Patch roots hold the code
SourbyCraft adds to Minecraft and Paper as git-format feature patches; each hunk's
post-image (context plus added lines, removed lines dropped) is read as Java and only a
call that starts on an added line is reported. Every root is explicit and must exist: a
missing root is an error, never a skip (audit 2026-10-14, OBS-7).
"""
import re
from pathlib import Path

# Roots that are SourbyCraft-owned. The engine package is included: the region-threading
# engine has been SourbyCraft's own code since the 2026-10-05 migration (AGENTS.md).
# SourbyClip is no longer here -- it lives in the private YanIanZ/SourbyClip repository and
# its old root under this tree was being skipped silently. The spark classes kept in this tree
# (me/lucko/spark, edited copies of Paper's bundled spark) schedule async work of their own,
# so they are held to the same rule (audit PM-246).
SOURCE_ROOTS = (
    "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft",
    "sourbyapi/src/main/java/dev/iyanz/sourbycraft",
    "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine",
    "sourbycraft-server/src/main/java/me/lucko/spark",
)

# Code SourbyCraft adds to Minecraft and Paper (AGENTS.md, "Patch application"). The
# inherited baseline (minecraft-patches/sources, paper-patches/files) is not SourbyCraft's
# to police here and is excluded, as in independence_policy.py.
PATCH_ROOTS = (
    "sourbycraft-server/minecraft-patches/features",
    "sourbycraft-server/paper-patches/features",
)

ASYNC_CALLS = ("supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync",
               "thenComposeAsync", "thenCombineAsync", "whenCompleteAsync", "handleAsync",
               "exceptionallyAsync", "acceptEitherAsync", "applyToEitherAsync")

CALL = re.compile(r"\b(" + "|".join(ASYNC_CALLS) + r")\s*\(")
COMMON_POOL = re.compile(r"\bForkJoinPool\s*\.\s*commonPool\s*\(")
PARALLEL = re.compile(r"\.\s*(parallelStream|parallel)\s*\(\s*\)")
HUNK_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def required_dir(root, relative):
    """The directory ``relative`` under ``root``. Missing is an error, not a skip."""
    base = Path(root) / relative
    if not base.is_dir():
        raise FileNotFoundError(
            f"policy root is missing: {base} -- if the layout changed, update the roots in "
            f"scripts/concurrency_policy.py rather than letting the audit shrink silently")
    return base


def _top_level_argument_count(text, open_index):
    """Count arguments in the list starting at ``open_index`` (the '(' position).

    Returns None when the list is unterminated. String and character literals are
    skipped so a comma inside one is not counted as a separator.
    """
    depth = 0
    commas = 0
    saw_content = False
    index = open_index
    length = len(text)
    while index < length:
        char = text[index]
        if char in "\"'":
            if depth == 1:
                saw_content = True          # A literal is an argument like any other.
            quote = char
            index += 1
            while index < length:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == quote:
                    break
                index += 1
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                return (commas + 1) if saw_content else 0
        elif depth == 1:
            if char == ",":
                commas += 1
            elif not char.isspace():
                saw_content = True
        index += 1
    return None


def _scan(text):
    """Yield ``(offset, kind, detail)`` for every finding in a piece of Java text."""
    for match in COMMON_POOL.finditer(text):
        yield match.start(), "commonPool", "ForkJoinPool.commonPool()"
    for match in PARALLEL.finditer(text):
        yield match.start(), "parallelStream", match.group(0).strip()
    for match in CALL.finditer(text):
        count = _top_level_argument_count(text, match.end() - 1)
        if count is not None and count <= 1:
            yield (match.start(), "async-without-executor",
                   f"{match.group(1)}() with {count} argument(s)")


def _hunks(lines):
    """Yield ``(target, first_line, [(text, added)])`` per hunk of a git-format patch.

    The post-image of a hunk is its context and added lines in order, which is contiguous
    code in the patched file; removed lines are dropped. ``first_line`` is the post-image
    line number from the hunk header, so a finding can be placed in the target file.
    """
    target = None
    first_line = 1
    hunk = None
    for raw in lines:
        if raw.startswith("+++ "):
            target = raw[4:]
            target = target[2:] if target.startswith("b/") else target
            continue
        header = HUNK_HEADER.match(raw)
        if header:
            if hunk:
                yield target, first_line, hunk
            first_line = int(header.group(1))
            hunk = []
            continue
        if hunk is None:
            continue
        if raw.startswith("+"):
            hunk.append((raw[1:], True))
        elif raw.startswith(" ") or raw == "":
            hunk.append((raw[1:], False))
        elif raw.startswith("-") or raw.startswith("\\"):
            continue                              # Removed line / "No newline at end of file".
        else:                                     # The next file's diff header.
            yield target, first_line, hunk
            hunk = None
    if hunk:
        yield target, first_line, hunk


def _source_findings(root):
    findings = []
    for source_root in SOURCE_ROOTS:
        base = required_dir(root, source_root)
        for path in sorted(base.rglob("*.java")):
            text = path.read_text(errors="replace")
            relative = str(path.relative_to(root))
            for offset, kind, detail in _scan(text):
                findings.append({"file": relative, "kind": kind,
                                 "line": text.count("\n", 0, offset) + 1, "detail": detail})
    return findings


def _patch_findings(root):
    findings = []
    for patch_root in PATCH_ROOTS:
        base = required_dir(root, patch_root)
        for patch in sorted(base.rglob("*.patch")):
            relative = str(patch.relative_to(root))
            for target, first_line, hunk in _hunks(patch.read_text(errors="replace").splitlines()):
                text = "\n".join(line for line, _ in hunk)
                for offset, kind, detail in _scan(text):
                    index = text.count("\n", 0, offset)
                    if not hunk[index][1]:
                        continue                  # Context: Paper's code, not SourbyCraft's.
                    findings.append({"file": relative, "target": target, "kind": kind,
                                     "line": first_line + index, "detail": detail})
    return findings


def unowned_async_calls(root):
    """Async calls that pass no executor, plus direct common-pool and parallel-stream use.

    Source findings carry ``file`` and ``line`` in that file; patch findings carry the patch
    as ``file``, the patched file as ``target`` and ``line`` in the patched file.
    """
    return _source_findings(root) + _patch_findings(root)
