#!/usr/bin/env python3
"""Local fixture for the auto-updater's apply -> failed boot -> `.prev` restore path (audit SEC-3).

Build 49 Security gate: "`.prev` rollback exercised on a real boot (apply -> failed boot ->
restore)". This drives it end to end on one machine with no network beyond loopback:

* a fake release server on 127.0.0.1 answers the GitHub releases listing and serves the assets;
  the server under test is pointed at it with the test-only JVM property
  ``sourbycraft.update.api-base`` (loopback only, disabled by default; UpdateOverrides);
* SEC-2 stays ON: a throwaway Ed25519 key pair is generated at run time (OpenSSL 3, outside the
  repository, private key deleted when the run ends); the public key reaches the server through
  ``sourbycraft.update.release-key-file``, so the jar signature AND the signed release manifest are
  mandatory and are checked exactly as on a build that ships a key;
* three variants of the same slim jar differ only by one marker entry
  (``META-INF/sourbycraft-fixture.properties``) that the test plugin reads: ``original`` (boots),
  ``new-good`` (boots) and ``new-broken`` (the test plugin halts the JVM with exit code 3 from
  ``onLoad``: after the SourbyCraft boot stage that runs the rollback guard, before worlds load and
  before ``ServerLoadEvent STARTUP``, i.e. before ready);
* ``agent_testserver.py`` boots it (one slot, loopback, own directory) and ``restart`` re-boots the
  SAME directory so the jar swapped by the updater and ``auto_update/`` carry over.

Scenarios (``run --scenario``):

``rollback``      apply the broken jar -> boot 2 dies before ready -> boot 3 (still the broken jar, it
                  is what is on disk) finds BOOTING and restores ``.prev`` -> boot 4 reaches Done on
                  the original jar and the updater is held; ``/update resume`` then ``/update apply``
                  does not re-offer the rolled-back tag. With clipVersion >=3.1.4, boot 3 instead
                  restores in the launcher and exits 71 before any server code.
``healthy``       apply the good jar -> boot 2 reaches ready -> the guard confirms (phase OK), the
                  new jar stays, ``.prev`` stays, the same tag is not applied twice.
``dead-launcher`` apply a stub that calls the embedded launcher guard, then exits 4 before server code. The restore
                  is required on boot 3 with exit 71 when the runtime clipVersion pin is >=3.1.4;
                  older pins explicitly skip that expectation and check manual recovery instead.

Functional evidence on a shared machine, nothing else: no performance claim, no real GitHub, no
real signing key. See docs/development/updater-rollback-fixture-2026-10-22.md.

    python3 scripts/updater_rollback_fixture.py run --scenario rollback --name rb
    python3 scripts/updater_rollback_fixture.py run --scenario all --name rb
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

import agent_testserver as ats  # noqa: E402
import release_manifest  # noqa: E402
import release_signing_key  # noqa: E402

SCHEMA = "sourbycraft.updater-rollback-fixture/1"
MARKER_ENTRY = "META-INF/sourbycraft-fixture.properties"
ASSET_NAME = "SourbyCraft-26.2-REL.jar"
REPO_SLUG = "YanIanZ/SourbyCraft"
FAKE_TAG = "v26.2-r9001"            # Far above any real build number, so it is always "newer".
EXIT_BROKEN = 3                     # test plugin halt code for the broken jar
EXIT_DEAD = 4                       # stub Main-Class exit code for the dead-launcher jar
EXIT_APPLY = 70                     # restart_mode=exit: DedicatedServer.onServerExit -> System.exit(70)
DEFAULT_JAR = REPO / "build" / "libs" / "SourbyCraft-slim.jar"
DEFAULT_PLUGIN = REPO / "test-plugin" / "build" / "libs" / "test-plugin-1.0.0.jar"
SCENARIOS = ("rollback", "healthy", "dead-launcher")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MAX_JAR_BYTES = 512 * 1024 * 1024

DEAD_MAIN_SOURCE = """\
public final class FixtureDeadMain {
    public static void main(String[] args) throws Exception {
        // Exercise the embedded guard before the simulated failure, without loading server code.
        // Older clips have no guard and retain the historical manual-recovery behavior.
        Class<?> guard = null;
        try {
            guard = Class.forName("dev.iyanz.sourbyclip.update.BootRollbackGuard");
        } catch (ClassNotFoundException olderClip) {
            // Expected for pins below 3.1.4.
        }
        if (guard != null) {
            Class<?> logger = Class.forName("dev.iyanz.sourbyclip.update.BootRollbackGuard$Logger");
            Object log = java.lang.reflect.Proxy.newProxyInstance(logger.getClassLoader(), new Class<?>[]{logger},
                (proxy, method, values) -> { System.err.println(values[0] + ": " + values[1]); return null; });
            java.nio.file.Path jar = java.nio.file.Path.of(FixtureDeadMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Object outcome = guard.getMethod("decideAndApply", java.nio.file.Path.class, java.nio.file.Path.class, logger)
                .invoke(null, java.nio.file.Path.of(""), jar, log);
            if (outcome.toString().equals("ROLLED_BACK")) {
                System.exit(71);
                return;
            }
        }
        System.out.println("SOURBY_UPDATER_FIXTURE_DEAD_LAUNCHER role=new-dead exit=%d: no SourbyCraft code has run");
        System.out.flush();
        System.exit(%d);
    }
}
""" % (EXIT_DEAD, EXIT_DEAD)


class FixtureError(RuntimeError):
    pass


def pinned_clip_version(properties=None):
    properties = Path(properties) if properties is not None else REPO / "gradle.properties"
    match = re.search(r"^\s*clipVersion\s*=\s*(\S+)\s*$", properties.read_text(), re.MULTILINE)
    if not match or not re.fullmatch(r"\d+\.\d+\.\d+", match[1]):
        raise FixtureError(f"missing or unsupported clipVersion in {properties}")
    return match[1]


def launcher_restore_expected(properties=None):
    return tuple(map(int, pinned_clip_version(properties).split("."))) >= (3, 1, 4)


# ------------------------------------------------------------------------------------ jars

def marker_text(role, fail=False, exit_code=EXIT_BROKEN):
    """Contents of META-INF/sourbycraft-fixture.properties (read by UpdaterFixtureProbe)."""
    lines = [f"role={role}", f"boot={'fail' if fail else 'ok'}"]
    if fail:
        lines.append(f"exit-code={exit_code}")
    return "\n".join(lines) + "\n"


def repack_jar(source, target, marker, main_class=None, extra_entries=None):
    """Copy ``source`` to ``target`` entry by entry, add the marker entry, optionally replace the
    manifest's ``Main-Class`` and add ``extra_entries`` ({name: bytes}). Everything else (order,
    compression, comments, timestamps) is kept, so the variants differ by those entries only."""
    source, target = Path(source), Path(target)
    extra = dict(extra_entries or {})
    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w") as zout:
        for info in zin.infolist():
            if info.filename in (MARKER_ENTRY, *extra):
                continue
            data = zin.read(info)
            if main_class is not None and info.filename == "META-INF/MANIFEST.MF":
                data = replace_main_class(data, main_class)
            zout.writestr(info, data, compress_type=info.compress_type)
        zout.writestr(zipfile.ZipInfo(MARKER_ENTRY, (2026, 10, 22, 0, 0, 0)), marker.encode("utf-8"),
                      compress_type=zipfile.ZIP_DEFLATED)
        for name, data in extra.items():
            zout.writestr(zipfile.ZipInfo(name, (2026, 10, 22, 0, 0, 0)), data,
                          compress_type=zipfile.ZIP_DEFLATED)
    return target


def replace_main_class(manifest, main_class):
    """MANIFEST.MF bytes with ``Main-Class`` replaced (lines are folded at 72 bytes; Main-Class of
    the slim jar is short, but a continuation line is dropped with its header to stay correct)."""
    text = manifest.decode("utf-8")
    out, skipping, seen = [], False, False
    for line in text.splitlines():
        if skipping and line.startswith(" "):
            continue
        skipping = False
        if line.lower().startswith("main-class:"):
            out.append(f"Main-Class: {main_class}")
            skipping, seen = True, True
        else:
            out.append(line)
    if not seen:
        raise FixtureError("the jar's manifest has no Main-Class to replace")
    return ("\r\n".join(out) + "\r\n").encode("utf-8")


def compile_dead_main(javac, workdir):
    """Compile the stub Main-Class; returns the class file bytes."""
    workdir = Path(workdir)
    workdir.mkdir(parents=True, exist_ok=True)
    (workdir / "FixtureDeadMain.java").write_text(DEAD_MAIN_SOURCE)
    result = subprocess.run([str(javac), "-d", str(workdir), str(workdir / "FixtureDeadMain.java")],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise FixtureError(f"javac failed: {result.stderr.strip()}")
    return (workdir / "FixtureDeadMain.class").read_bytes()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# --------------------------------------------------------------------------- keys + signing

class Signer:
    """A throwaway Ed25519 key pair made with OpenSSL 3 outside the repository."""

    def __init__(self, directory, openssl=None):
        self.openssl = release_signing_key.find_openssl(openssl)
        self.directory = Path(directory)
        self.key, self.pub = release_signing_key.generate(self.directory, self.openssl)
        if release_signing_key.inside(self.key, REPO):
            raise FixtureError("the private key would be inside the repository")

    def sign(self, data):
        """Raw 64-byte Ed25519 signature over ``data`` (the same openssl call CI uses)."""
        with tempfile.TemporaryDirectory(dir=self.directory) as scratch:
            blob, sig = Path(scratch) / "blob", Path(scratch) / "sig"
            blob.write_bytes(data)
            release_signing_key.run(self.openssl, "pkeyutl", "-sign", "-rawin", "-inkey", str(self.key),
                                    "-in", str(blob), "-out", str(sig))
            return sig.read_bytes()

    def destroy(self):
        """Delete the private key (the public key stays for the evidence)."""
        try:
            self.key.unlink()
        except FileNotFoundError:
            pass


# ----------------------------------------------------------------------- fake release server

def release_listing(base_url, tag, assets):
    """The GitHub releases listing (one release) the updater parses.

    ``assets`` maps asset name -> bytes. Every asset gets ``browser_download_url`` on ``base_url``;
    the jar (``*.jar``) also carries the ``sha256:`` digest the updater requires.
    """
    entries = []
    for name, data in assets.items():
        entry = {"name": name, "size": len(data),
                 "browser_download_url": f"{base_url}/assets/{tag}/{name}"}
        if name.endswith(".jar"):
            entry["digest"] = "sha256:" + hashlib.sha256(data).hexdigest()
        entries.append(entry)
    return [{"tag_name": tag, "draft": False, "prerelease": False,
             "html_url": f"{base_url}/release/{tag}", "body": "Local updater rollback fixture release.",
             "assets": entries}]


def release_assets(signer, tag, jar_bytes):
    """jar + detached signature + signed manifest + manifest signature, as CI would publish them."""
    manifest = release_manifest.encode(release_manifest.build(
        tag, ASSET_NAME, hashlib.sha256(jar_bytes).hexdigest(), 100, []))
    return {ASSET_NAME: jar_bytes, ASSET_NAME + ".sig": signer.sign(jar_bytes),
            release_manifest.ASSET_NAME: manifest, release_manifest.ASSET_NAME + ".sig": signer.sign(manifest)}


class FakeReleaseServer:
    """Loopback-only HTTP server for one release. ``requests`` records every request it saw."""

    def __init__(self):
        self.requests = []
        self.tag = None
        self.assets = {}
        self._lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):                                  # noqa: N802 (http.server API)
                path = self.path.split("?", 1)[0]
                with outer._lock:
                    outer.requests.append({"path": self.path, "at": time.time()})
                    tag, assets = outer.tag, dict(outer.assets)
                body, kind = None, "application/octet-stream"
                if path == f"/repos/{REPO_SLUG}/releases" and tag is not None:
                    body = json.dumps(release_listing(outer.base_url, tag, assets)).encode()
                    kind = "application/json"
                else:
                    match = re.fullmatch(r"/assets/([^/]+)/([^/]+)", path)
                    if match and match.group(1) == tag and match.group(2) in assets:
                        body = assets[match.group(2)]
                if body is None:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", kind)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def publish(self, tag, assets):
        with self._lock:
            self.tag, self.assets = tag, dict(assets)

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self._server.shutdown()
        self._server.server_close()

    def request_paths(self):
        with self._lock:
            return [r["path"] for r in self.requests]


# --------------------------------------------------------------------------- reading evidence

def parse_properties(text):
    """java.util.Properties file text -> dict (``\\:``-style escapes undone)."""
    result = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        key, _sep, value = line.partition("=")
        result[re.sub(r"\\(.)", r"\1", key.strip())] = re.sub(r"\\(.)", r"\1", value.strip())
    return result


def read_state(workdir):
    path = Path(workdir) / "auto_update" / "rollback.state"
    return parse_properties(path.read_text()) if path.is_file() else None


def clean_log(text):
    return ANSI.sub("", text)


def log_has(log, pattern):
    return re.search(pattern, log, re.MULTILINE) is not None


def log_line(log, pattern):
    match = re.search(pattern, log, re.MULTILINE)
    if not match:
        return None
    start = log.rfind("\n", 0, match.start()) + 1
    end = log.find("\n", match.end())
    return log[start:end if end >= 0 else None].strip()


# Log lines the updater, the guard and the probe write (UpdateApplier, AutoUpdateSettings, probe).
LINES = {
    "signatures_enforced": r"release signatures ENFORCED \(TEST key",
    "override_warning": r"TEST OVERRIDE -Dsourbycraft\.update\.api-base is set",
    "staged": r"auto-updater: staged " + re.escape(FAKE_TAG) + r" at",
    "applying": r"auto-updater: applying " + re.escape(FAKE_TAG) + r" .* restart in \d+s \(mode=exit\)",
    "swapped": r"auto-updater: swapped .* -> " + re.escape(FAKE_TAG) + r" \(previous build kept at .*\.prev\)",
    "restarting": r"auto-updater: restarting now \(plan=EXIT_70\)",
    "first_boot": r"auto-updater: first boot after applying " + re.escape(FAKE_TAG) + r"; the previous jar is kept at",
    "confirmed": r"auto-updater: " + re.escape(FAKE_TAG) + r" reached ready; update confirmed",
    "rolled_back": r"UPDATE ROLLED BACK: the boot after applying " + re.escape(FAKE_TAG) + r" did not reach ready",
    "held": r"AUTO-UPDATER HELD: update " + re.escape(FAKE_TAG) + r" was rolled back",
    "already_applied": r"auto-updater: " + re.escape(FAKE_TAG) + r" already applied; nothing to do",
    "fixture_jar": r"SOURBY_UPDATER_FIXTURE jar=\S+ role=(\S+) disk-sha256=([0-9a-f]{64}) boot=(ok|fail)",
    "fixture_fail": r"SOURBY_UPDATER_FIXTURE_FAIL role=\S+ exit=\d+",
    "dead_launcher": r"SOURBY_UPDATER_FIXTURE_DEAD_LAUNCHER role=new-dead",
}


def fixture_jar_line(log):
    """(role of the RUNNING jar, SHA-256 of the file on disk at that moment, boot) from the probe, or None."""
    match = re.search(LINES["fixture_jar"], log)
    return match.groups() if match else None


class Checks:
    """Ordered list of (label, ok, detail); the fixture passes when all are ok."""

    def __init__(self):
        self.items = []

    def add(self, label, ok, detail=""):
        self.items.append({"check": label, "ok": bool(ok), "detail": str(detail)})
        print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f" -- {detail}" if detail else ""), flush=True)
        return bool(ok)

    def skip(self, label, detail=""):
        self.items.append({"check": label, "ok": True, "skipped": True, "detail": str(detail)})
        print(f"  [SKIP] {label} -- {detail}", flush=True)

    @property
    def ok(self):
        return all(item["ok"] for item in self.items)


# ------------------------------------------------------------------------------ the scenarios

RESOURCE_REFUSALS = ("RAM available", "Gradle builds are running")


def with_resource_retry(call, attempts=30, delay=20.0, sleep=time.sleep, log=print):
    """Run ``call`` (a harness start/restart); a refusal because both slots are busy, RAM is low or
    too many Gradle builds run (a shared machine) is waited out, anything else is raised at once.
    These refusals happen before the run directory is touched, so the retry is safe."""
    for attempt in range(1, attempts + 1):
        try:
            return call()
        except ats.LockBusy as problem:
            reason = str(problem)
        except ats.HarnessError as problem:
            reason = str(problem)
            if not any(word in reason for word in RESOURCE_REFUSALS):
                raise
        if attempt == attempts:
            raise FixtureError(f"gave up after {attempts} tries: {reason}")
        log(f"  (machine busy: {reason.splitlines()[0]}; waiting {delay:.0f} s, try {attempt}/{attempts})", flush=True)
        sleep(delay)

class Fixture:
    """One scenario run: fake server, signer, jars, the harness and the evidence directory."""

    def __init__(self, scenario, name, jar, plugin_jar, out_dir, harness=None, openssl=None,
                 boot_timeout_s=240):
        if scenario not in SCENARIOS:
            raise FixtureError(f"unknown scenario {scenario!r} (one of {', '.join(SCENARIOS)})")
        self.scenario, self.name = scenario, name
        self.jar, self.plugin_jar = Path(jar), Path(plugin_jar)
        for path, hint in ((self.jar, "build it: bash scripts/gradle_locked.sh slimServerJar"),
                           (self.plugin_jar, "build it: bash scripts/gradle_locked.sh -PincludeTestPlugin=true :test-plugin:jar")):
            if not path.is_file():
                raise FixtureError(f"{path} is missing ({hint})")
        self.out_dir = Path(out_dir)
        self.harness = harness or ats.Harness(jar=self.out_dir / "jars" / "original" / "SourbyCraft-slim.jar")
        self.openssl, self.boot_timeout_s = openssl, boot_timeout_s
        self.checks = Checks()
        self.boots = []             # per boot: {"boot", "run", "log", "launch_sha256", "state"}
        self.signer = self.server = None
        self.jars = {}

    # -- setup / teardown
    def prepare(self):
        self.out_dir.mkdir(parents=True, exist_ok=True)
        (self.out_dir / "jars").mkdir(exist_ok=True)
        self.signer = Signer(self.out_dir / "keys", self.openssl)
        self.server = FakeReleaseServer().start()
        original = self.out_dir / "jars" / "original" / "SourbyCraft-slim.jar"
        original.parent.mkdir(exist_ok=True)
        repack_jar(self.jar, original, marker_text("original"))
        self.harness.jar = original
        self.jars["original"] = original
        if self.scenario == "healthy":
            new = repack_jar(self.jar, self.out_dir / "jars" / "new-good.jar", marker_text("new-good"))
        elif self.scenario == "rollback":
            new = repack_jar(self.jar, self.out_dir / "jars" / "new-broken.jar",
                             marker_text("new-broken", fail=True, exit_code=EXIT_BROKEN))
        else:
            javac = Path(ats.java_probe()[0]).parent / "javac"
            stub = compile_dead_main(javac, self.out_dir / "stub")
            new = repack_jar(self.jar, self.out_dir / "jars" / "new-dead.jar", marker_text("new-dead"),
                             main_class="FixtureDeadMain", extra_entries={"FixtureDeadMain.class": stub})
        self.jars["new"] = new
        data = new.read_bytes()
        if len(data) > MAX_JAR_BYTES:
            raise FixtureError("fixture jar is larger than the updater accepts")
        self.server.publish(FAKE_TAG, release_assets(self.signer, FAKE_TAG, data))

    def teardown(self):
        if self.signer is not None:
            self.signer.destroy()
        if self.server is not None:
            self.server.stop()

    # -- boots
    def jvm_properties(self):
        return [f"{ats.UPDATER_API_PROPERTY}={self.server.base_url}",
                f"{ats.UPDATER_KEY_PROPERTY}={self.signer.pub}", "sourby.testplugin.updater-fixture=true"]

    def start_first_boot(self):
        run = with_resource_retry(lambda: self.harness.start(
            self.name, max_lifetime_s=900, startup_timeout_s=self.boot_timeout_s,
            jvm_properties=self.jvm_properties(),
            config_files={"plugins/SourbyCraft-Test-Plugin.jar": str(self.plugin_jar)},
            auto_update={"apply_mode": "auto", "restart_mode": "exit", "restart_delay_seconds": 5,
                         "check_interval_minutes": 0, "channel": "REL"}))
        return run

    def workdir(self):
        return self.harness.existing(self.name)

    def wait_for_exit(self, timeout=180):
        workdir = self.workdir()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            run = ats.read_json(workdir / "run.json") or {}
            if run.get("status") in ats.FINAL_STATES and "lock_released" in run:
                return run
            time.sleep(0.5)
        raise FixtureError(f"the server did not exit within {timeout}s")

    def record_boot(self, run):
        workdir = self.workdir()
        log = clean_log((workdir / "server.log").read_text(errors="replace"))
        number = int(run.get("boot", 1))
        (self.out_dir / "evidence").mkdir(exist_ok=True)
        (self.out_dir / "evidence" / f"boot-{number}.log").write_text(log)
        launch = workdir / Path(run["jar"]["copied_to"]).name
        record = {"boot": number, "run": run, "log": log, "launch_sha256": sha256_file(launch),
                  "state": read_state(workdir)}
        self.boots.append(record)
        return record

    def restart(self):
        """Boot the same directory again; returns the settled run.json (ready, or ended)."""
        return with_resource_retry(lambda: self.harness.restart(self.name, startup_timeout_s=self.boot_timeout_s))

    def stop_if_running(self):
        run = ats.read_json(self.workdir() / "run.json") or {}
        if run.get("status") not in ats.FINAL_STATES:
            return self.harness.stop(self.name, timeout=120)
        return run

    # -- the shared first part: boot 1 applies the release and exits 70
    def apply_phase(self):
        c, orig = self.checks, self.jars["original"]
        run = self.start_first_boot()
        first = self.harness.workdir(self.name)
        log = clean_log((first / "server.log").read_text(errors="replace"))
        c.add("boot 1 reached Done on the original jar", run.get("status") == "ready", run.get("done_line"))
        probe = fixture_jar_line(log)
        c.add("boot 1 probe reports role=original with the original jar's SHA-256",
              probe is not None and probe[0] == "original" and probe[1] == sha256_file(orig), probe)
        c.add("signatures enforced from the TEST key, endpoint overridden to loopback",
              log_has(log, LINES["signatures_enforced"]) or log_has(log, r"signatures=enforced"),
              log_line(log, LINES["signatures_enforced"]) or log_line(log, r"signatures=enforced"))
        self.harness.command(self.name, "update apply", timeout=30, quiet=3.0, bracket=False)
        if self.harness.wait_for(self.name, LINES["applying"], timeout=60) is None:
            c.add("/update apply scheduled the apply", False, "no 'auto-updater: applying' line within 60 s")
            raise FixtureError("the updater did not apply the release")
        ended = self.wait_for_exit()
        record = self.record_boot(ended)
        log = record["log"]
        for key, label in (("staged", "release staged (digest, jar signature, manifest signature, launcher gate passed)"),
                           ("applying", "apply scheduled (restart_mode=exit, delay 5 s)"),
                           ("swapped", "launch jar swapped; previous build kept as <jar>.prev"),
                           ("restarting", "restart requested (plan=EXIT_70)")):
            c.add(label, log_has(log, LINES[key]), log_line(log, LINES[key]))
        c.add("boot 1 exited with code 70", ended.get("exit_code") == EXIT_APPLY, ended.get("exit_code"))
        wd = self.workdir()
        launch = wd / Path(ended["jar"]["copied_to"]).name
        prev = launch.with_name(launch.name + ".prev")
        new_sha = sha256_file(self.jars["new"])
        c.add("launch jar on disk is now the release jar", sha256_file(launch) == new_sha, sha256_file(launch)[:16])
        c.add("<jar>.prev is the original jar", prev.is_file() and sha256_file(prev) == sha256_file(orig),
              sha256_file(prev)[:16] if prev.is_file() else "missing")
        state = record["state"] or {}
        c.add("rollback.state phase=APPLIED with the fake tag", state.get("phase") == "APPLIED" and state.get("tag") == FAKE_TAG, state)
        c.add("prev_sha256 records the original jar in lowercase hex", state.get("prev_sha256") == sha256_file(orig), state.get("prev_sha256"))
        applied = wd / "auto_update" / "applied.tag"
        c.add("applied.tag holds the fake tag", applied.is_file() and applied.read_text().strip() == FAKE_TAG)
        paths = self.server.request_paths()
        c.add("every request went to the loopback fake server (listing, manifest + sig, jar + sig)",
              any("/releases" in p for p in paths) and any(p.endswith(".jar") for p in paths)
              and any(p.endswith(".jar.sig") for p in paths) and any(p.endswith("manifest.json") for p in paths)
              and any(p.endswith("manifest.json.sig") for p in paths), paths)
        return launch, prev

    def run_rollback(self):
        c, orig = self.checks, self.jars["original"]
        launch, prev = self.apply_phase()
        orig_sha, broken_sha = sha256_file(orig), sha256_file(self.jars["new"])

        run2 = self.restart()
        rec2 = self.record_boot(run2)
        c.add("boot 2 (broken jar) did NOT reach ready", run2.get("status") != "ready" and not run2.get("done_line"),
              run2.get("status"))
        if run2.get("status") == "ready":
            raise FixtureError("the broken jar reached ready")
        probe = fixture_jar_line(rec2["log"])
        c.add("boot 2 probe reports role=new-broken", probe is not None and probe[0] == "new-broken" and probe[1] == broken_sha, probe)
        c.add("boot 2 ran the guard: first boot after the apply, previous jar kept",
              log_has(rec2["log"], LINES["first_boot"]), log_line(rec2["log"], LINES["first_boot"]))
        c.add(f"boot 2 died with exit code {EXIT_BROKEN} before ready", run2.get("exit_code") == EXIT_BROKEN
              and log_has(rec2["log"], LINES["fixture_fail"]), run2.get("exit_code"))
        c.add("rollback.state phase=BOOTING after boot 2", (rec2["state"] or {}).get("phase") == "BOOTING", rec2["state"])
        c.add("BOOTING preserves prev_sha256", (rec2["state"] or {}).get("prev_sha256") == orig_sha)
        c.add("launch jar still the broken one (the restore has not happened yet)", rec2["launch_sha256"] == broken_sha)

        run3 = self.restart()
        rec3 = self.record_boot(run3)
        if launcher_restore_expected():
            c.add("boot 3 launcher restored before server code and exited 71",
                  run3.get("exit_code") == 71 and "UPDATE ROLLED BACK by launcher:" in rec3["log"]
                  and fixture_jar_line(rec3["log"]) is None, run3.get("exit_code"))
        else:
            c.add("boot 3 found BOOTING and logged UPDATE ROLLED BACK", log_has(rec3["log"], LINES["rolled_back"]),
                  log_line(rec3["log"], LINES["rolled_back"]))
            probe = fixture_jar_line(rec3["log"])
            c.add("boot 3 still RAN the broken jar's code (role=new-broken; a JVM cannot replace its own jar) while the file"
                  " on disk was already the original (disk-sha256), and it died the same way",
                  probe is not None and probe[0] == "new-broken" and probe[1] == orig_sha
                  and run3.get("exit_code") == EXIT_BROKEN, probe)
        if run3.get("status") == "ready":
            raise FixtureError("boot 3 reached ready; the broken jar must not")
        c.add("after boot 3 the launch jar is byte-identical to the original", rec3["launch_sha256"] == orig_sha, rec3["launch_sha256"][:16])
        c.add("rollback.state phase=ROLLED_BACK", (rec3["state"] or {}).get("phase") == "ROLLED_BACK", rec3["state"])
        c.add("ROLLED_BACK preserves prev_sha256", (rec3["state"] or {}).get("prev_sha256") == orig_sha)
        c.add("<jar>.prev is kept after the restore (copy, not move)", prev.is_file() and sha256_file(prev) == orig_sha)

        run4 = self.restart()
        log4 = clean_log((self.workdir() / "server.log").read_text(errors="replace"))
        c.add("boot 4 reached Done", run4.get("status") == "ready", run4.get("done_line"))
        probe = fixture_jar_line(log4)
        c.add("boot 4 ran the ORIGINAL jar (role=original, original SHA-256)",
              probe is not None and probe[0] == "original" and probe[1] == orig_sha, probe)
        c.add("boot 4 logs the held updater and does not check or apply", log_has(log4, LINES["held"]),
              log_line(log4, LINES["held"]))
        before = len(self.server.request_paths())
        _ok, text = self.harness.command(self.name, "update apply", timeout=30, quiet=3.0, bracket=False)
        c.add("while held, /update apply makes no request to the release server and says HELD",
              len(self.server.request_paths()) == before and log_has(text, r"HELD"),
              log_line(text, r"HELD"))
        self.harness.command(self.name, "update resume", timeout=30, quiet=2.0, bracket=False)
        c.add("/update resume removed rollback.state", not (self.workdir() / "auto_update" / "rollback.state").exists())
        self.harness.command(self.name, "update apply", timeout=30, quiet=3.0, bracket=False)
        time.sleep(3)
        log4 = clean_log((self.workdir() / "server.log").read_text(errors="replace"))
        c.add("after resume the rolled-back tag is not offered again (applied.tag)", log_has(log4, LINES["already_applied"]),
              log_line(log4, LINES["already_applied"]))
        final = self.harness.stop(self.name, timeout=120)
        self.record_boot(final)
        c.add("boot 4 stopped cleanly (exit 0, lock released)", final.get("exit_code") == 0 and final.get("lock_released")
              and not final.get("jvm_still_running"), final.get("exit_code"))
        c.add("the launch jar is still the original after the clean stop", sha256_file(launch) == orig_sha)

    def run_healthy(self):
        c, orig = self.checks, self.jars["original"]
        launch, prev = self.apply_phase()
        orig_sha, good_sha = sha256_file(orig), sha256_file(self.jars["new"])
        run2 = self.restart()
        log2 = clean_log((self.workdir() / "server.log").read_text(errors="replace"))
        c.add("boot 2 (good jar) reached Done", run2.get("status") == "ready", run2.get("done_line"))
        probe = fixture_jar_line(log2)
        c.add("boot 2 ran the new jar (role=new-good, release jar SHA-256)",
              probe is not None and probe[0] == "new-good" and probe[1] == good_sha, probe)
        c.add("boot 2 ran the guard (first boot after the apply)", log_has(log2, LINES["first_boot"]),
              log_line(log2, LINES["first_boot"]))
        c.add("the guard confirmed the update at ready", log_has(log2, LINES["confirmed"]), log_line(log2, LINES["confirmed"]))
        state = read_state(self.workdir()) or {}
        c.add("rollback.state phase=OK", state.get("phase") == "OK", state)
        c.add("OK preserves prev_sha256", state.get("prev_sha256") == orig_sha)
        c.add("no rollback block was logged", not log_has(log2, LINES["rolled_back"]) and not log_has(log2, LINES["held"]))
        c.add("the new jar is kept on disk", sha256_file(launch) == good_sha)
        c.add("<jar>.prev is kept (the original) after the confirmation", prev.is_file() and sha256_file(prev) == orig_sha)
        self.harness.command(self.name, "update apply", timeout=30, quiet=3.0, bracket=False)
        time.sleep(3)
        log2 = clean_log((self.workdir() / "server.log").read_text(errors="replace"))
        c.add("the same tag is not applied twice", log_has(log2, LINES["already_applied"]), log_line(log2, LINES["already_applied"]))
        final = self.harness.stop(self.name, timeout=120)
        self.record_boot(final)
        c.add("boot 2 stopped cleanly (exit 0, lock released)", final.get("exit_code") == 0 and final.get("lock_released")
              and not final.get("jvm_still_running"), final.get("exit_code"))
        c.add("the new jar is still on disk after the clean stop", sha256_file(launch) == good_sha)

    def run_dead_launcher(self):
        c, orig = self.checks, self.jars["original"]
        launch, prev = self.apply_phase()
        orig_sha, dead_sha = sha256_file(orig), sha256_file(self.jars["new"])
        expected = launcher_restore_expected()
        if not expected:
            c.skip("launcher restore (exit 71) requires SourbyClip >= 3.1.4",
                   f"pinned clipVersion={pinned_clip_version()}; checking legacy/manual recovery")
        for number in (2, 3):
            run = self.restart()
            rec = self.record_boot(run)
            if run.get("status") == "ready":
                raise FixtureError(f"boot {number} of the dead-launcher jar reached ready")
            if expected and number == 3:
                state = rec["state"] or {}
                c.add("launcher restores on boot 3 and exits 71 before server code",
                      run.get("exit_code") == 71 and "UPDATE ROLLED BACK by launcher:" in rec["log"]
                      and not log_has(rec["log"], LINES["dead_launcher"]), run.get("exit_code"))
                c.add("launcher records ROLLED_BACK, fake tag and retained hash",
                      state.get("phase") == "ROLLED_BACK" and state.get("tag") == FAKE_TAG
                      and state.get("prev_sha256") == orig_sha
                      and state.get("note", "").startswith("launcher: the boot after applying " + FAKE_TAG
                          + " did not reach the updater stage or ready; "), state)
                c.add("launcher restored the original jar", rec["launch_sha256"] == orig_sha)
            else:
                c.add(f"boot {number} exited {EXIT_DEAD} before any SourbyCraft code",
                      run.get("exit_code") == EXIT_DEAD and log_has(rec["log"], LINES["dead_launcher"])
                      and not log_has(rec["log"], LINES["first_boot"]) and fixture_jar_line(rec["log"]) is None,
                      run.get("exit_code"))
                c.add(f"boot {number}: state remains APPLIED and launch jar is still dead",
                      (rec["state"] or {}).get("phase") == "APPLIED" and rec["launch_sha256"] == dead_sha, rec["state"])
        c.add("<jar>.prev is intact", prev.is_file() and sha256_file(prev) == orig_sha)
        if not expected:
            c.add("legacy pin: no automatic recovery after two failed boots", sha256_file(launch) == dead_sha)
            shutil.copyfile(prev, launch)          # the operator's manual step for old clips
        run4 = self.restart()
        log4 = clean_log((self.workdir() / "server.log").read_text(errors="replace"))
        probe = fixture_jar_line(log4)
        c.add("recovery boots to Done on the original",
              run4.get("status") == "ready" and probe is not None and probe[0] == "original", run4.get("done_line"))
        if expected:
            c.add("launcher rollback holds the updater", log_has(log4, LINES["held"]))
            before = len(self.server.request_paths())
            _, text = self.harness.command(self.name, "update apply", timeout=30, quiet=3.0, bracket=False)
            c.add("launcher rollback refuses reapply without network requests",
                  len(self.server.request_paths()) == before and log_has(text, r"HELD"))
        final = self.harness.stop(self.name, timeout=120)
        self.record_boot(final)
        c.add("recovered boot stopped cleanly (exit 0, lock released)", final.get("exit_code") == 0 and final.get("lock_released"))

    # -- driver
    def run(self):
        started = time.time()
        error = None
        try:
            self.prepare()
            {"rollback": self.run_rollback, "healthy": self.run_healthy,
             "dead-launcher": self.run_dead_launcher}[self.scenario]()
        except (FixtureError, ats.HarnessError) as problem:
            error = str(problem)
            self.checks.add("fixture ran to the end", False, error)
        finally:
            try:
                self.stop_if_running()
            except (ats.HarnessError, FixtureError, OSError):
                pass
            self.teardown()
        summary = {
            "schema": SCHEMA, "scenario": self.scenario, "name": self.name, "ok": self.checks.ok and error is None,
            "error": error, "started": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(started)),
            "seconds": round(time.time() - started, 1), "tag": FAKE_TAG,
            "jars": {k: {"path": str(v), "sha256": sha256_file(v)} for k, v in self.jars.items() if Path(v).is_file()},
            "fake_server_requests": self.server.request_paths() if self.server else [],
            "public_key": str(self.signer.pub) if self.signer else None,
            "private_key_deleted": bool(self.signer and not self.signer.key.exists()),
            "boots": [{"boot": b["boot"], "exit_code": b["run"].get("exit_code"), "status": b["run"].get("status"),
                       "reached_ready": bool(b["run"].get("done_line")), "boot_seconds": b["run"].get("boot_seconds"),
                       "launch_sha256_after": b["launch_sha256"], "rollback_state_after": b["state"]} for b in self.boots],
            "checks": self.checks.items, "workdir": str(self.harness.workdir(self.name)),
        }
        (self.out_dir / "summary.json").write_text(json.dumps(summary, indent=2, default=str))
        return summary


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="action", required=True)
    run = sub.add_parser("run", help="run one scenario (or all) and print the verdict")
    run.add_argument("--scenario", choices=[*SCENARIOS, "all"], default="all")
    run.add_argument("--name", required=True, help="run name prefix; the harness directory is <name>-<scenario>")
    run.add_argument("--jar", type=Path, default=DEFAULT_JAR, help="the slim jar to repack (default build/libs)")
    run.add_argument("--plugin-jar", type=Path, default=DEFAULT_PLUGIN)
    run.add_argument("--out", type=Path, help="evidence directory (default <agent-testservers>/_updater-fixture-<name>)")
    run.add_argument("--boot-timeout", type=int, default=240)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    names = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    results = []
    for scenario in names:
        name = f"{args.name}-{scenario}"
        out = (args.out or ats.BASE / f"_updater-fixture-{args.name}") / scenario
        print(f"== scenario {scenario} (run {name}, evidence {out})", flush=True)
        try:
            fixture = Fixture(scenario, name, args.jar, args.plugin_jar, out, boot_timeout_s=args.boot_timeout)
            summary = fixture.run()
        except (FixtureError, ats.HarnessError, release_signing_key.SigningKeyError) as problem:
            print(f"updater_rollback_fixture: {problem}", file=sys.stderr)
            return 2
        results.append(summary)
        print(f"== {scenario}: {'PASS' if summary['ok'] else 'FAIL'} in {summary['seconds']} s", flush=True)
    print(json.dumps({"ok": all(r["ok"] for r in results),
                      "scenarios": {r["scenario"]: r["ok"] for r in results}}, indent=2))
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
