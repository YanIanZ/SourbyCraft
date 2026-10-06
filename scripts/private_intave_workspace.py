#!/usr/bin/env python3
"""Prepare and inspect a private Intave port workspace. Never activates server checks."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

from prepare_private_intave import ROOT, TARGET_PACKAGE, blob_hash


def read_plan() -> dict:
    return json.loads((ROOT / "build-data/intave-private-plan.json").read_text())


def verify_sources(source: Path, inventory: dict, plan: dict) -> list[tuple[str, bytes]]:
    if inventory["commit"] != plan["commit"]:
        raise ValueError("Source commit does not match the pinned private plan")
    verified = []
    names = set()
    for entry in inventory["files"]:
        name = entry["path"]
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name or name in names:
            raise ValueError(f"Unsafe or duplicate source path: {name}")
        if not (name.startswith("src/") or name in (
            "LICENSE.md", "build.gradle.kts", "gradle/packaging.gradle.kts"
        )):
            raise ValueError(f"Unexpected source path: {name}")
        path = source.joinpath(*relative.parts)
        if not path.resolve().is_relative_to(source.resolve()):
            raise ValueError(f"Source escapes snapshot: {name}")
        data = path.read_bytes()
        if blob_hash(data) != entry["sha"]:
            raise ValueError(f"Source hash mismatch: {name}")
        names.add(name)
        verified.append((name, data))
    if "LICENSE.md" not in names or not any(n.startswith("src/main/java/") for n in names):
        raise ValueError("Snapshot must include the upstream license and Java source")
    return verified


def relocate(name: str, data: bytes) -> tuple[str, bytes]:
    if name.startswith("src/"):
        name = name.replace("de/jpx3/intave/", "dev/yanianz/intave/")
        if PurePosixPath(name).suffix.lower() in (".java", ".json", ".properties", ".xml", ".yml", ".yaml", ".toml", ".txt", ".md"):
            text = data.decode("utf-8")
            data = text.replace("de.jpx3.intave", TARGET_PACKAGE).replace(
                "de/jpx3/intave", "dev/yanianz/intave"
            ).encode("utf-8")
    return name, data


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def fixture_target(workspace: Path, entry: dict) -> Path:
    name = entry["path"]
    relative = PurePosixPath(name)
    if (relative.is_absolute() or ".." in relative.parts or "\\" in name
            or not name.startswith(("src/test/resources/", "src/bundled/resources/"))):
        raise ValueError(f"Unsafe fixture path: {name}")
    return managed_path(workspace, name)


def publish_fixture(target: Path, data: bytes, expected: str) -> bool:
    if len(data) > 16 * 1024 * 1024 or blob_hash(data) != expected:
        raise ValueError(f"Pinned fixture size/hash verification failed: {target.name}")
    if target.exists():
        if blob_hash(target.read_bytes()) != expected:
            raise ValueError(f"Existing fixture differs from pin; preserved: {target.name}")
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
            staging = Path(output.name)
            output.write(data)
        try:
            os.link(staging, target)
        except FileExistsError:
            if blob_hash(target.read_bytes()) != expected:
                raise ValueError(f"Concurrent fixture differs from pin; preserved: {target.name}")
            return False
        return True
    finally:
        if staging:
            staging.unlink(missing_ok=True)


def recover_archived_fixtures(workspace: Path, archive_path: Path) -> dict:
    """Import only resources matching upstream Git blob hashes; never import archive classes/APIs."""
    plan = read_plan()
    metadata = json.loads((workspace / "workspace.json").read_text())
    if metadata["commit"] != plan["commit"]:
        raise ValueError("Fixture archive workspace does not match the pinned commit")
    recovered = []
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        for entry in metadata["missing_fixtures"]:
            target = fixture_target(workspace, entry)
            if target.exists():
                if blob_hash(target.read_bytes()) != entry["sha"]:
                    raise ValueError(f"Existing fixture differs from pin; preserved: {entry['path']}")
                continue
            resource = entry["path"].split("/resources/", 1)[1]
            if names.count(resource) != 1:
                continue
            info = archive.getinfo(resource)
            if info.file_size > 16 * 1024 * 1024:
                continue
            data = archive.read(info)
            if blob_hash(data) == entry["sha"] and publish_fixture(target, data, entry["sha"]):
                recovered.append({"path": entry["path"], "git_blob": entry["sha"]})
    digest = hashlib.sha256()
    with archive_path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return {"commit": plan["commit"], "archive": archive_path.name,
            "archive_sha256": digest.hexdigest(), "recovered": recovered,
            "native_active": False}


def fetch_fixtures(workspace: Path) -> int:
    """Recover pinned binary fixtures when network access is available; keep existing bytes."""
    plan = read_plan()
    metadata = json.loads((workspace / "workspace.json").read_text())
    if metadata["commit"] != plan["commit"]:
        raise ValueError("Fixture workspace does not match the pinned commit")
    fetched = 0
    for entry in metadata["missing_fixtures"]:
        name = entry["path"]
        target = fixture_target(workspace, entry)
        if target.exists():
            if blob_hash(target.read_bytes()) != entry["sha"]:
                raise ValueError(f"Existing fixture differs from pin; preserved: {name}")
            continue
        url = ("https://raw.githubusercontent.com/intave/intave/" + plan["commit"] + "/"
               + urllib.parse.quote(name, safe="/"))
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                if urllib.parse.urlparse(response.geturl()).scheme != "https":
                    raise ValueError(f"Non-HTTPS fixture redirect: {name}")
                data = response.read(16 * 1024 * 1024 + 1)
        except OSError as error:
            raise OSError(f"Could not fetch {url} -> {target}: {error}") from error
        fetched += publish_fixture(target, data, entry["sha"])
    return fetched


def prepare(source: Path, manifest: Path, legacy_libs: Path | None = None) -> Path:
    plan = read_plan()
    inventory = json.loads(manifest.read_text())
    verified = verify_sources(source, inventory, plan)
    workspace = ROOT / ".private-intave/workspaces" / plan["commit"]
    if workspace.exists():
        raise FileExistsError(f"Existing workspace preserved: {workspace}")
    workspace.mkdir(parents=True)
    output = []
    for name, data in verified:
        name, data = relocate(name, data)
        if name in ("build.gradle.kts", "gradle/packaging.gradle.kts"):
            name = "upstream-build/" + name
        target = workspace / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        output.append({"path": name, "sha256": hashlib.sha256(data).hexdigest()})
    # Make the original verified snapshot durable independently of the root build/ directory.
    upstream = workspace / "verified-upstream"
    for name, data in verified:
        target = upstream / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    write_json(upstream / "inventory.json", inventory)
    (workspace / "libs").mkdir()
    local_libraries = []
    if legacy_libs:
        for jar in sorted(legacy_libs.glob("*.jar")):
            with zipfile.ZipFile(jar) as archive:
                if archive.testzip():
                    raise ValueError(f"Corrupt local compile library: {jar.name}")
            target = workspace / "libs" / jar.name
            shutil.copyfile(jar, target)
            local_libraries.append({"name": jar.name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    template = ROOT / "scripts/templates/intave-private"
    for path in template.iterdir():
        if path.is_file():
            text = path.read_text()
            declarations = "\n".join(
                f'    {d["configuration"]}("{d["coordinate"]}")'
                + (' { isTransitive = false }' if d["coordinate"].startswith("ac.intave:") else "")
                for d in plan["dependencies"]
            )
            expectations = ",\n".join(
                "        " + json.dumps(f["sha"] + "\t" + f["path"])
                for f in inventory.get("missing_files", [])
            )
            (workspace / path.name).write_text(text.replace("@DEPENDENCIES@", declarations)
                                              .replace("@FIXTURES@", expectations))
    generated = workspace / "generated/main/java/dev/yanianz/intave/IntaveBuildConfig.java"
    generated.parent.mkdir(parents=True)
    generated.write_text(
        "package dev.yanianz.intave;\n"
        "public final class IntaveBuildConfig {\n"
        "  public static final boolean PRODUCTION = false, AUTHTEST = false, GOMME = false;\n"
        f'  public static final String VERSION = "private-preparation-{plan["commit"][:8]}";\n'
        "  private IntaveBuildConfig() {}\n}\n"
    )
    output.append({"path": generated.relative_to(workspace).as_posix(),
                   "sha256": hashlib.sha256(generated.read_bytes()).hexdigest()})
    # Root wrapper is used with -p; wrapper and Gradle metadata stay untouched.
    shutil.copyfile(ROOT / "build-data/intave-private-plan.json", workspace / "private-plan.json")
    (workspace / "missing-fixtures.txt").write_text(
        "".join(f["path"] + "\n" for f in inventory.get("missing_files", []))
    )
    write_json(workspace / "verification-matrix.json", {
        "native_active": False,
        "gates": [{**gate, "status": "PENDING"} for gate in plan["gates"]],
    })
    write_json(workspace / "workspace.json", {
        "commit": plan["commit"], "package": TARGET_PACKAGE,
        "state": "PREPARATION_ONLY", "native_active": False,
        "scope": plan["scope"], "source_files": output,
        "local_compile_libraries": local_libraries,
        "missing_fixtures": inventory.get("missing_files", []),
    })
    return workspace


def managed_path(workspace: Path, name: str) -> Path:
    relative = PurePosixPath(name)
    path = workspace / name
    if (not name or relative.is_absolute() or ".." in relative.parts or "\\" in name
            or str(relative) != name or path.is_symlink()
            or not path.resolve().is_relative_to(workspace.resolve())):
        raise ValueError(f"Unsafe private workspace path: {name}")
    return path


def source_expectations(workspace: Path, metadata: dict, commit: str, port: dict | None = None):
    if metadata["commit"] != commit:
        raise ValueError("Workspace does not match the pinned private plan")
    expected = {}
    for entry in metadata["source_files"]:
        name = entry["path"]
        managed_path(workspace, name)
        if (name in expected or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])
                or not (name.startswith(("src/", "generated/", "upstream-build/")) or name == "LICENSE.md")):
            raise ValueError(f"Unsafe/duplicate baseline source: {name}")
        expected[name] = entry
    removed = []
    if port is not None:
        if port["commit"] != commit or port["state"] != "NATIVE_CODE_INTEGRATED_UNVERIFIED":
            raise ValueError("Native port does not match pinned provenance/state")
        for name in port.get("removed_files", []):
            if name not in expected:
                raise ValueError(f"Native port removes an unknown baseline source: {name}")
            removed.append(name)
            del expected[name]
        names = set()
        for entry in port["files"]:
            name = entry["path"]
            managed_path(workspace, name)
            if (name in names or name in removed or not name.startswith(("src/", "generated/"))
                    or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])):
                raise ValueError(f"Unsafe/duplicate native port source: {name}")
            names.add(name)
            expected[name] = entry
    return expected, removed


def workspace_files(workspace: Path, *roots: str) -> set[str]:
    names = set()
    for name in roots:
        root = managed_path(workspace, name)
        for path in root.rglob("*"):
            relative = path.relative_to(workspace).as_posix()
            managed_path(workspace, relative)
            if path.is_file():
                names.add(relative)
    return names


def inspect(workspace: Path, cache: Path, *, plan: dict | None = None) -> dict:
    plan = plan if plan is not None else read_plan()
    metadata = json.loads((workspace / "workspace.json").read_text())
    port_path = workspace / "native-port.json"
    port = json.loads(port_path.read_text()) if port_path.is_file() else None
    expected, removed = source_expectations(workspace, metadata, plan["commit"], port)
    state = port["state"] if port else "PREPARATION_ONLY"
    changed = [f["path"] for f in expected.values()
               if not (workspace / f["path"]).is_file()
               or hashlib.sha256((workspace / f["path"]).read_bytes()).hexdigest() != f["sha256"]]
    changed.extend(name for name in removed if (workspace / name).exists())
    fixture_names = {entry["path"] for entry in metadata["missing_fixtures"]}
    for name in fixture_names:
        managed_path(workspace, name)
    changed.extend(sorted(workspace_files(workspace, "src", "generated")
                          - expected.keys() - fixture_names - set(removed)))
    library_names = set()
    for entry in metadata["local_compile_libraries"]:
        name = entry["name"]
        managed_path(workspace, "libs/" + name)
        if PurePosixPath(name).name != name or name in library_names:
            raise ValueError(f"Unsafe/duplicate private library: {name}")
        library_names.add(name)
    changed_libraries = [f["name"] for f in metadata["local_compile_libraries"]
                         if not (workspace / "libs" / f["name"]).is_file()
                         or hashlib.sha256((workspace / "libs" / f["name"]).read_bytes()).hexdigest() != f["sha256"]]
    changed_libraries.extend(sorted(name[len("libs/"):] for name in workspace_files(workspace, "libs")
                                    if name.endswith(".jar") and name[len("libs/"):] not in library_names))
    missing = []
    cached = []
    for dep in plan["dependencies"]:
        group, artifact, version = dep["coordinate"].split(":")
        found = list((cache / "caches/modules-2/files-2.1" / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        (cached if found else missing).append(dep["coordinate"])
    missing_fixtures = []
    for entry in metadata["missing_fixtures"]:
        path = workspace / entry["path"]
        if not path.is_file() or blob_hash(path.read_bytes()) != entry["sha"]:
            missing_fixtures.append(entry["path"])
    return {
        "commit": plan["commit"], "state": state, "native_active": False,
        "changed_sources": changed, "cached_direct_dependencies": cached,
        "changed_local_compile_libraries": changed_libraries,
        "missing_direct_dependencies": missing,
        "missing_fixtures": missing_fixtures,
        "compile_preflight_ready": not changed and not changed_libraries and not missing,
        "fixture_suite_preflight_ready": not changed and not changed_libraries and not missing and not missing_fixtures,
        "note": "Direct cache presence is a preflight only; Gradle must resolve transitives and validate checksums. Native port gates remain pending.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--source", type=Path, required=True)
    prep.add_argument("--manifest", type=Path, required=True)
    prep.add_argument("--legacy-libs", type=Path)
    doctor = commands.add_parser("doctor")
    doctor.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle")
    commands.add_parser("resolve", help="Resolve dependencies and generate Gradle SHA-256 verification metadata")
    commands.add_parser("fetch-fixtures", help="Download missing binary resources with pinned Git hash verification")
    recovery = commands.add_parser("recover-fixtures", help="Recover exact pinned resources from a local JAR/ZIP")
    recovery.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            print(prepare(args.source, args.manifest, args.legacy_libs))
        elif args.command == "doctor":
            workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
            result = inspect(workspace, args.gradle_cache)
            write_json(workspace / "preflight.json", result)
            print(json.dumps(result, indent=2))
            if not result["fixture_suite_preflight_ready"]:
                parser.exit(2, "Private preparation exists; dependencies/fixtures or source changes require attention.\n")
        elif args.command == "fetch-fixtures":
            workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
            print(f"Recovered {fetch_fixtures(workspace)} pinned fixtures/resources")
        elif args.command == "recover-fixtures":
            workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
            result = recover_archived_fixtures(workspace, args.archive)
            write_json(workspace / "fixture-recovery.json", result)
            print(json.dumps(result, indent=2))
        else:
            workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
            subprocess.run([
                str(ROOT / "gradlew"), "-p", str(workspace),
                "--gradle-user-home", str(ROOT / ".private-intave/gradle-cache"),
                "--write-verification-metadata", "sha256", "resolvePrivateDependencies",
            ], check=True)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Private Intave preparation failed: {error}\n")


if __name__ == "__main__":
    main()
