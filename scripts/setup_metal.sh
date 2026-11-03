#!/usr/bin/env bash
# Metal is vendored under Metal/. Upstream LuminolMC/Metal is unavailable.
# SourbyCraft keeps the generation step explicit for CI and patch workflows;
# Metal:compileJava also depends on it for incremental local builds.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "$repo_root/Metal/gen_sources.sh"
