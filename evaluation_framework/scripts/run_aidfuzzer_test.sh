#!/usr/bin/env bash
# Stage 03e — AidFuzzer over the corpus, run *directly* (related-work control, feasibility only).
#
#   scripts/run_aidfuzzer_test.sh                       # every ELF the ELF pass produced
#   scripts/run_aidfuzzer_test.sh --elfs 3              # a few firmwares
#   scripts/run_aidfuzzer_test.sh --fuzz-time 60s       # budget per firmware (default 60s)
#   scripts/run_aidfuzzer_test.sh --build               # build qemu + simulator first (long)
#
# AidFuzzer is the paper's related-work baseline and the expectation is that it does **not** run our
# images: it reads a fuzzware-style config, starts its own qemu fork (libqemu-system-arm.so) and waits
# for a fork server that the firmware has to bring up.  What matters for the study is the record of how
# it stops, not a fuzzing result.
#
# Two things the user has to supply:
#   - the tool tree at /data/tools/aidfuzzer (submodule; it is in UNFETCHED_TOOLS, so `setup.sh` does
#     not pull it),
#   - a build: qemu-7.2.0/configure --target-list=arm-softmmu --enable-modules + ninja, then
#     framework/scipt/compile.sh (needs clang++, glib, and clipp.h in framework/header).
# `--build` performs both; without it the script only runs what is already built.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TOOLS_ROOT=${TOOLS_ROOT:-/data/tools}
BINARIES_ROOT=${BINARIES_ROOT:-/data/akiba/binaries}
AIDFUZZ=${AIDFUZZ_ROOT:-$TOOLS_ROOT/aidfuzzer}
OUT="$RESULTS_DIR/aidfuzzer_test"
WORK="$RESULTS_DIR/generated/aidfuzzer_test"

ELFS=""; FUZZ_TIME=60; BUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --elfs)      ELFS="$2"; shift 2 ;;
    --fuzz-time) FUZZ_TIME="${2%[smh]}"; shift 2 ;;
    --build)     BUILD=1; shift ;;
    --list)      echo "steps: build (qemu + simulator), fuzz (iofuzz per firmware), classify"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

if ! in_container; then
  require_container
  args=""
  [ -n "$ELFS" ] && args="$args --elfs $ELFS"
  [ "$FUZZ_TIME" != 60 ] && args="$args --fuzz-time ${FUZZ_TIME}s"
  [ "$BUILD" = 1 ] && args="$args --build"
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/run_aidfuzzer_test.sh $args"
fi

[ -d "$AIDFUZZ" ] || die "aidfuzzer tree not found at $AIDFUZZ (it is in UNFETCHED_TOOLS; clone it first)"
mkdir -p "$OUT" "$WORK"

IOFUZZ="$AIDFUZZ/framework/bin/iofuzz"
SIM="$AIDFUZZ/framework/bin/simulator"
LIBQEMU="$AIDFUZZ/qemu-7.2.0/build/libqemu-system-arm.so"

# ---------------------------------------------------------------- build (optional)
if [ "$BUILD" = 1 ]; then
  if [ ! -f "$LIBQEMU" ]; then
    c_blue "==> building qemu-7.2.0 (libqemu-system-arm.so)"
    # AidFuzzer's simulator links against the module build of qemu; --enable-shared does not exist in 7.2
    ( cd "$AIDFUZZ/qemu-7.2.0" && mkdir -p build && cd build \
      && ../configure --target-list=arm-softmmu --enable-modules --disable-werror --disable-docs --disable-tools \
      && ninja ) > "$OUT/build_qemu.log" 2>&1 || { c_red "qemu build failed, see $OUT/build_qemu.log"; return 1 2>/dev/null || exit 1; }
  fi
  if [ ! -x "$SIM" ] || [ ! -x "$IOFUZZ" ]; then
    c_blue "==> building iofuzz + simulator"
    [ -f "$AIDFUZZ/framework/header/clipp.h" ] || \
      curl -fsSL --retry 5 https://raw.githubusercontent.com/muellan/clipp/master/include/clipp.h \
        -o "$AIDFUZZ/framework/header/clipp.h" || die "cannot fetch clipp.h"
    ( cd "$AIDFUZZ/framework/scipt" && bash compile.sh ) > "$OUT/build_simulator.log" 2>&1 \
      || { c_red "simulator build failed, see $OUT/build_simulator.log"; exit 1; }
  fi
fi

[ -x "$IOFUZZ" ] || die "iofuzz not built — run with --build"
[ -f "$LIBQEMU" ] || die "libqemu-system-arm.so missing — run with --build"

# ---------------------------------------------------------------- pick the firmwares
ids=""
if [ -n "$ELFS" ]; then
  ids=$(echo "$ELFS" | tr ',' ' ')
else
  for d in "$BINARIES_ROOT"/fuzzware_projects/*/; do
    [ -d "$d" ] || continue
    [ -f "$d/config.yml" ] && ids="$ids $(basename "$d")"
  done
fi

rows="$OUT/status.csv"
echo "id,result,detail,log" > "$rows"
started=0; total=0
for id in $ids; do
  proj="$BINARIES_ROOT/fuzzware_projects/$id"
  cfg="$proj/config.yml"
  [ -f "$cfg" ] || { echo "  #$id: no config.yml, skipped"; continue; }
  total=$((total + 1))

  # AidFuzzer concatenates the config's directory and the `file:` value, so it has to be a bare name
  fw=$(ls "$proj"/*.bin 2>/dev/null | head -1)
  [ -n "$fw" ] || { echo "  #$id: no firmware in the project, skipped"; continue; }
  d="$WORK/$id"; mkdir -p "$d/corpus"
  cp "$fw" "$d/$(basename "$fw")"
  sed "s#^\(\s*file:\s*\).*#\1$(basename "$fw")#" "$cfg" > "$d/config.yml"
  python3 - "$d/corpus" <<'PY'
import os, sys
d = sys.argv[1]
open(os.path.join(d, "seed_zeros"), "wb").write(b"\x00" * 512)
open(os.path.join(d, "seed_ones"),  "wb").write(b"\xff" * 512)
PY

  log="$d/run.log"
  ( cd "$d" && LD_LIBRARY_PATH="$(dirname "$LIBQEMU")" timeout "$FUZZ_TIME" \
      "$IOFUZZ" fuzz "$d/config.yml" "$SIM" -corpus "$d/corpus" ) > "$log" 2>&1
  rc=$?

  if [ $rc -eq 124 ]; then
    result="started"; detail="ran the full ${FUZZ_TIME}s budget"
    started=$((started + 1))
  elif grep -q "fork server is not up" "$log"; then
    result="no-fork-server"; detail=$(grep -m1 "fork server is not up" "$log" | cut -c1-80 | tr ',' ' ')
  elif grep -qi "error opening" "$log"; then
    result="config-error"; detail=$(grep -m1 -i "error opening" "$log" | cut -c1-80 | tr ',' ' ')
  else
    result="exit-$rc"; detail=$(tail -1 "$log" | cut -c1-80 | tr ',' ' ')
  fi
  printf '%s,%s,%s,%s\n' "$id" "$result" "$detail" "$log" >> "$rows"
  printf '  #%-4s %-16s %s\n' "$id" "$result" "$detail"
done

c_blue "==> aidfuzzer feasibility: $started/$total firmware(s) reached a running simulator"
echo "  status: $rows"
[ "$started" -gt 0 ] || c_yellow "    none started — expected: AidFuzzer does not bring our images up"
exit 0
