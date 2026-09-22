#!/usr/bin/env bash
# Run the rehosting pipeline inside the container; every stage writes its log to
# results/logs/ and its database tables are exported to results/db/ afterwards.
#
#   scripts/run_pipeline.sh                     # all stages, in dependency order
#   scripts/run_pipeline.sh --list              # show the stages
#   scripts/run_pipeline.sh --only 01,03        # run selected stages
#   scripts/run_pipeline.sh --skip 02,06b       # run everything except these
#   scripts/run_pipeline.sh --restore 01_firmxray   # resume an interrupted task
#   scripts/run_pipeline.sh --fuzz-time 10m     # shorten the fuzzing budget of 03/04/05
#
# --fuzz-time is meant for verification runs (a user checking that every tool works
# end to end); the shipped configurations budget one hour per firmware per fuzzer,
# which is what the published results were produced with.  The patched copies are
# written to results/generated/pipelines/ — the shipped configs are never modified.
#
# Stage dependency: 01 (FirmXRay) produces firmxray_results.base_address, which
# every later stage imports from the database (dbImports).  Run 01 first or the
# fuzzing stages will find nothing to do.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# stage id | config file | human description
STAGES=(
  "00b|00b_analyze.json|Program pre-analysis (Ghidra functions) — needed by the fuzzers"
  "01|01_firmxray.json|FirmXRay base-address recognition"
  "02b|02b_admission.json|Admission tests (fuzzware/hoedur/multifuzz seed admission)"
  "03|03_fuzzware.json|Fuzzware fuzzing + crash replay + statistics"
  "04|04_hoedur.json|Hoedur fuzzing + statistics"
  "05|05_multifuzz.json|MultiFuzz fuzzing + replay"
  "06|06_firmrca.json|FirmRCA root cause analysis (classify pass 1)"
  "06b|06b_firmrca_classify.json|FirmRCA classification (pass 2)"
  "02|02_firmline.json|Firmline firmware analysis"
)

ONLY=""; SKIP=""; RESTORE=""; FUZZ_TIME=""
while [ $# -gt 0 ]; do
  case "$1" in
    --only)    ONLY="$2"; shift 2 ;;
    --skip)    SKIP="$2"; shift 2 ;;
    --restore) RESTORE="$2"; shift 2 ;;
    --fuzz-time) FUZZ_TIME="$2"; shift 2 ;;
    --list)    printf '%-4s %-28s %s\n' "id" "config" "description"
               for s in "${STAGES[@]}"; do IFS='|' read -r id cfg desc <<<"$s"; printf '%-4s %-28s %s\n' "$id" "$cfg" "$desc"; done
               exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

wanted() {
  local id="$1"
  if [ -n "$ONLY" ] && ! tr ',' '\n' <<<"$ONLY" | grep -qx "$id"; then return 1; fi
  if [ -n "$SKIP" ] && tr ',' '\n' <<<"$SKIP" | grep -qx "$id"; then return 1; fi
  return 0
}

# ---------------------------------------------------------------- host wrapper
if ! in_container; then
  require_container
  args=""
  [ -n "$ONLY" ]    && args="$args --only $ONLY"
  [ -n "$SKIP" ]    && args="$args --skip $SKIP"
  [ -n "$RESTORE" ] && args="$args --restore $RESTORE"
  [ -n "$FUZZ_TIME" ] && args="$args --fuzz-time $FUZZ_TIME"
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/run_pipeline.sh $args"
fi

mkdir -p "$RESULTS_DIR/logs" "$RESULTS_DIR/db"
cd "$FRAMEWORK"

# ---------------------------------------------------------------- fuzz budget
# The three fuzzing stages park their budget in their own config key; for a
# verification run we rewrite those keys into copies under results/generated.
if [ -n "$FUZZ_TIME" ]; then
  PATCH_DIR="$RESULTS_DIR/generated/pipelines"
  mkdir -p "$PATCH_DIR"
  python3 - "/data/pipelines" "$PATCH_DIR" "$FUZZ_TIME" <<'PY'
import json, pathlib, re, sys

src, dst, spec = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
m = re.fullmatch(r"(\d+)\s*([smhd]?)", spec.strip())
if not m:
    sys.exit(f"cannot parse --fuzz-time '{spec}' (use e.g. 300s, 10m, 2h)")
