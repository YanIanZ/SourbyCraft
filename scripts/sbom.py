#!/usr/bin/env python3
"""CycloneDX 1.6 SBOM for a SourbyCraft slim release jar, generated from what the jar ships.

Why a generator over the jar and not the CycloneDX Gradle plugin: the release artifact is the
paperclip/SourbyClip slim jar, not a Gradle module. What the server runs is decided at boot by
META-INF/libraries.list (paperweight writes it from the server's resolved runtime classpath:
SHA-256, Maven coordinate, path), META-INF/versions.list (the patched server jar) and
META-INF/download-context (the Mojang server jar). slimServerJar then deletes some library jars
from the archive, which SourbyClip fetches by coordinate on first boot. A Gradle-graph SBOM would
describe the build's configurations, would not know which jars were stripped, and would add a
third-party plugin to a configuration-cache build that resolves its toolchain from a pinned private
Maven Local. Reading the jar describes exactly the shipped bytes, with the SHA-256 SourbyClip
itself verifies, and needs nothing but the standard library.

  sbom.py generate <slim jar> [--server-jar <sourbycraft-server-*.jar>] -o <out.json>
  sbom.py verify   <slim jar> <sbom.json>

`verify` is the release gate: every libraries.list line must be exactly one library component with
the same coordinate and SHA-256 and no other library may be listed. The output is deterministic
for a given jar (serial number from the jar hash, timestamp from the build properties or
SOURCE_DATE_EPOCH). It is checked structurally here; it is not validated against the official
CycloneDX JSON schema (no schema validator is vendored).
"""
import argparse
import hashlib
import io
import json
import os
import re
import sys
import uuid
import zipfile
from datetime import datetime, timezone
from urllib.parse import quote

SPEC_VERSION = "1.6"
LIBRARIES_LIST = "META-INF/libraries.list"
VERSIONS_LIST = "META-INF/versions.list"
DOWNLOAD_CONTEXT = "META-INF/download-context"
PROPERTY_DELIVERY = "sourbycraft:delivery"
PROPERTY_PATH = "sourbycraft:path"
BUNDLED = "bundled"
# Not inside this jar: SourbyClip obtains it on first boot (from the downloaded Mojang server jar
# or by Maven coordinate, SHA-256-checked against the hash listed here).
NOT_BUNDLED = "not-bundled"
SHA256 = re.compile(r"[0-9a-f]{64}")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_list(archive, name):
    """Tab-separated paperclip list: one (sha256, id, path) tuple per non-empty line."""
    rows = []
    for number, line in enumerate(archive.read(name).decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3 or not SHA256.fullmatch(parts[0]):
            raise ValueError(f"{name}:{number} is not '<sha256>\\t<id>\\t<path>'")
        rows.append(tuple(parts))
    return rows


def purl(coordinate):
    """pkg:maven purl for group:artifact:version[:classifier]."""
    parts = coordinate.split(":")
    if len(parts) not in (3, 4) or not all(parts):
        raise ValueError(f"not a Maven coordinate: {coordinate}")
    group, artifact, version = parts[:3]
    base = f"pkg:maven/{quote(group, safe='.')}/{quote(artifact, safe='.-_')}@{quote(version, safe='.-_+')}"
    return base + (f"?classifier={quote(parts[3], safe='.-_')}" if len(parts) == 4 else "")


def manifest_attributes(archive):
    text = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "").replace("\n ", "")
    return dict(line.split(": ", 1) for line in text.splitlines() if ": " in line)


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line and not line.startswith("#"))


