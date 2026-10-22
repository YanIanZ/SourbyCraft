#!/usr/bin/env python3
"""Check the server jar's two public build identity surfaces before publishing."""
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

from build_identity import PROVENANCE_JDK, PROVENANCE_KEYS, provenance

# The Java toolchain the sources compile with (root build.gradle.kts, AGENTS.md: JDK 25).
TOOLCHAIN_JDK_MAJOR = "25"

# "N" for a release from 26.2, "N.M" for a hotfix from hotfix/<mc>-rN.M (scripts/release_version.py).
RELEASE_NUMBER = re.compile(r"[1-9][0-9]*(?:\.[1-9][0-9]*)?")


# SEC-13 (GPL-3.0-only): licence texts the published (slim launcher) jar must carry itself.
LICENCE_DIR = "META-INF/licenses/"
LICENCE_SOURCE_DIR = "sourbycraft-server/src/main/resources/META-INF/licenses"
LICENCE_ENTRY = LICENCE_DIR + "LICENSE_SOURBYCRAFT"
NOTICE_ENTRY = LICENCE_DIR + "NOTICE_SOURBYCRAFT"
# SHA-256 of the GNU GPL version 3 text as shipped in LICENSE_SOURBYCRAFT. The licence text does not
# change; a different hash means the file was replaced or truncated.
GPL3_SHA256 = "33bc26d4498c183b703d1760ee45b8774d21d6e0178ca393dc715271840c7a21"
NOTICE_MARKERS = ("GPL-3.0-only", "https://github.com/YanIanZ/SourbyCraft", "LICENSE_SOURBYCRAFT")


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def verify_api_version(jar, api_version):
    with zipfile.ZipFile(jar) as archive:
        api_jars = [name for name in archive.namelist()
                    if name.startswith("META-INF/libraries/dev/iyanz/sourbycraft/sourbyapi/")
                    and name.endswith(".jar")]
        if len(api_jars) != 1:
            raise ValueError("Expected one embedded SourbyCraft API jar")
        with zipfile.ZipFile(io.BytesIO(archive.read(api_jars[0]))) as api_jar:
            api_info = json.loads(api_jar.read("apiVersioning.json"))
    if api_info.get("version") != f"{api_version}-R0.1-SNAPSHOT":
        raise ValueError("Bukkit API version is not plugin-compatible")
    if api_info.get("currentApiVersion") != api_version:
        raise ValueError("Current API version differs from the Minecraft API version")
    return api_info


def verify_provenance(info, attributes, publication):
    """provenance.<Name> in the properties and Provenance-<Name> in the manifest must agree.

    A publication build (``--commit``) must carry every key with a real value; a local build may
    lack the block (older jars) or carry ``unknown`` where a value could not be computed.
    """
    present = {key[len("provenance."):]: value for key, value in info.items() if key.startswith("provenance.")}
    for key, value in present.items():
        if attributes.get("Provenance-" + key) != value:
            raise ValueError(f"Manifest and properties disagree on provenance {key}")
    for attribute in attributes:
        if attribute.startswith("Provenance-") and attribute[len("Provenance-"):] not in present:
            raise ValueError(f"Manifest carries {attribute} that the properties lack")
    if publication:
        missing = [key for key in PROVENANCE_KEYS if key not in present]
        if missing:
            raise ValueError(f"Missing build provenance: {', '.join(missing)}")
    for key, value in present.items():
        if value == "unknown" and not publication:
            continue
        if key == PROVENANCE_JDK:
            if not re.search(r"(^| )" + TOOLCHAIN_JDK_MAJOR + r"([.+]|$)", value):
                raise ValueError(f"Build JDK is not the Java {TOOLCHAIN_JDK_MAJOR} toolchain: {value}")
        elif key in PROVENANCE_KEYS and not re.fullmatch(r"[0-9a-f]{64}", value):
            raise ValueError(f"Provenance {key} is not a SHA-256: {value}")
    return present


def verify_source(info, root):
    """Recompute every source-derived provenance hash from ``root`` and require equality."""
    expected = provenance(root)
    for key in (k for k in PROVENANCE_KEYS if k != PROVENANCE_JDK):
        actual = info.get("provenance." + key)
        if actual != expected[key]:
            raise ValueError(f"Provenance {key} differs from the source tree ({actual} != {expected[key]})")
    return expected


