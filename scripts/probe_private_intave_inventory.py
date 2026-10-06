#!/usr/bin/env python3
"""Check inventory, read-only AIR fallback and fluid mapping against actual local 26.2 classes.

Does not boot a server, run missing replay fixtures or verify the full Intave engine.
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

from private_intave_workspace import ROOT, inspect, read_plan


def probe(classpath_file: Path, java_home: Path | None = None) -> dict:
    workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
    checked = inspect(workspace, Path("/nonexistent-intave-cache"))
    if checked["changed_sources"] or checked["changed_local_compile_libraries"]:
        raise ValueError("Private workspace differs from its verified ledger; preserved")
    source = workspace / "src/main/java/dev/yanianz/intave/test/MockEmptyInventory.java"
    block = workspace / "src/main/java/dev/yanianz/intave/block/access/FakeFallbackBlock.java"
    fluid = workspace / "src/main/java/dev/yanianz/intave/integration/NativeFluidState.java"
    main = workspace / "src/main/java/dev/yanianz/intave"
    mapped = [main / name for name in (
        "integration/NativeBlockStates.java", "integration/NativeBlockView.java",
        "block/variant/index/Indexer.java", "block/variant/index/ModernIndexer.java",
        "block/variant/convert/ConversionBridge.java", "block/variant/convert/v16ConversionBridge.java",
        "block/variant/convert/SettingCache.java", "block/variant/Setting.java",
        "block/variant/Settings.java", "block/variant/NamedSetting.java",
        "block/variant/IntegerSetting.java", "block/variant/BooleanSetting.java", "block/variant/EnumSetting.java",
    )]
    fixture = ROOT / "scripts/fixtures/intave/NativeIntaveInventoryCompatibilityProbe.java"
    entries = classpath_file.read_text().strip().split(os.pathsep)
    if any(not entry for entry in entries):
        raise ValueError("Empty classpath entry")
    classpath = os.pathsep.join(str(Path(entry).resolve(strict=True)) for entry in entries)
    javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
    java = str(java_home / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        raise ValueError("JDK 25 is required")
    with tempfile.TemporaryDirectory(prefix="intave-inventory-probe-") as temporary:
        subprocess.run([javac, "--release", "25", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", temporary, str(source), str(block), str(fluid), *map(str, mapped), str(fixture)],
                       capture_output=True, text=True, check=True, timeout=60)
        subprocess.run([java, "-cp", temporary + os.pathsep + classpath,
                        "NativeIntaveInventoryCompatibilityProbe"], cwd=temporary,
                       capture_output=True, text=True, check=True, timeout=60)
    return {"inventory_compile_passed": True, "inventory_behavior_probe_passed": True,
            "fallback_block_compile_passed": True, "fallback_block_behavior_probe_passed": True,
            "mapped_fluid_state_compile_passed": True, "mapped_fluid_state_behavior_probe_passed": True,
            "mapped_block_adapters_compile_passed": True, "mapped_state_geometry_behavior_probe_passed": True,
            "world_region_ownership_runtime_verified": False,
            "inventory_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "fallback_block_sha256": hashlib.sha256(block.read_bytes()).hexdigest(),
            "mapped_fluid_state_sha256": hashlib.sha256(fluid.read_bytes()).hexdigest(),
            "full_engine_compiled": False, "server_boot_verified": False,
            "movement_replays_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(probe(args.classpath_file, args.java_home), indent=2))
    except subprocess.CalledProcessError as failure:
        parser.exit(1, f"Inventory compatibility probe failed:\n{failure.stdout}\n{failure.stderr}\n")
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"Inventory compatibility probe failed: {failure}\n")


if __name__ == "__main__":
    main()
