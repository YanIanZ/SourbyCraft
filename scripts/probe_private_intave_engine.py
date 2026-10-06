#!/usr/bin/env python3
"""Compile native engine sources with the real Connection patch and verified API source tags.

Diagnostic only: no substitute artifacts, dependency stubs, server boot or qualification.
The supplied server/runtime classpath must come from actual local 26.2 artifacts.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from private_intave_workspace import ROOT, inspect, read_plan
from probe_private_intave_apis import verified_api_sources
from probe_native_intave_packets import CONNECTION, PATCH


def probe(classpath_file: Path, log_file: Path, java_home: Path | None = None) -> dict:
    workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
    checked = inspect(workspace, Path("/nonexistent-intave-cache"))
    if checked["changed_sources"] or checked["changed_local_compile_libraries"]:
        raise ValueError("Native workspace differs from verified ledger; preserved")
    entries = classpath_file.read_text().strip().split(os.pathsep)
    if not entries or any(not entry for entry in entries):
        raise ValueError("Empty runtime classpath entry")
    classpath = os.pathsep.join(str(Path(entry).resolve(strict=True)) for entry in entries)
    javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
    if not javac: raise ValueError("JDK 25 is required")
    api_sources, api_provenance = verified_api_sources()
    sources = sorted((workspace / "src/main/java").rglob("*.java"))
    sources += sorted((workspace / "generated/main/java").rglob("*.java"))
    sources += sorted((ROOT / "sourbycraft-server/src/main/java/dev/yanianz").rglob("*.java"))
    controller = ROOT / "sourbycraft-server/src/main/java/dev/yanianz/intave/NativeIntave.java"
    with tempfile.TemporaryDirectory(prefix="intave-engine-probe-") as temporary:
        directory = Path(temporary)
        connection = directory / CONNECTION
        connection.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "sourbycraft-server/src/minecraft/java" / CONNECTION, connection)
        if "public void sendNative(" not in connection.read_text():
            subprocess.run(["git", "apply", "--include=" + CONNECTION.as_posix(), str(PATCH)],
                           cwd=directory, check=True, capture_output=True, text=True, timeout=30)
        bridge = directory / "bridge"
        subprocess.run([javac, "--release", "25", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(bridge), str(connection), str(controller)],
                       check=True, capture_output=True, text=True, timeout=60)
        # The actual patched class must precede the older packaged server Connection.
        compile_classpath = str(bridge) + os.pathsep + classpath + os.pathsep + os.pathsep.join(
            str(path.resolve()) for path in sorted((workspace / "libs").glob("*.jar")))
        result = subprocess.run([javac, "--release", "25", "-encoding", "UTF-8", "-Xmaxerrs", "1000",
                                 "-cp", compile_classpath, "-d", str(directory / "engine"),
                                 *map(str, sources + api_sources)],
                                capture_output=True, text=True, timeout=120)
        log = result.stdout + result.stderr
        log_file.parent.mkdir(parents=True, exist_ok=True)
        log_file.write_text(log, encoding="utf-8")
        failures = re.findall(r"([^\n]+\.java):(\d+): error: ([^\n]+)", log)
        return {"engine_source_compile_passed": result.returncode == 0,
                "engine_source_files": len(sources), "api_source_files": len(api_sources),
                "compiler_errors": len(failures), "error_source_files": sorted({Path(path).name for path, _, _ in failures}),
                "compiler_log": str(log_file), "connection_sha256": hashlib.sha256(connection.read_bytes()).hexdigest(),
                "api_sources": api_provenance, "maven_artifact_bytes_verified": False,
                "gradle_build_verified": False, "server_boot_verified": False, "anticheat_effectiveness_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--compiler-log", type=Path, default=ROOT / "build/private-intave/engine-diagnostic.log")
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    try:
        report = probe(args.classpath_file, args.compiler_log, args.java_home)
        print(json.dumps(report, indent=2))
        if not report["engine_source_compile_passed"]: raise SystemExit(1)
    except subprocess.CalledProcessError as failure:
        parser.exit(1, f"Native engine diagnostic failed:\n{failure.stdout}\n{failure.stderr}\n")
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"Native engine diagnostic failed: {failure}\n")


if __name__ == "__main__": main()
