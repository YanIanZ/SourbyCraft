#!/usr/bin/env python3
"""Boot an isolated Java 25 test server, record warmed JFR, verify clean shutdown."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--world", type=Path, help="Copy a saved world for a matched comparison")
    parser.add_argument("--warmup", type=int, default=60)
    parser.add_argument("--duration", type=int, default=120)
    parser.add_argument("--port", type=int, default=25585)
    parser.add_argument("--expect-config-unchanged", action="store_true")
    args = parser.parse_args()
    if args.duration < 1 or args.warmup < 0:
        parser.error("duration must be positive; warmup cannot be negative")
    jar = args.jar.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    java = Path(shutil.which("java")).resolve()
    jcmd, jfr = java.with_name("jcmd"), java.with_name("jfr")
    java_version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True)
    if 'version "25' not in java_version:
        raise RuntimeError("Java 25 required")
    (output / "eula.txt").write_text("eula=true\n")
    (output / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n"
        "level-type=minecraft\\:flat\nlevel-seed=440044\nspawn-protection=0\n"
        "enable-query=false\nenable-rcon=false\n")
    config = output / "sourbycraft_config" / "sourbycraft_global_config.toml"
    config.parent.mkdir()
    config.write_text("# Isolated profiling fixture; disable external plugin/version changes.\n"
                      "[viaversion]\nauto-provision=false\n"
                      "[misc.auto_update]\nenabled=false\n")
    config_before = sha256(config)
    if args.world:
        shutil.copytree(args.world.resolve(strict=True), output / "world")
    if args.fixture:
        (output / "plugins").mkdir()
        shutil.copy2(args.fixture.resolve(strict=True), output / "plugins" / args.fixture.name)
    command = [str(java), "-Xmx2G", "-XX:+UseG1GC", "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
               "-jar", str(jar), "--nogui"]
    metadata = {"commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"])),
                "jar": str(jar), "sha256": sha256(jar), "java": java_version,
                "platform": platform.platform(), "cpu_count": os.cpu_count(),
                "command": command, "warmup_seconds": args.warmup, "duration_seconds": args.duration,
                "workload": "idle; zero players; explicitly forceloaded spawn chunk",
                "fixture_sha256": sha256(args.fixture) if args.fixture else None,
                "config_before_sha256": config_before,
                "status": "running"}
    metadata_path = output / "metadata.json"
    started = time.monotonic()
    process = None
    try:
        with (output / "server.log").open("w") as log:
            process = subprocess.Popen(command, cwd=output, stdin=subprocess.PIPE, stdout=log,
                                       stderr=subprocess.STDOUT, text=True)

            def send(commands):
                process.stdin.write(commands + "\n")
                process.stdin.flush()

            def wait_alive(seconds):
                deadline = time.monotonic() + seconds
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError(f"Server exited early: {process.returncode}")
                    time.sleep(min(1, max(0, deadline - time.monotonic())))

            while "Done (" not in (output / "server.log").read_text(errors="replace"):
                if time.monotonic() - started > 300:
                    raise RuntimeError("Server startup timeout (300s)")
                wait_alive(1)
            metadata["startup_seconds"] = time.monotonic() - started
            print(f"Ready: {output}; warming {args.warmup}s", flush=True)
            send("forceload add 0 0")
            wait_alive(args.warmup)
            subprocess.run([str(jcmd), str(process.pid), "JFR.start", "name=SourbyProfile", "settings=profile",
                            f"duration={args.duration}s", f"filename={output / 'profile.jfr'}"], check=True)
            send("version\ntps\nmspt\nperf\nram")
            wait_alive(args.duration + 2)
            for name, diagnostic in (("threads.txt", "Thread.print"), ("heap.txt", "GC.heap_info")):
                with (output / name).open("w") as target:
                    subprocess.run([str(jcmd), str(process.pid), diagnostic], stdout=target, check=True)
            send("save-all\ntps\nmspt\nstop")
            shutdown = time.monotonic()
            process.stdin.close()
            if process.wait(timeout=60) != 0:
                raise RuntimeError(f"Unclean server exit: {process.returncode}")
            metadata["shutdown_seconds"] = time.monotonic() - shutdown
        text = (output / "server.log").read_text(errors="replace")
        if args.fixture:
            for marker in ("SOURBY_METRICS_ONLOAD_OK", "SOURBY_METRICS_ONENABLE_OK"):
                if marker not in text:
                    raise RuntimeError(f"Missing fixture marker {marker}")
        if not (output / "world" / "level.dat").is_file():
            raise RuntimeError("World metadata not persisted")
        metadata["config_after_sha256"] = sha256(config)
        if args.expect_config_unchanged and sha256(config) != config_before:
            raise RuntimeError("Operator utility configuration changed during boot/run/stop")
        with (output / "jfr-summary.txt").open("w") as summary:
            subprocess.run([str(jfr), "summary", str(output / "profile.jfr")], stdout=summary, check=True)
        metadata["status"] = "passed"
        print(f"Passed: {output / 'profile.jfr'}", flush=True)
    except BaseException as failure:
        metadata["status"] = "failed"
        metadata["failure"] = str(failure)
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    main()
