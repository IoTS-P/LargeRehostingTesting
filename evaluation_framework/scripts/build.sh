#!/usr/bin/env bash
# Build the container image (docker/docker-compose.yml -> akiba_allinone:<VERSION>).
#
#   scripts/build.sh                 # akiba runtime only (OS deps + ghidra + framework)
#   PROVISION_TOOLS=1 scripts/build.sh   # additionally build the six tools (hours)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

VERSION="${VERSION:-3.1.2}"
c_blue "==> building akiba_allinone:${VERSION} (PROVISION_TOOLS=${PROVISION_TOOLS:-0})"
VERSION="$VERSION" PROVISION_TOOLS="${PROVISION_TOOLS:-0}" \
  compose build --progress=plain
c_green "image built: akiba_allinone:${VERSION}"
echo "next: scripts/up.sh"
