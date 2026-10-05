#!/usr/bin/env bash
# Stage 03c — µEmu over the corpus, run *directly* (related-work control).
#
#   scripts/run_uemu_test.sh                          # every ELF the ELF pass produced
#   scripts/run_uemu_test.sh --elfs 3,3389            # a few firmwares
#   scripts/run_uemu_test.sh --fuzz-time 10m          # shorten the fuzzing budget
#   scripts/run_uemu_test.sh --seed testcases/seed_zeros.bin
#
# There is no akiba module and no database here: `uEmu-test` is a self-contained CLI
# (`pipeline.py`: cfg → kb → fuzz → analyze → coverage) and this script just drives it over the
# corpus that the ELF pass produced, the same way the reference ran it.  µEmu is fed the images
# directly — no stage-2 admission premise — and the expectation is that it does **not** run them,
# so what matters is the record of how it stops, in the harness's own vocabulary:
#
#   cfg       derives <fw>.cfg from the ELF's LOAD segments — needs no µEmu at all
#   kb        knowledge-base extraction  ┐ need µEmu built with S2E ($uEmuDIR/build, libs2e, the
#   fuzz      AFL dry run + µEmu fuzzing ┘ patched AFL/afl-fuzz) plus KVM for the guest
#   analyze   classifies each firmware: 正常fuzz / 提前退出 / 卡死 (+ end_type, details)
#   coverage  covered / total basic blocks, against a <fw>_bbl.txt supplied from outside
#
# The container cannot build that stack, so `kb` is attempted once with a short probe budget and
# the run records the tool's own message; on a µEmu host the same command line produces the full
# classification.  Everything is written under results/generated/uemu_test — the checkout stays
# read-only, so no batch or result ever dirties the submodule.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TOOLS_ROOT=${TOOLS_ROOT:-/data/tools}
BINARIES_ROOT=${BINARIES_ROOT:-/data/akiba/binaries}
HARNESS=${uEmuTestSource:-$TOOLS_ROOT/uEmu-test}
UEMU_ROOT=${uEmuDIR:-$TOOLS_ROOT/uEmu}
WORK="$RESULTS_DIR/generated/uemu_test"
OUT="$RESULTS_DIR/uemu_test"
BATCH=${BATCH:-artifact}
SEED=${SEED:-testcases/seed_alternating.bin}

ELFS=""; FUZZ_TIME=""; KB_TIME=""; KEEP_RAW=0
while [ $# -gt 0 ]; do
  case "$1" in
    --elfs)      ELFS="$2"; shift 2 ;;
    --fuzz-time) FUZZ_TIME="$2"; shift 2 ;;
    --kb-time)   KB_TIME="$2"; shift 2 ;;
    --seed)      SEED="$2"; shift 2 ;;
    --batch)     BATCH="$2"; shift 2 ;;
    --keep-raw)  KEEP_RAW=1; shift ;;
    --list)      printf '%s\n' "steps: cfg, kb, fuzz, analyze, coverage (uEmu-test pipeline.py)"
                 printf '%s\n' "work dir: $WORK    output: $OUT    batch: $BATCH"
                 exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

# ---------------------------------------------------------------- host wrapper
if ! in_container; then
  require_container
  args=""
  [ -n "$ELFS" ]      && args="$args --elfs $ELFS"
  [ -n "$FUZZ_TIME" ] && args="$args --fuzz-time $FUZZ_TIME"
  [ -n "$KB_TIME" ]   && args="$args --kb-time $KB_TIME"
  [ -n "$SEED" ]      && args="$args --seed $SEED"
  [ -n "$BATCH" ]     && args="$args --batch $BATCH"
  [ "$KEEP_RAW" = 1 ] && args="$args --keep-raw"
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/run_uemu_test.sh $args"
fi

