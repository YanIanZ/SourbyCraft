#!/usr/bin/env bash
# MaplePile is VENDORED into the repo (the upstream LuminolMC/MaplePile submodule
# was removed after the org's repos went offline). The dependency-settings patch is
# already applied in the vendored source. This just (re)generates MaplePile sources.
set -e
cd MaplePile
sh gen_sources.sh
cd ..
