#!/usr/bin/env python3
"""Java language targets declared across the build.

PRD section 6 makes Java 25 the baseline and says no production subsystem should
*silently* remain on an older target. Silently is the operative word: one module is
deliberately older and must stay that way. This extracts what each build script
declares so the exceptions are listed rather than discovered.
"""
import re
from pathlib import Path

SKIP_PARTS = ("build", ".gradle", "upstreams", "node_modules", ".worktrees", ".private-toolchain")

TOOLCHAIN = re.compile(r"languageVersion(?:\.set\(|\s*=\s*)\s*JavaLanguageVersion\.of\((\d+)\)")
RELEASE = re.compile(r"options\.release(?:\.set\(|\s*=\s*)\s*(\d+)")
COMPAT = re.compile(r"(?:source|target)Compatibility\s*=\s*JavaVersion\.VERSION_(\d+)")


def build_scripts(root):
    root = Path(root)
    for path in sorted(root.rglob("*.gradle.kts")):
        if not any(part in SKIP_PARTS for part in path.relative_to(root).parts):
            yield path
    for path in sorted(root.rglob("*.gradle")):
        if not any(part in SKIP_PARTS for part in path.relative_to(root).parts):
            yield path


def declared_targets(root):
    """{build script path: sorted set of Java versions it declares}."""
    targets = {}
    for path in build_scripts(root):
        text = path.read_text(errors="replace")
        versions = set()
        for pattern in (TOOLCHAIN, RELEASE, COMPAT):
            versions.update(int(value) for value in pattern.findall(text))
        if versions:
            targets[str(path.relative_to(root))] = sorted(versions)
    return targets
