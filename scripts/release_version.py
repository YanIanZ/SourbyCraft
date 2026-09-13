#!/usr/bin/env python3
"""Resolve one release identity for both the jar and GitHub tag. No network writes."""
import argparse
import re
import subprocess
from pathlib import Path


def resolve(properties: str, tags: str) -> tuple[str, int]:
    values = dict(re.findall(r"^([\w]+)=(.*)$", properties, re.MULTILINE))
    version = values["releaseVersion"].strip()
    raw = values["sourbyBuild"].strip()
    if not re.fullmatch(r"[0-9]+", raw) or int(raw) < 1:
        raise ValueError("Release sourbyBuild must be a positive integer")
    pattern = re.compile(r"^v" + re.escape(version) + r"-(?:r)?([0-9]+)(?:c|[.-].*)?$")
    previous = [int(match[1]) for tag in tags.splitlines()
                if (match := pattern.fullmatch(tag.strip()))]
    if previous and int(raw) <= max(previous):
        raise ValueError(f"sourbyBuild={raw} must exceed published build {max(previous)}; bump gradle.properties")
    return f"v{version}-r{raw}", int(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tags-file", type=Path)
    args = parser.parse_args()
    if args.tags_file:
        tags = args.tags_file.read_text()
    else:
        remote = subprocess.check_output(
            ["git", "ls-remote", "--tags", "--refs", "origin"], text=True, timeout=60)
        tags = "\n".join(line.split("refs/tags/", 1)[1] for line in remote.splitlines())
    tag, number = resolve(Path("gradle.properties").read_text(), tags)
    print(f"tag={tag}\nnumber={number}")


if __name__ == "__main__":
    main()
