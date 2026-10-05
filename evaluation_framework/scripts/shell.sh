#!/usr/bin/env bash
# Open a shell inside the pipeline container (the akiba user is the default).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_container
exec docker exec -it "$CONTAINER" bash -l
