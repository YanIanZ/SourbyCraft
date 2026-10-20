#!/usr/bin/env python3
"""Per-agent git worktrees of SourbyCraft, so agents can run Gradle without queueing on one tree.

  agent_worktree.py create <name> [--from-main-tree]   new detached worktree at main HEAD + generated inputs
  agent_worktree.py diff <name>                        write what the next apply sends as patches (prints paths)
  agent_worktree.py apply <name> [--as WORKER]         apply those patches to the main tree (git apply --3way)
  agent_worktree.py resolve <name>                     after fixing an apply's conflicts in the main tree
  agent_worktree.py remove <name> [--discard] [--force]
  agent_worktree.py list

Worktrees live in /Volumes/Ext/Sourby/sc-worktrees/<name> (outside the repo); their metadata and patches
in sc-worktrees/.meta/. A worktree starts as `git worktree add --detach <dir> HEAD`, then gets an APFS
clone (`cp -c`, falling back to a plain copy) of every generated, untracked input a build reads from the
main tree (GENERATED below: the materialized nested repos with their .git, Metal's generated sources, the
generated module build files) plus, when present, OPTIONAL_GENERATED (the paperweight upstream checkout
build_identity.py reads). With --from-main-tree it also gets the main tree's uncommitted tracked
changes (`git diff HEAD | git apply`) and its untracked, non-ignored files (minus CARRY_EXCLUDE).

The base of a worktree is a git tree object recorded right after creation, for the worktree itself and
for each nested repo (NESTED_REPOS). `diff` compares the current state (tracked + new non-ignored files,
taken through a temporary index, so no index or HEAD is touched) with that base, one patch for the main
repo and one per changed nested repo. So carried-over main-tree changes are never part of the diff.

Apply receipts (`applied_trees`, `applied_files`, `unresolved` in the metadata, bound to the worktree by
`receipt_digest`, a checksum against edits and copies, not a signature): a repo that applied cleanly records
the tree it applied and the resulting main-tree blob of every touched file. `diff`/`apply` send each repo's
changes since its last clean apply (else since the base), so reverting an applied change is sent too.
A conflicted or failed apply records the repo as unresolved: `remove` refuses, and after conflicts `apply`
also refuses until `resolve` (checks the markers are gone, then accepts the attempted tree). `remove` also
refuses when a recorded main-tree file no longer has the applied content (someone changed it since), and
ignores receipts that are legacy, edited or name trees the repo lacks. Applies are serialised per target
repository with flock (`sourby-apply-<sha1>.lock`); the optional paperweight cache is copied while holding
the main tree's Gradle lock and dropped unless it holds the worktree's paperRef.

`apply` refuses when any touched main-tree path is held by a board file lock
(/private/tmp/claude-501/sourby-filelocks/<sha1 of the resolved path>, the scheme of the scratchpad
board.py) by anyone other than `--as WORKER`. It applies with `git apply --3way` through a temporary
index, so the main tree's real index stays as it is and conflicts are left as markers in the files.
Nothing is ever committed. Gradle in a worktree: SOURBY_TREE=<dir> scripts/gradle_locked.sh ...
"""
import argparse
import contextlib
import fcntl
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
# Cloned when the main tree has them, never required. The paperweight upstream checkout is what
# scripts/build_identity.py reads (`git show <paperRef>:paper-server/build.gradle.kts`) to tell whether the
# generated module build files are clean; without it a worktree jar gets gitCommit=unknown and "-dirty".
# A clone, not a symlink, so a worktree's Gradle can never rewrite the main tree's cache.
OPTIONAL_GENERATED = (
    ".gradle/caches/paperweight/upstreams/paper",
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
    optional_generated: tuple = OPTIONAL_GENERATED
    apply_lock_root: Path = GRADLE_LOCK_ROOT   # sourby-apply-<sha1 of target repo>.lock (flock)
    apply_lock_timeout: float = 600
    cache_lock_timeout: float = 300           # wait for a main-tree Gradle build before skipping the cache
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
    try:
        meta = json.loads(path.read_text())
    except ValueError as exc:
        raise WorktreeError(f"metadata {path} is not valid JSON: {exc}")
    # The path decides what remove deletes, so it must be exactly where this tool puts the worktree.
    if not isinstance(meta, dict) or meta.get("name") != name or meta.get("path") != str(cfg.wt(name)) \
            or not isinstance(meta.get("base_trees"), dict) or "." not in meta["base_trees"]:
        raise WorktreeError(f"metadata {path} does not describe worktree {name!r} at {cfg.wt(name)}; refusing")
    return meta


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


def main_gradle_lock(cfg, owner, timeout):
    """Take the main tree's Gradle tree lock (the mkdir lock of scripts/gradle_locked.sh), breaking it only
    when its owner pid is gone. Returns the lock path, or None when a live holder kept it past timeout."""
    lock = cfg.gradle_lock_root / "sourby-gradle.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    while True:
        try:
            lock.mkdir()
            (lock / "owner").write_text(f"{owner} pid={os.getpid()} tree=main since={int(time.time())}\n")
            return lock
        except FileExistsError:
            try:
                holder = (lock / "owner").read_text()
            except OSError:
                holder = ""
            pid = re.search(r"pid=(\d+)", holder)
            if pid and not pid_alive(int(pid.group(1))):
                grave = lock.with_name(f"{lock.name}.stale.{os.getpid()}")
                with contextlib.suppress(OSError):
                    lock.rename(grave)
                    shutil.rmtree(grave, ignore_errors=True)
                continue
            if time.monotonic() >= deadline:
                return None
            time.sleep(1)


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def paper_ref(tree):
    props = Path(tree) / "gradle.properties"
    if not props.is_file():
        return None
    for line in props.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "paperRef" and re.fullmatch(r"[0-9a-f]{40}", value.strip()):
            return value.strip()
    return None


def copy_optional_cache(cfg, name, rel, wt):
    """Clone an optional cache while holding the main tree's Gradle lock (so no main-tree build rewrites it
    mid-copy), then check the copy holds the worktree's paperRef. Returns the clone_copy result, or the
    reason it was skipped (the partial copy is removed)."""
    dst = wt / rel
    lock = main_gradle_lock(cfg, f"agent_worktree create {name}", cfg.cache_lock_timeout)
    if lock is None:
        return "main-tree Gradle build holds sourby-gradle.lock; cache not copied (build identity will be unknown)"
    try:
        outcome = clone_copy(cfg.main / rel, dst)
    except (OSError, subprocess.CalledProcessError) as exc:
        shutil.rmtree(dst, ignore_errors=True)
        return f"copy failed: {exc}"
    finally:
        shutil.rmtree(lock, ignore_errors=True)
    pin = paper_ref(wt)
    ok = pin is not None and all(
        git(dst, "cat-file", "-e", f"{pin}:{module}/build.gradle.kts", check=False).returncode == 0
        for module in ("paper-server", "paper-api"))
    if not ok:
        shutil.rmtree(dst, ignore_errors=True)
        return f"copied checkout lacks the worktree's paperRef {pin}; removed"
    return outcome


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
        cache_skipped = {}
        for rel in cfg.optional_generated:
            if (cfg.main / rel).is_dir():
                outcome = copy_optional_cache(cfg, name, rel, wt)
                if outcome in ("clone", "copy", "merged"):
                    copied[rel] = outcome
                else:
                    cache_skipped[rel] = outcome
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
    if cache_skipped:
        meta["cache_skipped"] = cache_skipped
    cfg.meta_file(name).parent.mkdir(parents=True, exist_ok=True)
    save_meta(cfg, meta)
    emit(cfg, {"ok": True, **meta,
               "gradle": f"SOURBY_TREE={wt} AGENT_NAME=<you> {cfg.main}/scripts/gradle_locked.sh <tasks>"})
    return meta



def repos_of(cfg, meta):
    """(relative repo path, base tree) pairs: the worktree itself first, then nested repos."""
    return list(meta["base_trees"].items())


RECEIPT_KEYS = ("applied_trees", "applied_files", "unresolved")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def receipt_digest(meta):
    """Checksum binding the receipt to this worktree's identity. It catches hand edits, copies from another
    worktree and partial writes; it is not a signature against a deliberate forger with this source."""
    body = {k: meta.get(k) for k in ("name", "path", "base_commit", "base_trees", *RECEIPT_KEYS)}
    return hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()


def save_meta(cfg, meta):
    if any(k in meta for k in RECEIPT_KEYS):
        meta["receipt_digest"] = receipt_digest(meta)
    path = cfg.meta_file(meta["name"])
    tmp = path.with_name(path.name + f".tmp{os.getpid()}")
    tmp.write_text(json.dumps(meta, indent=2) + "\n")
    os.replace(tmp, path)


def receipt(cfg, meta):
    """(receipt, problem). The receipt is {applied_trees, applied_files, unresolved}; when it is missing,
    legacy (no digest), altered or refers to trees the repos do not have, it is empty and problem says why,
    so nothing is treated as applied."""
    empty = {"applied_trees": {}, "applied_files": {}, "unresolved": {}}
    if not any(k in meta for k in RECEIPT_KEYS):
        return empty, None
    if meta.get("receipt_digest") != receipt_digest(meta):
        return empty, "apply receipt has no valid digest (legacy or edited metadata); ignored"
    trees, files, unresolved = (meta.get(k, {}) for k in RECEIPT_KEYS)
    repos = meta["base_trees"]
    if not (isinstance(trees, dict) and isinstance(files, dict) and isinstance(unresolved, dict)):
        return empty, "apply receipt has the wrong shape; ignored"
    wt = Path(meta["path"])
    for rel, tree in trees.items():
        repo = wt if rel == "." else wt / rel
        if rel not in repos or not isinstance(tree, str) or not SHA_RE.match(tree) or \
                git(repo, "cat-file", "-t", tree, check=False).stdout.strip() != "tree":
            return empty, f"apply receipt names an unknown tree for {rel!r}; ignored"
    if any(not isinstance(k, str) or not (v is None or (isinstance(v, str) and SHA_RE.match(v)))
           for k, v in files.items()) or any(rel not in repos for rel in unresolved):
        return empty, "apply receipt has malformed entries; ignored"
    return {"applied_trees": trees, "applied_files": files, "unresolved": unresolved}, None


def blob_id(path):
    """Git blob id of a working-tree file (symlink: its target), None when it does not exist."""
    try:
        data = str(path.readlink()).encode() if path.is_symlink() else path.read_bytes()
    except FileNotFoundError:
        return None
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def current_tree(cfg, meta, rel):
    wt = Path(meta["path"])
    repo = wt if rel == "." else wt / rel
    return repo, snapshot(repo, exclude=CARRY_EXCLUDE_PATHS if rel == "." else ())


def unapplied(cfg, meta, rec=None):
    """{repo: changed-path count} of worktree state that no clean apply has taken: each repo's current
    snapshot is compared with the tree its last clean apply recorded (applied_trees), else with its base."""
    rec = rec if rec is not None else receipt(cfg, meta)[0]
    dirty = {}
    for rel, base in repos_of(cfg, meta):
        repo, current = current_tree(cfg, meta, rel)
        count = len(changed_paths(repo, rec["applied_trees"].get(rel, base), current))
        if count:
            dirty[rel] = count
    return dirty


def stale_files(cfg, rec):
    """Main-tree files whose content differs from what the last apply left there."""
    return sorted(rel for rel, blob in rec["applied_files"].items() if blob_id(cfg.main / rel) != blob)


def compute_diff(cfg, name, meta=None, rec=None):
    """Patches of what the next apply sends: each repo's current state against its last clean apply
    (applied_trees), else against the base. Reverting an applied change is part of that delta."""
    meta = meta or load_meta(cfg, name)
    rec = rec if rec is not None else receipt(cfg, meta)[0]
    wt = Path(meta["path"])
    out_dir = cfg.patch_dir(name)
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.patch"):
        old.unlink()
    patches = []
    for rel, base in repos_of(cfg, meta):
        base = rec["applied_trees"].get(rel, base)
        repo, current = current_tree(cfg, meta, rel)
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


@contextlib.contextmanager
def apply_locks(cfg, rels, owner):
    """flock one lock per target repository (sorted, so two applies never deadlock): two worktrees applying
    to the same repo run one after the other, and the second 3-way merges against the first's result."""
    handles = []
    try:
        for rel in sorted(rels):
            target = cfg.main if rel == "." else cfg.main / rel
            path = cfg.apply_lock_root / f"sourby-apply-{lock_key(target)}.lock"
            path.parent.mkdir(parents=True, exist_ok=True)
            fh = open(path, "a+")
            deadline = time.monotonic() + cfg.apply_lock_timeout
            while True:
                try:
                    fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        fh.seek(0)
                        holder = fh.read().strip() or "?"
                        fh.close()
                        raise WorktreeError(f"another apply holds {path} ({holder}); try again later")
                    time.sleep(0.2)
            fh.seek(0)
            fh.truncate()
            fh.write(f"{owner} pid={os.getpid()}\n")
            fh.flush()
            handles.append(fh)
        yield
    finally:
        for fh in reversed(handles):
            fcntl.flock(fh, fcntl.LOCK_UN)
            fh.close()


def cmd_apply(cfg, name, worker=None):
    meta = load_meta(cfg, name)
    with apply_locks(cfg, meta["base_trees"], f"{worker or 'agent_worktree'} apply {name}"):
        return locked_apply(cfg, name, meta, worker)


def locked_apply(cfg, name, meta, worker):
    rec, problem = receipt(cfg, meta)
    conflicted = {rel: u for rel, u in rec["unresolved"].items() if u.get("kind") == "conflict"}
    if conflicted:
        emit(cfg, {"ok": False, "refused": "an earlier apply left conflicts", "unresolved": conflicted,
                   "hint": f"fix the <<<<<<< markers in the main tree, then `resolve {name}`"})
        return 3
    summary = compute_diff(cfg, name, meta, rec)
    all_paths = [p for patch in summary["patches"] for p in patch["main_paths"]]
    if not all_paths:
        emit(cfg, {"ok": True, "name": name, "applied": [], "note": "no changes against the last apply"})
        return 0
    held = held_locks(cfg, all_paths, worker)
    if held:
        emit(cfg, {"ok": False, "refused": "file locks held by other workers", "held": held,
                   "hint": "wait for them, or lock the paths yourself and pass --as <you>"})
        return 3
    wt = Path(meta["path"])
    # Start from the validated receipt (an invalid one is dropped, never extended).
    meta.update({k: json.loads(json.dumps(v)) for k, v in rec.items()})
    results = []
    try:
        for patch in summary["patches"]:
            rel = patch["repo"]
            target = cfg.main if rel == "." else cfg.main / rel
            lock = None
            if rel.startswith("sourbycraft-server/src/minecraft"):
                lock = cfg.mc_lock
                acquire_dir_lock(lock, f"{worker or 'agent_worktree'} apply {name}")
            try:
                if rel != ".":
                    transfer_objects(wt / rel, target, patch["base"], patch["current"])
                try:
                    outcome = apply_patch(target, patch["patch"], patch["files"])
                except Exception:
                    # apply may have written part of it: nothing about this repo counts as taken
                    meta["unresolved"][rel] = {"kind": "failed", "attempted": patch["current"],
                                               "files": patch["main_paths"]}
                    raise
            finally:
                if lock:
                    shutil.rmtree(lock, ignore_errors=True)
            conflicts = [p if rel == "." else f"{rel}/{p}" for p in outcome["conflicts"]]
            if outcome["returncode"] == 0 and not conflicts:
                meta["applied_trees"][rel] = patch["current"]
                meta["applied_files"].update({p: blob_id(cfg.main / p) for p in patch["main_paths"]})
                meta["unresolved"].pop(rel, None)
            else:
                # The receipt stops authorising removal: remove and (for conflicts) apply refuse until
                # `resolve` (conflicts) or a successful retry (plain failure). applied_trees keeps the last
                # clean point, so a retry still sends every change since then, reverts included.
                meta["unresolved"][rel] = {"kind": "conflict" if conflicts else "failed",
                                           "attempted": patch["current"], "files": patch["main_paths"],
                                           "conflicts": conflicts}
            results.append({"repo": rel, "patch": patch["patch"], "files": patch["main_paths"], **outcome,
                            "conflicts": conflicts})
    finally:
        save_meta(cfg, meta)
    ok = all(r["returncode"] == 0 and not r["conflicts"] for r in results)
    conflicts = [c for r in results for c in r["conflicts"]]
    out = {"ok": ok, "name": name, "results": results, "conflicts": conflicts,
           "note": "applied to the working tree only (index untouched, nothing committed)" if ok else
           f"see results[].output; conflicted files contain <<<<<<< markers; fix them, then `resolve {name}`"}
    if problem:
        out["receipt_warning"] = problem
    emit(cfg, out)
    return 0 if ok else 1


MARKER_RE = re.compile(rb"^(<<<<<<<|>>>>>>>)( |$)", re.M)


def cmd_resolve(cfg, name):
    """After fixing an apply's conflicts by hand in the main tree: accept the attempted trees as applied."""
    meta = load_meta(cfg, name)
    with apply_locks(cfg, meta["base_trees"], f"agent_worktree resolve {name}"):
        rec, problem = receipt(cfg, meta)
        if problem:
            raise WorktreeError(f"{problem}; cannot resolve, use remove --discard after checking the main tree")
        if not rec["unresolved"]:
            emit(cfg, {"ok": True, "name": name, "resolved": [], "note": "nothing unresolved"})
            return
        marked = []
        for rel, entry in rec["unresolved"].items():
            for path in entry.get("files", []):
                try:
                    if MARKER_RE.search((cfg.main / path).read_bytes()):
                        marked.append(path)
                except (FileNotFoundError, IsADirectoryError):
                    pass
        if marked:
            raise WorktreeError(f"conflict markers still in the main tree: {sorted(marked)}")
        meta.update({k: json.loads(json.dumps(v)) for k, v in rec.items()})
        resolved = sorted(meta["unresolved"])
        for rel in resolved:
            entry = meta["unresolved"].pop(rel)
            meta["applied_trees"][rel] = entry["attempted"]
            meta["applied_files"].update({p: blob_id(cfg.main / p) for p in entry.get("files", [])})
        save_meta(cfg, meta)
        emit(cfg, {"ok": True, "name": name, "resolved": resolved})


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
        rec, problem = receipt(cfg, meta)
        if rec["unresolved"]:
            raise WorktreeError(f"{name} has an unresolved apply in {sorted(rec['unresolved'])} (conflicts or a "
                                f"failed apply in the main tree); fix it and `resolve`/re-apply, or pass --discard")
        pending = sum(unapplied(cfg, meta, rec).values())
        if pending:
            why = f" ({problem})" if problem else ""
            raise WorktreeError(f"{name} has {pending} changed file(s) not known to be applied{why} "
                                f"(run `diff`/`apply`; patches go to {cfg.patch_dir(name)}) or pass --discard")
        stale = stale_files(cfg, rec)
        if stale:
            raise WorktreeError(f"{name}: files changed in the main tree since they were applied: {stale}; "
                                f"the worktree may hold the only copy of the applied version. Check them, "
                                f"re-apply, or pass --discard")
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
            rec, problem = receipt(cfg, meta)
            row["unapplied"] = unapplied(cfg, meta, rec)
            row["unresolved"] = sorted(rec["unresolved"])
            if problem:
                row["receipt_warning"] = problem
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
    sub.add_parser("resolve").add_argument("name")
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
        elif args.cmd == "resolve":
            cmd_resolve(cfg, args.name)
        elif args.cmd == "list":
            cmd_list(cfg)
    except WorktreeError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
