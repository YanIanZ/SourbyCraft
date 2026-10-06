#!/usr/bin/env python3
"""Read-only report of SourbyCraft's patch surface, for planning a `paperRef` bump.

What it reads (nothing is written, no Gradle, no git state change):

* feature patches (git-format, one commit each) in
  ``sourbycraft-server/minecraft-patches/features``, ``sourbycraft-server/paper-patches/features``
  and ``sourbyapi/paper-patches/features``;
* the engine-baseline file patches (diffpatch, one per upstream file) in
  ``sourbycraft-server/minecraft-patches/sources``, ``.../resources``,
  ``sourbycraft-server/paper-patches/files`` and ``sourbyapi/paper-patches/files`` -- counted only.

Per feature patch it reports the number, subject, files touched, hunks, +/- lines and a
classification (``hook``, ``default``, ``fix``, ``logic``) together with the rule that fired.
The rules are deliberately simple so a human can disagree with them; see ``classify``.

It also reports merge-conflict hot spots (an upstream file touched by more than one feature
patch, or by a feature patch AND a baseline file patch), the ten largest feature patches,
patch files git does not track, numbering gaps/duplicates, and -- by comparing subjects with
the materialized repositories -- which patch files a ``rebuild*Patches`` run would delete,
add or renumber right now.

Exit status is 0 for the report. With ``--check`` it is 1 when any patch file is untracked by
git or any feature-patch number is used twice -- the two states in which a
``rebuild*Patches`` run silently deletes or renumbers work (AGENT-COORDINATION.md, patch
directory incident, 2026-10-06). ``--check-rebuild`` also fails when a rebuild would delete
a patch file because it has no materialized commit; run it before any ``rebuild*Patches``.

Python 3.9 compatible on purpose: some shells run the scripts with /usr/bin/python3.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from typing import Dict, List, Optional, Tuple

FEATURE_DIRS = [
    ("minecraft", "sourbycraft-server/minecraft-patches/features"),
    ("paper", "sourbycraft-server/paper-patches/features"),
    ("api", "sourbyapi/paper-patches/features"),
]

# Materialized git repositories that rebuild*Patches regenerates each feature directory from.
# Feature commits are the ones after the "... File Patches" commit paperweight makes.
MATERIALIZED = {
    "minecraft": "sourbycraft-server/src/minecraft/java",
    "paper": "paper-server",
    "api": "paper-api",
}
FILE_PATCHES_COMMIT = re.compile(r"file patches$", re.IGNORECASE)

# label -> (directory, feature label whose diff paths it shares)
BASELINE_DIRS = [
    ("minecraft-sources", "sourbycraft-server/minecraft-patches/sources", "minecraft"),
    ("minecraft-resources", "sourbycraft-server/minecraft-patches/resources", "minecraft"),
    ("paper-files", "sourbycraft-server/paper-patches/files", "paper"),
    ("api-files", "sourbyapi/paper-patches/files", "api"),
]

HOOK_MAX_ADDED = 10
HOOK_MAX_REMOVED = 2
DEFAULT_MAX_PAIRS = 6
OWNED_REFERENCE = re.compile(r"\bdev\.(iyanz|yanianz)\.")
FIX_SUBJECT = re.compile(r"\b(fix|fixes|fixed|typo|bug|bugfix|regression|crash)\b", re.IGNORECASE)
NUMBER_PREFIX = re.compile(r"^(\d{4})-")
HUNK_HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


# --------------------------------------------------------------------------- parsing


class FileDiff(object):
    def __init__(self, path):
        # type: (str) -> None
        self.path = path
        self.new_file = False
        self.deleted_file = False
        self.hunks = 0
        self.added = []  # type: List[str]
        self.removed = []  # type: List[str]
        # per hunk, the ordered list of ('-'|'+', text) changes, for default detection
        self.changes = []  # type: List[List[Tuple[str, str]]]


class FeaturePatch(object):
    def __init__(self, directory, filename):
        # type: (str, str) -> None
        self.directory = directory
        self.filename = filename
        match = NUMBER_PREFIX.match(filename)
        self.number = int(match.group(1)) if match else None  # type: Optional[int]
        self.subject = ""
        self.body = ""
        self.files = []  # type: List[FileDiff]
        self.classification = ""
        self.rule = ""

    @property
    def added(self):
        # type: () -> int
        return sum(len(f.added) for f in self.files)

    @property
    def removed(self):
        # type: () -> int
        return sum(len(f.removed) for f in self.files)

    @property
    def hunks(self):
        # type: () -> int
        return sum(f.hunks for f in self.files)

    @property
    def upstream_files(self):
        # type: () -> List[str]
        """Files that exist before the patch applies (modified or deleted, not created)."""
        return [f.path for f in self.files if not f.new_file]

    @property
    def new_files(self):
        # type: () -> List[str]
        return [f.path for f in self.files if f.new_file]


def _strip_subject(raw):
    # type: (str) -> str
    raw = re.sub(r"^\[PATCH[^\]]*\]\s*", "", raw.strip())
    return re.sub(r"\s+", " ", raw)


def parse_patch(text, directory="", filename=""):
    # type: (str, str, str) -> FeaturePatch
    """Parse a git-format patch. Hunk bodies are consumed by the counts in their headers, so
    the ``-- `` signature trailer and ``---`` header lines are never miscounted."""
    patch = FeaturePatch(directory, filename)
    lines = text.splitlines()
    i = 0
    subject_lines = []  # type: List[str]
    body_lines = []  # type: List[str]
    in_subject = False
    in_body = False
    current = None  # type: Optional[FileDiff]
    while i < len(lines):
        line = lines[i]
        if line.startswith("diff --git "):
            in_subject = in_body = False
            parts = line[len("diff --git "):].split(" b/", 1)
            path = parts[1] if len(parts) == 2 else parts[0]
            if path.startswith("b/"):
                path = path[2:]
            current = FileDiff(path)
            patch.files.append(current)
            i += 1
            continue
        if current is None:
            if line.startswith("Subject:"):
                in_subject = True
                subject_lines.append(line[len("Subject:"):])
            elif in_subject and line.startswith(" "):
                subject_lines.append(line)
            elif in_subject:
                in_subject = False
                in_body = True
                if line.strip():
                    body_lines.append(line)
            elif in_body:
                if line.strip() == "---":
                    in_body = False
                else:
                    body_lines.append(line)
            i += 1
            continue
        if line.startswith("new file mode") or line == "--- /dev/null":
            current.new_file = True
            i += 1
            continue
        if line.startswith("deleted file mode") or line == "+++ /dev/null":
            current.deleted_file = True
            i += 1
            continue
        header = HUNK_HEADER.match(line)
        if header:
            current.hunks += 1
            old_left = int(header.group(2)) if header.group(2) is not None else 1
            new_left = int(header.group(4)) if header.group(4) is not None else 1
            hunk_changes = []  # type: List[Tuple[str, str]]
            i += 1
            while i < len(lines) and (old_left > 0 or new_left > 0):
                body = lines[i]
                if body.startswith("\\"):
                    i += 1
                    continue
                tag = body[:1]
                content = body[1:]
                if tag == "+":
                    current.added.append(content)
                    hunk_changes.append(("+", content))
                    new_left -= 1
                elif tag == "-":
                    current.removed.append(content)
                    hunk_changes.append(("-", content))
                    old_left -= 1
                else:  # context (' ' or an empty line from a stripped trailing space)
                    old_left -= 1
                    new_left -= 1
                    hunk_changes.append((" ", content))
                i += 1
            current.changes.append(hunk_changes)
            continue
        i += 1
    patch.subject = _strip_subject(" ".join(subject_lines))
    patch.body = "\n".join(body_lines).strip()
    return patch


# --------------------------------------------------------------------------- classification


def is_comment_or_blank(line):
    # type: (str) -> bool
    s = line.strip()
    return not s or s.startswith("//") or s.startswith("/*") or s.startswith("*") or s.startswith("#")


def _normalise_literals(line):
    # type: (str) -> str
    s = re.sub(r"\s*//.*$", "", line).strip()
    s = re.sub(r'"(?:[^"\\]|\\.)*"', "S", s)
    s = re.sub(r"'(?:[^'\\]|\\.)'", "C", s)
    s = re.sub(r"\b(true|false)\b", "B", s)
    s = re.sub(r"(?<![\w.])-?(0x[0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][-+]?\d+)?)[lLfFdD]?\b", "N", s)
    return s


def literal_only_pairs(patch):
    # type: (FeaturePatch) -> Optional[int]
    """Number of changed code lines if every change is a literal swap, else None.

    Each run of removed code lines must be followed by an equally long run of added code
    lines, and each pair must be identical once string/number/boolean literals are masked.
    Comment-only added or removed lines are ignored."""
    pairs = 0
    for diff in patch.files:
        if diff.new_file or diff.deleted_file:
            return None
        for hunk in diff.changes:
            minus = []  # type: List[str]
            plus = []  # type: List[str]

            def flush():
                # type: () -> Optional[int]
                if len(minus) != len(plus):
                    return None
                for old, new in zip(minus, plus):
                    if old.strip() == new.strip() or _normalise_literals(old) != _normalise_literals(new):
                        return None
                return len(minus)

            for tag, content in hunk + [(" ", "")]:
                if tag in "+-" and is_comment_or_blank(content):
                    continue
                if tag == "-":
                    if plus:  # '+' run before a '-' run: not a simple swap
                        return None
                    minus.append(content)
                elif tag == "+":
                    plus.append(content)
                else:
                    if minus or plus:
                        count = flush()
                        if count is None:
                            return None
                        pairs += count
                        del minus[:]
                        del plus[:]
    return pairs if pairs else None


def classify(patch):
    # type: (FeaturePatch) -> Tuple[str, str]
    """Return (classification, rule that fired). Rules are tried in this order:

    1. default -- every code change is a removed/added line pair that differs only in
       literals (numbers, strings, booleans), at most DEFAULT_MAX_PAIRS pairs, no new files.
    2. hook    -- no new files, at most HOOK_MAX_ADDED added and HOOK_MAX_REMOVED removed code
       lines, and at least one added code line references dev.iyanz.* / dev.yanianz.*.
    3. fix     -- the subject says fix/typo/bug/regression/crash.
    4. logic   -- everything else: behaviour written inside upstream files (or a new class
       carried inside the patch).
    """
    added_code = [l for f in patch.files for l in f.added if not is_comment_or_blank(l)]
    removed_code = [l for f in patch.files for l in f.removed if not is_comment_or_blank(l)]
    new_files = patch.new_files

    pairs = literal_only_pairs(patch)
    if pairs is not None and pairs <= DEFAULT_MAX_PAIRS:
        return "default", "literal-only change: %d line pair(s) differ only in number/string/boolean literals" % pairs

    owned = [l for l in added_code if OWNED_REFERENCE.search(l)]
    if (not new_files and owned and len(added_code) <= HOOK_MAX_ADDED
            and len(removed_code) <= HOOK_MAX_REMOVED):
        return "hook", ("%d added / %d removed code lines (limits %d/%d); %d call into dev.iyanz/dev.yanianz"
                        % (len(added_code), len(removed_code), HOOK_MAX_ADDED, HOOK_MAX_REMOVED, len(owned)))

    if FIX_SUBJECT.search(patch.subject):
        return "fix", "subject matches /%s/" % FIX_SUBJECT.pattern

    reasons = []
    if new_files:
        reasons.append("creates %d new file(s) inside the patch" % len(new_files))
    reasons.append("%d added / %d removed code lines" % (len(added_code), len(removed_code)))
    if not owned:
        reasons.append("no call into dev.iyanz/dev.yanianz")
    elif len(added_code) > HOOK_MAX_ADDED or len(removed_code) > HOOK_MAX_REMOVED:
        reasons.append("over hook limits %d/%d" % (HOOK_MAX_ADDED, HOOK_MAX_REMOVED))
    return "logic", "; ".join(reasons)


# --------------------------------------------------------------------------- git


def _git(root, args):
    # type: (str, List[str]) -> Optional[str]
    try:
        out = subprocess.run(["git", "-C", root] + args, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, universal_newlines=True, check=False)
    except OSError:
        return None
    if out.returncode != 0:
        return None
    return out.stdout


def git_state(root, directories):
    # type: (str, List[str]) -> Optional[Dict[str, List[str]]]
    """Untracked, staged-but-uncommitted, and index-only (missing on disk) patch files.

    Returns None when ``root`` is not inside a git work tree."""
    if _git(root, ["rev-parse", "--is-inside-work-tree"]) is None:
        return None
    existing = [d for d in directories if os.path.isdir(os.path.join(root, d))]
    state = {"untracked": [], "staged_not_committed": [], "missing_on_disk": []}  # type: Dict[str, List[str]]
    if not existing:
        return state
    status = _git(root, ["status", "--porcelain", "--untracked-files=all", "--"] + existing) or ""
    for line in status.splitlines():
        if len(line) < 4:
            continue
        code, path = line[:2], line[3:]
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        path = path.strip('"')
        if not path.endswith(".patch"):
            continue
        if code == "??":
            state["untracked"].append(path)
            continue
        if code[0] in "AR":
            state["staged_not_committed"].append(path)
        if code[1] == "D":
            state["missing_on_disk"].append(path)
    for key in state:
        state[key].sort()
    return state


def materialized_feature_subjects(repo):
    # type: (str) -> Optional[List[str]]
    """Feature commit subjects of a materialized repo, oldest first, or None when ``repo`` is
    not its own git repository (not applied yet) or has no File Patches commit."""
    if not os.path.isdir(repo):
        return None
    top = _git(repo, ["rev-parse", "--show-toplevel"])
    if top is None or os.path.realpath(top.strip()) != os.path.realpath(repo):
        return None
    log = _git(repo, ["log", "--format=%s", "-n", "2000"])
    if log is None:
        return None
    subjects = []  # type: List[str]
    for subject in log.splitlines():
        if FILE_PATCHES_COMMIT.search(subject.strip()):
            subjects.reverse()
            return subjects
        subjects.append(subject.strip())
    return None


def rebuild_prediction(patches, subjects):
    # type: (List[FeaturePatch], List[str]) -> Dict[str, List]
    """What a rebuild*Patches run would do to one feature directory, judged by subject.

    It regenerates the directory from the materialized commits, so a patch file without a
    commit is deleted, a commit without a file is added, and files are renumbered by
    commit order."""
    by_subject = {}  # type: Dict[str, FeaturePatch]
    for p in patches:
        by_subject[p.subject] = p
    deleted = sorted(p.filename for p in patches if p.subject not in subjects)
    added = [s for s in subjects if s not in by_subject]
    renumbered = []  # type: List[Dict]
    for index, subject in enumerate(subjects, 1):
        p = by_subject.get(subject)
        if p is not None and p.number != index:
            renumbered.append({"file": p.filename, "from": p.number, "to": index})
    return {"deleted": deleted, "added": added, "renumbered": renumbered}


# --------------------------------------------------------------------------- report


def _patch_files(directory):
    # type: (str) -> List[str]
    if not os.path.isdir(directory):
        return []
    return sorted(n for n in os.listdir(directory) if n.endswith(".patch"))


def numbering(filenames):
    # type: (List[str]) -> Dict[str, List[int]]
    numbers = []  # type: List[int]
    for name in filenames:
        m = NUMBER_PREFIX.match(name)
        if m:
            numbers.append(int(m.group(1)))
    seen = set()  # type: set
    duplicates = sorted({n for n in numbers if n in seen or seen.add(n)})
    gaps = []  # type: List[int]
    if numbers:
        present = set(numbers)
        gaps = [n for n in range(1, max(numbers) + 1) if n not in present]
    return {"duplicates": duplicates, "gaps": gaps}


def baseline_targets(directory):
    # type: (str) -> List[str]
    """Upstream paths patched by a baseline directory (file patch path minus '.patch')."""
    targets = []  # type: List[str]
    for base, _dirs, files in os.walk(directory):
        for name in files:
            if name.endswith(".patch"):
                rel = os.path.relpath(os.path.join(base, name), directory)
                targets.append(rel[:-len(".patch")].replace(os.sep, "/"))
    return sorted(targets)


def build_report(root):
    # type: (str) -> Dict
    patches = []  # type: List[FeaturePatch]
    numbering_by_dir = {}  # type: Dict[str, Dict[str, List[int]]]
    for label, rel in FEATURE_DIRS:
        directory = os.path.join(root, rel)
        names = _patch_files(directory)
        numbering_by_dir[label] = numbering(names)
        for name in names:
            with open(os.path.join(directory, name), encoding="utf-8", errors="replace") as fh:
                patch = parse_patch(fh.read(), label, name)
            patch.classification, patch.rule = classify(patch)
            patches.append(patch)

    baseline = {}  # type: Dict[str, Dict]
    baseline_by_feature_label = {}  # type: Dict[str, Dict[str, str]]
    for label, rel, feature_label in BASELINE_DIRS:
        targets = baseline_targets(os.path.join(root, rel))
        baseline[label] = {"directory": rel, "file_patches": len(targets)}
        for t in targets:
            baseline_by_feature_label.setdefault(feature_label, {})[t] = label

    touched = {}  # type: Dict[Tuple[str, str], List[str]]
    for p in patches:
        for path in p.upstream_files:
            touched.setdefault((p.directory, path), []).append(p.filename[:4])
    hot_spots = [
        {"directory": d, "file": f, "patches": nums}
        for (d, f), nums in sorted(touched.items()) if len(nums) > 1
    ]
    baseline_overlap = [
        {"directory": d, "file": f, "patches": nums, "baseline": baseline_by_feature_label[d][f]}
        for (d, f), nums in sorted(touched.items())
        if f in baseline_by_feature_label.get(d, {})
    ]

    largest = sorted(patches, key=lambda p: (-(p.added + p.removed), p.directory, p.filename))[:10]
    directories = [rel for _l, rel in FEATURE_DIRS] + [rel for _l, rel, _f in BASELINE_DIRS]
    git = git_state(root, directories)

    upstream_union = {}  # type: Dict[str, set]
    for p in patches:
        upstream_union.setdefault(p.directory, set()).update(p.upstream_files)

    rebuild = {}  # type: Dict[str, Optional[Dict[str, List]]]
    for label, _rel in FEATURE_DIRS:
        subjects = materialized_feature_subjects(os.path.join(root, MATERIALIZED[label]))
        rebuild[label] = None if subjects is None else rebuild_prediction(
            [p for p in patches if p.directory == label], subjects)

    return {
        "root": os.path.abspath(root),
        "feature_patches": [
            {
                "directory": p.directory,
                "file": p.filename,
                "number": p.number,
                "subject": p.subject,
                "upstream_files": p.upstream_files,
                "new_files": p.new_files,
                "hunks": p.hunks,
                "added": p.added,
                "removed": p.removed,
                "classification": p.classification,
                "rule": p.rule,
            }
            for p in patches
        ],
        "summary": {
            label: {
                "patches": sum(1 for p in patches if p.directory == label),
                "upstream_files": len(upstream_union.get(label, set())),
                "by_classification": _count_by(p.classification for p in patches if p.directory == label),
            }
            for label, _rel in FEATURE_DIRS
        },
        "hot_spots": hot_spots,
        "baseline_overlap": baseline_overlap,
        "baseline": baseline,
        "largest": [
            {"directory": p.directory, "file": p.filename, "added": p.added, "removed": p.removed}
            for p in largest
        ],
        "numbering": numbering_by_dir,
        "git": git,
        "rebuild": rebuild,
    }


def _count_by(values):
    counts = {}  # type: Dict[str, int]
    for v in values:
        counts[v] = counts.get(v, 0) + 1
    return dict(sorted(counts.items()))


def check_failures(report):
    # type: (Dict) -> List[str]
    failures = []  # type: List[str]
    git = report.get("git")
    if git:
        for path in git["untracked"]:
            failures.append("untracked patch file: %s" % path)
    for label, info in report["numbering"].items():
        for n in info["duplicates"]:
            failures.append("duplicate patch number %04d in %s" % (n, label))
    return failures


def rebuild_failures(report):
    # type: (Dict) -> List[str]
    failures = []  # type: List[str]
    for label, prediction in report["rebuild"].items():
        if prediction:
            for name in prediction["deleted"]:
                failures.append("a rebuild of %s would delete %s (no materialized commit)" % (label, name))
    return failures


def _md(text):
    # type: (str) -> str
    return text.replace("|", "\\|")


def render_markdown(report):
    # type: (Dict) -> str
    out = []  # type: List[str]
    w = out.append
    w("# Patch surface report")
    w("")
    w("Root: `%s`" % report["root"])
    w("")
    w("## Summary")
    w("")
    w("| Directory | Feature patches | Upstream files touched | By classification |")
    w("| --- | ---: | ---: | --- |")
    for label, info in report["summary"].items():
        by = ", ".join("%s %d" % kv for kv in info["by_classification"].items()) or "-"
        w("| %s | %d | %d | %s |" % (label, info["patches"], info["upstream_files"], by))
    w("")
    w("| Baseline directory | File patches |")
    w("| --- | ---: |")
    for _label, info in report["baseline"].items():
        w("| `%s` | %d |" % (info["directory"], info["file_patches"]))
    w("")

    git = report["git"]
    w("## Git tracking")
    w("")
    if git is None:
        w("Not a git work tree; tracking not checked.")
    else:
        w("- untracked patch files: %d" % len(git["untracked"]))
        for p in git["untracked"]:
            w("  - `%s`" % p)
        w("- staged but not committed: %d" % len(git["staged_not_committed"]))
        for p in git["staged_not_committed"]:
            w("  - `%s`" % p)
        w("- in the index but missing on disk: %d" % len(git["missing_on_disk"]))
        for p in git["missing_on_disk"]:
            w("  - `%s`" % p)
    w("")
    w("## Numbering")
    w("")
    for label, info in report["numbering"].items():
        dup = ", ".join("%04d" % n for n in info["duplicates"]) or "none"
        gaps = ", ".join("%04d" % n for n in info["gaps"]) or "none"
        w("- %s: duplicates %s; gaps %s" % (label, dup, gaps))
    w("")

    w("## What a rebuild*Patches run would do now")
    w("")
    w("Judged by subject against the materialized repositories (%s)." % ", ".join(
        "`%s`" % v for v in MATERIALIZED.values()))
    w("")
    for label, prediction in report["rebuild"].items():
        if prediction is None:
            w("- %s: materialized repo absent or not applied; not checked" % label)
            continue
        if not (prediction["deleted"] or prediction["added"] or prediction["renumbered"]):
            w("- %s: no change" % label)
            continue
        w("- %s:" % label)
        for name in prediction["deleted"]:
            w("  - DELETES `%s` (no materialized commit)" % name)
        for subject in prediction["added"]:
            w("  - adds a patch for commit \"%s\"" % _md(subject))
        for r in prediction["renumbered"]:
            w("  - renumbers `%s` %04d -> %04d" % (r["file"], r["from"] or 0, r["to"]))
    w("")
    w("## Feature patches")
    w("")
    w("| Dir | # | Subject | Upstream files | New files | Hunks | + | - | Class | Rule |")
    w("| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | --- |")
    for p in report["feature_patches"]:
        files = "<br>".join("`%s`" % f for f in p["upstream_files"]) or "-"
        num = "%04d" % p["number"] if p["number"] is not None else "?"
        w("| %s | %s | %s | %s | %d | %d | %d | %d | **%s** | %s |" % (
            p["directory"], num, _md(p["subject"]), files, len(p["new_files"]), p["hunks"],
            p["added"], p["removed"], p["classification"], _md(p["rule"])))
    w("")

    w("## Hot spots: upstream files touched by more than one feature patch")
    w("")
    if report["hot_spots"]:
        w("| Dir | File | Patches |")
        w("| --- | --- | --- |")
        for h in report["hot_spots"]:
            w("| %s | `%s` | %s |" % (h["directory"], h["file"], ", ".join(h["patches"])))
    else:
        w("None.")
    w("")
    w("## Hot spots: files patched by a feature patch and by the engine baseline")
    w("")
    if report["baseline_overlap"]:
        w("| Dir | File | Feature patches | Baseline |")
        w("| --- | --- | --- | --- |")
        for h in report["baseline_overlap"]:
            w("| %s | `%s` | %s | %s |" % (h["directory"], h["file"], ", ".join(h["patches"]), h["baseline"]))
    else:
        w("None.")
    w("")
    w("## Ten largest feature patches (+ and - lines)")
    w("")
    w("| Dir | Patch | + | - |")
    w("| --- | --- | ---: | ---: |")
    for p in report["largest"]:
        w("| %s | `%s` | %d | %d |" % (p["directory"], p["file"], p["added"], p["removed"]))
    w("")
    w("Classification rules (first match wins): default = literal-only line swaps (<= %d pairs); "
      "hook = no new files, <= %d added and <= %d removed code lines, at least one added line "
      "calls dev.iyanz.*/dev.yanianz.*; fix = subject says fix/typo/bug/regression/crash; "
      "logic = everything else." % (DEFAULT_MAX_PAIRS, HOOK_MAX_ADDED, HOOK_MAX_REMOVED))
    return "\n".join(out) + "\n"


def main(argv=None):
    # type: (Optional[List[str]]) -> int
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="repository root (default: the checkout containing this script)")
    parser.add_argument("--json", metavar="PATH", help="also write the report as JSON to PATH")
    parser.add_argument("--check", action="store_true",
                        help="exit 1 when a patch file is untracked or a patch number is duplicated")
    parser.add_argument("--check-rebuild", action="store_true",
                        help="like --check, and also exit 1 when a rebuild*Patches run would delete "
                             "a patch file because it has no materialized commit")
    args = parser.parse_args(argv)

    report = build_report(args.root)
    sys.stdout.write(render_markdown(report))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")
    failures = check_failures(report)
    if args.check_rebuild:
        failures += rebuild_failures(report)
    if args.check or args.check_rebuild:
        if report["git"] is None:
            sys.stderr.write("patch_surface: --check: not a git work tree, tracking unchecked\n")
        if failures:
            for f in failures:
                sys.stderr.write("patch_surface: FAIL %s\n" % f)
            return 1
        sys.stderr.write("patch_surface: check passed\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
