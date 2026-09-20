#!/bin/bash
# Populate evaluation_samples/ with the firmware corpus in the server's own layout.
#
# INTERNAL USE ONLY - the reference server is not part of the public artifact.
# The released sample set is the one hosted on Google Drive; see README.md.
#
# Source: 222.20.126.138:/data/hongyuan/akiba_data/binaries/original, where every
# imported firmware sits as `<id>.bin` (id = binaries.id in the akiba database).
# Dest:   evaluation_samples/<id>.bin  (flat — the ids are unique, so nothing collides)
#
# Default selection: the stage-01 selection (arch = 'ARM:LE:32:v8T' AND
# format = 'Raw Binary' -> 4571 files, ~25.9 GiB).  Pass --all to take the whole
# original/ directory (19011 files, ~47 GiB).
#
# The server has no rsync and its root filesystem is 96% full, so GNU tar streams the
# selection over ssh; nothing is staged on the server.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC=/data/hongyuan/akiba_data/binaries/original
LIST=/tmp/fw4571_ids_bin.txt
: "${SSHPASS:?set SSHPASS (the reference server password) before running this script}"
SSH="sshpass -e ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 -p 31779 akiba@222.20.126.138"

if [ "${1:-}" = "--all" ]; then
    echo "== whole original/ directory"
    LIST=""
else
    [ -s "$LIST" ] || { echo "!! $LIST missing (run scripts/_fw4571_ids.py first)"; exit 1; }
    echo "== $(wc -l < "$LIST") file(s) from the stage-01 selection"
fi

cd "$REPO" || exit 1
echo "== clearing evaluation_samples/ (README.md is kept)"
find evaluation_samples -mindepth 1 -maxdepth 1 ! -name README.md -exec rm -rf {} +

echo "== streaming from $SRC ($(date -Is))"
start=$(date +%s)
if [ -n "$LIST" ]; then
    $SSH "cd $SRC && tar -cf - -T -" < "$LIST" | tar -C evaluation_samples -xf -
    rc=${PIPESTATUS[0]}
else
    $SSH "cd $SRC && tar -cf - ." | tar -C evaluation_samples -xf -
    rc=${PIPESTATUS[0]}
fi
echo "== finished in $(( $(date +%s) - start ))s (ssh rc=$rc)"

echo "== verification"
n=$(find evaluation_samples -type f ! -name README.md | wc -l)
echo "files: $n"
echo "size:  $(du -sh evaluation_samples | cut -f1)"
echo "names: $(ls evaluation_samples | grep -c '^[0-9]*\.bin$') file(s) match <id>.bin"
ls evaluation_samples | head -4
