#!/usr/bin/env bash
# Metal is VENDORED into the repo (the upstream LuminolMC/Metal submodule
# was removed after the org's repos went offline). The dependency-settings patch is
# already applied in the vendored source. This just (re)generates Metal sources.
set -e
cd Metal
sh gen_sources.sh
cd ..