def verify_licences(jar, source_root=None):
    """The jar must carry the GPL text and the source notice under META-INF/licenses/.

    With ``source_root``, every file of that checkout's licence directory must also be in the jar
    byte for byte (and nothing else under META-INF/licenses/), so the jar and the sources cannot drift.
    """
    with zipfile.ZipFile(jar) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()
                   if name.startswith(LICENCE_DIR) and not name.endswith("/")}
    for required in (LICENCE_ENTRY, NOTICE_ENTRY):
        if required not in entries:
            raise ValueError(f"Missing licence file {required}")
    if hashlib.sha256(entries[LICENCE_ENTRY]).hexdigest() != GPL3_SHA256:
        raise ValueError(f"{LICENCE_ENTRY} is not the GPL version 3 text")
    notice = entries[NOTICE_ENTRY].decode("utf-8", errors="replace")
    missing = [marker for marker in NOTICE_MARKERS if marker not in notice]
    if missing:
        raise ValueError(f"{NOTICE_ENTRY} lacks {', '.join(missing)}")
    if source_root is not None:
        source = Path(source_root) / LICENCE_SOURCE_DIR
        expected = {LICENCE_DIR + path.name: path.read_bytes() for path in source.iterdir() if path.is_file()}
        for name, content in expected.items():
            if name not in entries:
                raise ValueError(f"Missing licence file {name}")
            if entries[name] != content:
                raise ValueError(f"{name} differs from {LICENCE_SOURCE_DIR}")
        extra = sorted(set(entries) - set(expected))
        if extra:
            raise ValueError(f"Licence files not in the source tree: {', '.join(extra)}")
    return sorted(entries)


def verify(jar, number, channel=None, api_version=None, commit=None, source_root=None):
    with zipfile.ZipFile(jar) as archive:
        info = properties(archive.read("META-INF/sourbycraft-build.properties").decode())
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    # Build 47+: the public identity is "Build N" with no upstream-platform letter.
    dirty = info.get("dirty", "false")
    if dirty not in ("true", "false"):
        raise ValueError("Invalid dirty flag")
    number = str(number)
    if not RELEASE_NUMBER.fullmatch(number):
        raise ValueError(f"Release number {number} is neither N nor a hotfix N.M")
    label = number + ("-dirty" if dirty == "true" else "")
    if info["buildNumber"] != number or info["build"] != label:
        raise ValueError("Build properties do not match release number")
    if attributes["Implementation-Version"] != f"Build {label}":
        raise ValueError("Manifest differs from build properties")
    # Paper parses Build-Number as an int (ServerBuildInfo.buildNumber(), Bukkit.getVersion()), so a
    # hotfix N.M keeps the integer N there; a jar that carries one must agree with the release.
    if "." in number or "Build-Number" in attributes:
        if attributes.get("Build-Number") != number.split(".")[0]:
            raise ValueError(f"Manifest Build-Number {attributes.get('Build-Number')!r} is not the integer build of {number}")
    if commit is not None or "gitCommit" in info:
        expected = {"Git-Commit": "gitCommit", "Git-Tree-Hash": "treeHash", "Git-Dirty": "dirty"}
        for attribute, key in expected.items():
            if not info.get(key) or attributes.get(attribute) != info[key]:
                raise ValueError(f"Manifest and properties disagree on {key}")
        local_unknown = commit is None and dirty == "true"
        if not re.fullmatch(r"[0-9a-f]{40}", info["gitCommit"]) and not (local_unknown and info["gitCommit"] == "unknown"):
            raise ValueError("Missing full Git commit")
        if not re.fullmatch(r"[0-9a-f]{64}", info["treeHash"]) and not (local_unknown and info["treeHash"] == "unknown"):
            raise ValueError("Missing SHA-256 source tree hash")
    verify_provenance(info, attributes, publication=commit is not None)
    if commit is not None:
        if info.get("gitCommit") != commit:
            raise ValueError("Build commit differs from expected commit")
        if dirty != "false":
            raise ValueError("Publication requires a clean source tree")
    if channel is not None and not info.get("version", "").endswith("-" + channel):
        raise ValueError("Build channel differs from expected artifact channel")
    if api_version is not None:
        verify_api_version(jar, api_version)
    if source_root is not None:
        verify_source(info, source_root)
    return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("number", nargs="?", help="N, or N.M for a hotfix")
    parser.add_argument("--channel", choices=("DEV", "EXP", "REL"))
    parser.add_argument("--api-version")
    parser.add_argument("--commit", help="Require this full Git commit and a clean source tree")
    parser.add_argument("--api-only", action="store_true")
    parser.add_argument("--source-root", help="Recompute the patch/baseline/toolchain provenance hashes from this checkout and require equality")
    parser.add_argument("--licences", action="store_true",
                        help="Require the GPL text and source notice under META-INF/licenses/ (with --source-root: byte-identical to the checkout's)")
    args = parser.parse_args()
    if args.licences:
        print(verify_licences(args.jar, args.source_root))
    if args.api_only:
        if args.api_version is None:
            parser.error("--api-only requires --api-version")
        print(verify_api_version(args.jar, args.api_version))
    else:
        if args.number is None:
            parser.error("a build number is required unless --api-only is used")
        print(verify(args.jar, args.number, args.channel, args.api_version, args.commit, args.source_root))
