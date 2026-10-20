#!/usr/bin/env python3
"""Per-agent git worktrees of SourbyCraft, so agents can run Gradle without queueing on one tree.

  agent_worktree.py create <name> [--from-main-tree]   new detached worktree at main HEAD + generated inputs
  agent_worktree.py diff <name>                        write the worktree's changes as patches (prints paths)
  agent_worktree.py apply <name> [--as WORKER]         apply those patches to the main tree (git apply --3way)
  agent_worktree.py remove <name> [--discard] [--force]
  agent_worktree.py list

Worktrees live in /Volumes/Ext/Sourby/sc-worktrees/<name> (outside the repo); their metadata and patches
in sc-worktrees/.meta/. A worktree starts as `git worktree add --detach <dir> HEAD`, then gets an APFS
clone (`cp -c`, falling back to a plain copy) of every generated, untracked input a build reads from the
main tree (GENERATED below: the materialized nested repos with their .git, Metal's generated sources, the
generated module build files). With --from-main-tree it also gets the main tree's uncommitted tracked
changes (`git diff HEAD | git apply`) and its untracked, non-ignored files (minus CARRY_EXCLUDE).

The base of a worktree is a git tree object recorded right after creation, for the worktree itself and
for each nested repo (NESTED_REPOS). `diff` compares the current state (tracked + new non-ignored files,
taken through a temporary index, so no index or HEAD is touched) with that base, one patch for the main
repo and one per changed nested repo. So carried-over main-tree changes are never part of the diff.

`apply` refuses when any touched main-tree path is held by a board file lock
(/private/tmp/claude-501/sourby-filelocks/<sha1 of the resolved path>, the scheme of the scratchpad
board.py) by anyone other than `--as WORKER`. It applies with `git apply --3way` through a temporary
index, so the main tree's real index stays as it is and conflicts are left as markers in the files.
Nothing is ever committed. Gradle in a worktree: SOURBY_TREE=<dir> scripts/gradle_locked.sh ...
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

MAIN_TREE = Path("/Volumes/Ext/Sourby/SourbyCraft")
WORKTREE_ROOT = Path("/Volumes/Ext/Sourby/sc-worktrees")
FILE_LOCKS = Path("/private/tmp/claude-501/sourby-filelocks")
GRADLE_LOCK_ROOT = Path("/private/tmp/claude-501")
TMP_ROOT = Path("/Volumes/Ext/DevCache/agent-tmp/sourbycraft-wt")  # gradle_locked.sh: $SOURBY_TMP_ROOT/sourbycraft-wt
MC_REPO_LOCK = Path("/private/tmp/claude-501/sourby-mcrepo.lock")  # same lock as the scratchpad mc_git_locked.sh

# Untracked inputs a build of the worktree needs from the main tree (paths relative to the tree root).
GENERATED = (
    "paper-api",                                   # materialized by applyAllPatches (nested git repo)
    "paper-server",                                # materialized by applyAllPatches (nested git repo)
    "sourbycraft-server/src/minecraft",            # materialized MC sources: java/ + resources/ repos
    "Metal/src/main/java/ca/spottedleaf/concurrentutil/map/concurrent",  # setup_metal.sh output
    "sourbycraft-server/build.gradle.kts",         # generated from build.gradle.kts.patch
    "sourbyapi/build.gradle.kts",                  # generated from paper-api/build.gradle.kts
)
NESTED_REPOS = (
    "paper-api",
    "paper-server",
    "sourbycraft-server/src/minecraft/java",
    "sourbycraft-server/src/minecraft/resources",
)
CARRY_EXCLUDE = ("AGENT-COORDINATION.md", ".board-queue")  # exact name or prefix of the top-level name
# Never part of a worktree's diff either (coordination state belongs to the main tree only).
CARRY_EXCLUDE_PATHS = ("AGENT-COORDINATION.md", ".board-queue.json", ".board-queue.json.lock")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


class WorktreeError(Exception):
    pass


@dataclass
class Config:
    main: Path = MAIN_TREE
    root: Path = WORKTREE_ROOT
    file_locks: Path = FILE_LOCKS
    gradle_lock_root: Path = GRADLE_LOCK_ROOT
    tmp_root: Path = TMP_ROOT
    mc_lock: Path = MC_REPO_LOCK
    daemon_lister: object = None  # () -> [(pid, java.io.tmpdir)]; default gradle_daemons
    generated: tuple = GENERATED
    nested: tuple = NESTED_REPOS
    out: object = field(default=sys.stdout)

    def __post_init__(self):
        if self.daemon_lister is None:
            self.daemon_lister = gradle_daemons

    def wt(self, name):
        return self.root / name

    def meta_file(self, name):
        return self.root / ".meta" / f"{name}.json"

    def patch_dir(self, name):
        return self.root / ".meta" / name


def git(repo, *args, env=None, check=True, input=None, binary=False):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, env=env, input=input,
                            text=not binary)
    if check and result.returncode != 0:
        err = result.stderr if not binary else result.stderr.decode(errors="replace")
        raise WorktreeError(f"git {' '.join(args)} in {repo} failed: {err.strip()}")
    return result


def clone_copy(src, dst):
    """Copy src to dst as an APFS clone (cp -c); plain copy where clonefile is unavailable."""
    src, dst = Path(src), Path(dst)
    if dst.is_dir() and not dst.is_symlink() and src.is_dir() and not src.is_symlink():
        for child in src.iterdir():
            clone_copy(child, dst / child.name)
        return "merged"
    if dst.exists() or dst.is_symlink():
        shutil.rmtree(dst) if dst.is_dir() and not dst.is_symlink() else dst.unlink()
    dst.parent.mkdir(parents=True, exist_ok=True)
    if subprocess.run(["cp", "-cRp", str(src), str(dst)], capture_output=True).returncode == 0:
        return "clone"
    if dst.exists() or dst.is_symlink():
        shutil.rmtree(dst, ignore_errors=True) if dst.is_dir() and not dst.is_symlink() else dst.unlink()
    subprocess.run(["cp", "-Rp", str(src), str(dst)], check=True, capture_output=True)
    return "copy"


def snapshot(repo, exclude=()):
    """Tree object of the repo's working state (tracked + untracked non-ignored) via a temporary index."""
    with tempfile.TemporaryDirectory() as tmp:
        index = Path(tmp) / "index"
        real = Path(git(repo, "rev-parse", "--path-format=absolute", "--git-path", "index").stdout.strip())
        if real.exists():
            shutil.copy2(real, index)
        env = dict(os.environ, GIT_INDEX_FILE=str(index))
        git(repo, "add", "-A", "--", ".", env=env)
        for path in exclude:
            git(repo, "rm", "-r", "-q", "--cached", "--ignore-unmatch", "--", path, env=env)
        return git(repo, "write-tree", env=env).stdout.strip()


