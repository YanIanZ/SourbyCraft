"""Unit tests for agent_testserver.py. No JVM: a small Python program stands in for the server."""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest
from unittest import mock

import agent_testserver as ats

FAKE_SERVER = textwrap.dedent("""
    import signal, sys, time
    mode = sys.argv[1] if len(sys.argv) > 1 else "normal"
    if mode == "stubborn":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    print("Starting fake server", flush=True)
    if mode == "no-done":
        time.sleep(600)
    if mode == "bind-fail":
        print("**** FAILED TO BIND TO PORT!", flush=True)
        sys.exit(1)
    print("[Aurora Engine] COMPLETE | 3/3 stages completed without error | 0 failed | 5ms", flush=True)
    print('[12:00:00 INFO]: Done (0.42s)! For help, type "help"', flush=True)
    if mode == "crash":
        time.sleep(2.5)
        print("Exception in server tick loop", flush=True)
        sys.exit(3)
    import threading
    held = []                      # reorder mode: the begin marker waits for the end marker
    for line in sys.stdin:
        line = line.strip()
        if line.startswith("help sc-capture-"):
            # Bukkit HelpCommand on an unknown topic: logs "No help for <topic>" synchronously.
            marker = "[12:00:01 INFO]: No help for " + line[len("help "):]
            if mode == "no-markers":
                continue
            if mode == "no-end" and line.endswith("-end"):
                continue
            if mode == "reorder":
                if line.endswith("-begin"):
                    held.append(marker)
                    continue
                print(marker, flush=True)
                for late in held:
                    print(late, flush=True)
                held.clear()
                continue
            print(marker, flush=True)
        elif line.startswith("slow "):
            threading.Timer(0.8, lambda l=line: print("[12:00:03 INFO]: late reply " + l, flush=True)).start()
        elif line == "time query gametime":
            print("[12:00:01 INFO]: The game time is 42", flush=True)
        elif line == "stop":
            if mode == "stubborn":
                continue
            print("[12:00:02 INFO]: Stopping server", flush=True)
            sys.exit(0)
        else:
            print(f"[12:00:01 INFO]: echo {line}", flush=True)
    time.sleep(600 if mode == "stubborn" else 0)
""")


def no_hardware(java_version=None):
    return {"cpu_model": "fake", "java_version": java_version}


def fake_git():
    return {"head": "0" * 40, "dirty": False, "head_commit_time": 0}


class TempCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name).resolve()
        self.addCleanup(self._tmp.cleanup)


class PathRefusalTest(TempCase):
    def test_gradle_wrapper_default_is_repository_local(self):
        self.assertEqual(ats.GRADLE_LOCKED, ats.REPO / "scripts" / "gradle_locked.sh")
        self.assertTrue(ats.GRADLE_LOCKED.is_relative_to(ats.REPO))
        self.assertTrue(ats.GRADLE_LOCKED.is_file())

    def test_a_plain_name_resolves_inside_the_base(self):
        base = self.root / "base"
        self.assertEqual(ats.resolve_workdir(base, "smoke-1", repo=self.root / "repo",
                                             home=self.root / "home"), base / "smoke-1")

    def test_names_that_escape_or_are_not_identifiers_are_refused(self):
        for name in ("../x", "a/b", "", ".hidden", "x" * 65, "a b", ats.CACHE_NAME, None):
            with self.subTest(name=name), self.assertRaises(ats.HarnessError):
                ats.resolve_workdir(self.root / "base", name, repo=self.root / "repo",
                                    home=self.root / "home")

    def test_a_base_inside_the_repository_is_refused(self):
        repo = self.root / "repo"
        with self.assertRaisesRegex(ats.HarnessError, "repository"):
            ats.resolve_workdir(repo / "build" / "servers", "x", repo=repo, home=self.root / "h")

    def test_a_base_inside_home_is_refused(self):
        home = self.root / "home"
        with self.assertRaisesRegex(ats.HarnessError, "home"):
            ats.resolve_workdir(home / "servers", "x", repo=self.root / "repo", home=home)

    def test_the_owners_server_tree_is_refused_except_the_harness_base(self):
        for base in (Path("/Volumes/Ext/Sourby/TestServer"), Path("/Volumes/Ext/Sourby")):
            with self.subTest(base=base), self.assertRaisesRegex(ats.HarnessError, "owner"):
                ats.resolve_workdir(base, "x", repo=self.root / "repo", home=self.root / "home")
        self.assertEqual(ats.resolve_workdir(ats.BASE, "x", repo=self.root / "repo",
                                             home=self.root / "home"), ats.BASE.resolve() / "x")

    def test_the_real_repository_and_home_are_refused_with_defaults(self):
        with self.assertRaises(ats.HarnessError):
            ats.resolve_workdir(ats.REPO / "run", "x")
        with self.assertRaises(ats.HarnessError):
            ats.resolve_workdir(Path.home() / "servers", "x")

    def test_a_symlink_out_of_the_base_is_refused(self):
        base = self.root / "base"
        base.mkdir()
        (base / "link").symlink_to(self.root / "elsewhere")
        with self.assertRaises(ats.HarnessError):
            ats.resolve_workdir(base, "link", repo=self.root / "repo", home=self.root / "home")


