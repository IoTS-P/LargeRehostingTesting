#!/usr/bin/env bash
# Build the container image (docker/docker-compose.yml -> akiba_for_artifacts:<VERSION>).
#
#   scripts/build.sh                      # akiba runtime only (OS deps + ghidra + framework)
#   PROVISION_TOOLS=1 scripts/build.sh    # additionally build the six tools into the image (hours)
#   scripts/build.sh --no-cache           # ignore the layer cache: a from-scratch image
#   scripts/build.sh --no-cache --pull    # ... and refresh the base image first
#
# A rebuild alone does not touch the named volumes; the images' contents only reach the container
# through them when they are empty (scripts/down.sh --wipe drops them).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BUILD_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-cache|--pull|--progress=*) BUILD_ARGS+=("$arg") ;;
        *) die "unknown option $arg (use --no-cache, --pull)" ;;
    esac
done

VERSION="${VERSION:-3.1.2}"
c_blue "==> building akiba_for_artifacts:${VERSION} (PROVISION_TOOLS=${PROVISION_TOOLS:-0} flags=${BUILD_ARGS[*]:-none})"
VERSION="$VERSION" PROVISION_TOOLS="${PROVISION_TOOLS:-0}" \
  compose build --progress=plain ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"}
c_green "image built: akiba_for_artifacts:${VERSION}"
echo "next: scripts/up.sh"
