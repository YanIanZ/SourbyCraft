#!/usr/bin/env python3
"""Resolve one release identity for both the jar and GitHub tag. No network writes."""
import argparse
import re
import subprocess
from pathlib import Path

# Owner decision 2026-10-24: the Minecraft 26.3 line starts at Build 100 with codename
# "aurora-forge" (Aurora Forge). 26.2 and earlier stay below 100, so the two series never share a
# build number and a 26.2 hotfix cannot collide with the first 26.3 release.
FIRST_BUILD_100_VERSION = (26, 3)
VERSION_CODENAMES = {"26.3": "aurora-forge"}


def _version_tuple(version: str) -> tuple[int, int]:
    parts = version.split(".")
    if len(parts) < 2 or not parts[0].isdigit() or not parts[1].isdigit():
        raise ValueError(f"releaseVersion={version} is not <major>.<minor>")
    return int(parts[0]), int(parts[1])


def check_version_rules(values: dict[str, str], build: int) -> None:
    """The build-number line and codename the owner fixed per Minecraft version."""
    version = values["releaseVersion"].strip()
    major, minor = _version_tuple(version)
    if (major, minor) >= FIRST_BUILD_100_VERSION:
        if build < 100:
            raise ValueError(
                f"{version} starts at Build 100 (owner decision 2026-10-24); sourbyBuild={build} is below it")
    elif build >= 100:
        raise ValueError(
            f"Build {build} is reserved for 26.3 and later (owner decision 2026-10-24); {version} stays below 100")
    expected = VERSION_CODENAMES.get(f"{major}.{minor}")
    if expected is not None and values.get("codename", "").strip() != expected:
        raise ValueError(f"{version} uses codename={expected} (Aurora Forge)")


def resolve(properties: str, tags: str, *, development: bool = False) -> tuple[str, int]:
    values = dict(re.findall(r"^([\w]+)=(.*)$", properties, re.MULTILINE))
    version = values["releaseVersion"].strip()
    raw = values["sourbyBuild"].strip()
    if not re.fullmatch(r"[0-9]+", raw) or int(raw) < 1:
        raise ValueError("Release sourbyBuild must be a positive integer")
    check_version_rules(values, int(raw))
    pattern = re.compile(r"^v" + re.escape(version) + r"-(?:r)?([0-9]+)(?:c|[.-].*)?$")
    previous = [int(match[1]) for tag in tags.splitlines()
                if (match := pattern.fullmatch(tag.strip()))]
    if not development and previous and int(raw) <= max(previous):
        raise ValueError(f"sourbyBuild={raw} must exceed published build {max(previous)}; bump gradle.properties")
    return f"v{version}-r{raw}", int(raw)


HOTFIX_BRANCH = re.compile(r"hotfix/(?P<mc>[0-9]+(?:\.[0-9]+)*)-r(?P<build>[1-9][0-9]*)\.(?P<fix>[1-9][0-9]*)")


def resolve_hotfix(properties: str, tags: str, branch: str, *,
                   development: bool = False) -> tuple[str, str, str]:
    """Identity of a hotfix build published from ``hotfix/<mc>-r<N>.<M>``.

    The branch starts at the published tag ``v<mc>-r<N>`` and publishes ``v<mc>-r<N>.<M>``.
    gradle.properties must say ``sourbyBuild=<N>.<M>`` so the jar carries the same identity as the
    tag. Returns (tag, number, base tag). Outside development the base tag must exist, the hotfix
    tag must not, and M must exceed every hotfix already published for N.
    """
    match = HOTFIX_BRANCH.fullmatch(branch.strip())
    if not match:
        raise ValueError(f"{branch} is not a hotfix branch (hotfix/<mc>-r<N>.<M>)")
    values = dict(re.findall(r"^([\w]+)=(.*)$", properties, re.MULTILINE))
    version = values["releaseVersion"].strip()
    raw = values["sourbyBuild"].strip()
    if match["mc"] != version:
        raise ValueError(f"{branch} is for {match['mc']}, gradle.properties releaseVersion is {version}")
    number = f"{match['build']}.{match['fix']}"
    if raw != number:
        raise ValueError(f"sourbyBuild={raw} must be {number} on {branch}")
    check_version_rules(values, int(match["build"]))
    tag = f"v{version}-r{number}"
    base = f"v{version}-r{match['build']}"
    if development:
        return tag, number, base
    published = {line.strip() for line in tags.splitlines() if line.strip()}
    if base not in published:
        raise ValueError(f"{branch} hotfixes {base}, which was never published")
    if tag in published:
        raise ValueError(f"{tag} is already published; bump the hotfix branch")
    fixes = re.compile(re.escape(base) + r"\.([0-9]+)")
    previous = [int(m[1]) for t in published if (m := fixes.fullmatch(t))]
    if previous and int(match["fix"]) <= max(previous):
        raise ValueError(f"{tag} must exceed published hotfix {base}.{max(previous)}")
    return tag, number, base


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tags-file", type=Path)
    parser.add_argument("--development", action="store_true",
                        help="Validate build metadata without reserving a new release number")
    parser.add_argument("--hotfix-branch",
                        help="Resolve a hotfix release from this branch (hotfix/<mc>-r<N>.<M>)")
    args = parser.parse_args()
    if args.development:
        tags = ""
    elif args.tags_file:
        tags = args.tags_file.read_text()
    else:
        remote = subprocess.check_output(
            ["git", "ls-remote", "--tags", "--refs", "origin"], text=True, timeout=60)
        tags = "\n".join(line.split("refs/tags/", 1)[1] for line in remote.splitlines())
    properties = Path("gradle.properties").read_text()
    if args.hotfix_branch:
        tag, number, base = resolve_hotfix(properties, tags, args.hotfix_branch,
                                           development=args.development)
        print(f"tag={tag}\nnumber={number}\nbase={base}")
        return
    tag, number = resolve(properties, tags, development=args.development)
    print(f"tag={tag}\nnumber={number}")


if __name__ == "__main__":
    main()
