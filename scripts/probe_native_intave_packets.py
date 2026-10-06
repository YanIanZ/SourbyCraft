#!/usr/bin/env python3
"""Compile and exercise the actual native Connection patch against a supplied 26.2 classpath.

Runs isolated JVMs with Netty EmbeddedChannel. Does not boot a server or certify Intave.
Materialized Minecraft sources and their Git working copy are left unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCH = ROOT / "sourbycraft-server/minecraft-patches/features/0024-SourbyCraft-private-native-Intave-lifecycle.patch"
CONNECTION = Path("net/minecraft/network/Connection.java")
SCENARIOS = ("ordered", "clear", "filtered", "write-failure")


def run_probe(classpath_file: Path, java_home: Path | None = None) -> dict:
    entries = classpath_file.read_text().strip().split(os.pathsep)
    if not entries or any(not entry for entry in entries):
        raise ValueError("Classpath must list the actual SourbyCraft server/API and runtime libraries")
    classpath = os.pathsep.join(str(Path(entry).resolve(strict=True)) for entry in entries)
    java = str(java_home / "bin/java") if java_home else shutil.which("java")
    javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
    if not java or not javac:
        raise ValueError("JDK 25 is required")
    controller = ROOT / "sourbycraft-server/src/main/java/dev/yanianz/intave/NativeIntave.java"
    fixture = ROOT / "scripts/fixtures/intave/NativeIntavePacketQueueProbe.java"
    with tempfile.TemporaryDirectory(prefix="intave-packet-probe-") as temporary:
        directory = Path(temporary)
        source = directory / CONNECTION
        source.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "sourbycraft-server/src/minecraft/java" / CONNECTION, source)
        if "public void sendNative(" not in source.read_text():
            subprocess.run(["git", "apply", "--include=" + CONNECTION.as_posix(), str(PATCH)],
                           cwd=directory, check=True, capture_output=True, text=True, timeout=30)
        classes = directory / "classes"
        subprocess.run([javac, "--release", "25", "-cp", classpath, "-d", str(classes),
                        str(controller), str(source), str(fixture)],
                       check=True, capture_output=True, text=True, timeout=60)
        for scenario in SCENARIOS:
            subprocess.run([java, "-cp", str(classes) + os.pathsep + classpath,
                            "NativeIntavePacketQueueProbe", scenario], cwd=directory,
                           check=True, capture_output=True, text=True, timeout=30)
        return {"scenarios_passed": list(SCENARIOS),
                "connection_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "patch_sha256": hashlib.sha256(PATCH.read_bytes()).hexdigest(),
                "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
                "engine_compiled": False, "server_boot_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--java-home", type=Path,
                        default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args = parser.parse_args()
    try:
        print(json.dumps(run_probe(args.classpath_file, args.java_home), indent=2))
    except subprocess.CalledProcessError as failure:
        parser.exit(1, f"Native packet probe failed:\n{failure.stdout}\n{failure.stderr}\n")
    except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"Native packet probe failed: {failure}\n")


if __name__ == "__main__":
    main()