# The harness (and µEmu's uEmu-helper.py) call configparser.SafeConfigParser(), which Python 3.12
# removed, so it must run on <= 3.11 - the container's default `python3` may well be newer
# (/opt/conda/bin/python3 is 3.14).  Pick the first interpreter that has both SafeConfigParser and
# PyYAML; PYTHON=<path> overrides the choice.
if [ -z "${PYTHON:-}" ]; then
  for c in python3.11 python3.10 python3.9 python3.8 /usr/bin/python3 /usr/bin/python3.8 python3; do
    command -v "$c" >/dev/null 2>&1 || continue
    if "$c" -c 'import yaml, configparser; configparser.SafeConfigParser' >/dev/null 2>&1; then PYTHON="$c"; break; fi
  done
fi
[ -n "${PYTHON:-}" ] || die "no Python <= 3.11 with PyYAML found — run 'setup_tools.sh uemu' (see docs/pipeline.md, stage 03c)"
export PYTHON

# pipeline.py calls µEmu's uEmu-helper.py as a bare `python3` (and the generated launch scripts are
# shell), so the harness's children must find a <= 3.11 interpreter too.  Expose the chosen one as
# `python3` on PATH for every step - the same trick the P²IM stage needs for me.py.
mkdir -p "$WORK/bin"
ln -sfn "$(command -v "$PYTHON")" "$WORK/bin/python3"
export PATH="$WORK/bin:$PATH"

[ -f "$HARNESS/pipeline.py" ] || die "uEmu-test harness not found at $HARNESS (run setup_tools.sh uemu)"
[ -f "$HARNESS/pipeline.yaml" ] || die "uEmu-test harness is incomplete: $HARNESS/pipeline.yaml missing"
[ -f "$UEMU_ROOT/uEmu-helper.py" ] || die "µEmu snapshot not found at $UEMU_ROOT"

# seconds from a duration spec (300s / 10m / 2h)
secs_from() {
  python3 - "$1" <<'PY'
import re, sys
m = re.fullmatch(r"(\d+)\s*([smhd]?)", sys.argv[1].strip())
if not m:
    sys.exit(f"cannot parse duration '{sys.argv[1]}' (use 300s, 10m, 2h)")
print(int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2) or "s"])
PY
}
FUZZ_SECS=${FUZZ_TIME:+$(secs_from "$FUZZ_TIME")}
KB_SECS=${KB_TIME:+$(secs_from "$KB_TIME")}

# µEmu's fuzzing stack is an S2E build; without it kb/fuzz cannot start (cfg does not need it).
UEMU_USABLE=1
[ -d "$UEMU_ROOT/build" ] || UEMU_USABLE=0
[ -x "$UEMU_ROOT/AFL/afl-fuzz" ] || UEMU_USABLE=0

# ---------------------------------------------------------------- 1. work tree
mkdir -p "$WORK" "$OUT"
for f in pipeline.py pipeline.yaml README.md; do
  [ -f "$HARNESS/$f" ] && cp -f "$HARNESS/$f" "$WORK/$f"
