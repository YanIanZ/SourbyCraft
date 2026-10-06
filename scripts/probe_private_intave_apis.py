#!/usr/bin/env python3
"""Compile exact upstream API tag sources against a supplied local server classpath.

This is a source compatibility probe, not a substitute for Maven artifact verification,
dependency resolution, upstream API tests, or the full private server build.
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

from private_intave_workspace import ROOT, managed_path, read_plan
from prepare_private_intave import blob_hash


def verified_api_sources() -> tuple[list[Path], list[dict]]:
    plan = read_plan()
    source_files = []
    provenance = []
    for api in plan["dependency_sources"]:
        root = ROOT / ".private-intave/dependencies/snapshots" / api["repository"].split("/")[1] / api["commit"]
        inventory = json.loads((root / "source-provenance.json").read_text())
        if inventory["commit"] != api["commit"] or inventory["repository"] != api["repository"]:
            raise ValueError("API source provenance differs from the pinned plan")
        names = set()
        for entry in inventory["files"]:
            name = entry["path"]
            path = managed_path(root, name)
            if name in names or blob_hash(path.read_bytes()) != entry["git_blob"]:
                raise ValueError(f"API source changed/duplicate; preserved: {name}")
            names.add(name)
            if name.startswith("src/main/java/") and name.endswith(".java"):
                source_files.append(path)
        actual = {path.relative_to(root).as_posix() for path in (root / "src/main/java").rglob("*.java")}
        if not actual or actual - names:
            raise ValueError("Unrecorded or empty API Java sources")
        provenance.append({"coordinate": api["coordinate"], "commit": api["commit"]})
    return source_files, provenance


def probe(classpath_file: Path, java_home: Path | None = None) -> dict:
    source_files, provenance = verified_api_sources()
    java = java_home / "bin/javac" if java_home else shutil.which("javac")
    if not java:
        raise ValueError("JDK 25 is required")
    entries = classpath_file.read_text().strip().split(os.pathsep)
    if any(not entry for entry in entries):
        raise ValueError("Empty classpath entry")
    classpath = os.pathsep.join(str(Path(entry).resolve(strict=True)) for entry in entries)
    with tempfile.TemporaryDirectory(prefix="intave-api-probe-") as temporary:
        subprocess.run([str(java), "--release", "25", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", temporary, *map(str, source_files)],
                       capture_output=True, text=True, check=True, timeout=60)
    return {"api_source_compile_passed": True, "source_files": len(source_files),
            "apis": provenance, "classpath_file_sha256": hashlib.sha256(classpath_file.read_bytes()).hexdigest(),
            "maven_artifact_bytes_verified": False, "upstream_api_tests_run": False,
            "full_engine_build_verified": False, "server_boot_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--java-home", type=Path,
                        default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args = parser.parse_args()
    try:
        print(json.dumps(probe(args.classpath_file, args.java_home), indent=2))
    except subprocess.CalledProcessError as failure:
        parser.exit(1, f"API source probe failed:\n{failure.stdout}\n{failure.stderr}\n")
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"API source probe failed: {failure}\n")


if __name__ == "__main__":
    main()