def changed_paths(repo, base, current):
    out = git(repo, "diff", "--name-only", "--no-renames", "-z", base, current).stdout
    return [p for p in out.split("\0") if p]


def lock_key(path):
    return hashlib.sha1(str(Path(path).resolve()).encode()).hexdigest()


def excluded(rel):
    top = rel.split("/", 1)[0]
    return any(top == e or top.startswith(e) for e in CARRY_EXCLUDE)


def load_meta(cfg, name):
    path = cfg.meta_file(name)
    if not path.exists():
        raise WorktreeError(f"no worktree named {name!r} (no {path})")
    return json.loads(path.read_text())


def emit(cfg, obj):
    print(json.dumps(obj, indent=2), file=cfg.out)


def check_name(cfg, name):
    if not NAME_RE.match(name):
        raise WorktreeError(f"bad name {name!r}: use letters, digits, '.', '_' or '-' (max 64)")
    main, root = cfg.main.resolve(), cfg.root.resolve()
    if root == main or main in root.parents:
        raise WorktreeError(f"worktree root {root} is inside the repo {main}")
    wt = cfg.wt(name)
    listed = git(cfg.main, "worktree", "list", "--porcelain").stdout
    if wt.exists() or cfg.meta_file(name).exists() or f"worktree {wt.resolve()}\n" in listed + "\n":
        raise WorktreeError(f"a worktree named {name!r} already exists ({wt})")


