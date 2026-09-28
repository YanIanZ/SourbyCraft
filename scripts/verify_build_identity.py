#!/usr/bin/env python3
"""Check the server jar's two public build identity surfaces before publishing."""
import argparse
import io
import json
import zipfile


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


def verify(jar, number, channel=None, api_version=None):
    with zipfile.ZipFile(jar) as archive:
        info = properties(archive.read("META-INF/sourbycraft-build.properties").decode())
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    # Build 47+: the public identity is "Build N" with no upstream-platform letter.
    if info["buildNumber"] != str(number) or info["build"] != str(number):
        raise ValueError("Build properties do not match release number")
    if attributes["Implementation-Version"] != f"Build {number}":
        raise ValueError("Manifest differs from build properties")
    if channel is not None and not info.get("version", "").endswith("-" + channel):
        raise ValueError("Build channel differs from expected artifact channel")
    if api_version is not None:
        verify_api_version(jar, api_version)
    return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("number", type=int, nargs="?")
    parser.add_argument("--channel", choices=("DEV", "EXP", "REL"))
    parser.add_argument("--api-version")
    parser.add_argument("--api-only", action="store_true")
    args = parser.parse_args()
    if args.api_only:
        if args.api_version is None:
            parser.error("--api-only requires --api-version")
        print(verify_api_version(args.jar, args.api_version))
    else:
        if args.number is None:
            parser.error("a build number is required unless --api-only is used")
        print(verify(args.jar, args.number, args.channel, args.api_version))
