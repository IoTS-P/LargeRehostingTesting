#!/usr/bin/env bash
# Fetch the module JARs of the reference build from 222.20.126.138 (optional).
#
# Why they are useful:
#   * comparing a locally built amod-*.jar against the one that actually ran in the
#     evaluation;
#   * as a deployment fallback if a module cannot be compiled here.
#
# Why they are NOT used by the image build:
#   the reference build was made with a newer Kotlin - its JARs carry Kotlin metadata
#   2.3.0, which the Kotlin 2.1.20 compiler declared by this project's sources refuses
#   ("binary version of its metadata is 2.3.0, expected version is 2.1.0").  Seeding
#   them as build dependencies therefore breaks compilation; the image builds every
#   module from source with scripts/build_akiba_modules.py instead.
#
# They land in evaluation_framework/framework/prebuilt-modules/ which is git-ignored.
set -euo pipefail
export SSHPASS="${SSHPASS:?set SSHPASS to the akiba user password}"
DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/evaluation_framework/framework/prebuilt-modules"
mkdir -p "$DEST"
sshpass -e scp -o StrictHostKeyChecking=no -P "${AKIBA_PORT:-31779}" \
  'akiba@222.20.126.138:/home/akiba/akiba_framework/modules/*.jar' "$DEST/"
echo "$(ls "$DEST" | wc -l) jar(s) in $DEST"
du -sh "$DEST"