def cmd_create(cfg, name, from_main_tree=False):
    check_name(cfg, name)
    missing = [rel for rel in cfg.generated if not (cfg.main / rel).exists()]
    if missing:
        raise WorktreeError(f"main tree lacks generated inputs {missing}; run applyAllPatches / setup_metal.sh first")
    started = time.monotonic()
    wt = cfg.wt(name)
    cfg.root.mkdir(parents=True, exist_ok=True)
    head = git(cfg.main, "rev-parse", "HEAD").stdout.strip()
    git(cfg.main, "worktree", "add", "--detach", str(wt), head)
    try:
        copied = {rel: clone_copy(cfg.main / rel, wt / rel) for rel in cfg.generated}
        carried = {"tracked_patch_bytes": 0, "untracked_files": 0}
        if from_main_tree:
            excludes = [f":(exclude){e}" for e in ("AGENT-COORDINATION.md", ".board-queue*")]
            patch = git(cfg.main, "diff", "HEAD", "--binary", "--", ".", *excludes, binary=True).stdout
            if patch:
                git(wt, "apply", "--whitespace=nowarn", "-", input=patch, binary=True)
            carried["tracked_patch_bytes"] = len(patch)
            others = git(cfg.main, "ls-files", "--others", "--exclude-standard", "-z").stdout.split("\0")
            for rel in [p for p in others if p and not excluded(p)]:
                if (cfg.main / rel).exists() or (cfg.main / rel).is_symlink():
                    clone_copy(cfg.main / rel, wt / rel)
                    carried["untracked_files"] += 1
        bases = {".": snapshot(wt, exclude=CARRY_EXCLUDE_PATHS)}
        for rel in cfg.nested:
            repo = wt / rel
            if (repo / ".git").exists():
                git(repo, "update-index", "-q", "--refresh", check=False)
                bases[rel] = snapshot(repo)
    except Exception:
        git(cfg.main, "worktree", "remove", "--force", "--force", str(wt), check=False)
        shutil.rmtree(wt, ignore_errors=True)
        git(cfg.main, "worktree", "prune", check=False)
        raise
    meta = {"name": name, "path": str(wt), "base_commit": head, "from_main_tree": from_main_tree,
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "base_trees": bases, "copied": copied,
            "carried": carried, "create_seconds": round(time.monotonic() - started, 2)}
    cfg.meta_file(name).parent.mkdir(parents=True, exist_ok=True)
    cfg.meta_file(name).write_text(json.dumps(meta, indent=2) + "\n")
    emit(cfg, {"ok": True, **meta,
               "gradle": f"SOURBY_TREE={wt} AGENT_NAME=<you> {cfg.main}/scripts/gradle_locked.sh <tasks>"})
    return meta



def repos_of(cfg, meta):
    """(relative repo path, base tree) pairs: the worktree itself first, then nested repos."""
    return list(meta["base_trees"].items())


