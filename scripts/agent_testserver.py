#!/usr/bin/env python3
"""Boot an isolated, time-limited SourbyCraft test server for an agent, at most two at once.

The only sanctioned way for an agent to run a server (owner decision 2026-10-20). Every run
gets a fresh work directory under ``/Volumes/Ext/Sourby/agent-testservers/<name>/`` (never
inside the repository or a user's server directory), its own copy of the jar, a random
localhost port, ``online-mode=false``, and one of ``SLOTS`` machine-wide slot locks, so at most
two functional servers run at once. ``--exclusive`` waits until every slot is free and holds
them all (for anything performance-related). Slot 1's lock is the historical single lock path,
so a supervisor started before slots existed still counts as "slot 1 busy". A detached
supervisor owns the JVM, its console stdin, the lifetime watchdog and its lock(s), so they are
released however the run ends.

    python3 scripts/agent_testserver.py start --name smoke
    python3 scripts/agent_testserver.py start --name measure --exclusive
    python3 scripts/agent_testserver.py cmd --name smoke "ver" --expect "Build"
    python3 scripts/agent_testserver.py wait-for --name smoke "Saving" --timeout 30
    python3 scripts/agent_testserver.py stop --name smoke
    python3 scripts/agent_testserver.py run --name smoke2 --scenario scenario.json
    python3 scripts/agent_testserver.py cleanup --keep 5

Nothing this harness records is a certified baseline. It reuses run_baseline.py's startup
markers, git provenance and workload plans, but not its certification rules; use
run_baseline.py on benchmark hardware for a performance claim (docs/BASELINE.md).
See docs/development/AGENT-TEST-SERVER.md.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

import baseline_workloads  # noqa: E402
import run_baseline  # noqa: E402  (startup markers, git_state, sha256, jdk_tools, seed_cache)

SCHEMA = "sourbycraft.agent-testserver/1"
BASE = Path("/Volumes/Ext/Sourby/agent-testservers")
LOCK_PATH = Path("/private/tmp/claude-501/sourby-testserver.lock")   # = slot 1 (historical path)
SLOTS = 2
# Redis port convention for a scenario that starts its own redis-server: slot N uses
# REDIS_PORT_BASE + N (6401, 6402); an --exclusive run uses slot 1's port.
REDIS_PORT_BASE = 6400
EXCLUSIVE_WAIT_S = 60 * 60       # How long --exclusive waits for every slot by default.
EXCLUSIVE_POLL_S = 2.0
# Before a server starts (16 GiB machine shared with Gradle builds and other agents).
MIN_FREE_RAM_BYTES = 3 * 1024 ** 3
MAX_GRADLE_BUILDS = 2
GRADLE_LOCKED = Path("/private/tmp/claude-501/-Volumes-Ext-Sourby-SourbyCraft/"
                     "a9dedc8d-e562-4440-8e46-781542e8f43d/scratchpad/gradle_locked.sh")
JAR = REPO / "build" / "libs" / "SourbyCraft-slim.jar"
# Directories a harness run must never be created in, besides the repository and $HOME:
# /Volumes/Ext/Sourby holds the owner's own servers (TestServer, StressTest, ...).
PROTECTED_ROOTS = (Path("/Volumes/Ext/Sourby"),)

MIN_FREE_BYTES = 20 * 1024 ** 3
DEFAULT_LIFETIME_S = 20 * 60
MAX_LIFETIME_CEILING_S = 3 * 60 * 60
LIFETIME_GRACE_S = 30            # After the lifetime: console "stop" first, kill after this.
LOCK_STALE_MARGIN_S = 300        # Lock older than lifetime + this is broken regardless.
LOCK_UNOWNED_STALE_S = 60        # A lock directory without an owner file for this long.
STARTUP_TIMEOUT_S = 600          # First boot downloads the Mojang jar and libraries.
STOP_TIMEOUT_S = 180
SIGNAL_STOP_TIMEOUT_S = 60
POLL_S = 0.2
LOG_TAIL_LINES = 40

MARKER = ".agent-testserver"
CACHE_NAME = "_bootstrap-cache"
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
DONE_LINE = re.compile(r"^.*Done \([^)]*\)!.*$", re.MULTILINE)
# Properties the harness owns: isolation depends on them.
PINNED_PROPERTIES = ("server-ip", "server-port", "online-mode", "enable-rcon", "enable-query",
                     "rcon.password", "rcon.port", "query.port")
REFUSED_COMMANDS = ("stop", "restart", "end")
FINAL_STATES = ("exited", "failed")


class HarnessError(RuntimeError):
    """A refusal or failure the CLI reports as one line, exit code 2."""


# --------------------------------------------------------------------------------- small utils

def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def write_json(path, data):
    """Atomic: a reader sees the old or the new file, never half of one."""
    path = Path(path)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{secrets.token_hex(3)}.tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
    os.replace(tmp, path)


def read_json(path):
    try:
        return json.loads(Path(path).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    # A zombie (exited, not yet reaped by its parent) still answers kill(0). The harness's
    # own client can be that parent, so a dead supervisor must not look alive to it.
    try:
        state = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True,
                               text=True).stdout.strip()
    except OSError:
        return True
    return bool(state) and not state.startswith("Z")


def process_command(pid):
    try:
        return subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True,
                              text=True).stdout.strip()
    except OSError:
        return ""


def kill_group(pid, grace=10.0, alive=pid_alive):
    """SIGTERM the process group led by ``pid``, then SIGKILL after ``grace`` seconds."""
    for sig, wait in ((signal.SIGTERM, grace), (signal.SIGKILL, 5.0)):
        if not alive(pid):
            return
        try:
            os.killpg(pid, sig)
        except (ProcessLookupError, PermissionError):
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                return
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline and alive(pid):
            time.sleep(0.1)


def kill_leftover(pid, workdir):
    """Kill a JVM a dead supervisor left behind, only if it is really this run's JVM."""
    if pid_alive(pid) and workdir and str(workdir) in process_command(pid):
        kill_group(pid)
        return True
    return False


def strip_ansi(text):
    return ANSI.sub("", text)


def tail(text, lines=LOG_TAIL_LINES):
    return strip_ansi(text).splitlines()[-lines:]


def free_port(host="127.0.0.1", exclude=(25565, 25575), attempts=50):
    """A port the OS reports free on ``host`` right now (the server binds it a few seconds
    later; a lost race shows up as Paper's FAILED TO BIND TO PORT and fails the start)."""
    for _ in range(attempts):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind((host, 0))
            port = probe.getsockname()[1]
        if port not in exclude and port > 1024:
            return port
    raise HarnessError("no free localhost port found")


VM_STAT_PAGE = re.compile(r"page size of (\d+) bytes")
VM_STAT_LINE = re.compile(r"^Pages (free|inactive|speculative|purgeable):\s+(\d+)\.", re.MULTILINE)


def parse_vm_stat(text):
    """Reclaimable-now memory from macOS ``vm_stat``: (free + inactive + speculative +
    purgeable) pages x page size. Inactive pages are reclaimed without swapping first; wired,
    active and compressor pages are not counted. None if the output is not understood."""
    page = VM_STAT_PAGE.search(text)
    counts = dict(VM_STAT_LINE.findall(text))
    if not page or "free" not in counts:
        return None
    return int(page.group(1)) * sum(int(counts.get(k, 0)) for k in
                                    ("free", "inactive", "speculative", "purgeable"))


