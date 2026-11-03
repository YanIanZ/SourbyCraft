#!/usr/bin/env bash
# SourbyCraft: compile the vendored Metal source generator outside the tracked
# template tree. Reproducible on Linux/macOS and safe to invoke from any cwd.
set -euo pipefail

metal_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
generator_dir="$(mktemp -d "${TMPDIR:-/tmp}/sourby-metal-codegen.XXXXXXXX")"
trap 'rm -rf -- "$generator_dir"' EXIT

java_bin="java"
javac_bin="javac"
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="${JAVA_HOME}/bin/java"
  javac_bin="${JAVA_HOME}/bin/javac"
fi

"$javac_bin" --release 25 -d "$generator_dir" \
  "$metal_dir/templates/GenerateSources.java" \
  "$metal_dir/templates/Preprocessor.java"

"$java_bin" -cp "$generator_dir" GenerateSources \
  "$metal_dir/templates" "$metal_dir"