def compute_diff(cfg, name):
    meta = load_meta(cfg, name)
    wt = Path(meta["path"])
    out_dir = cfg.patch_dir(name)
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.patch"):
        old.unlink()
    patches = []
    for rel, base in repos_of(cfg, meta):
        repo = wt if rel == "." else wt / rel
        current = snapshot(repo, exclude=CARRY_EXCLUDE_PATHS if rel == "." else ())
        paths = changed_paths(repo, base, current)
        if not paths:
            continue
        body = git(repo, "diff", "--binary", "--no-renames", base, current, binary=True).stdout
        slug = "repo" if rel == "." else rel.replace("/", "__")
        patch_file = out_dir / f"{slug}.patch"
        patch_file.write_bytes(body)
        patches.append({"repo": rel, "patch": str(patch_file), "base": base, "current": current,
                        "files": paths, "main_paths": [p if rel == "." else f"{rel}/{p}" for p in paths]})
    summary = {"name": name, "patches": patches, "files": sum(len(p["files"]) for p in patches)}
    (out_dir / "diff.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def cmd_diff(cfg, name):
    summary = compute_diff(cfg, name)
    emit(cfg, {"ok": True, **summary})
    return summary


def held_locks(cfg, main_paths, worker=None):
    held = []
    for rel in main_paths:
        lock = cfg.file_locks / lock_key(cfg.main / rel)
        if lock.is_dir():
            try:
                owner = (lock / "owner").read_text().strip()
            except OSError:
                owner = "?"
            if worker is None or owner.split(" ", 1)[0] != worker:
                held.append({"path": rel, "by": owner})
    return held


def acquire_dir_lock(lock, owner, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        try:
            lock.mkdir(parents=True)
            (lock / "owner").write_text(f"{owner}\n")
            return
        except FileExistsError:
            if time.monotonic() > deadline:
                holder = (lock / "owner").read_text().strip() if (lock / "owner").exists() else "?"
                raise WorktreeError(f"{lock} is held by {holder}")
            time.sleep(1)


def transfer_objects(src_repo, dst_repo, base, current):
    """Copy the blobs a nested-repo patch refers to into the target repo, so --3way can find them."""
    raw = git(src_repo, "diff", "--raw", "--no-renames", "--no-abbrev", "-z", base, current).stdout
    shas = {tok for tok in re.findall(r"\b[0-9a-f]{40}\b", raw) if set(tok) != {"0"}}
    if not shas:
        return 0
    pack = git(src_repo, "pack-objects", "--stdout", input=("\n".join(sorted(shas)) + "\n").encode(),
               binary=True).stdout
    git(dst_repo, "unpack-objects", "-q", input=pack, binary=True)
    return len(shas)


def apply_patch(target, patch_file, files):
    """git apply --3way through a temporary index that matches the target's working tree."""
    with tempfile.TemporaryDirectory() as tmp:
        index = Path(tmp) / "index"
        real = Path(git(target, "rev-parse", "--path-format=absolute", "--git-path", "index").stdout.strip())
        if real.exists():
            shutil.copy2(real, index)
        env = dict(os.environ, GIT_INDEX_FILE=str(index))
        known = set(p for p in git(target, "ls-files", "-z", "--", *files, env=env).stdout.split("\0") if p)
        present = [p for p in files if (target / p).exists() or p in known]
        if present:
            git(target, "add", "-A", "--", *present, env=env)
        result = git(target, "apply", "--3way", "--whitespace=nowarn", str(patch_file), env=env, check=False)
        unmerged = sorted({line.split("\t", 1)[1] for line in
                           git(target, "ls-files", "-u", env=env).stdout.splitlines() if "\t" in line})
        return {"returncode": result.returncode, "conflicts": unmerged,
                "output": (result.stdout + result.stderr).strip()}


def cmd_apply(cfg, name, worker=None):
    meta = load_meta(cfg, name)
    summary = compute_diff(cfg, name)
    all_paths = [p for patch in summary["patches"] for p in patch["main_paths"]]
    if not all_paths:
        emit(cfg, {"ok": True, "name": name, "applied": [], "note": "no changes against the base"})
        return 0
    held = held_locks(cfg, all_paths, worker)
    if held:
        emit(cfg, {"ok": False, "refused": "file locks held by other workers", "held": held,
                   "hint": "wait for them, or lock the paths yourself and pass --as <you>"})
        return 3
    wt = Path(meta["path"])
    results = []
    for patch in summary["patches"]:
        rel = patch["repo"]
        target = cfg.main if rel == "." else cfg.main / rel
        lock = None
        if rel != ".":
            if rel.startswith("sourbycraft-server/src/minecraft"):
                lock = cfg.mc_lock
                acquire_dir_lock(lock, f"{worker or 'agent_worktree'} apply {name}")
            try:
                transfer_objects(wt / rel, target, patch["base"], patch["current"])
            except WorktreeError:
                if lock:
                    shutil.rmtree(lock, ignore_errors=True)
                raise
        try:
            outcome = apply_patch(target, patch["patch"], patch["files"])
        finally:
            if lock:
                shutil.rmtree(lock, ignore_errors=True)
        results.append({"repo": rel, "patch": patch["patch"], "files": patch["main_paths"], **outcome,
                        "conflicts": [p if rel == "." else f"{rel}/{p}" for p in outcome["conflicts"]]})
    ok = all(r["returncode"] == 0 for r in results)
    conflicts = [c for r in results for c in r["conflicts"]]
    emit(cfg, {"ok": ok, "name": name, "results": results, "conflicts": conflicts,
               "note": "applied to the working tree only (index untouched, nothing committed)" if ok else
               "see results[].output; conflicted files contain <<<<<<< markers"})
    return 0 if ok else 1


def gradle_daemons():
    """(pid, java.io.tmpdir) of the running Gradle daemons, via jps + jinfo; [] when the JDK tools are missing."""
    try:
        listing = subprocess.run(["jps", "-l"], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    daemons = []
    for line in listing.splitlines():
        pid, _, main_class = line.partition(" ")
        if "GradleDaemon" not in main_class:
            continue
        try:
            props = subprocess.run(["jinfo", "-sysprops", pid], capture_output=True, text=True, timeout=30).stdout
        except (OSError, subprocess.TimeoutExpired):
            continue
        for prop in props.splitlines():
            if prop.startswith("java.io.tmpdir="):
                daemons.append((int(pid), prop.split("=", 1)[1].strip()))
    return daemons


def stop_tree_daemons(cfg, name):
    """Stop the Gradle daemons started for this worktree. gradle_locked.sh gives each tree its own
    java.io.tmpdir, which Gradle treats as a daemon-compatibility property, so every worktree build gets
    its own daemon; nothing else can reuse it, and it would idle for hours after the worktree is gone."""
    tmp = str(cfg.tmp_root / name).rstrip("/")
    stopped = []
    for pid, tmpdir in cfg.daemon_lister():
        if tmpdir.rstrip("/") == tmp:
            try:
                os.kill(pid, 15)
                stopped.append(pid)
            except OSError:
                pass
    return stopped


def cmd_remove(cfg, name, discard=False, force=False):
    meta = load_meta(cfg, name)
    wt = Path(meta["path"])
    gradle_lock = cfg.gradle_lock_root / f"sourby-gradle-{name}.lock"
    if gradle_lock.exists() and not force:
        raise WorktreeError(f"a Gradle build holds {gradle_lock}; wait for it or pass --force")
    if wt.exists() and not discard:
        summary = compute_diff(cfg, name)
        if summary["files"]:
            raise WorktreeError(f"{name} has {summary['files']} changed file(s) not known to be applied "
                                f"(patches in {cfg.patch_dir(name)}); apply them or pass --discard")
    if git(cfg.main, "worktree", "remove", "--force", "--force", str(wt), check=False).returncode != 0:
        shutil.rmtree(wt, ignore_errors=True)
        git(cfg.main, "worktree", "prune")
    stopped = stop_tree_daemons(cfg, name)
    shutil.rmtree(cfg.tmp_root / name, ignore_errors=True)
    shutil.rmtree(cfg.patch_dir(name), ignore_errors=True)
    cfg.meta_file(name).unlink()
    emit(cfg, {"ok": True, "removed": name, "path": str(wt), "exists_after": wt.exists(),
               "stopped_gradle_daemons": stopped})


def cmd_list(cfg):
    rows = []
    meta_dir = cfg.root / ".meta"
    for meta_file in sorted(meta_dir.glob("*.json")) if meta_dir.is_dir() else []:
        meta = json.loads(meta_file.read_text())
        wt = Path(meta["path"])
        row = {"name": meta["name"], "path": str(wt), "base_commit": meta["base_commit"][:12],
               "created_at": meta["created_at"], "from_main_tree": meta["from_main_tree"],
               "exists": wt.is_dir()}
        if wt.is_dir():
            dirty = {}
            for rel, base in repos_of(cfg, meta):
                repo = wt if rel == "." else wt / rel
                current = snapshot(repo, exclude=CARRY_EXCLUDE_PATHS if rel == "." else ())
                count = len(changed_paths(repo, base, current))
                if count:
                    dirty[rel] = count
            row["changed_vs_base"] = dirty
            row["dirty"] = bool(dirty)
            row["gradle_lock_held"] = (cfg.gradle_lock_root / f"sourby-gradle-{meta['name']}.lock").exists()
        rows.append(row)
    managed = {r["path"] for r in rows}
    listed = git(cfg.main, "worktree", "list", "--porcelain").stdout.splitlines()
    root = str(cfg.root.resolve())
    strays = [line.split(" ", 1)[1] for line in listed if line.startswith("worktree ")
              and line.split(" ", 1)[1].startswith(root + "/") and line.split(" ", 1)[1] not in managed]
    emit(cfg, {"worktrees": rows, "unmanaged": strays})
    return rows


def main(argv=None, cfg=None):
    cfg = cfg or Config()
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("create")
    p.add_argument("name")
    p.add_argument("--from-main-tree", action="store_true",
                   help="also carry over the main tree's uncommitted tracked changes and untracked files")
    sub.add_parser("diff").add_argument("name")
    p = sub.add_parser("apply")
    p.add_argument("name")
    p.add_argument("--as", dest="worker", help="your worker name: file locks you hold do not block the apply")
    p = sub.add_parser("remove")
    p.add_argument("name")
    p.add_argument("--discard", action="store_true", help="remove even if it has changes")
    p.add_argument("--force", action="store_true", help="remove even while a Gradle build holds its lock")
    sub.add_parser("list")
    args = parser.parse_args(argv)
    try:
        if args.cmd == "create":
            cmd_create(cfg, args.name, args.from_main_tree)
        elif args.cmd == "diff":
            cmd_diff(cfg, args.name)
        elif args.cmd == "apply":
            return cmd_apply(cfg, args.name, args.worker)
        elif args.cmd == "remove":
            cmd_remove(cfg, args.name, args.discard, args.force)
        elif args.cmd == "list":
            cmd_list(cfg)
    except WorktreeError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