done
if [ ! -d "$WORK/testcases" ]; then
  mkdir -p "$WORK/testcases"
  cp -f "$HARNESS"/testcases/* "$WORK/testcases/" 2>/dev/null || true
fi
mkdir -p "$WORK/firmware/$BATCH" "$WORK/result/$BATCH"
# the harness rewrites the launch templates to this path, so it must point at the µEmu AFL
python3 - "$WORK/pipeline.yaml" "$UEMU_ROOT/AFL/afl-fuzz" "${FUZZ_SECS:-3600}" "${KB_SECS:-1800}" <<'PY'
import re, sys, pathlib
p, afl, fuzz_s, kb_s = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
t = p.read_text()
t = re.sub(r"(?m)^afl_bin:.*$", f"afl_bin: {afl}", t)
t = re.sub(r"(?m)^(\s*fuzz:)\s*\d+", rf"\g<1> {fuzz_s}", t)
t = re.sub(r"(?m)^(\s*kb:)\s*\d+", rf"\g<1> {kb_s}", t)
p.write_text(t)
print(f"[uemu] work tree: {p.parent}  afl_bin={afl}  fuzz={fuzz_s}s  kb={kb_s}s")
PY

# ---------------------------------------------------------------- 2. stage the corpus
want_elf() { [ -z "$ELFS" ] && return 0; tr ',' '\n' <<<"$ELFS" | grep -qx "$1"; }
staged=0; skipped=0
for elf in "$BINARIES_ROOT"/parsed_elfs/*.elf; do
  [ -e "$elf" ] || continue
  id=$(basename "$elf" .elf)
  want_elf "$id" || continue
  d="$WORK/firmware/$BATCH/$id"
  mkdir -p "$d"
  cp -f "$elf" "$d/$id.elf"
  staged=$((staged + 1))
done
[ "$staged" -gt 0 ] || die "no ELF staged — did the ELF pass (03b_p2im_elf) run? looked in $BINARIES_ROOT/parsed_elfs"
c_blue "==> staged $staged firmware into $WORK/firmware/$BATCH"
c_blue "==> harness interpreter: $PYTHON ($("$PYTHON" -V 2>&1))"

# ---------------------------------------------------------------- 3. harness steps
elfs_arg=()
[ -n "$ELFS" ] && elfs_arg=(--elfs "$ELFS")

step() {
  local name="$1"; shift
  c_blue "---- pipeline.py $name $*"
  ( cd "$WORK" && uEmuDIR="$UEMU_ROOT" "$PYTHON" pipeline.py "$name" --batch "$BATCH" "${elfs_arg[@]}" "$@" )
  local rc=$?
  [ "$rc" = 0 ] || c_yellow "    pipeline.py $name returned $rc"
  return 0
}

step cfg
if [ "$UEMU_USABLE" = 1 ]; then
  step kb --timeout "${KB_SECS:-1800}"
  step fuzz --seed "$SEED" --timeout "${FUZZ_SECS:-3600}"
  if [ "$KEEP_RAW" = 1 ]; then step analyze; else step analyze; fi
  step coverage
else
  c_yellow "==> µEmu's S2E build is absent ($UEMU_ROOT/build, $UEMU_ROOT/AFL/afl-fuzz): kb and fuzz"
  c_yellow "    cannot run here.  Probing kb once with a 60 s budget so the tool's own message is on"
  c_yellow "    record, then stopping — cfg above is the part a container can reproduce."
  step kb --timeout 60
  step analyze
  step coverage
fi

# ---------------------------------------------------------------- 4. results
cp -f "$WORK/result/$BATCH"/*.csv "$OUT/" 2>/dev/null || true
mkdir -p "$OUT/logs"
cp -f "$WORK/result/$BATCH"/logs/* "$OUT/logs/" 2>/dev/null || true
echo
c_blue "==> µEmu results ($OUT)"
for f in kb_status fuzz_status results coverage; do
  if [ -s "$WORK/result/$BATCH/$f.csv" ]; then
    printf '\n-- %s.csv\n' "$f"
    sed -n '1,25p' "$WORK/result/$BATCH/$f.csv"
  fi
done
if [ -s "$WORK/result/$BATCH/results.csv" ]; then
  echo
  c_blue "==> per-result class counts"
  tail -n +2 "$WORK/result/$BATCH/results.csv" | awk -F, '{print $2}' | sort | uniq -c | sort -rn
fi
[ -s "$WORK/result/$BATCH/logs/evidence.jsonl" ] && { echo; c_blue "==> evidence.jsonl"; sed -n '1,12p' "$WORK/result/$BATCH/logs/evidence.jsonl"; }
echo
if [ "$UEMU_USABLE" = 1 ]; then
  c_green "==> finished — see $OUT (CSV) and $WORK/result/$BATCH/logs (raw)"
else
  c_yellow "==> finished with kb/fuzz blocked by the missing µEmu build; files in $OUT"
fi
exit 0
