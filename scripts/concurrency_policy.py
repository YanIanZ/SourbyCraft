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
"""
import re
from pathlib import Path

# Roots that are SourbyCraft-owned. sourbyclip is included deliberately: it is a
# separate Gradle project and an audit scoped to the server package silently skips it.
SOURCE_ROOTS = (
    "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft",
    "sourbyclip/java25/src/main/java/dev/iyanz/sourbyclip",
    "sourbyapi/src/main/java/dev/iyanz/sourbycraft",
)

ASYNC_CALLS = ("supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync",
               "thenComposeAsync", "thenCombineAsync", "whenCompleteAsync", "handleAsync",
               "exceptionallyAsync", "acceptEitherAsync", "applyToEitherAsync")

CALL = re.compile(r"\b(" + "|".join(ASYNC_CALLS) + r")\s*\(")
COMMON_POOL = re.compile(r"\bForkJoinPool\s*\.\s*commonPool\s*\(")
PARALLEL = re.compile(r"\.\s*(parallelStream|parallel)\s*\(\s*\)")


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


def unowned_async_calls(root):
    """Async calls that pass no executor, plus direct common-pool and parallel-stream use."""
    findings = []
    for source_root in SOURCE_ROOTS:
        base = Path(root) / source_root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            text = path.read_text(errors="replace")
            relative = path.relative_to(root)
            for match in COMMON_POOL.finditer(text):
                findings.append({"file": str(relative), "kind": "commonPool",
                                 "line": text.count("\n", 0, match.start()) + 1,
                                 "detail": "ForkJoinPool.commonPool()"})
            for match in PARALLEL.finditer(text):
                findings.append({"file": str(relative), "kind": "parallelStream",
                                 "line": text.count("\n", 0, match.start()) + 1,
                                 "detail": match.group(0).strip()})
            for match in CALL.finditer(text):
                count = _top_level_argument_count(text, match.end() - 1)
                if count is not None and count <= 1:
                    findings.append({"file": str(relative), "kind": "async-without-executor",
                                     "line": text.count("\n", 0, match.start()) + 1,
                                     "detail": f"{match.group(1)}() with {count} argument(s)"})
    return findings
