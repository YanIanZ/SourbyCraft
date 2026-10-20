#!/usr/bin/env python3
"""Write or check the signed release manifest the auto-updater reads (rollout % + yank list).

The format and the server-side rules are in docs/development/AUTO-UPDATER.md ("Signed release
manifest") and ReleaseManifest.java; this script produces exactly that shape and refuses anything
the server would refuse. Signing is separate: the CI release job (or the owner, when changing the
rollout of a published release) signs the written bytes with
`openssl pkeyutl -sign -rawin`, the same Ed25519 key and command as the SEC-2 jar signature.

  release_manifest.py generate --tag v26.2-r48 --jar release/SourbyCraft-26.2-REL.jar \
      [--rollout 100] [--yanked-file build-data/yanked-releases.txt] -o release/sourbycraft-release-manifest.json
  release_manifest.py check release/sourbycraft-release-manifest.json --tag v26.2-r48 --jar release/SourbyCraft-26.2-REL.jar

Every manifest carries `policy_revision` (default: signing time in epoch seconds, so a later
signature of the same tag is always higher) and `expires_at` (default 90 days); servers persist the
highest revision they accepted and refuse to install from a lower or expired one.
`--rollout` defaults to `rolloutPercent` in gradle.properties. The yank list is read from
build-data/yanked-releases.txt (one tag per line, `#` comments). Every new release's manifest
carries the whole list, so the newest release always yanks what the repository says is yanked.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET_NAME = "sourbycraft-release-manifest.json"
SCHEMA = 1
MAX_BYTES = 64 * 1024
MAX_YANKED = 4096
# Freshness (ReleaseManifest/ReleasePolicyState): a server never lets a manifest whose
# policy_revision is below the highest it accepted for that tag, or whose expires_at has passed,
# make the release installable. The default revision is the signing time in epoch seconds, so every
# later signature of the same tag is higher; the default validity is 90 days.
DEFAULT_VALID_DAYS = 90
# Release tags as release_version.py and the updater write them: v<mc>-r<N>[...][-CHANNEL].
TAG = re.compile(r"v\d+(\.\d+)*-r\d[\w.-]*")


class ManifestError(ValueError):
    pass


def read_yanked(path):
    tags = []
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if not TAG.fullmatch(line):
            raise ManifestError(f"{path}:{number}: '{line}' is not a release tag (v26.2-rN)")
        if line not in tags:
            tags.append(line)
    if len(tags) > MAX_YANKED:
        raise ManifestError(f"{path} lists {len(tags)} tags; the server accepts at most {MAX_YANKED}")
    return tags


def default_rollout(properties=ROOT / "gradle.properties"):
    for line in Path(properties).read_text(encoding="utf-8").splitlines():
        if line.startswith("rolloutPercent="):
            return int(line.split("=", 1)[1].strip())
    return 100


def signing_time():
    epoch = os.environ.get("SOURCE_DATE_EPOCH", "").strip()
    moment = datetime.fromtimestamp(int(epoch), timezone.utc) if epoch.isdigit() else datetime.now(timezone.utc)
    return moment.replace(microsecond=0)


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text):
    if not isinstance(text, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z", text):
        raise ManifestError(f"{text!r} is not an ISO-8601 UTC instant (YYYY-MM-DDTHH:MM:SSZ)")
    return datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)


def reject_duplicates(pairs):
    """json object_pairs_hook: keys arrive decoded, so escaped spellings of one name collide."""
    seen = {}
    for key, value in pairs:
        if key in seen:
            raise ManifestError(f"duplicate member name {key!r}")
        seen[key] = value
    return seen


def loads_strict(data):
    """Strict UTF-8 JSON, no duplicate member names, no NaN/Infinity (what ReleaseManifest accepts)."""
    def no_constants(name):
        raise ManifestError(f"{name} is not strict JSON")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=no_constants)
    except UnicodeDecodeError as error:
        raise ManifestError("manifest is not UTF-8") from error
    except json.JSONDecodeError as error:
        raise ManifestError(f"manifest is not JSON: {error}") from error


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def build(tag, asset, sha256, rollout, yanked, issued=None, revision=None, valid_days=DEFAULT_VALID_DAYS):
    moment = parse_iso(issued) if issued else signing_time()
    if not isinstance(valid_days, int) or valid_days < 1:
        raise ManifestError("validity must be at least one day")
    manifest = {
        "schema": SCHEMA,
        "tag": tag,
        "asset": asset,
        "sha256": sha256,
        "rollout_percent": rollout,
        "yanked": list(yanked),
        "policy_revision": revision if revision is not None else int(moment.timestamp()),
        "issued_at": iso(moment),
        "expires_at": iso(moment + timedelta(days=valid_days)),
    }
    validate(manifest)
    return manifest


def encode(manifest):
    data = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ManifestError(f"manifest is {len(data)} bytes; the server reads at most {MAX_BYTES}")
    return data


def validate(manifest, tag=None, asset=None, sha256=None, now=None):
    """The server's rules (ReleaseManifest.parse + requireBoundTo), so CI fails before publishing.

    With ``now``, an already-expired manifest is refused too (the server would never install from it).
    """
    if not isinstance(manifest, dict):
        raise ManifestError("manifest is not a JSON object")
    if manifest.get("schema") != SCHEMA or isinstance(manifest.get("schema"), bool):
        raise ManifestError(f"schema must be {SCHEMA}")
    for key in ("tag", "asset", "sha256"):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            raise ManifestError(f"{key} is missing or not a string")
    if not TAG.fullmatch(manifest["tag"]):
        raise ManifestError(f"tag {manifest['tag']} is not a release tag")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
        raise ManifestError("sha256 must be 64 lower-case hex characters")
    rollout = manifest.get("rollout_percent")
    if not isinstance(rollout, int) or isinstance(rollout, bool) or not 0 <= rollout <= 100:
        raise ManifestError("rollout_percent must be an integer in 0..100")
    yanked = manifest.get("yanked")
    if not isinstance(yanked, list) or len(yanked) > MAX_YANKED:
        raise ManifestError("yanked must be a list of at most %d tags" % MAX_YANKED)
    for entry in yanked:
        if not isinstance(entry, str) or not entry.strip():
            raise ManifestError("yanked holds a non-string or blank entry")
    revision = manifest.get("policy_revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or not 1 <= revision < 2 ** 63:
        raise ManifestError("policy_revision must be a positive integer")
    expires = parse_iso(manifest.get("expires_at"))
    if "issued_at" in manifest and parse_iso(manifest["issued_at"]) >= expires:
        raise ManifestError("expires_at must be after issued_at")
    if now is not None and now > expires:
        raise ManifestError(f"manifest expired at {manifest['expires_at']}")
    if tag is not None and manifest["tag"] != tag:
        raise ManifestError(f"manifest is for {manifest['tag']}, not {tag}")
    if asset is not None and manifest["asset"] != asset:
        raise ManifestError(f"manifest describes {manifest['asset']}, not {asset}")
    if sha256 is not None and manifest["sha256"] != sha256:
        raise ManifestError("manifest sha256 differs from the jar")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    gen = commands.add_parser("generate")
    gen.add_argument("--tag", required=True)
    gen.add_argument("--jar", required=True)
    gen.add_argument("--rollout", type=int)
    gen.add_argument("--yanked-file", default=str(ROOT / "build-data/yanked-releases.txt"))
    gen.add_argument("--revision", type=int, help="policy_revision (default: signing time, epoch seconds)")
    gen.add_argument("--valid-days", type=int, default=DEFAULT_VALID_DAYS, help="expires_at = issued_at + this many days")
    gen.add_argument("-o", "--output", required=True)
    check = commands.add_parser("check")
    check.add_argument("manifest")
    check.add_argument("--tag", required=True)
    check.add_argument("--jar", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            rollout = args.rollout if args.rollout is not None else default_rollout()
            manifest = build(args.tag, os.path.basename(args.jar), sha256_file(args.jar), rollout, read_yanked(args.yanked_file),
                             revision=args.revision, valid_days=args.valid_days)
            Path(args.output).write_bytes(encode(manifest))
            print(json.dumps(manifest))
        else:
            data = Path(args.manifest).read_bytes()
            if len(data) > MAX_BYTES:
                raise ManifestError("manifest exceeds the server's size bound")
            validate(loads_strict(data), args.tag, os.path.basename(args.jar), sha256_file(args.jar),
                     now=datetime.now(timezone.utc))
            print(f"{args.manifest}: OK for {args.tag}")
    except (ManifestError, ValueError, OSError) as error:
        print(f"release_manifest: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
