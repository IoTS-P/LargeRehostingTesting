#!/usr/bin/env bash
# Stop (and optionally remove) the pipeline container. Volumes are kept unless
# --wipe is given, because a run of the whole pipeline takes hours to rebuild.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case "${1:-}" in
  --wipe)
    c_red "removing container AND volumes (akiba database, ghidra projects, fuzzware projects)"
    compose down -v ;;
  "")
    compose down ;;
  *)
    die "unknown option $1 (use --wipe to drop the volumes too)" ;;
esac
c_green "done"
