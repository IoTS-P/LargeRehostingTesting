#!/usr/bin/env bash
# Host-side bootstrap: check out the tool submodules at the pinned commits,
# optionally initialise their nested submodules, and apply the overlays in patches/.
#
#   scripts/setup.sh                 # submodules + patches
#   scripts/setup.sh --with-nested   # also fetch nested submodules (fuzzware, firmline, ...)
#   scripts/setup.sh --no-patches
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WITH_NESTED=0
WITH_PATCHES=1
for arg in "$@"; do
  case "$arg" in
    --with-nested) WITH_NESTED=1 ;;
    --no-patches)  WITH_PATCHES=0 ;;
    *) die "unknown option $arg" ;;
  esac
done

cd "$REPO_ROOT"

# pinned commits — same as the reference server, see patches/README.md
declare -A PINS=(
  [evaluated_tools_and_configurations/tools/RealworldFirmware]=4133f1fe23e7cfec0e2690acce2039e886ef869a
  [evaluated_tools_and_configurations/tools/firmline]=38f2ddb8a26781070d3ce27b09a84aee1a2fd045
  [evaluated_tools_and_configurations/tools/fuzzware]=e43dfbd38185ef4a374e1e9a6c9d930a9cc40653
  [evaluated_tools_and_configurations/tools/gdma]=f5979d009f18ce0069c7e57c9f557766b5f977c8
  [evaluated_tools_and_configurations/tools/hoedur]=a021fd064e5d831a427a25b17975779eef05c45b
  [evaluated_tools_and_configurations/tools/MultiFuzz]=44d0cc5df781ccba0cfb805814abe60655eb3334
  [evaluated_tools_and_configurations/tools/FirmRCA]=357958d05ebe69ac95af699a0f0da96c1cd7585d
)

export GIT_HTTP_LOW_SPEED_LIMIT=1000 GIT_HTTP_LOW_SPEED_TIME=60

c_blue "==> fetching submodules"
git submodule sync --quiet
if ! git submodule update --init --depth 1 "${!PINS[@]}" 2>&1 | tail -20; then
  c_red "git submodule update failed — check network access to github.com"
fi

for path in "${!PINS[@]}"; do
  have="$(git -C "$path" rev-parse HEAD 2>/dev/null || echo none)"
  want="${PINS[$path]}"
  if [ "$have" = "$want" ]; then
    c_green "ok      $path @ ${want:0:8}"
  else
    c_blue "pin     $path ${have:0:8} -> ${want:0:8}"
    git -C "$path" fetch --quiet origin "$want" 2>/dev/null || true
    git -C "$path" checkout --quiet "$want" 2>&1 | tail -2
    [ "$(git -C "$path" rev-parse HEAD)" = "$want" ] \
      && c_green "ok      $path @ ${want:0:8}" \
      || c_red "FAILED  $path is not at ${want:0:8}"
  fi
done

if [ "$WITH_NESTED" = 1 ]; then
  c_blue "==> fetching nested submodules (this pulls radare2, binwalk, qemu patches, …)"
  for path in "evaluated_tools_and_configurations/tools/firmline" "evaluated_tools_and_configurations/tools/fuzzware" "evaluated_tools_and_configurations/tools/MultiFuzz"; do
    echo "--- $path"
    git -C "$path" submodule update --init --recursive 2>&1 | tail -10 \
      || c_red "nested update for $path failed"
  done
fi

if [ "$WITH_PATCHES" = 1 ]; then
  c_blue "==> applying tool overlays"
  bash "$SCRIPT_DIR/apply_patches.sh" || c_red "some patches did not apply (see above)"
fi

echo
c_green "setup done."
echo "next:  scripts/build.sh        # build the container image"
echo "       scripts/up.sh           # start it"