def timestamp(build_properties):
    epoch = os.environ.get("SOURCE_DATE_EPOCH", "").strip()
    if epoch.isdigit():
        return datetime.fromtimestamp(int(epoch), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    stamp = build_properties.get("buildTimestamp", "").strip()
    if stamp:
        return stamp
    return None


def generate(jar, server_jar=None):
    jar_sha = sha256_file(jar)
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        libraries = read_list(archive, LIBRARIES_LIST)
        versions = read_list(archive, VERSIONS_LIST)
        context = archive.read(DOWNLOAD_CONTEXT).decode("utf-8").strip().split("\t") if DOWNLOAD_CONTEXT in names else None
        attributes = manifest_attributes(archive)

    build = {}
    if server_jar is not None:
        server_sha = sha256_file(server_jar)
        if not any(row[0] == server_sha for row in versions):
            raise ValueError(f"{server_jar} (sha256 {server_sha}) is not the server jar in {VERSIONS_LIST}")
        with zipfile.ZipFile(server_jar) as server:
            build = properties(server.read("META-INF/sourbycraft-build.properties").decode("utf-8"))

    label = build.get("build")
    root_version = f"Build {label}" if label else "unknown"
    root_ref = "sourbycraft-release-jar"
    metadata_properties = [{"name": "sourbycraft:" + key, "value": build[key]}
                           for key in sorted(build) if key in ("version", "buildNumber", "gitCommit", "treeHash", "dirty",
                                                               "mcVersion", "codename") or key.startswith("provenance.")]
    metadata = {
        "tools": {"components": [{"type": "application", "name": "sourbycraft scripts/sbom.py"}]},
        "component": {
            "type": "application",
            "bom-ref": root_ref,
            "name": "SourbyCraft",
            "version": root_version,
            "description": "SourbyCraft slim server jar (SourbyClip launcher + paperclip layout)",
            "hashes": [{"alg": "SHA-256", "content": jar_sha}],
            "properties": [{"name": "sourbycraft:file", "value": os.path.basename(jar)}] + metadata_properties,
        },
    }
    stamp = timestamp(build)
    if stamp:
        metadata["timestamp"] = stamp

    components = []
    clip = attributes.get("Clip-Version")
    if clip:
        components.append({
            "type": "application", "bom-ref": purl(f"dev.iyanz:sourbyclip:{clip}"), "group": "dev.iyanz",
            "name": "sourbyclip", "version": clip, "purl": purl(f"dev.iyanz:sourbyclip:{clip}"),
            "description": "Launcher classes embedded at the jar root (Main-Class)",
            "properties": [{"name": PROPERTY_DELIVERY, "value": BUNDLED},
                           {"name": "sourbycraft:protocol", "value": attributes.get("SourbyClip-Protocol", "unknown")}],
        })
    for sha, ident, path in versions:
        components.append({
            "type": "application", "bom-ref": f"sourbycraft-server@{ident}", "name": "sourbycraft-server",
            "version": ident, "hashes": [{"alg": "SHA-256", "content": sha}],
            "description": "Patched server jar, rebuilt at boot from the Mojang jar plus the bundled binary patch",
            "properties": [{"name": PROPERTY_DELIVERY, "value": BUNDLED}, {"name": PROPERTY_PATH, "value": path}],
        })
    if context is not None:
        if len(context) != 3 or not SHA256.fullmatch(context[0]):
            raise ValueError(f"{DOWNLOAD_CONTEXT} is not '<sha256>\\t<url>\\t<file>'")
        components.append({
            "type": "application", "bom-ref": "minecraft-server", "supplier": {"name": "Mojang"},
            "name": "minecraft-server", "version": versions[0][1] if versions else "unknown",
            "hashes": [{"alg": "SHA-256", "content": context[0]}],
            "externalReferences": [{"type": "distribution", "url": context[1]}],
            "properties": [{"name": PROPERTY_DELIVERY, "value": NOT_BUNDLED}, {"name": PROPERTY_PATH, "value": context[2]}],
        })
    seen = set()
    for sha, coordinate, path in libraries:
        ref = purl(coordinate)
        if ref in seen:
            raise ValueError(f"{LIBRARIES_LIST} lists {coordinate} twice")
        seen.add(ref)
        group, name, version = coordinate.split(":")[:3]
        delivery = BUNDLED if f"META-INF/libraries/{path}" in names else NOT_BUNDLED
        components.append({
            "type": "library", "bom-ref": ref, "group": group, "name": name, "version": version, "purl": ref,
            "hashes": [{"alg": "SHA-256", "content": sha}],
            "properties": [{"name": PROPERTY_DELIVERY, "value": delivery}, {"name": PROPERTY_PATH, "value": path}],
        })

    return {
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": SPEC_VERSION,
        "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, "sourbycraft-sbom:" + jar_sha)),
        "version": 1,
        "metadata": metadata,
        "components": components,
        "dependencies": [{"ref": root_ref, "dependsOn": [c["bom-ref"] for c in components]}],
    }


def _property(component, name):
    for entry in component.get("properties", []):
        if entry.get("name") == name:
            return entry.get("value")
    return None


def _sha(component):
    hashes = [h.get("content") for h in component.get("hashes", []) if h.get("alg") == "SHA-256"]
    return hashes[0] if len(hashes) == 1 else None


def verify(jar, bom):
    """Raise ValueError unless ``bom`` is a well-formed SBOM of exactly what ``jar`` ships."""
    if bom.get("bomFormat") != "CycloneDX" or bom.get("specVersion") != SPEC_VERSION or bom.get("version") != 1:
        raise ValueError("not a CycloneDX 1.6 BOM (bomFormat/specVersion/version)")
    if not re.fullmatch(r"urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", bom.get("serialNumber", "")):
        raise ValueError("serialNumber is not a urn:uuid")
    root = bom.get("metadata", {}).get("component", {})
    if _sha(root) != sha256_file(jar):
        raise ValueError("metadata.component SHA-256 is not the jar's")
    components = bom.get("components")
    if not isinstance(components, list):
        raise ValueError("components is not a list")
    refs = [c.get("bom-ref") for c in components]
    if None in refs or len(set(refs)) != len(refs) or root.get("bom-ref") in refs:
        raise ValueError("bom-ref values are missing or not unique")
    for component in components:
        if component.get("type") not in ("library", "application") or not component.get("name") or not component.get("version"):
            raise ValueError(f"component {component.get('bom-ref')} lacks type/name/version")
        if _property(component, PROPERTY_DELIVERY) not in (BUNDLED, NOT_BUNDLED):
            raise ValueError(f"component {component.get('bom-ref')} has no delivery property")
        for entry in component.get("hashes", []):
            if entry.get("alg") != "SHA-256" or not SHA256.fullmatch(entry.get("content", "")):
                raise ValueError(f"component {component.get('bom-ref')} has a malformed hash")
    dependencies = bom.get("dependencies", [])
    if [d.get("ref") for d in dependencies] != [root.get("bom-ref")] or sorted(dependencies[0].get("dependsOn", [])) != sorted(refs):
        raise ValueError("dependencies must list every component under the release jar")

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        expected = {purl(coordinate): (sha, path) for sha, coordinate, path in read_list(archive, LIBRARIES_LIST)}
        versions = read_list(archive, VERSIONS_LIST)
    libraries = {c["bom-ref"]: c for c in components if c.get("type") == "library"}
    missing = sorted(set(expected) - set(libraries))
    extra = sorted(set(libraries) - set(expected))
    if missing or extra:
        raise ValueError(f"library components differ from {LIBRARIES_LIST}: missing {missing[:5]}, extra {extra[:5]}")
    for ref, (sha, path) in expected.items():
        component = libraries[ref]
        if component.get("purl") != ref:
            raise ValueError(f"{ref}: purl differs from bom-ref")
        if _sha(component) != sha:
            raise ValueError(f"{ref}: SHA-256 differs from {LIBRARIES_LIST}")
        if _property(component, PROPERTY_PATH) != path:
            raise ValueError(f"{ref}: path differs from {LIBRARIES_LIST}")
        delivery = BUNDLED if f"META-INF/libraries/{path}" in names else NOT_BUNDLED
        if _property(component, PROPERTY_DELIVERY) != delivery:
            raise ValueError(f"{ref}: delivery should be {delivery}")
    server = [c for c in components if c.get("name") == "sourbycraft-server"]
    if sorted(_sha(c) for c in server) != sorted(row[0] for row in versions):
        raise ValueError(f"server components differ from {VERSIONS_LIST}")
    return {"libraries": len(expected),
            "bundled": sum(1 for c in libraries.values() if _property(c, PROPERTY_DELIVERY) == BUNDLED),
            "components": len(components)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    gen = commands.add_parser("generate")
    gen.add_argument("jar")
    gen.add_argument("--server-jar", help="the sourbycraft-server jar named in versions.list; adds its build/provenance properties")
    gen.add_argument("-o", "--output", required=True)
    check = commands.add_parser("verify")
    check.add_argument("jar")
    check.add_argument("sbom")
    args = parser.parse_args(argv)
    if args.command == "generate":
        bom = generate(args.jar, args.server_jar)
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(bom, handle, indent=2, sort_keys=False)
            handle.write("\n")
        print(json.dumps(verify(args.jar, bom)))
    else:
        with open(args.sbom, encoding="utf-8") as handle:
            print(json.dumps(verify(args.jar, json.load(handle))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
