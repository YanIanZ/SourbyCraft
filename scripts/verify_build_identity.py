#!/usr/bin/env python3
"""Check the server jar's two public build identity surfaces before publishing."""
import argparse
import io
import json
import zipfile


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def verify(jar, number, channel=None, api_version=None):
    with zipfile.ZipFile(jar) as archive:
        info = properties(archive.read("META-INF/sourbycraft-build.properties").decode())
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        if api_version is not None:
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
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    # Build 47+: the public identity is "Build N" with no upstream-platform letter.
    if info["buildNumber"] != str(number) or info["build"] != str(number):
        raise ValueError("Build properties do not match release number")
    if attributes["Implementation-Version"] != f"Build {number}":
        raise ValueError("Manifest differs from build properties")
    if channel is not None and not info.get("version", "").endswith("-" + channel):
        raise ValueError("Build channel differs from expected artifact channel")
    return info


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("number", type=int)
    parser.add_argument("--channel", choices=("DEV", "EXP", "REL"))
    parser.add_argument("--api-version")
    args = parser.parse_args()
    print(verify(args.jar, args.number, args.channel, args.api_version))