def available_memory(run=subprocess.run, system=platform.system):
    """Bytes of RAM available to a new JVM right now, or None when it cannot be measured:
    ``vm_stat`` on macOS (see parse_vm_stat), ``MemAvailable`` from /proc/meminfo elsewhere."""
    if system() == "Darwin":
        try:
            out = run(["vm_stat"], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            return None
        return parse_vm_stat(out.stdout) if out.returncode == 0 else None
    try:
        found = re.search(r"MemAvailable:\s*(\d+) kB", Path("/proc/meminfo").read_text())
    except OSError:
        return None
    return int(found.group(1)) * 1024 if found else None


GRADLE_CLIENT = re.compile(r"org\.gradle\.(wrapper\.GradleWrapperMain|launcher\.GradleMain)\b")


def running_gradle_builds(run=subprocess.run):
    """Gradle builds running now: client processes (``./gradlew`` = GradleWrapperMain, or a
    ``gradle`` launcher). An idle daemon is not a build and is not counted."""
    try:
        out = run(["ps", "-axo", "command="], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return 0
    return sum(1 for line in out.stdout.splitlines() if GRADLE_CLIENT.search(line))


def free_bytes(path):
    path = Path(path)
    while not path.exists():
        path = path.parent
    return shutil.disk_usage(path).free


def _inside(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def resolve_workdir(base, name, repo=REPO, home=None, protected=PROTECTED_ROOTS):
    """The work directory for ``name``, or HarnessError if it would escape isolation.

    Refused: a name that is not a plain identifier, a base or work directory inside the
    repository or the user's home, and anything under /Volumes/Ext/Sourby except the
    harness base itself (the owner's own servers live there).
    """
    if not isinstance(name, str) or not NAME_RE.match(name) or name == CACHE_NAME:
        raise HarnessError(f"invalid run name {name!r}: use [A-Za-z0-9._-], 1-64 chars, "
                           "starting with a letter or digit")
    base = Path(base).resolve()
    home = Path(home or Path.home()).resolve()
    workdir = (base / name).resolve()
    if not _inside(workdir, base) or workdir == base:
        raise HarnessError(f"{workdir} is not inside {base}")
    for candidate in (base, workdir):
        if _inside(candidate, Path(repo).resolve()):
            raise HarnessError(f"refusing {candidate}: inside the repository {repo}")
        if _inside(candidate, home):
            raise HarnessError(f"refusing {candidate}: inside the home directory {home}")
        for root in protected:
            root = Path(root).resolve()
            if _inside(candidate, root) and not _inside(candidate, BASE.resolve()):
                raise HarnessError(f"refusing {candidate}: under {root}, which holds the "
                                   f"owner's servers; only {BASE} is allowed there")
    return workdir


def is_harness_dir(path):
    return (Path(path) / MARKER).is_file()


# ------------------------------------------------------------------------------- machine lock

class LockBusy(HarnessError):
    def __init__(self, owner):
        self.owner = owner or {}
        super().__init__(
            f"another test server holds the lock: name={self.owner.get('name')} "
            f"pid={self.owner.get('pid')} since {self.owner.get('created_at')} "
            f"(workdir {self.owner.get('workdir')}). That slot is busy; wait for it or "
            "for its max lifetime to pass.")


class MachineLock:
    """``mkdir`` lock with an owner file; broken when its owner is dead or it outlived
    its declared max lifetime plus a margin (killing that run's leftover JVM first)."""

    def __init__(self, path=LOCK_PATH, alive=pid_alive, clock=time.time,
                 leftover=kill_leftover, stale_margin=LOCK_STALE_MARGIN_S,
                 unowned_stale=LOCK_UNOWNED_STALE_S):
        self.path = Path(path)
        self._alive, self._clock, self._leftover = alive, clock, leftover
        self._margin, self._unowned = stale_margin, unowned_stale

    @property
    def owner_file(self):
        return self.path / "owner.json"

    def read(self):
        return read_json(self.owner_file)

    def stale_reason(self, owner):
        """Why the current lock may be broken, or None while it is legitimately held."""
        if owner is None:
            try:
                age = self._clock() - self.path.stat().st_mtime
            except FileNotFoundError:
                return "vanished"
            return f"no owner file for {age:.0f}s" if age > self._unowned else None
        if not self._alive(owner.get("pid")):
            return f"owner pid {owner.get('pid')} is not running"
        limit = float(owner.get("max_lifetime_s", DEFAULT_LIFETIME_S)) + self._margin
        age = self._clock() - float(owner.get("created", 0))
        if age > limit:
            return f"held {age:.0f}s, beyond max lifetime + margin ({limit:.0f}s)"
        return None

    def acquire(self, token=None, **owner):
        token = token or secrets.token_hex(8)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        for _ in range(3):
            try:
                self.path.mkdir()
            except FileExistsError:
                current = self.read()
                reason = self.stale_reason(current)
                if reason is None:
                    raise LockBusy(current) from None
                self.break_stale(current, reason)
                continue
            record = dict(owner, token=token, pid=owner.get("pid", os.getpid()),
                          created=self._clock(), created_at=now_iso())
            write_json(self.owner_file, record)
            return token
        raise LockBusy(self.read())

    def break_stale(self, owner, reason):
        owner = owner or {}
        killed = False
        if owner.get("jvm_pid"):
            killed = self._leftover(owner.get("jvm_pid"), owner.get("workdir"))
        print(f"[agent-testserver] breaking stale lock ({reason}); "
              f"leftover JVM killed: {killed}", file=sys.stderr)
        shutil.rmtree(self.path, ignore_errors=True)

    def update(self, token, **fields):
        owner = self.read()
        if not owner or owner.get("token") != token:
            raise HarnessError("lock is not held with this token")
        owner.update(fields)
        write_json(self.owner_file, owner)

    def release(self, token):
        """Remove the lock if ``token`` holds it. Returns whether it was released."""
        owner = self.read()
        if owner is not None and owner.get("token") != token:
            return False
        if not self.path.exists():
            return False
        shutil.rmtree(self.path, ignore_errors=True)
        return not self.path.exists()


def slot_lock_paths(slot1_path=LOCK_PATH, slots=SLOTS):
    """Slot 1 is the historical single lock path; slot N>1 is ``<stem>-slot-N.lock`` beside it."""
    slot1_path = Path(slot1_path)
    stem = slot1_path.name[:-len(".lock")] if slot1_path.name.endswith(".lock") \
        else slot1_path.name
    return [slot1_path] + [slot1_path.with_name(f"{stem}-slot-{n}.lock")
                           for n in range(2, slots + 1)]


def exclusive_lock_path(slot1_path=LOCK_PATH):
    slot1_path = Path(slot1_path)
    stem = slot1_path.name[:-len(".lock")] if slot1_path.name.endswith(".lock") \
        else slot1_path.name
    return slot1_path.with_name(f"{stem}-exclusive.lock")


def redis_port(slot):
    return REDIS_PORT_BASE + slot


class ExclusiveBusy(LockBusy):
    def __init__(self, owner):
        self.owner = owner or {}
        HarnessError.__init__(
            self, f"an --exclusive run holds or waits for every slot: name="
            f"{self.owner.get('name')} pid={self.owner.get('pid')} since "
            f"{self.owner.get('created_at')}. No functional server may start until it ends.")


class SlotsBusy(LockBusy):
    def __init__(self, owners):
        self.owners = owners
        self.owner = owners[0][1] if owners else {}
        held = "; ".join(f"slot {slot}: name={(o or {}).get('name')} pid={(o or {}).get('pid')} "
                         f"since {(o or {}).get('created_at')}" for slot, o in owners)
        HarnessError.__init__(self, f"all {len(owners)} test-server slots are busy ({held}). "
                              "Wait for one to stop or for its max lifetime to pass.")


class SlotLocks:
    """``SLOTS`` MachineLocks plus an exclusive reservation lock.

    A functional run takes the first free slot (refused while an exclusive reservation is
    held). An exclusive run takes the reservation (so no new functional run starts), then
    waits for and takes every slot. All locks of one run share one token; each is broken
    on its own when stale (dead owner pid, or older than its max lifetime + margin).
    """

    def __init__(self, slot1_path=LOCK_PATH, slots=SLOTS, lock=MachineLock,
                 clock=time.monotonic, sleep=time.sleep, poll=EXCLUSIVE_POLL_S, report=None):
        self.paths = slot_lock_paths(slot1_path, slots)
        self.exclusive_path = exclusive_lock_path(slot1_path)
        self._lock, self._clock, self._sleep, self._poll = lock, clock, sleep, poll
        self._report = report or (lambda text: print(f"[agent-testserver] {text}",
                                                     file=sys.stderr, flush=True))

    def slot(self, number):
        return self._lock(self.paths[number - 1])

    def exclusive(self):
        return self._lock(self.exclusive_path)

    def owners(self):
        return [(n, self.slot(n).read()) for n in range(1, len(self.paths) + 1)]

    def _reservation_held(self):
        lock = self.exclusive()
        owner = lock.read()
        if owner is None and not lock.path.exists():
            return None
        reason = lock.stale_reason(owner)
        if reason is None:
            return owner or {}
        lock.break_stale(owner, reason)
        return None

    def acquire_one(self, **owner):
        """(slot number, token, [lock path]) for the first free slot, else LockBusy."""
        reserved = self._reservation_held()
        if reserved is not None:
            raise ExclusiveBusy(reserved)
        token = secrets.token_hex(8)
        busy = []
        for number in range(1, len(self.paths) + 1):
            try:
                self.slot(number).acquire(token=token, slot=number, exclusive=False, **owner)
                return number, token, [self.paths[number - 1]]
            except LockBusy as held:
                busy.append((number, held.owner))
        raise SlotsBusy(busy)

    def acquire_all(self, wait_s=EXCLUSIVE_WAIT_S, max_lifetime_s=DEFAULT_LIFETIME_S, **owner):
        """(token, [lock paths]) once the reservation and every slot are held; waits up to
        ``wait_s``. While waiting, locks already taken carry ``max_lifetime_s + wait_s`` so
        they are not broken as stale; on success they are reset to ``max_lifetime_s``."""
        token = secrets.token_hex(8)
        deadline = self._clock() + wait_s
        waiting_life = max_lifetime_s + wait_s
        held = []
        last_report = None
        try:
            for path, number in [(self.exclusive_path, 0)] + \
                    [(p, n) for n, p in enumerate(self.paths, 1)]:
                lock = self._lock(path)
                while True:
                    try:
                        lock.acquire(token=token, slot=number or None, exclusive=True,
                                     max_lifetime_s=waiting_life, **owner)
                        held.append(path)
                        break
                    except LockBusy as busy:
                        if self._clock() >= deadline:
                            raise HarnessError(
                                f"--exclusive gave up after {wait_s:.0f}s: "
                                f"{'the exclusive reservation' if not number else f'slot {number}'}"
                                f" is still held ({busy})") from None
                        what = (number, (busy.owner or {}).get("name"))
                        if what != last_report:
                            self._report(f"--exclusive waiting for "
                                         f"{'the exclusive reservation' if not number else f'slot {number}'}"
                                         f" (held by {what[1]!r})")
                            last_report = what
                        self._sleep(self._poll)
            for path in held:
                self._lock(path).update(token, max_lifetime_s=max_lifetime_s, created=time.time(),
                                        created_at=now_iso())
            return token, held
        except BaseException:
            for path in reversed(held):
                self._lock(path).release(token)
            raise


# ---------------------------------------------------------------------------------- watchdog

class LifetimeWatchdog:
    """Ends the server at ``seconds``: ``on_expire`` (graceful stop request) first, then
    ``kill`` if ``alive()`` still holds after ``grace``. A thread of its own, so a blocked
    console write or a stuck main loop cannot keep a server past its lifetime."""

    def __init__(self, seconds, on_expire, kill, alive, grace=LIFETIME_GRACE_S,
                 clock=time.monotonic, interval=0.25):
        self.deadline = clock() + seconds
        self._on_expire, self._kill, self._alive = on_expire, kill, alive
        self._grace, self._clock, self._interval = grace, clock, interval
        self._cancel = threading.Event()
        self.expired = False
        self.killed = False
        self._thread = threading.Thread(target=self._run, name="testserver-lifetime",
                                        daemon=True)

    def start(self):
        self._thread.start()
        return self

    def cancel(self):
        self._cancel.set()

    def join(self, timeout=None):
        self._thread.join(timeout)

    def _run(self):
        while not self._cancel.is_set():
            if self._clock() >= self.deadline:
                break
            self._cancel.wait(self._interval)
        if self._cancel.is_set():
            return
        self.expired = True
        self._on_expire()
        kill_at = self._clock() + self._grace
        while not self._cancel.is_set() and self._alive():
            if self._clock() >= kill_at:
                self.killed = True
                self._kill()
                return
            self._cancel.wait(self._interval)


# ---------------------------------------------------------------------------- provenance etc.

def repo_state(repo=REPO):
    """run_baseline.git_state() run in the repository, whatever the caller's cwd."""
    previous = os.getcwd()
    try:
        os.chdir(repo)
        commit, dirty, untracked, build_inputs = run_baseline.git_state()
        tracked = subprocess.check_output(
            ["git", "status", "--porcelain", "--untracked-files=no"], text=True).splitlines()
        commit_time = int(subprocess.check_output(["git", "log", "-1", "--format=%ct"],
                                                  text=True).strip())
        branch = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"],
                                         text=True).strip()
    finally:
        os.chdir(previous)
    return {"head": commit, "branch": branch, "head_commit_time": commit_time, "dirty": dirty,
            "tracked_changes": tracked, "untracked_files": untracked,
            "untracked_build_inputs": build_inputs}


def _sysctl(key, run):
    try:
        out = run(["sysctl", "-n", key], capture_output=True, text=True, timeout=5)
        return out.stdout.strip() or None if out.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def hardware_context(java_version=None, run=subprocess.run, system=platform.system):
    """CPU model, cores, RAM, OS and JDK of the machine the run used (DEVELOPMENT.md:
    a number without its hardware is not evidence). Also the load average at capture,
    because this machine is shared with other agents and the owner's own servers."""
    info = {"platform": platform.platform(), "machine": platform.machine(),
            "logical_cpus": os.cpu_count(), "java_version": java_version}
    if system() == "Darwin":
        info["cpu_model"] = _sysctl("machdep.cpu.brand_string", run)
        for key, field in (("hw.physicalcpu", "physical_cpus"),
                           ("hw.perflevel0.physicalcpu", "performance_cores"),
                           ("hw.perflevel1.physicalcpu", "efficiency_cores"),
                           ("hw.memsize", "ram_bytes")):
            value = _sysctl(key, run)
            info[field] = int(value) if value and value.isdigit() else None
        try:
            info["os_version"] = run(["sw_vers", "-productVersion"], capture_output=True,
                                     text=True, timeout=5).stdout.strip() or None
        except (OSError, subprocess.TimeoutExpired):
            info["os_version"] = None
    else:
        try:
            cpuinfo = Path("/proc/cpuinfo").read_text()
            model = re.search(r"model name\s*:\s*(.+)", cpuinfo)
            info["cpu_model"] = model.group(1).strip() if model else None
            mem = re.search(r"MemTotal:\s*(\d+) kB", Path("/proc/meminfo").read_text())
            info["ram_bytes"] = int(mem.group(1)) * 1024 if mem else None
        except OSError:
            info.setdefault("cpu_model", None)
        info["os_version"] = platform.release()
    if info.get("ram_bytes"):
        info["ram_gib"] = round(info["ram_bytes"] / 1024 ** 3, 1)
    try:
        info["load_average_1_5_15"] = [round(x, 2) for x in os.getloadavg()]
    except OSError:
        info["load_average_1_5_15"] = None
    return info


def java_probe():
    """(java path, `java -version` text); asserts JDK 25 through run_baseline.jdk_tools."""
    java, _jcmd, _jfr, version = run_baseline.jdk_tools()
    return str(java), version


def ensure_jar(jar=JAR, rebuild=False, gradle_locked=GRADLE_LOCKED):
    if jar.is_file() and not rebuild:
        return jar
    if not Path(gradle_locked).is_file():
        raise HarnessError(f"{jar} is missing and {gradle_locked} (the Gradle lock wrapper) "
                           "does not exist; build the jar with slimServerJar first")
    print(f"[agent-testserver] building {jar.name} with slimServerJar ...", flush=True)
    result = subprocess.run(["bash", str(gradle_locked), "slimServerJar"], cwd=REPO)
    if result.returncode != 0 or not jar.is_file():
        raise HarnessError(f"slimServerJar failed (exit {result.returncode})")
    return jar


def jar_provenance(jar, head_commit_time=None):
    stat = jar.stat()
    record = {"source": str(jar), "sha256": run_baseline.sha256(jar), "size": stat.st_size,
              "mtime": int(stat.st_mtime),
              "mtime_iso": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(stat.st_mtime))}
    if head_commit_time is not None:
        # The harness does not know which commit built the jar. A jar older than HEAD's
        # commit was certainly not built from HEAD; a newer one may still not be.
        record["older_than_head_commit"] = stat.st_mtime < head_commit_time
    return record


def server_properties(port, view_distance, simulation_distance, level_type, extra=None):
    props = {"server-ip": "127.0.0.1", "server-port": str(port), "online-mode": "false",
             "enable-rcon": "false", "enable-query": "false", "max-players": "5",
             "view-distance": str(view_distance), "simulation-distance": str(simulation_distance),
             "level-type": level_type, "level-seed": "440044", "spawn-protection": "0",
             "motd": "SourbyCraft agent test server"}
    for key, value in (extra or {}).items():
        if key in PINNED_PROPERTIES:
            raise HarnessError(f"server property {key} is pinned by the harness (isolation)")
        props[key] = str(value)
    return props


def render_properties(props):
    return "".join(f"{key}={value.replace(':', chr(92) + ':')}\n" for key, value in props.items())


GLOBAL_TOML = ("# Agent test server: no plugin downloads, no auto-update.\n"
               "[viaversion]\nauto-provision=false\n[protocollib]\nauto-provision=false\n"
               "[misc.auto_update]\nenabled=false\n")


# ------------------------------------------------------------------------------- supervisor

class Supervisor:
    """Runs detached (``_supervise``). Owns the JVM, its stdin, the watchdog and the lock."""

    def __init__(self, workdir, lock_paths, token):
        self.workdir = Path(workdir)
        self.run_path = self.workdir / "run.json"
        if isinstance(lock_paths, (str, Path)):
            lock_paths = [lock_paths]
        self.locks = [MachineLock(path) for path in lock_paths]
        self.token = token
        self.run = read_json(self.run_path)
        if self.run is None:
            raise HarnessError(f"no run.json in {workdir}")
        self.limits = self.run["limits"]
        self.spool = self.workdir / "console-spool"
        self.proc = None
        self._stop = threading.Event()
        self._stop_reason = None
        self._stop_timeout = STOP_TIMEOUT_S
        self._signals = 0
        self._killed = False

    def save(self, **fields):
        self.run.update(fields)
        write_json(self.run_path, self.run)

    def request_stop(self, reason, timeout):
        if not self._stop.is_set():
            self._stop_reason, self._stop_timeout = reason, timeout
            self._stop.set()

    def _on_signal(self, signum, _frame):
        self._signals += 1
        name = signal.Signals(signum).name
        if self._signals > 1 and self.proc is not None:
            kill_group(self.proc.pid, grace=2)
        self.request_stop(f"signal {name}", self.limits.get("signal_stop_timeout_s",
                                                             SIGNAL_STOP_TIMEOUT_S))

    def _send(self, line):
        try:
            self.proc.stdin.write(line + "\n")
            self.proc.stdin.flush()
            with (self.workdir / "commands.log").open("a") as log:
                log.write(f"{now_iso()} {line}\n")
            return True
        except (BrokenPipeError, OSError, ValueError):
            return False

    def _drain_spool(self):
        for entry in sorted(self.spool.glob("*.cmd")) + sorted(self.spool.glob("*.ctl")):
            try:
                text = entry.read_text()
            finally:
                entry.unlink(missing_ok=True)
            if entry.suffix == ".ctl":
                parts = text.split()
                if parts and parts[0] == "stop":
                    timeout = float(parts[1]) if len(parts) > 1 else STOP_TIMEOUT_S
                    self.request_stop("requested", timeout)
                elif parts and parts[0] == "kill":
                    # Crash test: SIGKILL the whole JVM group now, no console stop, no save.
                    self._stop_reason = "killed on request"
                    self._stop.set()
                    self._killed = True
                    try:
                        os.killpg(self.proc.pid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        self.proc.kill()
                continue
            for line in text.splitlines():
                if line.strip():
                    self._send(line.strip())

    def main(self):
        for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(sig, self._on_signal)
        watchdog = None
        log = None
        failure = None
        try:
            for lock in self.locks:
                lock.update(self.token, pid=os.getpid(), role="supervisor")
            self.spool.mkdir(exist_ok=True)
            log = (self.workdir / "server.log").open("w")
            env = dict(os.environ, TMPDIR=str(self.workdir / "tmp"))
            started = time.monotonic()
            self.proc = subprocess.Popen(self.run["launch"]["command"], cwd=self.workdir,
                                         stdin=subprocess.PIPE, stdout=log,
                                         stderr=subprocess.STDOUT, text=True, env=env,
                                         start_new_session=True)
            for lock in self.locks:
                lock.update(self.token, jvm_pid=self.proc.pid)
            self.save(status="booting", supervisor_pid=os.getpid(), jvm_pid=self.proc.pid,
                      jvm_started_at=now_iso())
            watchdog = LifetimeWatchdog(
                self.limits["max_lifetime_s"],
                on_expire=lambda: self.request_stop("max lifetime reached",
                                                    self.limits["lifetime_grace_s"]),
                kill=lambda: kill_group(self.proc.pid, grace=5),
                alive=lambda: self.proc.poll() is None,
                grace=self.limits["lifetime_grace_s"]).start()
            self._loop(started)
        except BaseException as problem:              # Recorded, then the lock is released.
            failure = f"{type(problem).__name__}: {problem}"
        finally:
            if watchdog is not None:
                watchdog.cancel()
            killed = self._killed
            if self.proc is not None and self.proc.poll() is None:
                kill_group(self.proc.pid)
                killed = True
            code = self.proc.wait() if self.proc is not None else None
            if log is not None:
                log.close()
            text = (self.workdir / "server.log").read_text(errors="replace") \
                if (self.workdir / "server.log").exists() else ""
            reason = self._stop_reason or ("jvm exited by itself" if failure is None else None)
            if watchdog is not None and watchdog.killed:
                killed = True
            fields = dict(status="failed" if failure else "exited", exit_code=code,
                          ended_at=now_iso(), stop_reason=reason, killed=killed,
                          lifetime_expired=bool(watchdog and watchdog.expired),
                          log_tail=tail(text))
            if failure:
                fields["failure"] = failure
            if code == 0 and not killed:
                fields["bootstrap_cache_saved"] = self._save_bootstrap_cache()
            self.save(**fields)
            released = [lock.release(self.token) for lock in self.locks]
            self.save(lock_released=all(released))

    def _loop(self, started):
        startup_timeout = self.limits["startup_timeout_s"]
        stop_deadline = None
        offset, boot_text, ready = 0, "", False
        server_log = self.workdir / "server.log"
        while self.proc.poll() is None:
            if not ready:
                with server_log.open("rb") as handle:
                    handle.seek(offset)
                    chunk = handle.read()
                offset += len(chunk)
                boot_text += chunk.decode(errors="replace")
                clean = strip_ansi(boot_text)
                state, why = run_baseline.startup_state(clean)
                done = DONE_LINE.search(clean)
                summary = run_baseline.BOOT_SUMMARY.search(clean)
                failed_marker = next((m for m in run_baseline.STARTUP_FAILURES if m in clean),
                                     None)
                if failed_marker:
                    self.save(startup_failure=why)
                    self.request_stop(f"startup failure: {failed_marker}",
                                      self.limits["lifetime_grace_s"])
                elif done:
                    ready = True
                    self.save(status="ready", boot_seconds=round(time.monotonic() - started, 1),
                              done_line=done.group(0).strip(), ready_at=now_iso(),
                              boot_summary=summary.group(0) if summary else None,
                              boot_verdict=summary.group(1) if summary else None)
                    boot_text = ""
                elif time.monotonic() - started > startup_timeout:
                    self.save(startup_failure=f"no 'Done (' within {startup_timeout}s")
                    self.request_stop("startup timeout", self.limits["lifetime_grace_s"])
            if not self._stop.is_set():
                self._drain_spool()
            if self._stop.is_set() and stop_deadline is None and not self._killed:
                self.save(status="stopping", stop_requested_at=now_iso(),
                          stop_reason=self._stop_reason)
                self._send("stop")
                stop_deadline = time.monotonic() + self._stop_timeout
            if stop_deadline is not None and time.monotonic() > stop_deadline:
                kill_group(self.proc.pid)
                self._killed = True
                break
            time.sleep(POLL_S)

    def _save_bootstrap_cache(self):
        """After a clean first boot, keep cache/ + libraries/ so later runs seed from them
        (run_baseline.seed_cache) instead of downloading again."""
        target = self.workdir.parent / CACHE_NAME
        if target.exists() or not all((self.workdir / d).is_dir()
                                      for d in run_baseline.BOOTSTRAP_DIRS):
            return False
        staging = self.workdir.parent / f".{CACHE_NAME}.{os.getpid()}"
        try:
            staging.mkdir()
            for name in run_baseline.BOOTSTRAP_DIRS:
                shutil.copytree(self.workdir / name, staging / name)
            os.replace(staging, target)
            return True
        except OSError:
            shutil.rmtree(staging, ignore_errors=True)
            return False


# ------------------------------------------------------------------------------- the harness

class Console:
    """The run's console as run_baseline / verify_persistence helpers expect it:
    ``.text`` (the whole log), ``.send(*commands)`` and ``.hold(seconds)``."""

    def __init__(self, harness, workdir):
        self.harness, self.workdir = harness, Path(workdir)

    @property
    def log(self):
        return self.workdir / "server.log"

    @property
    def text(self):
        return self.log.read_text(errors="replace") if self.log.exists() else ""

    def size(self):
        return self.log.stat().st_size if self.log.exists() else 0

    def since(self, offset):
        if not self.log.exists():
            return ""
        with self.log.open("rb") as handle:
            handle.seek(offset)
            return strip_ansi(handle.read().decode(errors="replace"))

    def send(self, *commands):
        self.harness.spool(self.workdir, commands)

    def hold(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.harness.require_running(self.workdir)
            time.sleep(min(0.5, max(0.0, deadline - time.monotonic())))


class Harness:
    def __init__(self, base=BASE, lock_path=LOCK_PATH, jar=JAR, min_free=MIN_FREE_BYTES,
                 java=java_probe, git=repo_state, hardware=hardware_context,
                 disk_free=free_bytes, gradle_locked=GRADLE_LOCKED, slots=SLOTS,
                 memory=available_memory, min_free_ram=MIN_FREE_RAM_BYTES,
                 gradle_builds=running_gradle_builds, max_gradle_builds=MAX_GRADLE_BUILDS,
                 exclusive_poll=EXCLUSIVE_POLL_S):
        self.base, self.lock_path, self.jar = Path(base), Path(lock_path), Path(jar)
        self.min_free, self._java, self._git = min_free, java, git
        self._hardware, self._disk_free, self._gradle = hardware, disk_free, gradle_locked
        self.slots, self._memory, self.min_free_ram = slots, memory, min_free_ram
        self._gradle_builds, self.max_gradle_builds = gradle_builds, max_gradle_builds
        self.exclusive_poll = exclusive_poll

    # -- paths
    def workdir(self, name):
        return resolve_workdir(self.base, name)

    def existing(self, name):
        workdir = self.workdir(name)
        if not is_harness_dir(workdir):
            raise HarnessError(f"{workdir} is not a harness run directory")
        return workdir

    def lock(self):
        """Slot 1's lock (the historical single lock path)."""
        return MachineLock(self.lock_path)

    def slot_locks(self):
        return SlotLocks(self.lock_path, self.slots, poll=self.exclusive_poll)

    def check_resources(self):
        """Refuse (HarnessError) when RAM or concurrent Gradle builds leave no room for a
        server. Returns what was measured, for run.json. A point sample, not a reservation."""
        free = self._memory()
        builds = self._gradle_builds()
        if free is not None and free < self.min_free_ram:
            raise HarnessError(f"only {free / 1024 ** 3:.2f} GiB RAM available (vm_stat free + "
                               f"inactive + speculative + purgeable); refusing below "
                               f"{self.min_free_ram / 1024 ** 3:.1f} GiB")
        if builds > self.max_gradle_builds:
            raise HarnessError(f"{builds} Gradle builds are running; refusing a server above "
                               f"{self.max_gradle_builds}")
        return {"available_ram_bytes": free,
                "available_ram_gib": round(free / 1024 ** 3, 2) if free is not None else None,
                "min_free_ram_bytes": self.min_free_ram, "gradle_builds_running": builds,
                "max_gradle_builds": self.max_gradle_builds, "measured_at": now_iso()}

    # -- start
    def start(self, name, view_distance=4, simulation_distance=5, heap_mib=2048,
              max_lifetime_s=DEFAULT_LIFETIME_S, level_type="minecraft:normal",
              properties=None, jvm_properties=(), startup_timeout_s=STARTUP_TIMEOUT_S,
              rebuild_jar=False, seed_cache=True, console_probe=True, command=None,
              lifetime_grace_s=LIFETIME_GRACE_S, world_from=None, aurora_toml=None,
              exclusive=False, exclusive_wait_s=EXCLUSIVE_WAIT_S, config_files=None):
        """Create the run, start the supervisor, wait for ``Done (``. Returns run.json.

        ``world_from`` names an earlier, finished run whose world directories are copied in
        first, so a restart (persistence) or post-kill (crash) boot reads that run's saves.
        ``exclusive`` waits (up to ``exclusive_wait_s``) until every slot is free and holds all
        of them for the whole run; otherwise the first free slot is taken or LockBusy raised.
        ``config_files`` maps a run-relative destination (e.g. ``spigot.yml``) to a source file
        copied there before boot.
        """
        if not (60 <= max_lifetime_s <= MAX_LIFETIME_CEILING_S) and command is None:
            raise HarnessError(f"max lifetime must be 60..{MAX_LIFETIME_CEILING_S}s")
        if not (2 <= view_distance <= 32 and 2 <= simulation_distance <= 32):
            raise HarnessError("view/simulation distance must be 2..32")
        workdir = self.workdir(name)
        if workdir.exists():
            raise HarnessError(f"{workdir} already exists; pick a new --name or run cleanup")
        free = self._disk_free(self.base)
        if free < self.min_free:
            raise HarnessError(f"only {free / 1024 ** 3:.1f} GiB free under {self.base}; "
                               f"refusing below {self.min_free / 1024 ** 3:.0f} GiB")
        world_source = self.world_source(world_from) if world_from else None
        aurora = self.aurora_config(aurora_toml, world_source)
        configs = self.config_files(config_files)
        jar = ensure_jar(self.jar, rebuild_jar, self._gradle)
        java, java_version = self._java()
        git = self._git()
        slots = self.slot_locks()
        lock_life = max_lifetime_s + lifetime_grace_s + STOP_TIMEOUT_S
        if exclusive:
            token, paths = slots.acquire_all(exclusive_wait_s, lock_life, name=name,
                                             workdir=str(workdir), role="client")
            slot = {"number": 1, "exclusive": True, "slots_held": self.slots,
                    "redis_port": redis_port(1)}
        else:
            number, token, paths = slots.acquire_one(name=name, workdir=str(workdir),
                                                     role="client", max_lifetime_s=lock_life)
            slot = {"number": number, "exclusive": False, "slots_held": 1,
                    "redis_port": redis_port(number)}
        try:
            resources = self.check_resources()
            busy_ports = {o.get("port") for _n, o in slots.owners() if o and o.get("port")}
            port = free_port(exclude=(25565, 25575, *busy_ports,
                                      *(redis_port(n) for n in range(1, self.slots + 1))))
            for path in paths:
                MachineLock(path).update(token, port=port, redis_port=slot["redis_port"])
            slot["lock_paths"] = [str(p) for p in paths]
            run = self._prepare(workdir, name, jar, java, java_version, git, view_distance,
                                simulation_distance, heap_mib, max_lifetime_s, level_type,
                                properties, jvm_properties, startup_timeout_s, seed_cache,
                                command, lifetime_grace_s, token, world_source, aurora,
                                port=port, slot=slot, resources=resources, configs=configs)
            self._spawn_supervisor(workdir, token, paths)
        except BaseException:
            for path in reversed(paths):
                MachineLock(path).release(token)
            raise
        try:
            run = self.await_ready(workdir, startup_timeout_s + 30)
            if console_probe:
                import verify_persistence     # Reused: "Done (" precedes a working console.
                seconds = verify_persistence.await_console(Console(self, workdir), timeout=90)
                run = read_json(workdir / "run.json")
                run["console_ready_seconds_after_done"] = seconds
                # The supervisor owns run.json; a client-side field goes in its own file.
                write_json(workdir / "client.json", {"console_ready_seconds_after_done": seconds})
        except BaseException:
            self.stop(name, timeout=60)
            raise
        return run

    def _prepare(self, workdir, name, jar, java, java_version, git, view_distance,
                 simulation_distance, heap_mib, max_lifetime_s, level_type, properties,
                 jvm_properties, startup_timeout_s, seed_cache, command, lifetime_grace_s,
                 token, world_source=None, aurora=None, port=None, slot=None, resources=None,
                 configs=()):
        workdir.mkdir(parents=True)
        (workdir / MARKER).write_text("created by scripts/agent_testserver.py\n")
        (workdir / "tmp").mkdir()
        (workdir / "eula.txt").write_text("eula=true\n")
        port = port or free_port()
        props = server_properties(port, view_distance, simulation_distance, level_type,
                                  properties)
        (workdir / "server.properties").write_text(render_properties(props))
        (workdir / "sourbycraft_config").mkdir()
        (workdir / "sourbycraft_config" / "sourbycraft_global_config.toml").write_text(GLOBAL_TOML)
        aurora_toml = None
        if aurora is not None:
            text, origin = aurora
            (workdir / "sourbycraft_config" / "aurora.toml").write_text(text)
            aurora_toml = {"source": origin, "text": text}
        installed = []
        for dest, source, text in configs:
            (workdir / dest).parent.mkdir(parents=True, exist_ok=True)
            (workdir / dest).write_text(text)
            installed.append({"dest": dest, "source": source, "text": text})
        seeded = []
        cache = self.base / CACHE_NAME
        if seed_cache and cache.is_dir():
            seeded = run_baseline.seed_cache(workdir, cache)
        world_from = None
        if world_source is not None:
            source, dirs, source_run = world_source
            for entry in dirs:
                shutil.copytree(source / entry, workdir / entry, symlinks=True)
            world_from = {"name": source.name, "workdir": str(source), "copied": dirs,
                          "source_exit_code": source_run.get("exit_code"),
                          "source_stop_reason": source_run.get("stop_reason"),
                          "source_killed": source_run.get("killed"),
                          "source_git_head": (source_run.get("git") or {}).get("head"),
                          "source_jar_sha256": (source_run.get("jar") or {}).get("sha256")}
        local_jar = workdir / jar.name
        shutil.copy2(jar, local_jar)               # A rebuild cannot change a running server.
        jvm_args = [f"-Xmx{heap_mib}M", "-XX:+UseG1GC", f"-Djava.io.tmpdir={workdir / 'tmp'}"]
        jvm_args += [f"-D{prop}" for prop in jvm_properties]
        launch = command or [java, *jvm_args, "-jar", str(local_jar), "--nogui"]
        hardware = self._hardware(java_version=java_version.splitlines()[0]
                                  if java_version else None)
        write_json(workdir / "hardware.json", hardware)
        run = {"schema": SCHEMA, "name": name, "workdir": str(workdir), "created_at": now_iso(),
               "created": time.time(), "status": "starting", "git": git,
               "jar": dict(jar_provenance(jar, git.get("head_commit_time")),
                           copied_to=str(local_jar)),
               "java": {"path": java, "version": java_version},
               "launch": {"command": [str(part) for part in launch], "heap_mib": heap_mib,
                          "jvm_properties": list(jvm_properties)},
               "server": {"port": port, "properties": props, "seeded_from_cache": seeded},
               "limits": {"max_lifetime_s": max_lifetime_s, "lifetime_grace_s": lifetime_grace_s,
                          "startup_timeout_s": startup_timeout_s,
                          "signal_stop_timeout_s": SIGNAL_STOP_TIMEOUT_S},
               "lock": {"path": (slot or {}).get("lock_paths", [str(self.lock_path)])[0],
                        "paths": (slot or {}).get("lock_paths", [str(self.lock_path)]),
                        "token": token},
               "slot": slot,
               "resources_at_start": resources,
               "world_from": world_from,
               "aurora_toml": aurora_toml,
               "config_files": installed,
               "other_server_jars_running": run_baseline.competing_servers(jar, -1),
               "hardware": hardware}
        write_json(workdir / "run.json", run)
        return run

    @staticmethod
    def aurora_config(path, world_source=None):
        """(text, origin) for sourbycraft_config/aurora.toml, or None for the engine defaults.

        An explicit file wins; otherwise a ``world_from`` source's own aurora.toml is carried over,
        so a world stored through AWF comes back through AWF instead of through region files.
        """
        if path is not None:
            path = Path(path)
            if not path.is_file() or path.stat().st_size > 64 * 1024:
                raise HarnessError(f"aurora.toml {path} is not a file under 64 KiB")
            return path.read_text(), str(path)
        if world_source is not None:
            source = world_source[0] / "sourbycraft_config" / "aurora.toml"
            if source.is_file():
                return source.read_text(), f"world_from {world_source[0].name}"
        return None

    @staticmethod
    def config_files(mapping):
        """[(dest, source, text)] for extra config files (e.g. spigot.yml), validated up front.

        Only server config locations are accepted: a top-level ``*.yml``/``*.yaml`` (bukkit.yml,
        spigot.yml, ...) or a file under ``config/``, ``plugins/`` or ``sourbycraft_config/``.
        The two TOMLs the harness writes stay reserved (``--aurora-toml`` sets aurora.toml), so a
        copy can neither re-enable downloads nor leave ``run.json`` describing a replaced file.
        """
        configs = []
        for dest, source in (mapping or {}).items():
            rel = Path(dest)
            if ("\\" in dest or ":" in dest or rel.is_absolute() or not rel.parts
                    or any(part in ("", ".", "..") for part in rel.parts)):
                raise HarnessError(f"config file destination {dest!r} must be a relative POSIX "
                                   "path inside the run directory")
            top_level_yaml = len(rel.parts) == 1 and rel.suffix in (".yml", ".yaml")
            config_dir = len(rel.parts) > 1 and rel.parts[0] in CONFIG_FILE_DIRS
            if not (top_level_yaml or config_dir) or rel.as_posix() in RESERVED_CONFIG_FILES:
                raise HarnessError(f"config file destination {dest!r} is not an allowed config "
                                   "location (top-level *.yml, config/, plugins/, "
                                   "sourbycraft_config/ except the harness TOMLs)")
            path = Path(source)
            if not path.is_file() or path.stat().st_size > 64 * 1024:
                raise HarnessError(f"config file {path} is not a file under 64 KiB")
            configs.append((rel.as_posix(), str(path), path.read_text()))
        return configs

    def world_source(self, name):
        """(directory, world dirs, run.json) of a finished run to boot over again.

        World dirs are the level-name directory and its ``_nether``/``_the_end`` siblings;
        nothing else (configs, logs, jar, caches) is copied: the new run gets its own.
        """
        source = self.existing(name)
        run = read_json(source / "run.json") or {}
        if run.get("status") not in FINAL_STATES or "lock_released" not in run:
            raise HarnessError(f"run {name!r} is still running (status {run.get('status')}); "
                               "stop or kill it first")
        level = "world"
        props = source / "server.properties"
        if props.is_file():
            for line in props.read_text(errors="replace").splitlines():
                if line.startswith("level-name="):
                    level = line.split("=", 1)[1].strip() or level
        dirs = [d for d in (level, f"{level}_nether", f"{level}_the_end")
                if (source / d).is_dir() and not (source / d).is_symlink()]
        if level not in dirs:
            raise HarnessError(f"run {name!r} has no world directory {level!r} to copy")
        return source, dirs, run

    def _spawn_supervisor(self, workdir, token, lock_paths=None):
        locks = []
        for path in lock_paths or [self.lock_path]:
            locks += ["--lock", str(path)]
        with (workdir / "supervisor.log").open("w") as out:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "_supervise",
                              "--workdir", str(workdir), *locks,
                              "--token", token], cwd=workdir, stdin=subprocess.DEVNULL,
                             stdout=out, stderr=subprocess.STDOUT, start_new_session=True,
                             close_fds=True)

    def await_ready(self, workdir, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            run = read_json(Path(workdir) / "run.json") or {}
            status = run.get("status")
            if status == "ready":
                return run
            if status in FINAL_STATES or status == "stopping":
                raise HarnessError(
                    f"server did not become ready (status {status}, reason "
                    f"{run.get('stop_reason') or run.get('failure')}): "
                    f"{run.get('startup_failure') or ''}\n" + "\n".join(run.get("log_tail") or []))
            time.sleep(0.5)
        raise HarnessError(f"no ready state within {timeout}s")

    # -- console
    def require_running(self, workdir):
        run = read_json(Path(workdir) / "run.json") or {}
        if run.get("status") not in ("booting", "ready"):
            raise HarnessError(f"server is not running (status {run.get('status')})")
        if not pid_alive(run.get("supervisor_pid")):
            raise HarnessError("the supervisor is not running")
        return run

    def spool(self, workdir, commands):
        workdir = Path(workdir)
        self.require_running(workdir)
        lines = []
        for command in commands:
            command = command.strip().lstrip("/")
            if not command or "\n" in command or "\r" in command:
                raise HarnessError(f"one single-line command per entry: {command!r}")
            if command.split()[0].lower() in REFUSED_COMMANDS:
                raise HarnessError(f"{command.split()[0]!r} is lifecycle; use the stop subcommand")
            lines.append(command)
        self._write_spool(workdir, "cmd", "\n".join(lines) + "\n")

    @staticmethod
    def _write_spool(workdir, suffix, text):
        spool = Path(workdir) / "console-spool"
        spool.mkdir(exist_ok=True)
        stem = f"{time.time_ns():020d}-{secrets.token_hex(3)}"
        tmp = spool / f".tmp-{stem}"
        tmp.write_text(text)
        os.replace(tmp, spool / f"{stem}.{suffix}")

    def command(self, name, command, expect=None, timeout=30.0, quiet=1.5):
        """Send one console command; return (matched, the log text it produced)."""
        console = Console(self, self.existing(name))
        before = console.size()
        console.send(command)
        pattern = re.compile(expect) if expect else None
        deadline = time.monotonic() + timeout
        last_len, last_change = 0, time.monotonic()
        while time.monotonic() < deadline:
            produced = console.since(before)
            if pattern is not None and pattern.search(produced):
                return True, produced
            if len(produced) != last_len:
                last_len, last_change = len(produced), time.monotonic()
            elif pattern is None and produced and time.monotonic() - last_change >= quiet:
                break
            time.sleep(0.2)
        produced = console.since(before)
        if pattern is not None:
            return bool(pattern.search(produced)), produced
        return not any(m in produced for m in run_baseline.COMMAND_FAILURES) and bool(produced), \
            produced

    def wait_for(self, name, regex, timeout=60.0, new_only=False):
        console = Console(self, self.existing(name))
        pattern = re.compile(regex, re.MULTILINE)
        start = console.size() if new_only else 0
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            match = pattern.search(console.since(start))
            if match:
                line_start = match.string.rfind("\n", 0, match.start()) + 1
                line_end = match.string.find("\n", match.end())
                return match.string[line_start:line_end if line_end >= 0 else None]
            time.sleep(0.3)
        return None

    # -- stop / status / cleanup
    def stop(self, name, timeout=STOP_TIMEOUT_S):
        """Console ``stop``; the supervisor kills the JVM after ``timeout``. Returns run.json."""
        workdir = self.existing(name)
        run = read_json(workdir / "run.json") or {}
        if run.get("status") not in FINAL_STATES:
            self._write_spool(workdir, "ctl", f"stop {timeout}\n")
            began = time.monotonic()
            while True:
                run = read_json(workdir / "run.json") or {}
                if run.get("status") in FINAL_STATES and "lock_released" in run:
                    break
                elapsed = time.monotonic() - began
                supervisor_gone = run.get("supervisor_pid") is not None \
                    and not pid_alive(run.get("supervisor_pid"))
                never_started = run.get("supervisor_pid") is None and elapsed > 20
                if supervisor_gone or never_started or elapsed > timeout + 45:
                    self._force_stop(workdir, run)
                    break
                time.sleep(0.5)
            run = read_json(workdir / "run.json") or run
        leftover = run.get("jvm_pid")
        run["jvm_still_running"] = bool(leftover and pid_alive(leftover)
                                        and str(workdir) in process_command(leftover))
        return run

    def kill(self, name, timeout=60.0):
        """SIGKILL the server (a crash test): no console stop, nothing saved on the way out."""
        workdir = self.existing(name)
        run = read_json(workdir / "run.json") or {}
        if run.get("status") not in FINAL_STATES:
            self.require_running(workdir)
            self._write_spool(workdir, "ctl", "kill\n")
            began = time.monotonic()
            while True:
                run = read_json(workdir / "run.json") or {}
                if run.get("status") in FINAL_STATES and "lock_released" in run:
                    break
                if not pid_alive(run.get("supervisor_pid")) or time.monotonic() - began > timeout:
                    self._force_stop(workdir, run)
                    break
                time.sleep(0.2)
            run = read_json(workdir / "run.json") or run
        leftover = run.get("jvm_pid")
        run["jvm_still_running"] = bool(leftover and pid_alive(leftover)
                                        and str(workdir) in process_command(leftover))
        return run

    def _force_stop(self, workdir, run):
        """Last resort when the supervisor itself did not finish: kill both, free the lock."""
        for pid in (run.get("supervisor_pid"), run.get("jvm_pid")):
            if pid and pid_alive(pid) and str(workdir) in process_command(pid):
                kill_group(pid, grace=5)
        lock = run.get("lock") or {}
        paths = lock.get("paths") or [lock.get("path") or self.lock_path]
        released = all([MachineLock(path).release(lock.get("token")) for path in paths])
        run.update(status="failed", failure="supervisor did not finish; force-stopped",
                   ended_at=now_iso(), lock_released=released)
        write_json(workdir / "run.json", run)

    def status(self, name=None):
        slots = self.slot_locks()
        listed = []
        for number, owner in slots.owners():
            listed.append({"slot": number, "lock_path": str(slots.paths[number - 1]),
                           "busy": owner is not None or slots.paths[number - 1].exists(),
                           "owner": owner, "redis_port": redis_port(number)})
        exclusive = slots.exclusive().read()
        # "lock"/"lock_path" keep their old meaning (slot 1, the historical single lock).
        result = {"lock": listed[0]["owner"], "lock_path": str(self.lock_path),
                  "slots": listed, "free_slots": [s["slot"] for s in listed if not s["busy"]],
                  "exclusive": exclusive, "exclusive_lock_path": str(slots.exclusive_path),
                  "any_busy": any(s["busy"] for s in listed) or exclusive is not None}
        if name:
            result["run"] = read_json(self.existing(name) / "run.json")
        return result

    def runs(self):
        if not self.base.is_dir():
            return []
        found = []
        for entry in self.base.iterdir():
            if entry.is_dir() and is_harness_dir(entry):
                run = read_json(entry / "run.json") or {}
                found.append((run.get("created", entry.stat().st_mtime), entry, run))
        return sorted(found, key=lambda item: item[0], reverse=True)

    def cleanup(self, keep=5, dry_run=False):
        """Delete harness run directories beyond the newest ``keep``. Never a live run."""
        if keep < 0:
            raise HarnessError("keep must be >= 0")
        held = {(owner or {}).get("workdir") for _n, owner in self.slot_locks().owners()}
        deleted = []
        for _created, entry, run in self.runs()[keep:]:
            live = (run.get("status") not in FINAL_STATES
                    and pid_alive(run.get("supervisor_pid"))) \
                or str(entry) in held
            if live:
                continue
            resolve_workdir(self.base, entry.name)   # Same isolation check as creation.
            if not dry_run:
                shutil.rmtree(entry)
            deleted.append(str(entry))
        return deleted


# ---------------------------------------------------------------------------------- scenario

STEP_KEYS = {
    "command": {"expect", "timeout", "save_as"},
    "wait_for": {"timeout", "new_only"},
    "sleep": set(),
    "perf": {"timeout"},
    "workload": {"duration"},
}
COMMON_KEYS = {"label", "continue_on_failure"}
CONFIG_FILE_DIRS = ("config", "plugins", "sourbycraft_config")
RESERVED_CONFIG_FILES = ("sourbycraft_config/aurora.toml",
                         "sourbycraft_config/sourbycraft_global_config.toml")
SETTINGS_KEYS = {"view_distance": int, "simulation_distance": int, "heap_mib": int,
                 "max_lifetime_s": int, "level_type": str, "startup_timeout_s": int,
                 "properties": dict, "jvm_properties": list}
SAFE_FILE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
PERF_VIEW = re.compile(r"^[a-z]*( [A-Za-z0-9_:./-]+)?$")


class ScenarioError(HarnessError):
    pass


def parse_scenario(data):
    """Validate a scenario document before anything boots. Returns (settings, steps)."""
    if not isinstance(data, dict):
        raise ScenarioError("scenario must be a JSON object")
    unknown = set(data) - {"settings", "steps", "description"}
    if unknown:
        raise ScenarioError(f"unknown top-level keys: {sorted(unknown)}")
    settings = data.get("settings", {})
    if not isinstance(settings, dict):
        raise ScenarioError("settings must be an object")
    for key, value in settings.items():
        if key not in SETTINGS_KEYS:
            raise ScenarioError(f"unknown setting {key!r}")
        if not isinstance(value, SETTINGS_KEYS[key]) or isinstance(value, bool):
            raise ScenarioError(f"setting {key} must be {SETTINGS_KEYS[key].__name__}")
    steps = data.get("steps")
    if not isinstance(steps, list) or not steps:
        raise ScenarioError("steps must be a non-empty list")
    parsed = []
    for index, step in enumerate(steps, 1):
        where = f"step {index}"
        if not isinstance(step, dict):
            raise ScenarioError(f"{where}: must be an object")
        kinds = [key for key in step if key in STEP_KEYS]
        if len(kinds) != 1:
            raise ScenarioError(f"{where}: needs exactly one of {sorted(STEP_KEYS)}")
        kind = kinds[0]
        extra = set(step) - {kind} - STEP_KEYS[kind] - COMMON_KEYS
        if extra:
            raise ScenarioError(f"{where}: unknown keys for {kind}: {sorted(extra)}")
        value = step[kind]
        for key in ("timeout", "duration"):
            if key in step and (not isinstance(step[key], (int, float)) or isinstance(step[key], bool)
                                or not 0 < step[key] <= MAX_LIFETIME_CEILING_S):
                raise ScenarioError(f"{where}: {key} must be a positive number of seconds")
        if kind == "command":
            if not isinstance(value, str) or not value.strip() or "\n" in value:
                raise ScenarioError(f"{where}: command must be one non-empty line")
            if value.strip().lstrip("/").split()[0].lower() in REFUSED_COMMANDS:
                raise ScenarioError(f"{where}: {value!r} is lifecycle; the harness stops the server")
        elif kind == "wait_for":
            if not isinstance(value, str) or not value:
                raise ScenarioError(f"{where}: wait_for must be a regex string")
        elif kind == "sleep":
            if not isinstance(value, (int, float)) or isinstance(value, bool) \
                    or not 0 <= value <= 3600:
                raise ScenarioError(f"{where}: sleep must be 0..3600 seconds")
        elif kind == "perf":
            if not isinstance(value, str) or not PERF_VIEW.match(value):
                raise ScenarioError(f"{where}: perf takes a view name ('' for overview)")
        elif kind == "workload":
            if value not in baseline_workloads.NAMES:
                raise ScenarioError(f"{where}: unknown workload {value!r}; known: "
                                    f"{', '.join(baseline_workloads.NAMES)}")
            if baseline_workloads.build(value).plugins:
                raise ScenarioError(f"{where}: {value} needs a plugin set; not supported here")
        for key in ("expect", "wait_for") if kind in ("command", "wait_for") else ():
            if key in step:
                try:
                    re.compile(step[key])
                except re.error as problem:
                    raise ScenarioError(f"{where}: bad regex in {key}: {problem}") from None
        if "save_as" in step and not (isinstance(step["save_as"], str)
                                      and SAFE_FILE.match(step["save_as"])):
            raise ScenarioError(f"{where}: save_as must be a plain file name")
        if "label" in step and not (isinstance(step["label"], str) and SAFE_FILE.match(step["label"])):
            raise ScenarioError(f"{where}: label must be a plain identifier")
        parsed.append(dict(step, kind=kind, index=index))
    return settings, parsed


def run_workload(harness, workdir, step, heap_mib, port):
    """Apply a baseline_workloads plan to the running server, as run_baseline does, but
    without its measurement: no JFR window, no certification."""
    plan = baseline_workloads.build(step["workload"])
    notes = ["harness run: not a baseline; no JFR window, not certified (docs/BASELINE.md)"]
    if heap_mib < plan.minimum_heap_mib:
        return False, f"{plan.name} needs {plan.minimum_heap_mib} MiB heap; run has {heap_mib}", notes
    if plan.requires_connected_players:
        notes.append("no clients connected: entity activation range keeps mob AI inactive")
    console = Console(harness, workdir)
    before = console.size()
    run_baseline.Server.apply(console, list(plan.setup))
    console.hold(plan.settle_seconds)
    duration = step.get("duration", 0)
    load = None
    if duration and plan.network_clients:
        import baseline_network
        load = baseline_network.Load("127.0.0.1", port, plan.network_clients,
                                     plan.network_rate_per_second)
        load.start()
    steps, began = 0, time.monotonic()
    try:
        while duration and time.monotonic() - began < duration:
            if plan.steady_interval_seconds:
                for entry in plan.steady:
                    console.send(baseline_workloads.window_commands(plan, steps)
                                 if entry == "@window" else entry)
                steps += 1
                console.hold(min(plan.steady_interval_seconds,
                                 max(0, duration - (time.monotonic() - began))))
            else:
                console.hold(min(5, max(0, duration - (time.monotonic() - began))))
    finally:
        if load is not None:
            load.stop()
            notes.append(f"network load report: {json.dumps(load.report(), default=str)[:500]}")
    produced = console.since(before)
    errors = [m for m in run_baseline.COMMAND_FAILURES if m in produced]
    detail = (f"{len(plan.setup)} setup entries, settle {plan.settle_seconds}s, "
              f"steady steps {steps}, fidelity: {'; '.join(plan.fidelity)}")
    if errors:
        return False, f"server rejected setup commands: {errors}", notes + [detail]
    return True, produced, notes + [detail]


def run_scenario(harness, name, scenario_path, overrides=None):
    data = json.loads(Path(scenario_path).read_text())
    settings, steps = parse_scenario(data)
    options = dict(settings)
    options.update({k: v for k, v in (overrides or {}).items() if v is not None})
    jvm_properties = options.pop("jvm_properties", ())
    run = harness.start(name, jvm_properties=tuple(jvm_properties), **options)
    workdir = Path(run["workdir"])
    outputs = workdir / "outputs"
    outputs.mkdir(exist_ok=True)
    shutil.copy2(scenario_path, workdir / "scenario.json")
    results, interrupted = [], None

    def on_term(signum, _frame):
        raise KeyboardInterrupt(f"signal {signal.Signals(signum).name}")
    previous = signal.signal(signal.SIGTERM, on_term)
    try:
        for step in steps:
            started = time.monotonic()
            kind, label = step["kind"], step.get("label")
            out_name = step.get("save_as") or \
                f"{step['index']:02d}-{kind}{'-' + label if label else ''}.txt"
            ok, text, notes = False, "", []
            try:
                if kind == "command":
                    ok, text = harness.command(name, step["command"], step.get("expect"),
                                               step.get("timeout", 30))
                elif kind == "perf":
                    command = ("perf " + step["perf"]).strip()
                    ok, text = harness.command(name, command, None, step.get("timeout", 20))
                elif kind == "wait_for":
                    line = harness.wait_for(name, step["wait_for"], step.get("timeout", 60),
                                            step.get("new_only", False))
                    ok, text = line is not None, line or ""
                elif kind == "sleep":
                    Console(harness, workdir).hold(step["sleep"])
                    ok = True
                elif kind == "workload":
                    ok, text, notes = run_workload(harness, workdir, step,
                                                   run["launch"]["heap_mib"], run["server"]["port"])
            except HarnessError as problem:
                ok, text = False, str(problem)
            (outputs / out_name).write_text(text)
            results.append({"index": step["index"], "kind": kind, "label": label,
                            "spec": {k: v for k, v in step.items() if k not in ("kind", "index")},
                            "ok": ok, "seconds": round(time.monotonic() - started, 2),
                            "output": str(outputs / out_name), "notes": notes})
            print(f"[agent-testserver] step {step['index']} {kind}: {'ok' if ok else 'FAILED'}",
                  flush=True)
            if not ok and not step.get("continue_on_failure"):
                break
    except KeyboardInterrupt as stop:
        interrupted = str(stop) or "interrupted"
    finally:
        signal.signal(signal.SIGTERM, previous)
        final = harness.stop(name)
        summary = {"schema": SCHEMA + "#scenario", "name": name, "scenario": str(scenario_path),
                   "workdir": str(workdir), "steps": results,
                   "all_steps_ok": len(results) == len(steps) and all(r["ok"] for r in results),
                   "interrupted": interrupted, "boot_seconds": final.get("boot_seconds"),
                   "done_line": final.get("done_line"), "exit_code": final.get("exit_code"),
                   "stop_reason": final.get("stop_reason"), "killed": final.get("killed"),
                   "lock_released": final.get("lock_released"),
                   "jvm_still_running": final.get("jvm_still_running"),
                   "hardware": read_json(workdir / "hardware.json"),
                   "git": final.get("git"), "jar": final.get("jar"),
                   "note": "Smoke/functional evidence only; not a certified baseline."}
        write_json(workdir / "scenario-summary.json", summary)
        print(f"[agent-testserver] summary: {workdir / 'scenario-summary.json'}", flush=True)
    return summary


# ----------------------------------------------------------------------------------------- CLI

def _kv(text):
    if "=" not in text:
        raise argparse.ArgumentTypeError(f"expected KEY=VALUE, got {text!r}")
    return text.split("=", 1)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="action", required=True)

    def start_options(p, defaults):
        d = (lambda value: value) if defaults else (lambda _value: None)
        p.add_argument("--view-distance", type=int, default=d(4))
        p.add_argument("--simulation-distance", type=int, default=d(5))
        p.add_argument("--heap-mib", type=int, default=d(2048))
        p.add_argument("--max-lifetime", dest="max_lifetime_s", type=int,
                       default=d(DEFAULT_LIFETIME_S), help="hard limit in seconds (default 1200)")
        p.add_argument("--level-type", default=d("minecraft:normal"))
        p.add_argument("--startup-timeout", dest="startup_timeout_s", type=int,
                       default=d(STARTUP_TIMEOUT_S))
        p.add_argument("--property", action="append", type=_kv, default=[],
                       metavar="KEY=VALUE", help="extra server.properties entry")
        p.add_argument("--jvm-property", action="append", default=[], metavar="KEY=VALUE",
                       help="extra -D system property")
        p.add_argument("--rebuild-jar", action="store_true",
                       help="run slimServerJar (through gradle_locked.sh) before starting")
        p.add_argument("--world-from", metavar="RUN",
                       help="copy this finished run's world dirs in first (restart/crash checks)")
        p.add_argument("--aurora-toml", metavar="FILE",
                       help="install FILE as sourbycraft_config/aurora.toml (e.g. AWF worlds)")
        p.add_argument("--config-file", action="append", type=_kv, default=[],
                       metavar="DEST=FILE",
                       help="copy FILE to DEST in the run dir before boot (e.g. spigot.yml=...)")
        p.add_argument("--no-seed-cache", action="store_true",
                       help=f"do not copy {CACHE_NAME}/ (bootstrap downloads) into the run")
        p.add_argument("--exclusive", action="store_true",
                       help="wait until every slot is free and hold them all (measurements)")
        p.add_argument("--exclusive-wait", dest="exclusive_wait_s", type=float,
                       default=d(EXCLUSIVE_WAIT_S),
                       help=f"seconds --exclusive waits for the slots (default {EXCLUSIVE_WAIT_S})")

    p = sub.add_parser("start", help="create a run and boot it; returns once 'Done (' is seen")
    p.add_argument("--name", required=True)
    start_options(p, True)
    p = sub.add_parser("run", help="start, execute a scenario JSON, stop")
    p.add_argument("--name", required=True)
    p.add_argument("--scenario", required=True, type=Path)
    start_options(p, False)
    p = sub.add_parser("cmd", help="send one console command and print what it logged")
    p.add_argument("--name", required=True)
    p.add_argument("command")
    p.add_argument("--expect", help="regex; exit 1 unless the output matches")
    p.add_argument("--timeout", type=float, default=30)
    p = sub.add_parser("wait-for", help="wait for a log line matching a regex")
    p.add_argument("--name", required=True)
    p.add_argument("regex")
    p.add_argument("--timeout", type=float, default=60)
    p.add_argument("--new", action="store_true", help="only lines logged after this call")
    p = sub.add_parser("stop", help="console stop; kill after --timeout")
    p.add_argument("--name", required=True)
    p.add_argument("--timeout", type=float, default=STOP_TIMEOUT_S)
    p = sub.add_parser("kill", help="SIGKILL the server (crash test): no console stop, no save")
    p.add_argument("--name", required=True)
    p.add_argument("--timeout", type=float, default=60)
    p = sub.add_parser("status")
    p.add_argument("--name")
    p = sub.add_parser("log")
    p.add_argument("--name", required=True)
    p.add_argument("--lines", type=int, default=50)
    p = sub.add_parser("cleanup", help="delete old run directories, keeping the newest N")
    p.add_argument("--keep", type=int, default=5)
    p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("_supervise", help=argparse.SUPPRESS)
    p.add_argument("--workdir", required=True)
    p.add_argument("--lock", required=True, action="append")
    p.add_argument("--token", required=True)
    return parser


def _start_kwargs(args):
    kwargs = {"view_distance": args.view_distance, "simulation_distance": args.simulation_distance,
              "heap_mib": args.heap_mib, "max_lifetime_s": args.max_lifetime_s,
              "level_type": args.level_type, "startup_timeout_s": args.startup_timeout_s,
              "properties": dict(args.property) or None, "world_from": args.world_from,
              "aurora_toml": args.aurora_toml, "exclusive": args.exclusive or None,
              "exclusive_wait_s": args.exclusive_wait_s,
              "config_files": dict(args.config_file) or None}
    return kwargs


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.action == "_supervise":
        Supervisor(args.workdir, args.lock, args.token).main()
        return 0
    harness = Harness()
    try:
        if args.action == "start":
            run = harness.start(args.name, jvm_properties=tuple(args.jvm_property),
                                rebuild_jar=args.rebuild_jar, seed_cache=not args.no_seed_cache,
                                **_start_kwargs(args))
            print(json.dumps({k: run.get(k) for k in ("workdir", "status", "boot_seconds",
                                                      "done_line", "boot_verdict", "slot")}
                             | {"port": run["server"]["port"]}, indent=2))
        elif args.action == "run":
            if args.rebuild_jar:
                ensure_jar(JAR, True)
            overrides = {k: v for k, v in _start_kwargs(args).items() if v is not None}
            overrides["seed_cache"] = not args.no_seed_cache
            if args.jvm_property:
                overrides["jvm_properties"] = list(args.jvm_property)
            summary = run_scenario(harness, args.name, args.scenario, overrides)
            return 0 if summary["all_steps_ok"] and summary["lock_released"] else 1
        elif args.action == "cmd":
            ok, text = harness.command(args.name, args.command, args.expect, args.timeout)
            sys.stdout.write(text)
            return 0 if ok else 1
        elif args.action == "wait-for":
            line = harness.wait_for(args.name, args.regex, args.timeout, args.new)
            print(line if line is not None else f"no match within {args.timeout}s")
            return 0 if line is not None else 1
        elif args.action in ("stop", "kill"):
            run = (harness.stop if args.action == "stop" else harness.kill)(args.name,
                                                                            args.timeout)
            print(json.dumps({k: run.get(k) for k in (
                "status", "exit_code", "stop_reason", "killed", "lock_released",
                "jvm_still_running", "boot_seconds", "done_line")}, indent=2))
            clean = run.get("exit_code") == 0 if args.action == "stop" \
                else run.get("stop_reason") == "killed on request"
            return 0 if clean and run.get("lock_released") \
                and not run.get("jvm_still_running") else 1
        elif args.action == "status":
            print(json.dumps(harness.status(args.name), indent=2, default=str))
        elif args.action == "log":
            print("\n".join(tail(Console(harness, harness.existing(args.name)).text, args.lines)))
        elif args.action == "cleanup":
            for path in harness.cleanup(args.keep, args.dry_run):
                print(("would delete " if args.dry_run else "deleted ") + path)
    except HarnessError as problem:
        print(f"agent_testserver: {problem}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