class LockTest(TempCase):
    def lock(self, **kwargs):
        return ats.MachineLock(self.root / "t.lock", **kwargs)

    def test_acquire_writes_an_owner_and_a_second_acquire_is_busy(self):
        first = self.lock()
        token = first.acquire(name="a", workdir="/w/a", max_lifetime_s=100)
        owner = first.read()
        self.assertEqual((owner["token"], owner["name"], owner["pid"]), (token, "a", os.getpid()))
        with self.assertRaises(ats.LockBusy) as busy:
            self.lock().acquire(name="b", max_lifetime_s=100)
        self.assertIn("name=a", str(busy.exception))

    def test_release_needs_the_token(self):
        lock = self.lock()
        token = lock.acquire(name="a", max_lifetime_s=100)
        self.assertFalse(lock.release("not-the-token"))
        self.assertTrue(lock.path.exists())
        self.assertTrue(lock.release(token))
        self.assertFalse(lock.path.exists())
        self.lock().acquire(name="b", max_lifetime_s=100)      # Free again.

    def test_a_dead_owner_is_broken_and_its_leftover_jvm_killed(self):
        killed = []
        lock = self.lock(leftover=lambda pid, workdir: killed.append((pid, workdir)) or True)
        lock.acquire(name="a", workdir="/w/a", pid=999999, max_lifetime_s=100)
        lock.update(lock.read()["token"], jvm_pid=4242)
        alive = self.lock(alive=lambda pid: False,
                          leftover=lambda pid, workdir: killed.append((pid, workdir)) or True)
        token = alive.acquire(name="b", max_lifetime_s=100)
        self.assertEqual(killed, [(4242, "/w/a")])
        self.assertEqual(alive.read()["token"], token)

    def test_a_live_owner_past_its_lifetime_plus_margin_is_broken(self):
        clock = [1000.0]
        held = self.lock(clock=lambda: clock[0], stale_margin=10)
        held.acquire(name="a", max_lifetime_s=50)
        later = self.lock(clock=lambda: clock[0], stale_margin=10, leftover=lambda *_: False)
        clock[0] += 59
        with self.assertRaises(ats.LockBusy):
            later.acquire(name="b", max_lifetime_s=50)
        clock[0] += 2
        later.acquire(name="b", max_lifetime_s=50)
        self.assertEqual(later.read()["name"], "b")

    def test_a_directory_without_owner_is_stale_only_after_the_window(self):
        (self.root / "t.lock").mkdir()
        mtime = (self.root / "t.lock").stat().st_mtime
        early = self.lock(clock=lambda: mtime + 5, unowned_stale=60)
        with self.assertRaises(ats.LockBusy):
            early.acquire(name="x")
        late = self.lock(clock=lambda: mtime + 61, unowned_stale=60)
        late.acquire(name="x")

    def test_update_with_a_foreign_token_is_refused(self):
        lock = self.lock()
        lock.acquire(name="a", max_lifetime_s=100)
        with self.assertRaises(ats.HarnessError):
            lock.update("nope", jvm_pid=1)

    def test_only_one_of_many_concurrent_acquirers_wins(self):
        wins, errors = [], []

        def contend():
            try:
                wins.append(self.lock().acquire(name="c", max_lifetime_s=100))
            except ats.LockBusy:
                errors.append(1)
        threads = [threading.Thread(target=contend) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual((len(wins), len(errors)), (1, 7))


class PortTest(unittest.TestCase):
    def test_returns_a_bindable_localhost_port_outside_the_defaults(self):
        port = ats.free_port()
        self.assertGreater(port, 1024)
        self.assertNotIn(port, (25565, 25575))
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    def test_excluded_ports_are_never_returned(self):
        ports = {ats.free_port() for _ in range(5)}
        self.assertFalse(ports & {25565})
        self.assertGreater(len(ports), 1)


class WatchdogTest(unittest.TestCase):
    def test_expiry_requests_a_stop_then_kills_a_process_that_does_not_exit(self):
        events, alive = [], [True]
        dog = ats.LifetimeWatchdog(0.2, on_expire=lambda: events.append("stop"),
                                   kill=lambda: (events.append("kill"), alive.__setitem__(0, False)),
                                   alive=lambda: alive[0], grace=0.3, interval=0.02).start()
        dog.join(3)
        self.assertEqual(events, ["stop", "kill"])
        self.assertTrue(dog.expired and dog.killed)

    def test_a_process_that_exits_within_the_grace_is_not_killed(self):
        events, alive = [], [True]

        def stop():
            events.append("stop")
            alive[0] = False
        dog = ats.LifetimeWatchdog(0.1, stop, lambda: events.append("kill"),
                                   lambda: alive[0], grace=0.5, interval=0.02).start()
        dog.join(3)
        self.assertEqual(events, ["stop"])
        self.assertFalse(dog.killed)

    def test_cancel_before_the_deadline_does_nothing(self):
        events = []
        dog = ats.LifetimeWatchdog(5, lambda: events.append("stop"), lambda: events.append("kill"),
                                   lambda: True, grace=0.1, interval=0.02).start()
        dog.cancel()
        dog.join(2)
        self.assertEqual(events, [])
        self.assertFalse(dog.expired)

    def test_kills_a_real_process_group(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"],
                                 start_new_session=True)
        self.addCleanup(lambda: child.poll() is None and child.kill())
        dog = ats.LifetimeWatchdog(0.1, lambda: None, lambda: ats.kill_group(child.pid, grace=1),
                                   lambda: child.poll() is None, grace=0.1, interval=0.02).start()
        dog.join(10)
        self.assertIsNotNone(child.wait(timeout=5))


class ScenarioParseTest(unittest.TestCase):
    def test_a_valid_scenario_parses_every_step_kind(self):
        settings, steps = ats.parse_scenario({
            "settings": {"view_distance": 3, "properties": {"difficulty": "peaceful"}},
            "steps": [{"command": "ver", "expect": "Build", "save_as": "ver.txt"},
                      {"wait_for": "Done \\(", "timeout": 10},
                      {"sleep": 1.5}, {"perf": ""}, {"perf": "chunks", "label": "chunks"},
                      {"workload": "idle", "duration": 30, "continue_on_failure": True}]})
        self.assertEqual(settings["view_distance"], 3)
        self.assertEqual([s["kind"] for s in steps],
                         ["command", "wait_for", "sleep", "perf", "perf", "workload"])
        self.assertEqual(steps[4]["index"], 5)

    def test_bracket_false_is_accepted_on_command_and_perf_steps(self):
        _, steps = ats.parse_scenario({"steps": [
            {"command": "perf player x", "bracket": False, "expect": "x"},
            {"perf": "player x", "bracket": False}]})
        self.assertEqual([s["bracket"] for s in steps], [False, False])

    def test_invalid_scenarios_are_refused_before_boot(self):
        bad = [
            [], {"steps": []}, {"steps": "x"}, {"steps": [{}]}, {"oops": 1, "steps": [{"sleep": 1}]},
            {"steps": [{"command": "ver", "sleep": 1}]},             # two kinds
            {"steps": [{"command": "ver", "bogus": 1}]},             # unknown key
            {"steps": [{"command": "stop"}]}, {"steps": [{"command": "/restart"}]},
            {"steps": [{"command": "say a\nstop"}]},
            {"steps": [{"command": "ver", "expect": "("}]},          # bad regex
            {"steps": [{"wait_for": "["}]},
            {"steps": [{"sleep": -1}]}, {"steps": [{"sleep": True}]}, {"steps": [{"sleep": 4000}]},
            {"steps": [{"perf": "Chunks; stop"}]},
            {"steps": [{"workload": "no-such"}]},
            {"steps": [{"workload": "plugins-10"}]},                 # needs a plugin set
            {"steps": [{"command": "ver", "timeout": 0}]},
            {"steps": [{"command": "ver", "save_as": "../x"}]},
            {"steps": [{"command": "ver", "bracket": "no"}]},        # must be a boolean
            {"steps": [{"sleep": 1, "bracket": False}]},             # only command/perf
            {"steps": [{"wait_for": "x", "bracket": False}]},
            {"settings": {"heap_mib": "big"}, "steps": [{"sleep": 1}]},
            {"settings": {"unknown": 1}, "steps": [{"sleep": 1}]},
        ]
        for data in bad:
            with self.subTest(data=data), self.assertRaises(ats.ScenarioError):
                ats.parse_scenario(data)


class PropertiesAndContextTest(TempCase):
    def test_properties_are_local_offline_and_small_by_default(self):
        props = ats.server_properties(30001, 4, 5, "minecraft:normal")
        self.assertEqual((props["server-ip"], props["online-mode"], props["server-port"]),
                         ("127.0.0.1", "false", "30001"))
        self.assertEqual((props["view-distance"], props["simulation-distance"]), ("4", "5"))
        self.assertIn("level-type=minecraft\\:normal\n", ats.render_properties(props))

    def test_isolation_properties_cannot_be_overridden(self):
        for key in ("online-mode", "server-ip", "server-port", "enable-rcon"):
            with self.subTest(key=key), self.assertRaises(ats.HarnessError):
                ats.server_properties(1, 4, 5, "minecraft:flat", {key: "x"})
        self.assertEqual(ats.server_properties(1, 4, 5, "f", {"difficulty": "peaceful"})
                         ["difficulty"], "peaceful")

    def test_hardware_context_reads_sysctl_on_darwin(self):
        answers = {"machdep.cpu.brand_string": "Apple M2", "hw.physicalcpu": "8",
                   "hw.perflevel0.physicalcpu": "4", "hw.perflevel1.physicalcpu": "4",
                   "hw.memsize": str(16 * 1024 ** 3)}

        def run(command, **_kwargs):
            out = answers.get(command[-1], "26.0" if command[0] == "sw_vers" else "")
            return subprocess.CompletedProcess(command, 0, stdout=out + "\n", stderr="")
        info = ats.hardware_context("openjdk 25.0.2", run=run, system=lambda: "Darwin")
        self.assertEqual((info["cpu_model"], info["physical_cpus"], info["performance_cores"],
                          info["ram_gib"], info["os_version"], info["java_version"]),
                         ("Apple M2", 8, 4, 16.0, "26.0", "openjdk 25.0.2"))

    def test_jar_provenance_flags_a_jar_older_than_head(self):
        jar = self.root / "x.jar"
        jar.write_bytes(b"jar")
        os.utime(jar, (1000, 1000))
        self.assertTrue(ats.jar_provenance(jar, head_commit_time=2000)["older_than_head_commit"])
        self.assertFalse(ats.jar_provenance(jar, head_commit_time=500)["older_than_head_commit"])
        self.assertEqual(len(ats.jar_provenance(jar)["sha256"]), 64)


class HarnessCase(TempCase):
    """End-to-end through the real supervisor subprocess, with the fake server."""

    def setUp(self):
        super().setUp()
        self.fake = self.root / "fake_server.py"
        self.fake.write_text(FAKE_SERVER)
        self.jar = self.root / "SourbyCraft-slim.jar"
        self.jar.write_bytes(b"not really a jar")
        self.harness = ats.Harness(base=self.root / "servers", lock_path=self.root / "ts.lock",
                                   jar=self.jar, min_free=0,
                                   java=lambda: (sys.executable, "fake 25"), git=fake_git,
                                   hardware=no_hardware, memory=lambda: 8 * 1024 ** 3,
                                   gradle_builds=lambda: 0, exclusive_poll=0.05)
        self.addCleanup(self._kill_leftovers)

    def _kill_leftovers(self):
        for run in self.root.glob("servers/*/run.json"):
            data = json.loads(run.read_text())
            for pid in (data.get("supervisor_pid"), data.get("jvm_pid")):
                if pid and ats.pid_alive(pid):
                    ats.kill_group(pid, grace=1)

    def start(self, name="t", mode="normal", **kwargs):
        kwargs.setdefault("max_lifetime_s", 120)
        kwargs.setdefault("startup_timeout_s", 30)
        return self.harness.start(name, command=[sys.executable, str(self.fake), mode], **kwargs)

    def run_json(self, name="t"):
        return json.loads((self.root / "servers" / name / "run.json").read_text())


class LifecycleTest(HarnessCase):
    def test_start_command_wait_for_stop_and_run_json(self):
        run = self.start()
        workdir = Path(run["workdir"])
        self.assertEqual(run["status"], "ready")
        self.assertIn("Done (0.42s)!", run["done_line"])
        self.assertEqual(run["boot_verdict"], "COMPLETE")
        self.assertTrue((workdir / ats.MARKER).is_file())
        self.assertEqual((workdir / "eula.txt").read_text(), "eula=true\n")
        self.assertEqual((workdir / self.jar.name).read_bytes(), self.jar.read_bytes())
        self.assertNotEqual(workdir / self.jar.name, self.jar)
        self.assertEqual(self.harness.lock().read()["role"], "supervisor")

        ok, text = self.harness.command("t", "/say hello", expect="echo say hello", timeout=10)
        self.assertTrue(ok, text)
        self.assertEqual(self.harness.wait_for("t", r"echo say hello", timeout=5).split("]: ")[-1],
                         "echo say hello")
        self.assertIsNone(self.harness.wait_for("t", "never-logged", timeout=0.5))
        with self.assertRaises(ats.HarnessError):
            self.harness.command("t", "stop")

        final = self.harness.stop("t", timeout=10)
        self.assertEqual((final["status"], final["exit_code"], final["stop_reason"]),
                         ("exited", 0, "requested"))
        self.assertTrue(final["lock_released"])
        self.assertFalse(final["jvm_still_running"])
        self.assertFalse(self.harness.lock_path.exists())
        self.assertIn("Stopping server", "\n".join(final["log_tail"]))
        for key in ("git", "jar", "hardware", "boot_seconds", "ended_at", "launch", "limits"):
            self.assertIn(key, self.run_json())
        self.assertFalse(ats.pid_alive(final["jvm_pid"]))
        self.assertIn("say hello", (workdir / "commands.log").read_text())

    def test_a_third_start_is_refused_while_two_run(self):
        self.start("a")
        self.start("b")
        with self.assertRaises(ats.SlotsBusy) as busy:
            self.start("c")
        self.assertIn("slot 1: name=a", str(busy.exception))
        self.assertIn("slot 2: name=b", str(busy.exception))
        self.assertFalse((self.root / "servers" / "c").exists())
        self.harness.stop("a", timeout=10)
        self.assertEqual(self.start("c")["slot"]["number"], 1)
        self.harness.stop("b", timeout=10)
        self.harness.stop("c", timeout=10)

    def test_an_existing_workdir_is_never_reused(self):
        self.start("a")
        self.harness.stop("a", timeout=10)
        with self.assertRaisesRegex(ats.HarnessError, "already exists"):
            self.start("a")

    def test_low_disk_space_is_refused(self):
        self.harness.min_free = 20 * 1024 ** 3
        self.harness._disk_free = lambda _path: 19 * 1024 ** 3
        with self.assertRaisesRegex(ats.HarnessError, "GiB free"):
            self.start()
        self.assertFalse(self.harness.lock_path.exists())

    def test_a_server_that_ignores_stop_is_killed_after_the_timeout(self):
        self.start(mode="stubborn")
        final = self.harness.stop("t", timeout=1)
        self.assertEqual(final["status"], "exited")
        self.assertTrue(final["killed"])
        self.assertTrue(final["lock_released"])
        self.assertFalse(final["jvm_still_running"])

    def test_the_lifetime_watchdog_ends_a_run_and_frees_the_lock(self):
        self.start(mode="stubborn", max_lifetime_s=2, lifetime_grace_s=1)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and "lock_released" not in self.run_json():
            time.sleep(0.2)
        run = self.run_json()
        self.assertEqual(run["stop_reason"], "max lifetime reached")
        self.assertTrue(run["lifetime_expired"] and run["killed"] and run["lock_released"])
        self.assertFalse(ats.pid_alive(run["jvm_pid"]))

    def test_a_crashing_server_is_recorded_and_frees_the_lock(self):
        run = self.start(mode="crash", console_probe=False)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and "lock_released" not in self.run_json():
            time.sleep(0.2)
        run = self.run_json()
        self.assertEqual((run["status"], run["exit_code"], run["stop_reason"]),
                         ("exited", 3, "jvm exited by itself"))
        self.assertIn("Exception in server tick loop", run["log_tail"])
        self.assertTrue(run["lock_released"])

    def test_startup_failure_marker_fails_the_start_and_frees_the_lock(self):
        with self.assertRaisesRegex(ats.HarnessError, "did not become ready"):
            self.start(mode="bind-fail")
        self.assertFalse(self.harness.lock_path.exists())
        self.assertNotEqual(self.run_json()["exit_code"], 0)

    def test_startup_timeout_stops_the_server(self):
        with self.assertRaises(ats.HarnessError):
            self.start(mode="no-done", startup_timeout_s=1, lifetime_grace_s=1)
        run = self.run_json()
        self.assertEqual(run["stop_reason"], "startup timeout")
        self.assertTrue(run["lock_released"])
        self.assertFalse(ats.pid_alive(run["jvm_pid"]))

    def test_sigterm_to_the_supervisor_stops_cleanly_and_frees_the_lock(self):
        run = self.start()
        os.kill(run["supervisor_pid"], signal.SIGTERM)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and "lock_released" not in self.run_json():
            time.sleep(0.2)
        run = self.run_json()
        self.assertEqual((run["stop_reason"], run["exit_code"]), ("signal SIGTERM", 0))
        self.assertTrue(run["lock_released"])

    def test_a_killed_supervisor_leaves_a_lock_the_next_start_breaks(self):
        run = self.start("a")
        os.kill(run["supervisor_pid"], signal.SIGKILL)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and ats.pid_alive(run["supervisor_pid"]):
            time.sleep(0.1)
        self.assertTrue(self.harness.lock_path.exists())
        jvm = run["jvm_pid"]
        self.start("b")                         # Breaks the stale lock, kills a's JVM.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and ats.pid_alive(jvm):
            time.sleep(0.1)
        self.assertFalse(ats.pid_alive(jvm))
        self.harness.stop("b", timeout=10)


class BracketRegionTest(unittest.TestCase):
    LOG = textwrap.dedent("""\
        [12:00:00 INFO]: Successfully filled 9 block(s)
        [12:00:01 INFO]: No help for sc-capture-aa-begin
        [12:00:01 INFO]: Successfully filled 4 block(s)
        [12:00:01 INFO]: Found 3 entities
        [12:00:01 INFO]: No help for sc-capture-aa-end
        [12:00:02 INFO]: Successfully filled 7 block(s)
        """)
    BEGIN, END = "sc-capture-aa-begin", "sc-capture-aa-end"

    def test_only_lines_between_the_markers_belong_to_the_command(self):
        state, region = ats.capture_state(self.LOG, self.BEGIN, self.END)
        self.assertEqual(state, "closed")
        self.assertEqual(region.splitlines(), ["[12:00:01 INFO]: Successfully filled 4 block(s)",
                                               "[12:00:01 INFO]: Found 3 entities"])

    def test_region_thread_prefix_is_accepted_but_late_output_is_excluded(self):
        prefix = "[12:00:01] [Folia Region Scheduler Thread #1/INFO]: "
        log = (f"{self.BEGIN}\n{prefix}inside reply\n{self.END}\n"
               f"{prefix}late reply\n")
        state, output = ats.capture_state(log, self.BEGIN, self.END)
        self.assertEqual(state, "closed")
        self.assertIn("inside reply", output)
        self.assertNotIn("late reply", output)
        self.assertTrue(ats.Harness._finish_capture(output, None)[0])
        empty = f"{self.BEGIN}\n{self.END}\n{prefix}late reply\n"
        state, output = ats.capture_state(empty, self.BEGIN, self.END)
        self.assertEqual(state, "closed")
        self.assertFalse(ats.Harness._finish_capture(output, None)[0])

    def test_open_absent_and_empty_regions(self):
        cut = self.LOG.split(self.END)[0]
        state, region = ats.capture_state(cut, self.BEGIN, self.END)
        self.assertEqual(state, "open")
        self.assertIn("Found 3 entities", region)
        self.assertEqual(ats.capture_state("nothing yet\n", self.BEGIN, self.END), ("absent", ""))
        self.assertEqual(ats.capture_state("sc-capture-bb-begin\nsc-capture-bb-end\n",
                                           "sc-capture-bb-begin", "sc-capture-bb-end"),
                         ("closed", ""))

    def test_an_end_marker_logged_before_the_begin_marker_is_disordered(self):
        log = f"x\n{self.END}\nreply\n{self.BEGIN}\n"
        self.assertEqual(ats.capture_state(log, self.BEGIN, self.END)[0], "disordered")
        # end only (begin not logged yet) is not disordered, just absent
        self.assertEqual(ats.capture_state(f"{self.END}\n", self.BEGIN, self.END)[0], "disordered")

    def test_markers_carry_more_than_32_random_bits_and_never_repeat(self):
        seen = {ats.capture_markers()[0] for _ in range(50)}
        self.assertEqual(len(seen), 50)
        begin, end = ats.capture_markers()
        token = begin[len(ats.CAPTURE_MARKER):-len("-begin")]
        self.assertGreaterEqual(len(token), 16)          # hex digits: >= 64 bits
        self.assertTrue(begin.endswith("-begin") and end.endswith("-end"))
        self.assertNotIn(begin, end)


class ConcurrentCaptureTest(HarnessCase):
    def test_a_concurrent_command_loop_does_not_leak_into_a_captured_command(self):
        self.start()
        stop = threading.Event()
        sent = []

        def churn():
            while not stop.is_set():
                self.harness.spool(self.root / "servers" / "t", [f"fill churn{len(sent)}"])
                sent.append(1)
                time.sleep(0.005)
        worker = threading.Thread(target=churn, daemon=True)
        worker.start()
        try:
            outputs = [self.harness.command("t", f"count {i}", timeout=15)[1] for i in range(5)]
        finally:
            stop.set()
            worker.join(5)
        self.assertGreater(len(sent), 10, "the churn loop must actually have run")
        for index, text in enumerate(outputs):
            self.assertIn(f"echo count {index}", text)
            self.assertNotIn("churn", text)
            self.assertNotIn(ats.CAPTURE_MARKER, text)
        self.harness.stop("t", timeout=10)

    def test_a_missing_end_marker_never_counts_as_success(self):
        self.start(mode="no-end")
        ok, text = self.harness.command("t", "time query gametime", timeout=2)
        self.assertFalse(ok)
        self.assertIn("delivery uncertain", text)
        self.assertIn("end marker", text)
        # even when the partial output would satisfy --expect
        ok, text = self.harness.command("t", "time query gametime", expect="game time is 42",
                                        timeout=2)
        self.assertFalse(ok)
        self.assertIn("delivery uncertain", text)
        self.harness.stop("t", timeout=10)

    def test_missing_markers_are_reported_as_uncertain_not_as_not_run(self):
        self.start(mode="no-markers")
        ok, text = self.harness.command("t", "time query gametime", timeout=2)
        self.assertFalse(ok)
        self.assertIn("delivery uncertain", text)
        self.assertIn("begin marker", text)
        self.assertNotIn("did not run", text)
        self.harness.stop("t", timeout=10)

    def test_markers_logged_out_of_order_are_refused_not_trusted(self):
        self.start(mode="reorder")
        ok, text = self.harness.command("t", "time query gametime", timeout=5)
        self.assertFalse(ok)
        self.assertIn("out of order", text)
        self.harness.stop("t", timeout=10)

    def test_a_delayed_reply_is_invisible_when_bracketed_and_visible_raw(self):
        self.start()
        ok, text = self.harness.command("t", "slow one", expect="late reply", timeout=2)
        self.assertFalse(ok)                      # documented limit: replies after the end marker
        ok, text = self.harness.command("t", "slow two", expect="late reply slow two",
                                        timeout=5, bracket=False)
        self.assertTrue(ok, text)
        self.harness.stop("t", timeout=10)

    def test_raw_capture_keeps_the_old_every_new_line_behaviour(self):
        self.start()
        ok, text = self.harness.command("t", "time query gametime", expect="game time is 42",
                                        timeout=10, bracket=False)
        self.assertTrue(ok, text)
        self.assertNotIn(ats.CAPTURE_MARKER, self.harness.command(
            "t", "time query gametime", expect="game time", timeout=10, bracket=False)[1])
        self.harness.stop("t", timeout=10)

    def test_sequential_use_with_and_without_expect(self):
        self.start()
        ok, text = self.harness.command("t", "time query gametime", timeout=10)
        self.assertTrue(ok, text)
        self.assertEqual(text.splitlines(), ["[12:00:01 INFO]: The game time is 42"])
        ok, text = self.harness.command("t", "time query gametime", expect="is 42", timeout=10)
        self.assertTrue(ok, text)
        self.harness.stop("t", timeout=10)


class SlotTest(HarnessCase):
    """Two functional slots, the --exclusive mode, old-lock compatibility, resource checks."""

    def slot_paths(self):
        return ats.slot_lock_paths(self.harness.lock_path, 2)

    def wait_until(self, predicate, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return True
            time.sleep(0.05)
        return False

    def test_slot_paths_keep_the_historical_lock_as_slot_1(self):
        self.assertEqual(ats.slot_lock_paths(ats.LOCK_PATH, 2),
                         [Path("/private/tmp/claude-501/sourby-testserver.lock"),
                          Path("/private/tmp/claude-501/sourby-testserver-slot-2.lock")])
        self.assertEqual(ats.exclusive_lock_path(ats.LOCK_PATH),
                         Path("/private/tmp/claude-501/sourby-testserver-exclusive.lock"))
        self.assertEqual((ats.redis_port(1), ats.redis_port(2)), (6401, 6402))

    def test_two_runs_get_their_own_slot_lock_port_workdir_and_redis_port(self):
        a, b = self.start("a"), self.start("b")
        self.assertEqual((a["slot"]["number"], b["slot"]["number"]), (1, 2))
        self.assertEqual((a["slot"]["redis_port"], b["slot"]["redis_port"]), (6401, 6402))
        self.assertNotEqual(a["server"]["port"], b["server"]["port"])
        self.assertNotEqual(a["workdir"], b["workdir"])
        self.assertEqual(a["lock"]["paths"], [str(self.slot_paths()[0])])
        self.assertEqual(b["lock"]["paths"], [str(self.slot_paths()[1])])
        owners = [ats.MachineLock(p).read() for p in self.slot_paths()]
        self.assertEqual([o["name"] for o in owners], ["a", "b"])
        self.assertEqual([o["role"] for o in owners], ["supervisor", "supervisor"])
        self.assertEqual(owners[1]["port"], b["server"]["port"])
        status = self.harness.status()
        self.assertEqual([s["owner"]["name"] for s in status["slots"]], ["a", "b"])
        self.assertEqual((status["free_slots"], status["lock"]["name"]), ([], "a"))
        self.assertEqual(self.harness.stop("b", timeout=10)["lock_released"], True)
        self.assertFalse(self.slot_paths()[1].exists())
        self.assertTrue(self.slot_paths()[0].exists())     # Only b's slot was freed.
        self.assertEqual(self.harness.status()["free_slots"], [2])
        self.harness.stop("a", timeout=10)
        self.assertEqual(self.harness.status()["free_slots"], [1, 2])

    def test_a_stale_slot_is_broken_on_its_own(self):
        a = self.start("a")
        self.start("b")
        os.kill(self.run_json("b")["supervisor_pid"], signal.SIGKILL)
        self.assertTrue(self.wait_until(lambda: not ats.pid_alive(self.run_json("b")["supervisor_pid"])))
        c = self.start("c")                     # Breaks slot 2 only; a keeps slot 1.
        self.assertEqual(c["slot"]["number"], 2)
        self.assertEqual(ats.MachineLock(self.slot_paths()[0]).read()["name"], "a")
        self.assertTrue(self.wait_until(lambda: not ats.pid_alive(self.run_json("b")["jvm_pid"])))
        self.assertTrue(ats.pid_alive(a["jvm_pid"]))
        self.harness.stop("a", timeout=10)
        self.harness.stop("c", timeout=10)

    def test_an_old_single_lock_still_means_slot_1_busy(self):
        # A supervisor from before slots existed holds only the historical lock path.
        old = ats.MachineLock(self.harness.lock_path)
        old.acquire(name="old-run", workdir="/w/old", role="supervisor", max_lifetime_s=600)
        run = self.start("new")
        self.assertEqual(run["slot"]["number"], 2)
        self.assertEqual(old.read()["name"], "old-run")
        with self.assertRaises(ats.SlotsBusy):
            self.start("third")
        self.assertEqual(self.harness.status()["slots"][0]["owner"]["name"], "old-run")
        self.harness.stop("new", timeout=10)
        self.assertTrue(self.harness.lock_path.exists())    # Never touched by the new run.

    def test_old_cli_supervisor_arguments_still_parse(self):
        args = ats.build_parser().parse_args(["_supervise", "--workdir", "/w", "--lock", "/l",
                                              "--token", "t"])
        self.assertEqual(args.lock, ["/l"])

    def test_exclusive_waits_for_every_slot_and_blocks_new_starts(self):
        self.start("a")
        self.start("b")
        result = {}

        def exclusive():
            try:
                result["run"] = self.start("x", exclusive=True, exclusive_wait_s=60)
            except BaseException as problem:    # noqa: BLE001  (reported to the test thread)
                result["error"] = problem
        waiter = threading.Thread(target=exclusive)
        waiter.start()
        self.addCleanup(waiter.join, 30)
        reservation = ats.exclusive_lock_path(self.harness.lock_path)
        self.assertTrue(self.wait_until(reservation.exists))
        self.harness.stop("a", timeout=10)
        # Slot 1 is free but reserved: the waiter takes it, and nobody else may start.
        self.assertTrue(self.wait_until(
            lambda: (ats.MachineLock(self.slot_paths()[0]).read() or {}).get("name") == "x"))
        with self.assertRaises(ats.ExclusiveBusy):
            self.start("c")
        self.assertFalse((self.root / "servers" / "c").exists())
        self.assertNotIn("run", result)               # Still waiting for b.
        self.assertFalse((self.root / "servers" / "x" / "run.json").exists())
        self.harness.stop("b", timeout=10)
        waiter.join(30)
        self.assertNotIn("error", result)
        run = result["run"]
        self.assertEqual(run["slot"], dict(run["slot"], exclusive=True, slots_held=2,
                                           redis_port=6401))
        held = [ats.MachineLock(p).read() for p in [reservation, *self.slot_paths()]]
        self.assertEqual([h["name"] for h in held], ["x", "x", "x"])
        self.assertEqual([h["role"] for h in held], ["supervisor"] * 3)
        self.assertEqual(held[1]["max_lifetime_s"], held[2]["max_lifetime_s"])
        self.assertLess(held[1]["max_lifetime_s"], 60 + 120 + 30 + ats.STOP_TIMEOUT_S + 1)
        with self.assertRaises(ats.ExclusiveBusy):
            self.start("d")
        self.assertTrue(self.harness.status()["any_busy"])
        final = self.harness.stop("x", timeout=10)
        self.assertTrue(final["lock_released"])
        for path in [reservation, *self.slot_paths()]:
            self.assertFalse(path.exists(), path)
        self.start("e")
        self.harness.stop("e", timeout=10)

    def test_exclusive_gives_up_after_its_wait_and_releases_what_it_took(self):
        self.start("a")
        with self.assertRaisesRegex(ats.HarnessError, "gave up"):
            self.start("x", exclusive=True, exclusive_wait_s=0.3)
        self.assertFalse(ats.exclusive_lock_path(self.harness.lock_path).exists())
        self.assertFalse(self.slot_paths()[1].exists())
        self.assertEqual(ats.MachineLock(self.slot_paths()[0]).read()["name"], "a")
        self.assertFalse((self.root / "servers" / "x").exists())
        self.harness.stop("a", timeout=10)

    def test_a_dead_exclusive_waiter_does_not_block_forever(self):
        ats.MachineLock(ats.exclusive_lock_path(self.harness.lock_path)).acquire(
            name="ghost", pid=999999, max_lifetime_s=600)
        run = self.start("a")                   # Dead owner pid: the reservation is broken.
        self.assertEqual(run["slot"]["number"], 1)
        self.assertFalse(ats.exclusive_lock_path(self.harness.lock_path).exists())
        self.harness.stop("a", timeout=10)

    def test_low_ram_is_refused_and_frees_the_slot(self):
        self.harness._memory = lambda: int(2.9 * 1024 ** 3)
        with self.assertRaisesRegex(ats.HarnessError, r"only 2\.90 GiB RAM available"):
            self.start()
        self.assertFalse(self.harness.lock_path.exists())
        self.assertFalse((self.root / "servers" / "t").exists())
        self.harness._memory = lambda: int(3.1 * 1024 ** 3)
        run = self.start()
        self.assertEqual(run["resources_at_start"]["available_ram_gib"], 3.1)
        self.harness.stop("t", timeout=10)

    def test_more_than_two_gradle_builds_are_refused(self):
        self.harness._gradle_builds = lambda: 3
        with self.assertRaisesRegex(ats.HarnessError, "3 Gradle builds"):
            self.start()
        self.assertFalse(self.harness.lock_path.exists())
        self.harness._gradle_builds = lambda: 2
        self.assertEqual(self.start()["resources_at_start"]["gradle_builds_running"], 2)
        self.harness.stop("t", timeout=10)


class ResourceProbeTest(unittest.TestCase):
    VM_STAT = textwrap.dedent("""\
        Mach Virtual Memory Statistics: (page size of 16384 bytes)
        Pages free:                                    11200.
        Pages active:                                 187566.
        Pages inactive:                               184601.
        Pages speculative:                              2257.
        Pages throttled:                                   0.
        Pages wired down:                             145534.
        Pages purgeable:                                2566.
        File-backed pages:                            111954.
        """)

    def test_vm_stat_counts_free_inactive_speculative_and_purgeable_pages(self):
        self.assertEqual(ats.parse_vm_stat(self.VM_STAT), (11200 + 184601 + 2257 + 2566) * 16384)
        self.assertIsNone(ats.parse_vm_stat("garbage"))

    def test_available_memory_uses_vm_stat_on_darwin(self):
        def run(command, **_kwargs):
            self.assertEqual(command, ["vm_stat"])
            return subprocess.CompletedProcess(command, 0, stdout=self.VM_STAT, stderr="")
        self.assertEqual(ats.available_memory(run=run, system=lambda: "Darwin"),
                         ats.parse_vm_stat(self.VM_STAT))

    def test_gradle_clients_are_builds_and_daemons_are_not(self):
        listing = "\n".join([
            "java -cp gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test",
            "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.1",
            "java -cp x org.gradle.launcher.GradleMain build",
            "python3 something"])

        def run(command, **_kwargs):
            return subprocess.CompletedProcess(command, 0, stdout=listing, stderr="")
        self.assertEqual(ats.running_gradle_builds(run=run), 2)


class WorldReuseAndKillTest(HarnessCase):
    """Restart persistence and crash checks need a second boot over the first run's world."""

    def _fake_world(self, workdir):
        for rel, data in (("world/region/r.0.0.mca", b"overworld"), ("world/level.dat", b"lvl"),
                          ("world_nether/DIM-1/region/r.0.0.mca", b"nether")):
            path = Path(workdir) / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_world_from_copies_only_the_previous_runs_world_dirs_and_records_it(self):
        first = Path(self.start("a")["workdir"])
        self._fake_world(first)
        (first / "notes.txt").write_text("not a world")
        self.harness.stop("a", timeout=10)
        second = Path(self.start("b", world_from="a")["workdir"])
        try:
            self.assertEqual((second / "world/region/r.0.0.mca").read_bytes(), b"overworld")
            self.assertEqual((second / "world_nether/DIM-1/region/r.0.0.mca").read_bytes(),
                             b"nether")
            self.assertFalse((second / "notes.txt").exists())
            source = self.run_json("b")["world_from"]
            self.assertEqual(source["name"], "a")
            self.assertEqual(sorted(source["copied"]), ["world", "world_nether"])
            self.assertEqual(source["source_exit_code"], 0)
            # The copy is independent: the source world is untouched by the second run.
            (second / "world/level.dat").write_bytes(b"changed")
            self.assertEqual((first / "world/level.dat").read_bytes(), b"lvl")
        finally:
            self.harness.stop("b", timeout=10)

    def test_world_from_is_refused_for_unknown_running_or_worldless_runs(self):
        with self.assertRaisesRegex(ats.HarnessError, "not a harness run"):
            self.start("b", world_from="missing")
        live = self.root / "servers" / "live"
        live.mkdir(parents=True)
        (live / ats.MARKER).write_text("x")
        (live / "run.json").write_text(json.dumps({"status": "ready"}))
        self._fake_world(live)
        with self.assertRaisesRegex(ats.HarnessError, "still running"):
            self.start("b", world_from="live")
        self.start("empty")
        self.harness.stop("empty", timeout=10)
        with self.assertRaisesRegex(ats.HarnessError, "no world"):
            self.start("b", world_from="empty")
        self.assertFalse((self.root / "servers" / "b").exists())
        self.assertFalse(self.harness.lock_path.exists())

    def test_config_files_are_installed_and_validated(self):
        spigot = self.root / "spigot.yml"
        spigot.write_text("world-settings:\n  default:\n    entity-activation-range:\n"
                          "      monsters: 0\n")
        workdir = Path(self.start("a", config_files={"spigot.yml": spigot,
                                                     "config/x.yml": spigot})["workdir"])
        self.harness.stop("a", timeout=10)
        self.assertEqual((workdir / "spigot.yml").read_text(), spigot.read_text())
        self.assertEqual((workdir / "config/x.yml").read_text(), spigot.read_text())
        self.assertEqual([c["dest"] for c in self.run_json("a")["config_files"]],
                         ["spigot.yml", "config/x.yml"])
        for dest in ("../escape.yml", "/abs.yml", "server.properties", "eula.txt", "run.json",
                     "outputs/x.yml", "world/level.dat", "tmp/x.yml", "config/../x.yml",
                     "sourbycraft_config/aurora.toml",
                     "sourbycraft_config/sourbycraft_global_config.toml",
                     "C:/x.yml", "config\\..\\x.yml", ".", "config/"):
            with self.assertRaisesRegex(ats.HarnessError, "config file destination"):
                self.start("b", config_files={dest: spigot})
        with self.assertRaisesRegex(ats.HarnessError, "config file"):
            self.start("b", config_files={"spigot.yml": self.root / "missing.yml"})
        big = self.root / "big.yml"
        big.write_text("x" * (64 * 1024 + 1))
        with self.assertRaisesRegex(ats.HarnessError, "under 64 KiB"):
            self.start("b", config_files={"spigot.yml": big})
        self.assertFalse((self.root / "servers" / "b").exists())

    def test_a_plugin_jar_is_copied_as_bytes_and_recorded_by_hash(self):
        jar = self.root / "probe.jar"
        payload = b"PK\x03\x04\xb2\x00binary"
        jar.write_bytes(payload)
        workdir = Path(self.start("a", config_files={"plugins/Probe.jar": jar})["workdir"])
        self.harness.stop("a", timeout=10)
        self.assertEqual((workdir / "plugins/Probe.jar").read_bytes(), payload)
        entry = self.run_json("a")["config_files"][0]
        self.assertEqual(entry["dest"], "plugins/Probe.jar")
        self.assertEqual(entry["sha256"], hashlib.sha256(payload).hexdigest())
        self.assertNotIn("text", entry)
        for dest in ("plugins/sub/Probe.jar", "Probe.jar", "config/Probe.jar"):
            with self.assertRaisesRegex(ats.HarnessError, "config file"):
                self.start("b", config_files={dest: jar})
        big = self.root / "big.jar"
        big.write_bytes(b"x" * (ats.PLUGIN_JAR_MAX_BYTES + 1))
        with self.assertRaisesRegex(ats.HarnessError, "plugin jar"):
            self.start("b", config_files={"plugins/Big.jar": big})
        link = self.root / "link.jar"
        link.symlink_to(jar)
        with self.assertRaisesRegex(ats.HarnessError, "symlink"):
            self.start("b", config_files={"plugins/Link.jar": link})

    def test_a_plugin_jar_is_read_once_and_never_past_the_cap(self):
        # A file that grows after a size check must not be read unbounded: the cap is enforced on
        # the bytes actually read from one open file.
        jar = self.root / "grow.jar"
        jar.write_bytes(b"x" * 10)
        reads = []
        real_open = open

        class Spy:
            def __init__(self, handle):
                self.handle = handle
            def read(self, n=-1):
                reads.append(n)
                return b"x" * (ats.PLUGIN_JAR_MAX_BYTES + 1)
            def __enter__(self):
                return self
            def __exit__(self, *exc):
                self.handle.close()

        def fake_open(path, mode="r", *args, **kwargs):
            handle = real_open(path, mode, *args, **kwargs)
            return Spy(handle) if str(path) == str(jar) and "b" in mode else handle

        with mock.patch("builtins.open", fake_open):
            with self.assertRaisesRegex(ats.HarnessError, "plugin jar"):
                ats.Harness.config_files({"plugins/Grow.jar": str(jar)})
        self.assertEqual(reads, [ats.PLUGIN_JAR_MAX_BYTES + 1])
        self.assertFalse((self.root / "servers" / "b").exists())

    def test_a_plugin_data_file_is_copied_as_bytes_and_recorded_by_hash(self):
        # A module jar inside a plugin's data folder (SuperiorSkyblock2 loads modules only from
        # plugins/SuperiorSkyblock2/modules/*.jar), placed before boot.
        module = self.root / "module.jar"
        payload = b"PK\x03\x04\x00module-bytes"
        module.write_bytes(payload)
        plugin = self.root / "plugin.jar"
        plugin.write_bytes(b"PK\x03\x04plugin")
        workdir = Path(self.start("a", config_files={"plugins/Sky.jar": plugin},
                                  plugin_files={"plugins/Sky/modules/Mod.jar": module,
                                                "plugins/Sky/schematics/a.schem": module})
                       ["workdir"])
        self.harness.stop("a", timeout=10)
        self.assertEqual((workdir / "plugins/Sky/modules/Mod.jar").read_bytes(), payload)
        self.assertEqual((workdir / "plugins/Sky/schematics/a.schem").read_bytes(), payload)
        entries = self.run_json("a")["plugin_files"]
        self.assertEqual([e["dest"] for e in entries],
                         ["plugins/Sky/modules/Mod.jar", "plugins/Sky/schematics/a.schem"])
        self.assertEqual(entries[0]["sha256"], hashlib.sha256(payload).hexdigest())
        self.assertEqual(entries[0]["bytes"], len(payload))
        self.assertEqual(entries[0]["source"], str(module))
        self.assertNotIn("text", entries[0])
        # The plugin jar itself stays a --config-file entry.
        self.assertEqual([c["dest"] for c in self.run_json("a")["config_files"]],
                         ["plugins/Sky.jar"])

    def test_plugin_data_file_destinations_stay_inside_a_plugin_folder(self):
        module = self.root / "module.jar"
        module.write_bytes(b"PK")
        for dest in ("../plugins/Sky/m.jar", "/abs/plugins/Sky/m.jar", "plugins/Sky.jar",
                     "plugins/../Sky/m.jar", "plugins/Sky/../../m.jar", "plugins/Sky/./m.jar",
                     "config/Sky/m.jar", "Sky/modules/m.jar", "plugins", "plugins/",
                     "plugins/Sky/", "plugins\\Sky\\m.jar", "C:/plugins/Sky/m.jar",
                     "plugins//m.jar", "world/plugins/Sky/m.jar"):
            with self.assertRaisesRegex(ats.HarnessError, "plugin file destination"):
                self.start("b", plugin_files={dest: module})
        self.assertFalse((self.root / "servers" / "b").exists())
        self.assertFalse(self.harness.lock_path.exists())

    def test_plugin_data_file_sources_are_checked_like_plugin_jars(self):
        with self.assertRaisesRegex(ats.HarnessError, "plugin file .* not a file"):
            self.start("b", plugin_files={"plugins/Sky/modules/m.jar": self.root / "missing.jar"})
        with self.assertRaisesRegex(ats.HarnessError, "plugin file .* not a file"):
            self.start("b", plugin_files={"plugins/Sky/modules/m.jar": self.root})
        big = self.root / "big.jar"
        big.write_bytes(b"x" * (ats.PLUGIN_JAR_MAX_BYTES + 1))
        with self.assertRaisesRegex(ats.HarnessError, "plugin file .* under 8 MiB"):
            self.start("b", plugin_files={"plugins/Sky/modules/big.jar": big})
        real = self.root / "real.jar"
        real.write_bytes(b"PK")
        link = self.root / "link.jar"
        link.symlink_to(real)
        with self.assertRaisesRegex(ats.HarnessError, "symlink"):
            self.start("b", plugin_files={"plugins/Sky/modules/link.jar": link})
        self.assertFalse((self.root / "servers" / "b").exists())

    def test_plugin_data_files_may_not_collide_with_config_files(self):
        module = self.root / "module.jar"
        module.write_bytes(b"PK")
        conf = self.root / "c.yml"
        conf.write_text("a: 1\n")
        for configs, files in (({"plugins/Sky/config.yml": conf},
                                {"plugins/Sky/config.yml": module}),
                               ({"plugins/Sky/modules": conf},
                                {"plugins/Sky/modules/m.jar": module})):
            with self.assertRaisesRegex(ats.HarnessError, "collides"):
                self.start("b", config_files=configs, plugin_files=files)
        self.assertFalse((self.root / "servers" / "b").exists())

    def test_a_plugin_data_file_is_read_once_and_never_past_the_cap(self):
        module = self.root / "grow.jar"
        module.write_bytes(b"x" * 10)
        reads = []
        real_open = open

        class Spy:
            def __init__(self, handle):
                self.handle = handle
            def read(self, n=-1):
                reads.append(n)
                return b"x" * (ats.PLUGIN_JAR_MAX_BYTES + 1)
            def __enter__(self):
                return self
            def __exit__(self, *exc):
                self.handle.close()

        def fake_open(path, mode="r", *args, **kwargs):
            handle = real_open(path, mode, *args, **kwargs)
            return Spy(handle) if str(path) == str(module) and "b" in mode else handle

        with mock.patch("builtins.open", fake_open):
            with self.assertRaisesRegex(ats.HarnessError, "plugin file"):
                ats.Harness.plugin_files({"plugins/Sky/modules/g.jar": str(module)})
        self.assertEqual(reads, [ats.PLUGIN_JAR_MAX_BYTES + 1])

    def test_cli_forwards_plugin_files(self):
        args = ats.build_parser().parse_args(
            ["start", "--name", "a", "--plugin-file", "plugins/Sky/modules/m.jar=/x/m.jar"])
        self.assertEqual(ats._start_kwargs(args)["plugin_files"],
                         {"plugins/Sky/modules/m.jar": "/x/m.jar"})
        args = ats.build_parser().parse_args(["run", "--name", "a", "--scenario", "s.json"])
        self.assertIsNone(ats._start_kwargs(args)["plugin_files"])

    def test_cli_forwards_config_files(self):
        args = ats.build_parser().parse_args(
            ["start", "--name", "a", "--config-file", "spigot.yml=/x/s.yml",
             "--config-file", "config/paper-global.yml=/x/p.yml"])
        self.assertEqual(ats._start_kwargs(args)["config_files"],
                         {"spigot.yml": "/x/s.yml", "config/paper-global.yml": "/x/p.yml"})
        args = ats.build_parser().parse_args(["run", "--name", "a", "--scenario", "s.json",
                                              "--config-file", "spigot.yml=/x/s.yml"])
        self.assertEqual(ats._start_kwargs(args)["config_files"], {"spigot.yml": "/x/s.yml"})

    def test_aurora_toml_is_installed_and_carried_over_by_world_from(self):
        toml = self.root / "awf.toml"
        toml.write_text('[aurora.awf]\nworlds = ["world"]\n')
        first = Path(self.start("a", aurora_toml=toml)["workdir"])
        self.assertEqual((first / "sourbycraft_config/aurora.toml").read_text(), toml.read_text())
        self.assertEqual(self.run_json("a")["aurora_toml"]["source"], str(toml))
        self._fake_world(first)
        self.harness.stop("a", timeout=10)
        second = Path(self.start("b", world_from="a")["workdir"])
        self.harness.stop("b", timeout=10)
        # A world stored through AWF must come back through AWF, not through region files.
        self.assertEqual((second / "sourbycraft_config/aurora.toml").read_text(), toml.read_text())
        self.assertEqual(self.run_json("b")["aurora_toml"]["source"], "world_from a")
        with self.assertRaisesRegex(ats.HarnessError, "aurora.toml"):
            self.start("c", aurora_toml=self.root / "missing.toml")
        self.assertFalse((self.root / "servers" / "c").exists())

    def test_kill_sigkills_the_server_without_a_console_stop(self):
        run = self.start()
        final = self.harness.kill("t", timeout=10)
        self.assertEqual((final["status"], final["stop_reason"]), ("exited", "killed on request"))
        self.assertEqual(final["exit_code"], -signal.SIGKILL)
        self.assertTrue(final["killed"] and final["lock_released"])
        self.assertFalse(final["jvm_still_running"])
        self.assertFalse(ats.pid_alive(run["jvm_pid"]))
        self.assertNotIn("Stopping server", "\n".join(final["log_tail"]))
        self.assertNotIn("stop", (Path(run["workdir"]) / "commands.log").read_text()
                         if (Path(run["workdir"]) / "commands.log").exists() else "")


class ScenarioRunTest(HarnessCase):
    def test_run_writes_outputs_and_a_summary(self):
        scenario = self.root / "s.json"
        scenario.write_text(json.dumps({"steps": [
            {"command": "ver", "expect": "echo ver", "save_as": "ver.txt"},
            {"perf": "chunks"}, {"sleep": 0.2}, {"wait_for": "echo perf chunks"}]}))
        original = self.harness.start
        self.harness.start = lambda name, **kw: original(
            name, command=[sys.executable, str(self.fake)], **kw)
        summary = ats.run_scenario(self.harness, "s", scenario,
                                   {"max_lifetime_s": 120, "startup_timeout_s": 30})
        workdir = Path(summary["workdir"])
        self.assertTrue(summary["all_steps_ok"], summary["steps"])
        self.assertIn("echo ver", (workdir / "outputs" / "ver.txt").read_text())
        self.assertIn("echo perf chunks", (workdir / "outputs" / "02-perf.txt").read_text())
        self.assertEqual((summary["exit_code"], summary["lock_released"]), (0, True))
        self.assertTrue((workdir / "scenario-summary.json").is_file())
        self.assertEqual(summary["hardware"]["cpu_model"], "fake")

    def test_a_step_can_opt_out_of_bracketing_to_see_a_delayed_reply(self):
        scenario = self.root / "s.json"
        scenario.write_text(json.dumps({"steps": [
            {"command": "slow a", "expect": "late reply slow a", "bracket": False, "timeout": 5,
             "save_as": "slow.txt"}]}))
        original = self.harness.start
        self.harness.start = lambda name, **kw: original(
            name, command=[sys.executable, str(self.fake)], **kw)
        summary = ats.run_scenario(self.harness, "s", scenario,
                                   {"max_lifetime_s": 120, "startup_timeout_s": 30})
        self.assertTrue(summary["all_steps_ok"], summary["steps"])
        self.assertIn("late reply slow a", (Path(summary["workdir"]) / "outputs" / "slow.txt").read_text())

    def test_a_failed_step_stops_the_scenario_and_the_server(self):
        scenario = self.root / "s.json"
        scenario.write_text(json.dumps({"steps": [
            {"command": "ver", "expect": "never", "timeout": 1}, {"sleep": 0.1}]}))
        original = self.harness.start
        self.harness.start = lambda name, **kw: original(
            name, command=[sys.executable, str(self.fake)], **kw)
        summary = ats.run_scenario(self.harness, "s", scenario, {"max_lifetime_s": 120})
        self.assertFalse(summary["all_steps_ok"])
        self.assertEqual(len(summary["steps"]), 1)
        self.assertTrue(summary["lock_released"])

    def test_an_invalid_scenario_never_boots(self):
        scenario = self.root / "s.json"
        scenario.write_text(json.dumps({"steps": [{"command": "stop"}]}))
        with self.assertRaises(ats.ScenarioError):
            ats.run_scenario(self.harness, "s", scenario)
        self.assertFalse((self.root / "servers" / "s").exists())


class CleanupTest(HarnessCase):
    def test_keeps_the_newest_n_and_never_touches_foreign_or_live_dirs(self):
        base = self.root / "servers"
        for index in range(4):
            workdir = base / f"old{index}"
            workdir.mkdir(parents=True)
            (workdir / ats.MARKER).write_text("x")
            ats.write_json(workdir / "run.json", {"created": 100 + index, "status": "exited"})
        foreign = base / "not-ours"
        foreign.mkdir()
        (foreign / "server.properties").write_text("x")
        self.start("live")
        deleted = self.harness.cleanup(keep=2, dry_run=True)      # live + old3 kept
        self.assertEqual(sorted(Path(p).name for p in deleted), ["old0", "old1", "old2"])
        self.assertTrue((base / "old0").exists())
        deleted = self.harness.cleanup(keep=0)
        self.assertEqual(sorted(Path(p).name for p in deleted), ["old0", "old1", "old2", "old3"])
        self.assertTrue(foreign.exists() and (base / "live").exists())
        self.harness.stop("live", timeout=10)
        self.assertEqual([Path(p).name for p in self.harness.cleanup(keep=0)], ["live"])


def make_startup_cache(keys=("a.jar", "b.jar"), env="26.2|1|1|2|25"):
    """A valid cache in StartupCache's on-disk layout (header, env, one sealed line per entry)."""
    import base64
    lines = [ats.STARTUP_CACHE_MAGIC, "env " + env]
    for key in keys:
        payload = f"name: {key}\nregion: true".encode()
        lines.append(ats._seal_entry([
            base64.b64encode(key.encode()).decode(), hashlib.sha256(key.encode()).hexdigest(),
            "123", "1700000000000", hashlib.sha256(payload).hexdigest(),
            base64.b64encode(payload).decode()]))
    return ("\n".join(lines) + "\n").encode()


class StartupCacheShapesTest(unittest.TestCase):
    def test_a_made_cache_parses_as_valid(self):
        self.assertEqual(ats.parse_startup_cache(make_startup_cache()), ("26.2|1|1|2|25", 2, 0))

    def test_empty_and_garbage_need_no_donor(self):
        self.assertEqual(ats.corrupt_startup_cache("empty"), b"")
        self.assertEqual(ats.parse_startup_cache(b"")[0], None)
        garbage = ats.corrupt_startup_cache("garbage")
        with self.assertRaises(UnicodeDecodeError):
            garbage.decode("utf-8")               # Files.readAllLines fails on it: IO_ERROR
        self.assertIsNone(ats.parse_startup_cache(garbage)[0])

    def test_truncated_tears_the_last_entry_and_keeps_the_others(self):
        torn = ats.corrupt_startup_cache("truncated", make_startup_cache())
        self.assertFalse(torn.endswith(b"\n"))
        self.assertEqual(ats.parse_startup_cache(torn), ("26.2|1|1|2|25", 1, 1))

    def test_flipped_entry_fails_one_entry_hash(self):
        flipped = ats.corrupt_startup_cache("flipped-entry", make_startup_cache())
        self.assertEqual(ats.parse_startup_cache(flipped), ("26.2|1|1|2|25", 1, 1))

    def test_wrong_version_changes_only_the_format_field(self):
        wrong = ats.corrupt_startup_cache("wrong-version", make_startup_cache())
        self.assertEqual(ats.parse_startup_cache(wrong), ("26.2|1|1|3|25", 2, 0))

    def test_stale_hashes_stay_valid_entries_for_a_different_jar(self):
        donor = make_startup_cache()
        stale = ats.corrupt_startup_cache("stale-hashes", donor)
        self.assertEqual(ats.parse_startup_cache(stale), ("26.2|1|1|2|25", 2, 0))
        self.assertNotEqual(stale, donor)

    def test_unreadable_keeps_the_bytes_and_sets_mode_zero(self):
        donor = make_startup_cache()
        self.assertEqual(ats.corrupt_startup_cache("unreadable", donor), donor)
        self.assertEqual(ats.STARTUP_CACHE_SHAPES["unreadable"]["mode"], 0)

    def test_every_shape_with_a_donor_flag_is_refused_without_one(self):
        for shape, spec in ats.STARTUP_CACHE_SHAPES.items():
            if spec["donor"]:
                with self.assertRaisesRegex(ats.HarnessError, "needs --startup-cache-from"):
                    ats.corrupt_startup_cache(shape)

    def test_unknown_shape_and_unusable_donor_are_refused(self):
        with self.assertRaisesRegex(ats.HarnessError, "unknown startup-cache shape"):
            ats.corrupt_startup_cache("melted", make_startup_cache())
        for donor in (b"", make_startup_cache(keys=()), b"junk\n"):
            with self.assertRaisesRegex(ats.HarnessError, "no valid entries"):
                ats.corrupt_startup_cache("truncated", donor)

    def test_the_shape_table_matches_the_warning_text_in_the_java_source(self):
        # PluginStartupIndex.describe() owns the words; a reworded WARN must fail here, not at boot.
        source = (Path(__file__).resolve().parents[1] / "sourbycraft-server/src/main/java/dev/iyanz/"
                  "sourbycraft/startup/PluginStartupIndex.java").read_text()
        for shape, spec in ats.STARTUP_CACHE_SHAPES.items():
            if spec["expect_warn"] is None:
                continue
            tail = spec["expect_warn"].replace("Aurora startup cache ", "", 1)
            if tail.startswith("had "):
                self.assertIn('"had " + loaded.discardedEntries() + " corrupt entr"', source, shape)
            else:
                self.assertIn(tail, source, shape)
        self.assertIn('"Aurora startup cache " + describe(loaded)', source)
        self.assertIn('"; affected entries are rebuilt and boot continues"', source)


class StartupCacheCheckTest(unittest.TestCase):
    DONE = '[12:00:00 INFO]: Done (3.1s)! For help, type "help"'
    ENABLING = "[12:00:01 INFO]: [Probe] Enabling Probe v1"

    def log(self, warn=None, index="Aurora startup index: 2 jar(s), cold start (hit 0, miss 2, discarded 0) in 4.0 ms"):
        lines = []
        if warn:
            lines.append("[12:00:00 WARN]: [SourbyCraft] " + warn)
        if index:
            lines.append("[12:00:00 INFO]: [SourbyCraft] " + index)
        return "\n".join(lines + [self.ENABLING, self.DONE])

    def seed(self, shape, donor=None):
        data = ats.corrupt_startup_cache(shape, donor)
        return {"shape": shape, "sha256": hashlib.sha256(data).hexdigest()}

    def test_a_recovered_boot_passes_for_each_warning_shape(self):
        donor = make_startup_cache()
        cases = {
            "truncated": "Aurora startup cache had 1 corrupt entry discarded; affected entries are rebuilt and boot continues",
            "wrong-version": "Aurora startup cache was written by a different Minecraft/ABI/format/Java and was discarded; affected entries are rebuilt and boot continues",
            "empty": "Aurora startup cache was not a valid startup cache and was discarded; affected entries are rebuilt and boot continues",
            "garbage": "Aurora startup cache could not be read and was ignored; affected entries are rebuilt and boot continues",
        }
        for shape, warn in cases.items():
            ok, details = ats.check_startup_cache_boot(self.log(warn), self.seed(shape, donor), donor)
            self.assertTrue(ok, (shape, details["checks"]))

    def test_stale_hashes_pass_only_without_a_warning(self):
        donor = make_startup_cache()
        seed = self.seed("stale-hashes", donor)
        self.assertTrue(ats.check_startup_cache_boot(self.log(), seed, donor)[0])
        ok, details = ats.check_startup_cache_boot(self.log("Aurora startup cache had 1 corrupt entry discarded"),
                                                   seed, donor)
        self.assertFalse(ok)
        self.assertFalse(details["checks"]["no_cache_warning"])

    def test_unreadable_does_not_require_changed_bytes(self):
        donor = make_startup_cache()
        seed = self.seed("unreadable", donor)
        warn = "Aurora startup cache could not be read and was ignored; affected entries are rebuilt and boot continues"
        ok, details = ats.check_startup_cache_boot(self.log(warn), seed, donor)
        self.assertTrue(ok, details["checks"])
        self.assertNotIn("cache_changed", details["checks"])

    def test_failures_are_named(self):
        donor = make_startup_cache()
        seed = self.seed("truncated", donor)
        warn = "Aurora startup cache had 1 corrupt entry discarded; affected entries are rebuilt and boot continues"
        good = ats.corrupt_startup_cache("truncated", donor)
        ok, d = ats.check_startup_cache_boot(self.log(warn), seed, good)       # cache still torn
        self.assertFalse(ok)
        self.assertFalse(d["checks"]["cache_valid_after"])
        ok, d = ats.check_startup_cache_boot(self.log(None), seed, donor)      # no warning at all
        self.assertFalse(d["checks"]["one_clear_warning"])
        ok, d = ats.check_startup_cache_boot(self.log(warn + "\n[x] Aurora startup cache again"), seed, donor)
        self.assertFalse(d["checks"]["one_clear_warning"])
        ok, d = ats.check_startup_cache_boot(self.log(warn), seed, None)       # cache never rewritten
        self.assertFalse(d["checks"]["cache_valid_after"])
        ok, d = ats.check_startup_cache_boot(self.log(warn, index=None), seed, donor)
        self.assertFalse(d["checks"]["index_line"])

    def test_the_warm_second_boot_needs_hits_and_no_warning(self):
        donor = make_startup_cache()
        seed = {"shape": None, "sha256": hashlib.sha256(donor).hexdigest()}
        warm = "Aurora startup index: 2 jar(s), warm start (hit 2, miss 0, discarded 0) in 1.0 ms"
        self.assertTrue(ats.check_startup_cache_boot(self.log(index=warm), seed, donor)[0])
        self.assertFalse(ats.check_startup_cache_boot(self.log()   , seed, donor)[0])


class StartupCacheSeedingTest(HarnessCase):
    def _donor(self, name="donor", keys=("a.jar",), jars=True):
        workdir = Path(self.start(name)["workdir"])
        self.harness.stop(name, timeout=10)
        (workdir / "plugins").mkdir(exist_ok=True)
        if jars:
            for key in keys:
                jar = workdir / "plugins" / key
                jar.write_bytes(b"jar " + key.encode())
                os.utime(jar, (1_700_000_000, 1_700_000_000))
        cache = workdir / ats.STARTUP_CACHE_DEST
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(make_startup_cache(keys))
        return workdir

    def test_a_corrupted_cache_and_the_donor_jars_are_installed_before_boot(self):
        donor = self._donor(keys=("a.jar", "b.jar"))
        workdir = Path(self.start("seeded", startup_cache_from="donor",
                                  startup_cache_corrupt="wrong-version")["workdir"])
        try:
            record = self.run_json("seeded")["startup_cache"]
            cache = workdir / ats.STARTUP_CACHE_DEST
            self.assertEqual(record["shape"], "wrong-version")
            self.assertEqual(record["sha256"], hashlib.sha256(cache.read_bytes()).hexdigest())
            self.assertEqual(ats.parse_startup_cache(cache.read_bytes())[0], "26.2|1|1|3|25")
            self.assertEqual(sorted(record["plugin_jars_carried"]), ["a.jar", "b.jar"])
            self.assertEqual(int((workdir / "plugins/a.jar").stat().st_mtime), 1_700_000_000)
            # The donor run is untouched.
            self.assertEqual(ats.parse_startup_cache((donor / ats.STARTUP_CACHE_DEST).read_bytes())[0],
                             "26.2|1|1|2|25")
        finally:
            self.harness.stop("seeded", timeout=10)

    def test_without_a_shape_the_donor_cache_is_copied_unchanged_for_the_warm_boot(self):
        donor = self._donor()
        workdir = Path(self.start("warm", startup_cache_from="donor")["workdir"])
        try:
            self.assertEqual((workdir / ats.STARTUP_CACHE_DEST).read_bytes(),
                             (donor / ats.STARTUP_CACHE_DEST).read_bytes())
            self.assertIsNone(self.run_json("warm")["startup_cache"]["shape"])
        finally:
            self.harness.stop("warm", timeout=10)

    def test_unreadable_sets_mode_000(self):
        self._donor()
        workdir = Path(self.start("locked", startup_cache_from="donor",
                                  startup_cache_corrupt="unreadable")["workdir"])
        try:
            self.assertEqual((workdir / ats.STARTUP_CACHE_DEST).stat().st_mode & 0o777, 0)
            self.assertEqual(self.run_json("locked")["startup_cache"]["mode"], "0000")
        finally:
            self.harness.stop("locked", timeout=10)

    def test_empty_and_garbage_need_no_donor(self):
        for shape in ("empty", "garbage"):
            workdir = Path(self.start(shape, startup_cache_corrupt=shape)["workdir"])
            try:
                self.assertTrue((workdir / ats.STARTUP_CACHE_DEST).is_file())
                self.assertEqual(self.run_json(shape)["startup_cache"]["plugin_jars_carried"], [])
            finally:
                self.harness.stop(shape, timeout=10)

    def test_an_explicit_plugin_jar_wins_over_the_donor_copy(self):
        self._donor()
        mine = self.root / "mine.jar"
        mine.write_bytes(b"mine")
        workdir = Path(self.start("mixed", startup_cache_from="donor", startup_cache_corrupt="truncated",
                                  config_files={"plugins/a.jar": str(mine)})["workdir"])
        try:
            self.assertEqual((workdir / "plugins/a.jar").read_bytes(), b"mine")
            self.assertEqual(self.run_json("mixed")["startup_cache"]["plugin_jars_carried"], [])
        finally:
            self.harness.stop("mixed", timeout=10)

    def test_refusals_leave_no_run_directory_and_no_lock(self):
        live = self.root / "servers" / "live"
        live.mkdir(parents=True)
        (live / ats.MARKER).write_text("x")
        (live / "run.json").write_text(json.dumps({"status": "ready"}))
        self._donor("nocache", jars=False)
        (self.root / "servers/nocache" / ats.STARTUP_CACHE_DEST).unlink()
        for kwargs, message in (
                ({"startup_cache_from": "missing"}, "not a harness run"),
                ({"startup_cache_from": "live"}, "still running"),
                ({"startup_cache_from": "nocache"}, "has no sourbycraft_config/cache"),
                ({"startup_cache_corrupt": "truncated"}, "needs --startup-cache-from"),
                ({"startup_cache_corrupt": "melted"}, "unknown startup-cache shape")):
            with self.assertRaisesRegex(ats.HarnessError, message):
                self.start("b", **kwargs)
        self.assertFalse((self.root / "servers" / "b").exists())
        self.assertFalse(self.harness.lock_path.exists())

    def test_cli_options_reach_start_and_scenarios_accept_the_settings(self):
        args = ats.build_parser().parse_args(
            ["start", "--name", "x", "--startup-cache-from", "r1", "--startup-cache-corrupt", "truncated"])
        kwargs = ats._start_kwargs(args)
        self.assertEqual((kwargs["startup_cache_from"], kwargs["startup_cache_corrupt"]), ("r1", "truncated"))
        with self.assertRaises(SystemExit):
            ats.build_parser().parse_args(["start", "--name", "x", "--startup-cache-corrupt", "melted"])
        settings, _ = ats.parse_scenario({"settings": {"startup_cache_corrupt": "empty"},
                                          "steps": [{"sleep": 0}]})
        self.assertEqual(settings["startup_cache_corrupt"], "empty")


if __name__ == "__main__":
    unittest.main()
