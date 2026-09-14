#!/usr/bin/env python3
"""Check the server jar's two public build identity surfaces before publishing."""
import argparse
import zipfile


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def verify(jar, number, channel=None):
    with zipfile.ZipFile(jar) as archive:
        info = properties(archive.read("META-INF/sourbycraft-build.properties").decode())
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    if info["buildNumber"] != str(number) or info["build"] != f"{number}c":
        raise ValueError("Build properties do not match release number")
    if attributes["Implementation-Version"] != f"build {number}c":
        raise ValueError("Manifest differs from build properties")
    if channel is not None and not info.get("version", "").endswith("-" + channel):
        raise ValueError("Build channel differs from expected artifact channel")
    return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("number", type=int)
    parser.add_argument("--channel", choices=("DEV", "EXP", "REL"))
    args = parser.parse_args()
    print(verify(args.jar, args.number, args.channel))