n, unit = int(m.group(1)), m.group(2) or "s"
secs = n * {"s": 1, "m": 60, "h": 3600, "d": 86400}[unit]

# config file -> (task config key, dotted path of the budget field, value for secs)
targets = {
    "03_fuzzware.json": ("FirmXRayOnFuzzware", "maxTimeout",
                         "%02d:%02d:%02d:%02d" % (secs // 86400, secs % 86400 // 3600,
                                                  secs % 3600 // 60, secs % 60)),
    "04_hoedur.json":   ("HoedurFuzz", "maxTimeoutMinutes", f"{max(1, secs // 60)}m"),
    "05_multifuzz.json": ("MultiFuzz", "runFor", f"{secs}s"),
}
for name, (task, field, value) in targets.items():
    cfg = json.loads((src / name).read_text())
    if cfg.get(task, {}).get(field) is None:
        sys.exit(f"{name}: no {task}.{field} to override")
    old = cfg[task][field]
    cfg[task][field] = value
    (dst / name).write_text(json.dumps(cfg, indent=2) + "\n")
    print(f"[fuzz-time] {name}: {task}.{field} {old} -> {value}")
PY
  [ $? -eq 0 ] || die "could not build the shortened configs"
fi

step_header() { c_blue "==> [$1] $2"; }

if [ -n "$RESTORE" ]; then
  step_header "$RESTORE" "restore (continue an interrupted task)"
  ./bin/akiba_framework --restore "$RESTORE" 2>&1 | tee -a "$RESULTS_DIR/logs/restore_$RESTORE.log"
  exit $?
fi

# The bind mounts under /data come from the host drive (an external FUSE mount on the
# reference machine).  If that drive was unmounted and mounted again while the container
# kept running, the container holds the old, dead mount: the directories still appear
# under /data but cannot be read ("Input/output error", d?????????).  Every stage would
# then fail with "missing config".  Detect it here and say what to do.
for d in /data/pipelines /data/samples /data/results; do
  if [ ! -r "$d" ] || ! ls "$d" >/dev/null 2>&1; then
    c_red "bind mount $d is not readable — the host drive was (re)mounted after the container started"
    c_red "fix: docker restart largerehosting_akiba      # pipeline state lives in the named volumes"
    exit 1
  fi
done

rc_all=0
for s in "${STAGES[@]}"; do
  IFS='|' read -r id cfg desc <<<"$s"
  wanted "$id" || { echo "-- skipping $id ($desc)"; continue; }
  [ -f "/data/pipelines/$cfg" ] || { c_red "missing config /data/pipelines/$cfg"; rc_all=1; continue; }
  # a --fuzz-time run uses the shortened copy written above
  cfg_path="/data/pipelines/$cfg"
  [ -n "$FUZZ_TIME" ] && [ -f "/data/results/generated/pipelines/$cfg" ] \
    && cfg_path="/data/results/generated/pipelines/$cfg"
  step_header "$id" "$desc  ($(basename "$cfg_path"))"
  log="$RESULTS_DIR/logs/${id}_$(basename "$cfg" .json).log"
  start=$(date -Is)
  if ./bin/akiba_framework -c "$cfg_path@/main" 2>&1 | tee -a "$log"; then
    c_green "[$id] finished (log: $log)"
  else
    c_red "[$id] FAILED (log: $log)"
    rc_all=1
  fi
  echo "[$id] $start -> $(date -Is)" >> "$RESULTS_DIR/logs/pipeline_timeline.txt"
  # incremental export: keeps partial results usable if a later stage dies
  bash "$(dirname "${BASH_SOURCE[0]}")/export_results.sh" > "$RESULTS_DIR/logs/export_$id.log" 2>&1 \
    && c_green "    exported tables to $RESULTS_DIR/db" \
    || c_red "    export failed, see $RESULTS_DIR/logs/export_$id.log"
done

echo
if [ "$rc_all" = 0 ]; then
  c_green "pipeline finished — results in $RESULTS_DIR (db/*.csv, logs/)"
else
  c_red "pipeline finished with failures — check $RESULTS_DIR/logs"
fi
exit $rc_all
