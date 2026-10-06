#!/usr/bin/env python3
"""Verify and relocate a local Intave snapshot for a personal native-port workspace.

This prepares sources only. It does not install, build, or activate an anticheat.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
TARGET_PACKAGE = "dev.yanianz.intave"


def blob_hash(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def prepare(snapshot: Path, manifest_path: Path) -> Path:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    commit = manifest["commit"]
    if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise ValueError("The manifest must pin a full Git commit SHA")

    # Validate every byte before creating any output. Never include a dirty checkout implicitly.
    verified = []
    for entry in manifest["files"]:
        relative = PurePosixPath(entry["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in entry["path"]:
            raise ValueError(f"Unsafe snapshot path: {relative}")
        path = snapshot.joinpath(*relative.parts)
        if not path.resolve().is_relative_to(snapshot.resolve()):
            raise ValueError(f"Snapshot file escapes its root: {relative}")
        data = path.read_bytes()
        if blob_hash(data) != entry["sha"]:
            raise ValueError(f"Git blob hash mismatch: {relative}")
        verified.append((relative, data))
    if not any(str(path) == "LICENSE.md" for path, _ in verified):
        raise ValueError("The upstream license must accompany the snapshot")

    # A commit-specific directory preserves prior local port work on repeated imports.
    destination = ROOT / ".private-intave" / "snapshots" / commit
    if destination.exists():
        raise FileExistsError(f"Snapshot already exists; preserved without overwriting: {destination}")
    destination.mkdir(parents=True)
    output = []
    for relative, data in verified:
        relative_text = str(relative)
        if relative_text.startswith("src/main/"):
            relative_text = relative_text.replace("de/jpx3/intave/", "dev/yanianz/intave/")
            # Rewrite Java references, reflective names, ASM descriptors and textual resources.
            # Keep third-party classloader/JNI names intact; they are a separate dependency.
            try:
                source = data.decode("utf-8")
            except UnicodeDecodeError:
                pass
            else:
                data = source.replace("de.jpx3.intave", TARGET_PACKAGE).replace(
                    "de/jpx3/intave", "dev/yanianz/intave"
                ).encode("utf-8")
        target = destination / relative_text
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        output.append({"path": relative_text, "sha256": hashlib.sha256(data).hexdigest()})

    report = {
        "upstream": "https://github.com/intave/intave",
        "commit": commit,
        "package": TARGET_PACKAGE,
        "scope": "personal server use; not authorized for public distribution",
        "state": "SOURCE_PREPARED",
        "native_lifecycle_implemented": False,
        "anticheat_active": False,
        "upstream_blobs_verified": len(verified),
        "files": output,
    }
    (destination / "private-import.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    try:
        destination = prepare(args.snapshot, args.manifest)
    except (ValueError, FileExistsError, KeyError, OSError) as error:
        parser.exit(1, f"Intave source preparation failed: {error}\n")
    print(f"Verified private sources prepared at {destination}")
    print("Native lifecycle is not implemented; anticheat is not active.")


if __name__ == "__main__":
    main()
