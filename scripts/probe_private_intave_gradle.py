#!/usr/bin/env python3
"""Statically typecheck the private build wiring against an installed Gradle distribution.

Compiles a Project receiver wrapper with Gradle's bundled Kotlin compiler. Does not
evaluate the build script, resolve dependencies, or test configuration-cache reuse.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/gradle/private-intave.gradle.kts"


def project_wrapper(source: str) -> str:
    imports = "\n".join(re.findall(r"^import .+$", source, re.MULTILINE))
    imports += ("\nimport org.gradle.kotlin.dsl.*\nimport org.gradle.api.*\n"
                "import org.gradle.api.file.*\nimport org.gradle.api.provider.*\n"
                "import org.gradle.api.tasks.*\n")
    body = re.sub(r"^import .+\n", "", source, flags=re.MULTILINE)
    classes = []
    # The standalone task types use unindented top-level closing braces.
    while (match := re.search(r"^(?:abstract class |object )", body, re.MULTILINE)) is not None:
        start = match.start()
        closing = body.find("\n}", start)
        if closing < 0:
            raise ValueError("Task type has no top-level closing brace")
        end = closing + 2
        classes.append(body[start:end])
        body = body[:start] + body[end:]
    return imports + "\n".join(classes) + "\nfun Project.typecheckPrivateIntave() {\n" + body + "\n}\n"


def typecheck(gradle_home: Path, java_home: Path | None = None) -> dict:
    library = gradle_home.resolve(strict=True) / "lib"
    jars = sorted(library.glob("*.jar")) + sorted((library / "plugins").glob("*.jar"))
    plugins = list(library.glob("kotlin-sam-with-receiver-compiler-plugin-*.jar"))
    if not jars or len(plugins) != 1:
        raise ValueError("Expected an installed Gradle distribution with its bundled Kotlin compiler")
    java = str(java_home / "bin/java") if java_home else shutil.which("java")
    javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
    if not java or not javac:
        raise ValueError("JDK 25 is required")
    classpath = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix="intave-gradle-typecheck-") as temporary:
        directory = Path(temporary)
        source = directory / "PrivateIntaveTypecheck.kt"
        source.write_text(project_wrapper(SCRIPT.read_text()))
        subprocess.run([java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", classpath,
                        "-Xplugin=" + str(plugins[0]), "-P",
                        "plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver",
                        "-jvm-target", "25", "-d", str(directory / "classes"), str(source)],
                       check=True, capture_output=True, text=True, timeout=90)
        fixture = ROOT / "scripts/fixtures/intave/NativeIntaveInventoryProbe.java"
        probe_classpath = str(directory / "classes") + os.pathsep + classpath
        subprocess.run([javac, "--release", "25", "-cp", probe_classpath,
                        "-d", str(directory / "classes"), str(fixture)],
                       check=True, capture_output=True, text=True, timeout=30)
        subprocess.run([java, "-cp", probe_classpath, "NativeIntaveInventoryProbe",
                        str(directory / "inventory")],
                       check=True, capture_output=True, text=True, timeout=30)
    return {"typecheck_passed": True, "inventory_probe_passed": True,
            "gradle_distribution": gradle_home.name,
            "script_sha256": hashlib.sha256(SCRIPT.read_bytes()).hexdigest(),
            "build_script_executed": False, "configuration_cache_verified": False,
            "engine_compiled": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-home", type=Path, required=True)
    parser.add_argument("--java-home", type=Path,
                        default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args = parser.parse_args()
    try:
        print(json.dumps(typecheck(args.gradle_home, args.java_home), indent=2))
    except subprocess.CalledProcessError as failure:
        parser.exit(1, f"Private Gradle typecheck failed:\n{failure.stdout}\n{failure.stderr}\n")
    except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"Private Gradle typecheck failed: {failure}\n")


if __name__ == "__main__":
    main()
